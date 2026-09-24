/**
 * 1:1 calls (PROTOCOL.md §11). The server keeps the ringing/active/ended state
 * machine, relays the lifecycle events and writes one `call` message when a
 * call ends. SDP/ICE travel as encrypted call.signal frames (realtime/ws.ts).
 */
import type { Call, CallOutcome, EndReason, ServerEvent } from "../../protocol/types.js";
import { resolveSession, type Principal } from "../auth.js";
import { HttpError, badRequest, conflict, forbidden, notFound } from "../errors.js";
import { isBlockedBy } from "./friends.js";
import { json, readJson } from "../http.js";
import type { CallRecord, Ports } from "../ports.js";
import { invalidateConnections, listConnections, sendToConnection, sendToUsers } from "../realtime/fanout.js";
import type { Params } from "../router.js";
import { toUser } from "../dto.js";
import { optionalEnum, requireEnum, requireString } from "../validate.js";
import { appendMessage, requireMember } from "./messages.js";

const END_REASONS = ["hangup", "timeout", "failed", "cancelled"] as const;

async function loadCall(ports: Ports, callId: string): Promise<CallRecord> {
  const call = await ports.db.calls.get(callId);
  if (!call) throw notFound("call not found");
  return call;
}

function requireParty(p: Principal, call: CallRecord): void {
  if (p.userId !== call.callerId && p.userId !== call.calleeId) throw forbidden("not a party to this call");
}

/** Write the call log entry (sender = caller) once the call is over. */
async function appendCallMessage(ports: Ports, call: CallRecord, outcome: CallOutcome, durationMs: number | null): Promise<void> {
  await appendMessage(ports, {
    convId: call.convId,
    senderId: call.callerId,
    senderDeviceId: call.callerDeviceId,
    clientId: null,
    kind: "call",
    envelope: null,
    call: { callId: call.callId, type: call.type, outcome, durationMs },
    system: null,
    sentAt: call.endedAt ?? ports.clock.now(),
  });
}

/**
 * Move a ringing/active call to ended, log it and tell both parties.
 * Ending an already ended call returns it unchanged (idempotent).
 */
async function endCall(ports: Ports, call: CallRecord, endReason: EndReason, outcome: CallOutcome): Promise<Call> {
  if (call.state === "ended") return call;
  const now = ports.clock.now();
  const ended = await ports.db.calls.update(call.callId, { state: "ended", endedAt: now, endReason });
  const durationMs = outcome === "answered" && call.answeredAt != null ? now - call.answeredAt : null;
  await appendCallMessage(ports, ended, outcome, durationMs);
  await sendToUsers(ports, [call.callerId, call.calleeId], { type: "call.ended", call: ended, reason: endReason });
  return ended;
}

// ---------- handlers ----------

export async function startCall(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const convId = requireString(body, "convId", 256);
  const type = requireEnum(body, "type", ["audio", "video"] as const);
  const loaded = await requireMember(ports, convId, p.userId);
  if (loaded.conv.type !== "direct") throw badRequest("calls are only available in direct conversations");
  const callee = loaded.members.find((m) => m.userId !== p.userId);
  if (!callee) throw badRequest("conversation has no other member");
  if (await isBlockedBy(ports, callee.userId, p.userId)) throw new HttpError("blocked", "this person is not accepting calls from you");

  const now = ports.clock.now();
  const call: CallRecord = {
    callId: `k_${ports.ids.ulid(now)}`,
    convId,
    type,
    callerId: p.userId,
    callerDeviceId: p.deviceId,
    calleeId: callee.userId,
    calleeDeviceId: null,
    state: "ringing",
    createdAt: now,
    answeredAt: null,
    endedAt: null,
    endReason: null,
  };

  // Calls are rare and reachability must be exact, so bypass the short-lived
  // connection cache (a $disconnect handled by the WebSocket function is not
  // visible to this container's cache until the TTL passes).
  await invalidateConnections(ports, callee.userId);
  const calleeConns = await ports.db.connections.listByUser(callee.userId);
  let delivered = 0;
  if (calleeConns.length > 0) {
    await ports.db.calls.put(call);
    const invite: ServerEvent = { type: "call.invite", call, caller: toUser(p.user) };
    for (const conn of calleeConns) {
      if (await sendToConnection(ports, conn, invite)) delivered++;
    }
    if (delivered > 0) return json(call, 201);
  }

  // Nobody to ring (no live connection, or every connection turned out to be
  // gone): log it straight away so the callee sees a missed call on next sync.
  call.state = "ended";
  call.endedAt = now;
  call.endReason = "unreachable";
  await ports.db.calls.put(call);
  await appendCallMessage(ports, call, "unreachable", null);
  return json(call, 201);
}

export async function answerCall(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const call = await loadCall(ports, params.id ?? "");
  if (p.userId !== call.calleeId) throw forbidden("only the callee can answer");
  if (call.state === "active" && call.calleeDeviceId === p.deviceId) return json(call); // idempotent retry
  if (call.state !== "ringing") throw conflict(`call is ${call.state}`);

  const now = ports.clock.now();
  const active = await ports.db.calls.update(call.callId, { state: "active", calleeDeviceId: p.deviceId, answeredAt: now });
  await sendToUsers(ports, [call.callerId], { type: "call.answered", call: active });
  // The callee's other phones stop ringing.
  const others = (await listConnections(ports, call.calleeId)).filter((c) => c.deviceId !== p.deviceId);
  for (const conn of others) {
    await sendToConnection(ports, conn, { type: "call.ended", call: active, reason: "answered_elsewhere" });
  }
  return json(active);
}

export async function rejectCall(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const call = await loadCall(ports, params.id ?? "");
  if (p.userId !== call.calleeId) throw forbidden("only the callee can reject");
  if (call.state === "active") throw conflict("call already answered; use end");
  return json(await endCall(ports, call, "rejected", "rejected"));
}

export async function endCallHandler(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const call = await loadCall(ports, params.id ?? "");
  requireParty(p, call);
  const body = await readJson<Record<string, unknown>>(req);
  const reason = optionalEnum(body, "reason", END_REASONS) ?? "hangup";
  if (call.state === "ended") return json(call);

  let endReason: EndReason;
  let outcome: CallOutcome;
  if (call.state === "active") {
    endReason = reason;
    outcome = "answered";
  } else if (reason === "timeout") {
    endReason = "timeout";
    outcome = "missed";
  } else if (p.userId === call.callerId) {
    endReason = "cancelled";
    outcome = "cancelled";
  } else {
    endReason = "rejected";
    outcome = "rejected";
  }
  return json(await endCall(ports, call, endReason, outcome));
}

export async function getCall(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const call = await loadCall(ports, params.id ?? "");
  requireParty(p, call);
  return json(call);
}
