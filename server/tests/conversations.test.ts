import { describe, expect, it } from "vitest";
import { call, connect, directConversation, enrollUser, errorCode, groupConversation, makeApp } from "./helpers.js";

describe("direct conversations", () => {
  it("are idempotent, sorted members, and pushed to both users", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const connB = await connect(t, b);

    const first = await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: b.userId } });
    expect(first.status).toBe(201);
    expect(first.body.type).toBe("direct");
    expect(first.body.name).toBeNull();
    expect(first.body.members.map((m: { userId: string }) => m.userId)).toEqual([a.userId, b.userId].sort());
    expect(first.body.keyRotationRequired).toBe(false);
    expect(first.body.currentKeyId).toBeNull();
    expect(t.ports.realtime.ofType("conversation.updated", connB)).toHaveLength(1);

    const again = await call(t.app, "POST", "/conversations", { token: b.token, body: { type: "direct", memberId: a.userId } });
    expect(again.status).toBe(200);
    expect(again.body.convId).toBe(first.body.convId);
    expect((await call(t.app, "GET", "/conversations", { token: a.token })).body.items).toHaveLength(1);

    expect(errorCode(await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: a.userId } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: "u_ghost" } }))).toBe("invalid_request");

    // Non-members get forbidden, unknown ids not_found.
    const c = await enrollUser(t.app, "C");
    const get = await call(t.app, "GET", `/conversations/${first.body.convId}`, { token: c.token });
    expect(get.status).toBe(403);
    expect((await call(t.app, "GET", "/conversations/c_missing", { token: c.token })).status).toBe(404);
    // Direct conversations cannot be renamed or have members removed.
    expect(errorCode(await call(t.app, "PATCH", `/conversations/${first.body.convId}`, { token: a.token, body: { name: "x" } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "DELETE", `/conversations/${first.body.convId}/members/${b.userId}`, { token: a.token }))).toBe("invalid_request");
  });
});

describe("groups", () => {
  it("create/rename/add/remove with owner rules, system messages and key rotation flag", async () => {
    const t = makeApp();
    const owner = await enrollUser(t.app, "Owner");
    const m1 = await enrollUser(t.app, "M1");
    const m2 = await enrollUser(t.app, "M2");
    const outsider = await enrollUser(t.app, "Outsider");
    const connM1 = await connect(t, m1);

    expect(errorCode(await call(t.app, "POST", "/conversations", { token: owner.token, body: { type: "group", memberIds: [] } }))).toBe("invalid_request");

    const g = await groupConversation(t.app, owner, "Cousins", [m1]);
    expect(g.type).toBe("group");
    expect(g.name).toBe("Cousins");
    expect(g.createdBy).toBe(owner.userId);
    expect(g.members.find((m) => m.userId === owner.userId)?.role).toBe("owner");
    expect(g.members.find((m) => m.userId === m1.userId)?.role).toBe("member");
    expect(g.lastMsgId).toMatch(/^m_/); // the "created" system message
    expect(t.ports.realtime.ofType("message.new", connM1)[0]?.message.system?.event).toBe("created");
    expect(t.ports.realtime.ofType("conversation.updated", connM1)).toHaveLength(1);

    // Only owner/admin may rename or add.
    expect((await call(t.app, "PATCH", `/conversations/${g.convId}`, { token: m1.token, body: { name: "Nope" } })).status).toBe(403);
    expect((await call(t.app, "PATCH", `/conversations/${g.convId}`, { token: outsider.token, body: { name: "Nope" } })).status).toBe(403);
    const renamed = await call(t.app, "PATCH", `/conversations/${g.convId}`, { token: owner.token, body: { name: "Cousins 2" } });
    expect(renamed.status).toBe(200);
    expect(renamed.body.name).toBe("Cousins 2");

    expect((await call(t.app, "POST", `/conversations/${g.convId}/members`, { token: m1.token, body: { userIds: [m2.userId] } })).status).toBe(403);
    const added = await call(t.app, "POST", `/conversations/${g.convId}/members`, { token: owner.token, body: { userIds: [m2.userId, m1.userId] } });
    expect(added.status).toBe(200);
    expect(added.body.members.map((m: { userId: string }) => m.userId).sort()).toEqual([owner.userId, m1.userId, m2.userId].sort());
    expect(added.body.keyRotationRequired).toBe(false);
    expect(errorCode(await call(t.app, "POST", `/conversations/${g.convId}/members`, { token: owner.token, body: { userIds: ["u_ghost"] } }))).toBe("invalid_request");

    // Member cannot remove another member, but can leave.
    expect((await call(t.app, "DELETE", `/conversations/${g.convId}/members/${m2.userId}`, { token: m1.token })).status).toBe(403);
    const left = await call(t.app, "DELETE", `/conversations/${g.convId}/members/${m1.userId}`, { token: m1.token });
    expect(left.status).toBe(200);
    expect(left.body.keyRotationRequired).toBe(true);
    expect(left.body.members.map((m: { userId: string }) => m.userId)).not.toContain(m1.userId);
    // The leaver is told too, and no longer sees the conversation.
    const updates = t.ports.realtime.ofType("conversation.updated", connM1);
    expect(updates.at(-1)?.conversation.members.some((m) => m.userId === m1.userId)).toBe(false);
    expect((await call(t.app, "GET", `/conversations/${g.convId}`, { token: m1.token })).status).toBe(403);

    // Owner removes m2.
    const removed = await call(t.app, "DELETE", `/conversations/${g.convId}/members/${m2.userId}`, { token: owner.token });
    expect(removed.status).toBe(200);
    expect(errorCode(await call(t.app, "DELETE", `/conversations/${g.convId}/members/${m2.userId}`, { token: owner.token }))).toBe("not_found");

    // System messages tell the story, in order.
    const msgs = await call(t.app, "GET", `/conversations/${g.convId}/messages?after=m_0`, { token: owner.token });
    expect(msgs.body.items.map((m: { kind: string; system: { event: string; userIds?: string[]; text?: string } }) => [m.kind, m.system.event, m.system.userIds, m.system.text])).toEqual([
      ["system", "created", [owner.userId], undefined],
      ["system", "renamed", [owner.userId], "Cousins 2"],
      ["system", "member_added", [m2.userId], undefined],
      ["system", "member_left", [m1.userId], undefined],
      ["system", "member_removed", [m2.userId], undefined],
    ]);
  });

  it("an admin-role user who is a member may manage any group; conversations list newest activity first", async () => {
    const t = makeApp();
    const owner = await enrollUser(t.app, "Owner");
    const admin = await enrollUser(t.app, "Admin", { role: "admin" });
    const g = await groupConversation(t.app, owner, "G", [admin]);
    const r = await call(t.app, "PATCH", `/conversations/${g.convId}`, { token: admin.token, body: { avatarKey: "avatars/x/y" } });
    expect(r.status).toBe(200);
    expect(r.body.avatarKey).toBe("avatars/x/y");

    t.ports.clock.advance(1000);
    const d = await directConversation(t.app, owner, admin);
    const list = await call(t.app, "GET", "/conversations", { token: owner.token });
    expect(list.body.items.map((c: { convId: string }) => c.convId)).toEqual([d.convId, g.convId]);
  });

  it("PUT /read records lastReadMsgId and fans out conversation.read", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const connB = await connect(t, b);
    const r = await call(t.app, "PUT", `/conversations/${d.convId}/read`, { token: a.token, body: { msgId: "m_abc" } });
    expect(r.status).toBe(204);
    const ev = t.ports.realtime.ofType("conversation.read", connB);
    expect(ev).toHaveLength(1);
    expect(ev[0]).toMatchObject({ convId: d.convId, userId: a.userId, msgId: "m_abc" });
    const conv = await call(t.app, "GET", `/conversations/${d.convId}`, { token: b.token });
    expect(conv.body.members.find((m: { userId: string }) => m.userId === a.userId).lastReadMsgId).toBe("m_abc");
  });
});
