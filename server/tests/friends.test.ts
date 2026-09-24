/** Friends, user invites, visibility, blocking and leaving 1:1 chats (PROTOCOL.md §5a). */
import { describe, expect, it } from "vitest";
import { call, connect, createInvite, enrollUser, enrollWithCode, errorCode, makeApp, makeEnvelope, type Enrolled } from "./helpers.js";

async function inviteFrom(t: ReturnType<typeof makeApp>, who: Enrolled, body: Record<string, unknown> = { kind: "friend" }) {
  const r = await call(t.app, "POST", "/invites", { token: who.token, body });
  expect(r.status).toBe(201);
  return r.body as { code: string; kind: string; invitedBy: string; link: string };
}

const ids = (r: { body: { items: { userId?: string; user?: { userId: string } }[] } }) =>
  r.body.items.map((i) => i.userId ?? i.user?.userId).sort();

describe("friend invites", () => {
  it("a newcomer invited by a friend becomes that friend's friend, gets a 1:1 chat, and is not dropped into the family group", async () => {
    const t = makeApp();
    const family = (await call(t.app, "POST", "/admin/conversations", { admin: true, body: { name: "Family", autoJoin: true } })).body;
    const owner = await enrollUser(t.app, "Owner");
    const aunt = await enrollUser(t.app, "Aunt");
    const inv = await inviteFrom(t, owner);
    expect(inv.kind).toBe("friend");
    expect(inv.invitedBy).toBe(owner.userId);

    const colleague = await enrollWithCode(t.app, inv.code, { displayName: "Colleague", withBiometric: false });
    // friends both ways
    expect(ids(await call(t.app, "GET", "/friends", { token: owner.token }))).toEqual([colleague.userId]);
    expect(ids(await call(t.app, "GET", "/friends", { token: colleague.token }))).toEqual([owner.userId]);
    // a 1:1 chat was opened, and the family group was NOT auto-joined
    const convs = (await call(t.app, "GET", "/conversations", { token: colleague.token })).body.items;
    expect(convs).toHaveLength(1);
    expect(convs[0].type).toBe("direct");
    expect(convs[0].members.map((m: { userId: string }) => m.userId).sort()).toEqual([owner.userId, colleague.userId].sort());
    expect(convs.some((c: { convId: string }) => c.convId === family.convId)).toBe(false);
    // visibility: the colleague sees only the owner; the owner sees the family circle plus the colleague
    expect(ids(await call(t.app, "GET", "/users", { token: colleague.token }))).toEqual([owner.userId, colleague.userId].sort());
    expect(ids(await call(t.app, "GET", "/users", { token: owner.token }))).toEqual([owner.userId, aunt.userId, colleague.userId].sort());
    // the colleague can't start a chat with a stranger from the family circle
    expect((await call(t.app, "POST", "/conversations", { token: colleague.token, body: { type: "direct", memberId: aunt.userId } })).status).toBe(403);
  });

  it("an existing user redeems a code; codes are single-use and never your own", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const inv = await inviteFrom(t, a);
    expect(errorCode(await call(t.app, "POST", "/invites/redeem", { token: a.token, body: { code: inv.code } }))).toBe("invalid_request");
    const r = await call(t.app, "POST", "/invites/redeem", { token: b.token, body: { code: inv.code.toLowerCase() } });
    expect(r.status).toBe(200);
    expect(r.body.friend.userId).toBe(a.userId);
    expect(r.body.conversation.type).toBe("direct");
    expect(errorCode(await call(t.app, "POST", "/invites/redeem", { token: b.token, body: { code: inv.code } }))).toBe("invite_invalid");
    // admin (new-phone) invites can't be redeemed as friend codes
    const admin = await createInvite(t.app, { displayName: "X" });
    expect(errorCode(await call(t.app, "POST", "/invites/redeem", { token: b.token, body: { code: admin.code } }))).toBe("invalid_request");
  });

  it("a group invite adds the newcomer to that group", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const g = (await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "group", name: "Office" } })).body;
    const outsider = await enrollUser(t.app, "Outsider");
    expect((await call(t.app, "POST", "/invites", { token: outsider.token, body: { kind: "group", convId: g.convId } })).status).toBe(403);
    const inv = await inviteFrom(t, a, { kind: "group", convId: g.convId });
    const newbie = await enrollWithCode(t.app, inv.code, { displayName: "Newbie", withBiometric: false });
    const office = (await call(t.app, "GET", `/conversations/${g.convId}`, { token: newbie.token })).body;
    expect(office.members.map((m: { userId: string }) => m.userId).sort()).toEqual([a.userId, newbie.userId].sort());
  });
});

describe("blocking", () => {
  it("stops messages, calls and group adds from the blocked person until unblocked", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = (await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: b.userId } })).body;
    await connect(t, b);
    const send = () => call(t.app, "POST", `/conversations/${d.convId}/messages`, { token: a.token, body: { clientId: crypto.randomUUID(), sentAt: 1, envelope: makeEnvelope("x_1") } });
    expect((await send()).status).toBe(201);

    expect((await call(t.app, "POST", `/friends/${a.userId}/block`, { token: b.token })).status).toBe(204);
    expect((await call(t.app, "GET", "/friends", { token: b.token })).body.items[0]).toMatchObject({ blocked: true, since: null });
    expect(errorCode(await send())).toBe("blocked");
    expect(errorCode(await call(t.app, "POST", "/calls", { token: a.token, body: { convId: d.convId, type: "audio" } }))).toBe("blocked");
    const g = (await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "group", name: "G" } })).body;
    const added = (await call(t.app, "POST", `/conversations/${g.convId}/members`, { token: a.token, body: { userIds: [b.userId] } })).body;
    expect(added.members.map((m: { userId: string }) => m.userId)).toEqual([a.userId]);
    // B can still write to A (B chose to block, not to be silenced)
    expect((await call(t.app, "POST", `/conversations/${d.convId}/messages`, { token: b.token, body: { clientId: crypto.randomUUID(), sentAt: 1, envelope: makeEnvelope("x_1") } })).status).toBe(201);

    expect((await call(t.app, "DELETE", `/friends/${a.userId}/block`, { token: b.token })).status).toBe(204);
    expect((await send()).status).toBe(201);
  });
});

describe("leaving a 1:1 chat", () => {
  it("removes me, rotates the key, and starting the chat again brings me back", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = (await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: b.userId } })).body;
    expect((await call(t.app, "DELETE", `/conversations/${d.convId}/members/${b.userId}`, { token: a.token })).status).toBe(400);
    const left = await call(t.app, "DELETE", `/conversations/${d.convId}/members/${a.userId}`, { token: a.token });
    expect(left.status).toBe(200);
    expect(left.body.members.map((m: { userId: string }) => m.userId)).toEqual([b.userId]);
    expect(left.body.keyRotationRequired).toBe(true);
    expect((await call(t.app, "GET", "/conversations", { token: a.token })).body.items).toHaveLength(0);

    const again = await call(t.app, "POST", "/conversations", { token: b.token, body: { type: "direct", memberId: a.userId } });
    expect(again.status).toBe(200);
    expect(again.body.convId).toBe(d.convId);
    expect(again.body.members.map((m: { userId: string }) => m.userId).sort()).toEqual([a.userId, b.userId].sort());
  });
});
