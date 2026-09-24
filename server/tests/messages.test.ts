import { describe, expect, it } from "vitest";
import { call, connect, directConversation, encodeBase64Url, enrollUser, errorCode, groupConversation, makeApp, makeEnvelope, sendMessage } from "./helpers.js";

describe("messages", () => {
  it("send 201, duplicate clientId 200 with the original, lastMsgId moves, fan-out skips gone connections", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const live = await connect(t, b, "b_live");
    const gone = await connect(t, b, "b_gone");
    const connA = await connect(t, a, "a_1");
    t.ports.realtime.goneSet.add(gone);
    t.ports.realtime.clear();

    const clientId = "11111111-1111-1111-1111-111111111111";
    const first = await sendMessage(t.app, a, d.convId, "x_k1", clientId);
    expect(first.status).toBe(201);
    const msg = first.body.message;
    expect(msg).toMatchObject({ convId: d.convId, senderId: a.userId, senderDeviceId: a.deviceId, clientId, kind: "e2e", deletedAt: null });
    expect(msg.msgId).toMatch(/^m_[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(msg.envelope.keyId).toBe("x_k1");
    expect(msg.createdAt).toBe(t.ports.clock.now());

    // Retry from the outbox: same clientId, same device.
    const dup = await sendMessage(t.app, a, d.convId, "x_k1", clientId);
    expect(dup.status).toBe(200);
    expect(dup.body.message.msgId).toBe(msg.msgId);
    // Another device may reuse the clientId.
    expect((await sendMessage(t.app, b, d.convId, "x_k1", clientId)).status).toBe(201);

    const conv = await call(t.app, "GET", `/conversations/${d.convId}`, { token: a.token });
    expect(conv.body.lastMsgId).toMatch(/^m_/);
    expect(conv.body.lastMessageAt).toBe(t.ports.clock.now());

    // Fan-out: live connections of both members got message.new; the gone one was removed.
    expect(t.ports.realtime.ofType("message.new", live).map((e) => e.message.msgId)).toContain(msg.msgId);
    expect(t.ports.realtime.ofType("message.new", connA).map((e) => e.message.msgId)).toContain(msg.msgId);
    expect(t.ports.realtime.ofType("message.new", gone)).toHaveLength(0);
    expect(await t.ports.db.connections.get(gone)).toBeNull();
    expect(await t.ports.db.connections.get(live)).not.toBeNull();

    // Idempotency expires after 7 days.
    t.ports.clock.advance(7 * 24 * 3_600_000 + 1);
    expect((await sendMessage(t.app, a, d.convId, "x_k1", clientId)).status).toBe(201);
  });

  it("validates the envelope and caps it at 64 KiB", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const post = (envelope: unknown, extra: Record<string, unknown> = {}) =>
      call(t.app, "POST", `/conversations/${d.convId}/messages`, { token: a.token, body: { clientId: crypto.randomUUID(), sentAt: 1, envelope, ...extra } });

    for (const bad of [null, "x", { ...makeEnvelope("k"), v: 2 }, { ...makeEnvelope("k"), keyId: "" }, { ...makeEnvelope("k"), iv: "not base64!" }, { ...makeEnvelope("k"), ct: "" }]) {
      const r = await post(bad);
      expect(r.status).toBe(400);
      expect(errorCode(r)).toBe("invalid_request");
    }
    expect(errorCode(await post(makeEnvelope("k"), { clientId: "" }))).toBe("invalid_request");
    expect(errorCode(await post(makeEnvelope("k"), { sentAt: "now" }))).toBe("invalid_request");

    const big = await post(makeEnvelope("k", 64 * 1024));
    expect(big.status).toBe(413);
    expect(errorCode(big)).toBe("payload_too_large");
    expect((await post(makeEnvelope("k", 40 * 1024))).status).toBe(201); // ~53 KiB encoded
  });

  it("pages with after/before/limit and hasMore; rejects bad params; non-members get 403", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const x = await enrollUser(t.app, "X");
    const d = await directConversation(t.app, a, b);
    const ids: string[] = [];
    for (let i = 0; i < 7; i++) {
      const r = await sendMessage(t.app, i % 2 ? a : b, d.convId, "x_k");
      ids.push(r.body.message.msgId);
    }
    expect([...ids].sort()).toEqual(ids); // monotonic

    const latest = await call(t.app, "GET", `/conversations/${d.convId}/messages?limit=3`, { token: a.token });
    expect(latest.body.items.map((m: { msgId: string }) => m.msgId)).toEqual([ids[6], ids[5], ids[4]]);
    expect(latest.body.hasMore).toBe(true);

    const before = await call(t.app, "GET", `/conversations/${d.convId}/messages?before=${ids[4]}&limit=3`, { token: a.token });
    expect(before.body.items.map((m: { msgId: string }) => m.msgId)).toEqual([ids[3], ids[2], ids[1]]);
    expect(before.body.hasMore).toBe(true);
    const before2 = await call(t.app, "GET", `/conversations/${d.convId}/messages?before=${ids[1]}&limit=3`, { token: a.token });
    expect(before2.body.items.map((m: { msgId: string }) => m.msgId)).toEqual([ids[0]]);
    expect(before2.body.hasMore).toBe(false);

    const after = await call(t.app, "GET", `/conversations/${d.convId}/messages?after=${ids[2]}&limit=2`, { token: b.token });
    expect(after.body.items.map((m: { msgId: string }) => m.msgId)).toEqual([ids[3], ids[4]]);
    expect(after.body.hasMore).toBe(true);
    const tail = await call(t.app, "GET", `/conversations/${d.convId}/messages?after=${ids[4]}`, { token: b.token });
    expect(tail.body.items.map((m: { msgId: string }) => m.msgId)).toEqual([ids[5], ids[6]]);
    expect(tail.body.hasMore).toBe(false);

    const all = await call(t.app, "GET", `/conversations/${d.convId}/messages`, { token: b.token });
    expect(all.body.items).toHaveLength(7);

    for (const q of ["limit=0", "limit=201", "limit=abc", "limit=-1", `after=${ids[0]}&before=${ids[3]}`, "after="]) {
      const r = await call(t.app, "GET", `/conversations/${d.convId}/messages?${q}`, { token: a.token });
      expect(r.status).toBe(400);
      expect(errorCode(r)).toBe("invalid_request");
    }

    const outsider = await call(t.app, "GET", `/conversations/${d.convId}/messages`, { token: x.token });
    expect(outsider.status).toBe(403);
    expect(errorCode(outsider)).toBe("forbidden");
    expect((await sendMessage(t.app, x, d.convId, "x_k")).status).toBe(403);
    expect((await call(t.app, "GET", `/conversations/c_missing/messages`, { token: x.token })).status).toBe(404);
  });

  it("tombstones: sender, group owner or admin may delete; envelope removed; message.deleted fan-out", async () => {
    const t = makeApp();
    const owner = await enrollUser(t.app, "Owner");
    const m = await enrollUser(t.app, "M");
    const other = await enrollUser(t.app, "Other");
    const admin = await enrollUser(t.app, "Admin", { role: "admin" });
    const g = await groupConversation(t.app, owner, "G", [m, other, admin]);
    const connOther = await connect(t, other);

    const sent = await sendMessage(t.app, m, g.convId, "x_k");
    const msgId = sent.body.message.msgId;
    const path = `/conversations/${g.convId}/messages/${msgId}`;

    expect((await call(t.app, "DELETE", path, { token: other.token })).status).toBe(403);
    const del = await call(t.app, "DELETE", path, { token: m.token });
    expect(del.status).toBe(200);
    expect(del.body.message.envelope).toBeNull();
    expect(del.body.message.deletedAt).toBe(t.ports.clock.now());
    expect(del.body.message.clientId).toBe(sent.body.message.clientId);
    const ev = t.ports.realtime.ofType("message.deleted", connOther);
    expect(ev).toEqual([{ type: "message.deleted", convId: g.convId, msgId, deletedAt: t.ports.clock.now() }]);
    // Idempotent and visible as a tombstone in listings.
    expect((await call(t.app, "DELETE", path, { token: m.token })).status).toBe(200);
    const list = await call(t.app, "GET", `/conversations/${g.convId}/messages?limit=1`, { token: other.token });
    expect(list.body.items[0].envelope).toBeNull();

    const byOwner = await sendMessage(t.app, other, g.convId, "x_k");
    expect((await call(t.app, "DELETE", `/conversations/${g.convId}/messages/${byOwner.body.message.msgId}`, { token: owner.token })).status).toBe(200);
    const byAdmin = await sendMessage(t.app, other, g.convId, "x_k");
    expect((await call(t.app, "DELETE", `/conversations/${g.convId}/messages/${byAdmin.body.message.msgId}`, { token: admin.token })).status).toBe(200);
    expect((await call(t.app, "DELETE", `/conversations/${g.convId}/messages/m_missing`, { token: owner.token })).status).toBe(404);
  });

  it("never stores anything but the envelope fields the client sent", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const r = await call(t.app, "POST", `/conversations/${d.convId}/messages`, {
      token: a.token,
      body: { clientId: "c1", sentAt: 5, envelope: { ...makeEnvelope("x_k"), extra: "dropped" }, senderId: "u_spoof" },
    });
    expect(r.status).toBe(201);
    expect(r.body.message.senderId).toBe(a.userId);
    expect(Object.keys(r.body.message.envelope).sort()).toEqual(["ct", "iv", "keyId", "v"]);
    expect(r.body.message.sentAt).toBe(5);
    expect(encodeBase64Url(new Uint8Array(0))).toBe("");
  });
});
