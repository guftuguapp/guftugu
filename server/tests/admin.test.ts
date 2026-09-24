import { describe, expect, it } from "vitest";
import { call, connect, createInvite, enrollUser, errorCode, makeApp } from "./helpers.js";

describe("admin API", () => {
  it("invites: create with options, list, delete; validation", async () => {
    const t = makeApp();
    const u = await enrollUser(t.app, "U");
    const inv = await createInvite(t.app, { displayName: "Nani", role: "admin", expiresInHours: 2 });
    expect(inv).toMatchObject({ displayName: "Nani", role: "admin", forUserId: null, autoJoin: [], usedAt: null });
    expect(inv.expiresAt).toBe(t.ports.clock.now() + 2 * 3_600_000);
    const bound = await createInvite(t.app, { forUserId: u.userId });
    expect(bound.forUserId).toBe(u.userId);
    expect((await call(t.app, "POST", "/admin/invites", { admin: true, body: { forUserId: "u_nope" } })).status).toBe(404);
    expect(errorCode(await call(t.app, "POST", "/admin/invites", { admin: true, body: { autoJoin: ["c_nope"] } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "POST", "/admin/invites", { admin: true, body: { role: "root" } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "POST", "/admin/invites", { admin: true, body: { expiresInHours: 0 } }))).toBe("invalid_request");

    const list = await call(t.app, "GET", "/admin/invites", { admin: true });
    const codes = list.body.items.map((i: { code: string }) => i.code);
    expect(codes).toHaveLength(3); // enrollUser made one too
    expect(codes).toContain(inv.code);
    expect(codes).toContain(bound.code);
    expect((await call(t.app, "DELETE", `/admin/invites/${inv.code}`, { admin: true })).status).toBe(204);
    expect((await call(t.app, "DELETE", `/admin/invites/${inv.code}`, { admin: true })).status).toBe(404);
    expect((await call(t.app, "GET", "/admin/invites", { admin: true })).body.items.map((i: { code: string }) => i.code)).not.toContain(inv.code);
  });

  it("users: list and patch role/status; devices list", async () => {
    const t = makeApp();
    const u = await enrollUser(t.app, "U");
    const users = await call(t.app, "GET", "/admin/users", { admin: true });
    expect(users.body.items).toHaveLength(1);
    expect(users.body.items[0]).not.toHaveProperty("passwordHash");
    const patched = await call(t.app, "PATCH", `/admin/users/${u.userId}`, { admin: true, body: { role: "admin" } });
    expect(patched.body.role).toBe("admin");
    expect((await call(t.app, "GET", "/me", { token: u.token })).body.role).toBe("admin");
    expect((await call(t.app, "PATCH", "/admin/users/u_nope", { admin: true, body: { role: "admin" } })).status).toBe(404);
    expect(errorCode(await call(t.app, "PATCH", `/admin/users/${u.userId}`, { admin: true, body: {} }))).toBe("invalid_request");
    const devices = await call(t.app, "GET", `/admin/users/${u.userId}/devices`, { admin: true });
    expect(devices.body.items[0].deviceId).toBe(u.deviceId);
  });

  it("conversations: create with members / autoJoin default, patch, list", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const connA = await connect(t, a);
    t.ports.realtime.clear();
    const created = await call(t.app, "POST", "/admin/conversations", { admin: true, body: { name: "Family", memberIds: [a.userId] } });
    expect(created.status).toBe(201);
    expect(created.body).toMatchObject({ type: "group", name: "Family", createdBy: "admin", autoJoin: true });
    expect(created.body.members.map((m: { userId: string; role: string }) => [m.userId, m.role])).toEqual([[a.userId, "member"]]);
    expect(t.ports.realtime.ofType("conversation.updated", connA)).toHaveLength(1);
    expect(errorCode(await call(t.app, "POST", "/admin/conversations", { admin: true, body: { name: "X", memberIds: ["u_nope"] } }))).toBe("invalid_request");

    const patched = await call(t.app, "PATCH", `/admin/conversations/${created.body.convId}`, { admin: true, body: { name: "Khandaan", autoJoin: false } });
    expect(patched.body).toMatchObject({ name: "Khandaan", autoJoin: false });
    expect((await call(t.app, "PATCH", "/admin/conversations/c_nope", { admin: true, body: { name: "x" } })).status).toBe(404);
    const list = await call(t.app, "GET", "/admin/conversations", { admin: true });
    expect(list.body.items).toHaveLength(1);
    expect(list.body.items[0].name).toBe("Khandaan");

    // A later enrollee no longer auto-joins.
    const b = await enrollUser(t.app, "B");
    expect((await call(t.app, "GET", "/conversations", { token: b.token })).body.items).toHaveLength(0);
  });

  it("config: defaults, partial update, validation, and stats", async () => {
    const t = makeApp();
    const defaults = await call(t.app, "GET", "/admin/config", { admin: true });
    expect(defaults.body).toEqual({
      serverName: "Test family",
      iceServers: [{ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }],
      maxUploadBytes: 100 * 1024 * 1024,
      features: { calls: true, media: true },
      defaultAutoJoin: true,
      inviteTtlHours: 72,
    });
    const turn = { urls: ["turn:turn.example.org:3478"], username: "u", credential: "c" };
    const put = await call(t.app, "PUT", "/admin/config", { admin: true, body: { iceServers: [turn], serverName: "Our family", inviteTtlHours: 48, features: { calls: false } } });
    expect(put.status).toBe(200);
    expect(put.body).toMatchObject({ serverName: "Our family", iceServers: [turn], inviteTtlHours: 48, features: { calls: false, media: true }, defaultAutoJoin: true });
    expect((await call(t.app, "GET", "/admin/config", { admin: true })).body).toEqual(put.body);
    expect((await call(t.app, "GET", "/.well-known/guftugu")).body.name).toBe("Our family");
    expect((await createInvite(t.app)).expiresAt - t.ports.clock.now()).toBe(48 * 3_600_000);

    for (const bad of [{ iceServers: "x" }, { iceServers: [{ urls: [] }] }, { maxUploadBytes: -1 }, { features: { calls: "yes" } }, { serverName: "" }]) {
      expect(errorCode(await call(t.app, "PUT", "/admin/config", { admin: true, body: bad }))).toBe("invalid_request");
    }

    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: b.userId } });
    await connect(t, a);
    expect((await call(t.app, "GET", "/admin/stats", { admin: true })).body).toEqual({ users: 2, devices: 2, conversations: 1, connections: 1 });
  });
});
