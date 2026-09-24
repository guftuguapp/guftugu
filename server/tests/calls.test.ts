import { describe, expect, it } from "vitest";
import { call, connect, directConversation, enrollUser, enrollWithCode, errorCode, groupConversation, makeApp } from "./helpers.js";

describe("calls", () => {
  it("unreachable callee: call ends immediately and a call message is written", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const connA = await connect(t, a);
    t.ports.realtime.clear();

    const r = await call(t.app, "POST", "/calls", { token: a.token, body: { convId: d.convId, type: "audio" } });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ convId: d.convId, type: "audio", callerId: a.userId, callerDeviceId: a.deviceId, calleeId: b.userId, calleeDeviceId: null, state: "ended", endReason: "unreachable" });
    expect(r.body.callId).toMatch(/^k_/);
    expect(r.body.endedAt).toBe(t.ports.clock.now());

    const msgs = await call(t.app, "GET", `/conversations/${d.convId}/messages`, { token: b.token });
    expect(msgs.body.items).toHaveLength(1);
    expect(msgs.body.items[0]).toMatchObject({ kind: "call", senderId: a.userId, call: { callId: r.body.callId, type: "audio", outcome: "unreachable", durationMs: null } });
    expect(t.ports.realtime.ofType("message.new", connA)).toHaveLength(1);
    expect(t.ports.realtime.ofType("call.invite")).toHaveLength(0);

    // Only direct conversations, only members.
    const g = await groupConversation(t.app, a, "G", [b]);
    expect(errorCode(await call(t.app, "POST", "/calls", { token: a.token, body: { convId: g.convId, type: "audio" } }))).toBe("invalid_request");
    const x = await enrollUser(t.app, "X");
    expect((await call(t.app, "POST", "/calls", { token: x.token, body: { convId: d.convId, type: "audio" } })).status).toBe(403);
    expect((await call(t.app, "GET", `/calls/${r.body.callId}`, { token: x.token })).status).toBe(403);
    expect((await call(t.app, "GET", `/calls/${r.body.callId}`, { token: b.token })).status).toBe(200);
    expect((await call(t.app, "GET", "/calls/k_missing", { token: b.token })).status).toBe(404);
  });

  it("ring -> answer -> end: events to the right connections and an answered call message with duration", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const lc = await call(t.app, "POST", "/me/link-code", { token: b.token });
    const b2 = await enrollWithCode(t.app, lc.body.code);
    const d = await directConversation(t.app, a, b);
    const connA = await connect(t, a);
    const connB = await connect(t, b);
    const connB2 = await connect(t, b2);
    t.ports.realtime.clear();

    const started = await call(t.app, "POST", "/calls", { token: a.token, body: { convId: d.convId, type: "video" } });
    expect(started.status).toBe(201);
    expect(started.body.state).toBe("ringing");
    const callId = started.body.callId;
    for (const c of [connB, connB2]) {
      const inv = t.ports.realtime.ofType("call.invite", c);
      expect(inv).toHaveLength(1);
      expect(inv[0]?.call.callId).toBe(callId);
      expect(inv[0]?.caller.userId).toBe(a.userId);
      expect(inv[0]?.caller).not.toHaveProperty("passwordHash");
    }
    expect(t.ports.realtime.ofType("call.invite", connA)).toHaveLength(0);

    // Only the callee answers.
    expect((await call(t.app, "POST", `/calls/${callId}/answer`, { token: a.token })).status).toBe(403);
    t.ports.clock.advance(3000);
    const answered = await call(t.app, "POST", `/calls/${callId}/answer`, { token: b.token });
    expect(answered.status).toBe(200);
    expect(answered.body).toMatchObject({ state: "active", calleeDeviceId: b.deviceId, answeredAt: t.ports.clock.now() });
    expect(t.ports.realtime.ofType("call.answered", connA)[0]?.call.calleeDeviceId).toBe(b.deviceId);
    const elsewhere = t.ports.realtime.ofType("call.ended", connB2);
    expect(elsewhere).toHaveLength(1);
    expect(elsewhere[0]?.reason).toBe("answered_elsewhere");
    expect(t.ports.realtime.ofType("call.ended", connB)).toHaveLength(0);
    // Answering twice from the same device is a no-op; from another device it conflicts.
    expect((await call(t.app, "POST", `/calls/${callId}/answer`, { token: b.token })).status).toBe(200);
    expect(errorCode(await call(t.app, "POST", `/calls/${callId}/answer`, { token: b2.token }))).toBe("conflict");
    expect(errorCode(await call(t.app, "POST", `/calls/${callId}/reject`, { token: b.token }))).toBe("conflict");

    t.ports.realtime.clear();
    t.ports.clock.advance(65_000);
    const ended = await call(t.app, "POST", `/calls/${callId}/end`, { token: a.token, body: { reason: "hangup" } });
    expect(ended.status).toBe(200);
    expect(ended.body).toMatchObject({ state: "ended", endReason: "hangup", endedAt: t.ports.clock.now() });
    for (const c of [connA, connB, connB2]) {
      const ev = t.ports.realtime.ofType("call.ended", c);
      expect(ev).toHaveLength(1);
      expect(ev[0]?.reason).toBe("hangup");
    }
    const msgs = await call(t.app, "GET", `/conversations/${d.convId}/messages`, { token: b.token });
    expect(msgs.body.items).toHaveLength(1);
    expect(msgs.body.items[0].call).toEqual({ callId, type: "video", outcome: "answered", durationMs: 65_000 });
    expect(msgs.body.items[0].senderId).toBe(a.userId);

    // Idempotent end.
    t.ports.realtime.clear();
    const again = await call(t.app, "POST", `/calls/${callId}/end`, { token: b.token, body: { reason: "hangup" } });
    expect(again.status).toBe(200);
    expect(again.body).toEqual(ended.body);
    expect(t.ports.realtime.sent).toHaveLength(0);
    expect((await call(t.app, "GET", `/conversations/${d.convId}/messages`, { token: b.token })).body.items).toHaveLength(1);
  });

  it("reject, cancel and timeout outcomes", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const connA = await connect(t, a);
    const connB = await connect(t, b);
    const start = async () => (await call(t.app, "POST", "/calls", { token: a.token, body: { convId: d.convId, type: "audio" } })).body.callId as string;

    const c1 = await start();
    expect((await call(t.app, "POST", `/calls/${c1}/reject`, { token: a.token })).status).toBe(403);
    const rejected = await call(t.app, "POST", `/calls/${c1}/reject`, { token: b.token });
    expect(rejected.body).toMatchObject({ state: "ended", endReason: "rejected" });
    expect(t.ports.realtime.ofType("call.ended", connA).at(-1)?.reason).toBe("rejected");
    expect((await call(t.app, "POST", `/calls/${c1}/reject`, { token: b.token })).body.endReason).toBe("rejected");

    const c2 = await start();
    const cancelled = await call(t.app, "POST", `/calls/${c2}/end`, { token: a.token, body: { reason: "cancelled" } });
    expect(cancelled.body.endReason).toBe("cancelled");
    expect(t.ports.realtime.ofType("call.ended", connB).at(-1)?.reason).toBe("cancelled");

    const c3 = await start();
    const timedOut = await call(t.app, "POST", `/calls/${c3}/end`, { token: a.token, body: { reason: "timeout" } });
    expect(timedOut.body.endReason).toBe("timeout");

    const c4 = await start();
    // Callee hanging up while ringing counts as a rejection (and a missing body means "hangup").
    expect((await call(t.app, "POST", `/calls/${c4}/end`, { token: b.token })).body.endReason).toBe("rejected");
    expect(errorCode(await call(t.app, "POST", `/calls/${c4}/end`, { token: b.token, body: { reason: "bogus" } }))).toBe("invalid_request");

    const outcomes = (await call(t.app, "GET", `/conversations/${d.convId}/messages?after=m_0`, { token: a.token })).body.items.map(
      (m: { call: { outcome: string; durationMs: number | null } }) => [m.call.outcome, m.call.durationMs],
    );
    expect(outcomes).toEqual([["rejected", null], ["cancelled", null], ["missed", null], ["rejected", null]]);
  });
});

// Regression (seen live on AWS): a callee whose only connection is already gone
// (stale registry entry, $disconnect not yet processed) must be reported
// unreachable, not left ringing.
describe("startCall with a stale connection", () => {
  it("marks the call unreachable when every callee connection is gone", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const d = await directConversation(t.app, a, b);
    const connB = await connect(t, b);
    t.ports.realtime.goneSet.add(connB);
    t.ports.realtime.clear();

    const r = await call(t.app, "POST", "/calls", { token: a.token, body: { convId: d.convId, type: "audio" } });
    expect(r.status).toBe(201);
    expect(r.body).toMatchObject({ state: "ended", endReason: "unreachable" });
    expect(await t.ports.db.connections.get(connB)).toBeNull();
    const msgs = await call(t.app, "GET", `/conversations/${d.convId}/messages`, { token: a.token });
    expect(msgs.body.items.at(-1)).toMatchObject({ kind: "call", call: { outcome: "unreachable" } });
  });
});
