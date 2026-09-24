import { describe, expect, it } from "vitest";
import { call, connect, directConversation, enrollUser, enrollWithCode, groupConversation, makeApp, makeEnvelope, type TestApp } from "./helpers.js";

async function frame(t: TestApp, connectionId: string, payload: unknown): Promise<void> {
  await t.app.ws.onMessage(connectionId, typeof payload === "string" ? payload : JSON.stringify(payload));
}

describe("websocket", () => {
  it("connect requires a valid session; hello/ping/pong; disconnect forgets the connection", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    expect(await t.app.ws.onConnect("c0", null)).toEqual({ ok: false, status: 401 });
    expect((await t.app.ws.onConnect("c0", "bogus")).status).toBe(401);

    const r = await t.app.ws.onConnect("c1", a.token);
    expect(r).toEqual({ ok: true, userId: a.userId, deviceId: a.deviceId });
    expect(await t.ports.db.connections.get("c1")).toMatchObject({ connectionId: "c1", userId: a.userId, deviceId: a.deviceId, connectedAt: t.ports.clock.now() });
    expect((await call(t.app, "GET", "/admin/stats", { admin: true })).body.connections).toBe(1);

    await frame(t, "c1", { type: "hello" });
    expect(t.ports.realtime.eventsFor("c1")).toEqual([{ type: "hello", userId: a.userId, deviceId: a.deviceId, connectionId: "c1", serverTime: t.ports.clock.now() }]);
    t.ports.clock.advance(10);
    await frame(t, "c1", { type: "ping" });
    expect(t.ports.realtime.eventsFor("c1").at(-1)).toEqual({ type: "pong", serverTime: t.ports.clock.now() });

    await frame(t, "c1", "{bad json");
    expect(t.ports.realtime.eventsFor("c1").at(-1)).toMatchObject({ type: "error", code: "invalid_request" });
    await frame(t, "c1", { type: "dance" });
    expect(t.ports.realtime.eventsFor("c1").at(-1)).toMatchObject({ type: "error", code: "invalid_request" });

    await t.app.ws.onDisconnect("c1");
    expect(await t.ports.db.connections.get("c1")).toBeNull();
    await t.app.ws.onDisconnect("c1"); // idempotent
    const before = t.ports.realtime.sent.length;
    await frame(t, "c1", { type: "ping" }); // unknown socket: silently ignored
    expect(t.ports.realtime.sent.length).toBe(before);
  });

  it("typing goes to other members only; non-members get an error frame", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const c = await enrollUser(t.app, "C");
    const x = await enrollUser(t.app, "X");
    const g = await groupConversation(t.app, a, "G", [b, c]);
    const connA = await connect(t, a);
    const connA2 = await connect(t, a);
    const connB = await connect(t, b);
    const connX = await connect(t, x);
    t.ports.realtime.clear();

    await frame(t, connA, { type: "typing", convId: g.convId });
    expect(t.ports.realtime.ofType("typing", connB)).toEqual([{ type: "typing", convId: g.convId, userId: a.userId, at: t.ports.clock.now() }]);
    expect(t.ports.realtime.ofType("typing", connA)).toHaveLength(0);
    expect(t.ports.realtime.ofType("typing", connA2)).toHaveLength(0);

    await frame(t, connX, { type: "typing", convId: g.convId });
    expect(t.ports.realtime.eventsFor(connX).at(-1)).toMatchObject({ type: "error", code: "forbidden" });
    expect(t.ports.realtime.ofType("typing")).toHaveLength(1);
    await frame(t, connA, { type: "typing" });
    expect(t.ports.realtime.eventsFor(connA).at(-1)).toMatchObject({ type: "error", code: "invalid_request" });
  });

  it("call.signal routing: before answer caller->all callee devices, callee->caller device; after answer strictly the pair", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const lcA = await call(t.app, "POST", "/me/link-code", { token: a.token });
    const a2 = await enrollWithCode(t.app, lcA.body.code);
    const b = await enrollUser(t.app, "B");
    const lcB = await call(t.app, "POST", "/me/link-code", { token: b.token });
    const b2 = await enrollWithCode(t.app, lcB.body.code);
    const x = await enrollUser(t.app, "X");
    const d = await directConversation(t.app, a, b);
    const connA = await connect(t, a, "A");
    const connA2 = await connect(t, a2, "A2");
    const connB = await connect(t, b, "B");
    const connB2 = await connect(t, b2, "B2");
    const connX = await connect(t, x, "X");

    const started = await call(t.app, "POST", "/calls", { token: a.token, body: { convId: d.convId, type: "audio" } });
    const callId = started.body.callId as string;
    const env = makeEnvelope("x_k");
    t.ports.realtime.clear();

    // Caller -> every callee device (all phones may still answer).
    await frame(t, connA, { type: "call.signal", callId, envelope: env });
    expect(t.ports.realtime.ofType("call.signal", connB)).toEqual([{ type: "call.signal", callId, fromDeviceId: a.deviceId, envelope: env }]);
    expect(t.ports.realtime.ofType("call.signal", connB2)).toHaveLength(1);
    expect(t.ports.realtime.ofType("call.signal", connA2)).toHaveLength(0);

    // Any callee device -> the caller device only (not the caller's other phone).
    t.ports.realtime.clear();
    await frame(t, connB2, { type: "call.signal", callId, envelope: env });
    expect(t.ports.realtime.ofType("call.signal", connA)).toHaveLength(1);
    expect(t.ports.realtime.ofType("call.signal", connA).at(0)?.fromDeviceId).toBe(b2.deviceId);
    expect(t.ports.realtime.ofType("call.signal", connA2)).toHaveLength(0);

    // Strangers and the caller's other device are rejected.
    for (const bad of [connX, connA2]) {
      t.ports.realtime.clear();
      await frame(t, bad, { type: "call.signal", callId, envelope: env });
      expect(t.ports.realtime.eventsFor(bad).at(-1)).toMatchObject({ type: "error", code: "forbidden" });
      expect(t.ports.realtime.ofType("call.signal")).toHaveLength(0);
    }

    // After B answers, only B's device and A's device talk.
    await call(t.app, "POST", `/calls/${callId}/answer`, { token: b.token });
    t.ports.realtime.clear();
    await frame(t, connA, { type: "call.signal", callId, envelope: env });
    expect(t.ports.realtime.ofType("call.signal", connB)).toHaveLength(1);
    expect(t.ports.realtime.ofType("call.signal", connB2)).toHaveLength(0);
    await frame(t, connB, { type: "call.signal", callId, envelope: env });
    expect(t.ports.realtime.ofType("call.signal", connA)).toHaveLength(1);
    await frame(t, connB2, { type: "call.signal", callId, envelope: env });
    expect(t.ports.realtime.eventsFor(connB2).at(-1)).toMatchObject({ type: "error", code: "forbidden" });

    // Bad payloads and ended calls.
    await frame(t, connA, { type: "call.signal", callId, envelope: { v: 1 } });
    expect(t.ports.realtime.eventsFor(connA).at(-1)).toMatchObject({ type: "error", code: "invalid_request" });
    await frame(t, connA, { type: "call.signal", callId: "k_missing", envelope: env });
    expect(t.ports.realtime.eventsFor(connA).at(-1)).toMatchObject({ type: "error", code: "not_found" });
    await call(t.app, "POST", `/calls/${callId}/end`, { token: a.token, body: { reason: "hangup" } });
    await frame(t, connA, { type: "call.signal", callId, envelope: env });
    expect(t.ports.realtime.eventsFor(connA).at(-1)).toMatchObject({ type: "error", code: "conflict" });
  });
});
