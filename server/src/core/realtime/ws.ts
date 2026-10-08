/**
 * WebSocket lifecycle (PROTOCOL.md §12). The adapter owns the socket; core
 * owns the connection registry, the client frames and call.signal routing.
 */
import type { ClientEvent, ServerEvent } from "../../protocol/types.js";
import type { App } from "../app-contract.js";
import { resolveSessionByToken } from "../auth.js";
import { getConversation, getUser, invalidateDevice, invalidateUser } from "../cached.js";
import { toUser } from "../dto.js";
import { isBlockedBy } from "../services/friends.js";
import { coMemberIds } from "../services/users.js";
import { HttpError, badRequest, conflict, forbidden, notFound } from "../errors.js";
import { isObject } from "../http.js";
import type { ConnectionRecord, Ports } from "../ports.js";
import { validateEnvelope } from "../services/messages.js";
import { invalidateConnections, sendToConnection, sendToDevices, sendToUsers } from "./fanout.js";

export function createWsHandlers(ports: Ports): App["ws"] {
  /** Reply to the connection that sent us a frame. */
  const reply = (conn: ConnectionRecord, event: ServerEvent) => sendToConnection(ports, conn, event);

  const replyError = (conn: ConnectionRecord, e: unknown) => {
    if (e instanceof HttpError) return reply(conn, { type: "error", code: e.code, message: e.message });
    console.error(`ws frame failed: ${e instanceof Error ? e.message : "unknown error"}`);
    return reply(conn, { type: "error", code: "internal", message: "internal error" });
  };

  async function handleTyping(conn: ConnectionRecord, convId: string): Promise<void> {
    const loaded = await getConversation(ports, convId);
    if (!loaded || !loaded.members.some((m) => m.userId === conn.userId)) throw forbidden("not a member of this conversation");
    const others = loaded.members.map((m) => m.userId).filter((id) => id !== conn.userId);
    await sendToUsers(ports, others, { type: "typing", convId, userId: conn.userId, at: ports.clock.now() });
  }

  /**
   * The app reports that it was opened (`active: true`, repeated about once a minute while it stays
   * open) or closed (`active: false`). "Last seen" is the time of the latest report, so it means
   * "last had Guftugu open", not "last logged in"; the always-on background connection doesn't
   * count. People who share a conversation get `user.updated`, except anyone this user blocked.
   */
  async function handlePresence(conn: ConnectionRecord, active: boolean): Promise<void> {
    const now = ports.clock.now();
    const current = await getUser(ports, conn.userId);
    // A heartbeat within 20 s of the last stamp changes nothing visible: skip the write and fan-out.
    if (active && current?.lastSeenAt != null && now - current.lastSeenAt < 20_000) return;
    const user = await ports.db.users.update(conn.userId, { lastSeenAt: now });
    await invalidateUser(ports, conn.userId);
    const audience: string[] = [];
    for (const id of await coMemberIds(ports, conn.userId)) {
      if (id !== conn.userId && !(await isBlockedBy(ports, conn.userId, id))) audience.push(id);
    }
    if (audience.length > 0) await sendToUsers(ports, audience, { type: "user.updated", user: toUser(user) });
  }

  /**
   * Relay an encrypted signal to the other party's negotiating device.
   * Before answer: caller -> every callee connection (all their phones ring);
   *                any callee device -> the caller device.
   * After answer:  strictly callerDeviceId <-> calleeDeviceId.
   */
  async function handleCallSignal(conn: ConnectionRecord, frame: Extract<ClientEvent, { type: "call.signal" }>): Promise<void> {
    if (typeof frame.callId !== "string" || frame.callId.length === 0) throw badRequest("callId is required");
    const envelope = validateEnvelope(frame.envelope);
    const call = await ports.db.calls.get(frame.callId);
    if (!call) throw notFound("call not found");
    if (call.state === "ended") throw conflict("call has ended");

    const event: ServerEvent = { type: "call.signal", callId: call.callId, fromDeviceId: conn.deviceId, envelope };
    const isCallerDevice = conn.deviceId === call.callerDeviceId;

    if (call.state === "active" && call.calleeDeviceId) {
      if (isCallerDevice) await sendToDevices(ports, [call.calleeDeviceId], event);
      else if (conn.deviceId === call.calleeDeviceId) await sendToDevices(ports, [call.callerDeviceId], event);
      else throw forbidden("not a negotiating device of this call");
      return;
    }
    if (isCallerDevice) await sendToUsers(ports, [call.calleeId], event);
    else if (conn.userId === call.calleeId) await sendToDevices(ports, [call.callerDeviceId], event);
    else throw forbidden("not a party to this call");
  }

  return {
    async onConnect(connectionId, token) {
      if (!token) return { ok: false, status: 401 };
      let principal;
      try {
        principal = await resolveSessionByToken(ports, token);
      } catch (e) {
        return { ok: false, status: e instanceof HttpError ? e.status : 500 };
      }
      const now = ports.clock.now();
      await ports.db.connections.put({ connectionId, userId: principal.userId, deviceId: principal.deviceId, connectedAt: now });
      await invalidateConnections(ports, principal.userId);
      await ports.db.devices.update(principal.deviceId, { lastSeenAt: now });
      await invalidateDevice(ports, principal.deviceId);
      return { ok: true, userId: principal.userId, deviceId: principal.deviceId };
    },

    async onDisconnect(connectionId) {
      const conn = await ports.db.connections.get(connectionId);
      if (!conn) return;
      await ports.db.connections.remove(connectionId);
      await invalidateConnections(ports, conn.userId);
    },

    async onMessage(connectionId, raw) {
      const conn = await ports.db.connections.get(connectionId);
      if (!conn) return; // unknown socket: nothing to reply on
      let frame: unknown;
      try {
        frame = JSON.parse(raw);
      } catch {
        await reply(conn, { type: "error", code: "invalid_request", message: "malformed JSON frame" });
        return;
      }
      if (!isObject(frame) || typeof frame.type !== "string") {
        await reply(conn, { type: "error", code: "invalid_request", message: "frame needs a type" });
        return;
      }
      try {
        switch (frame.type) {
          case "hello":
            await reply(conn, { type: "hello", userId: conn.userId, deviceId: conn.deviceId, connectionId, serverTime: ports.clock.now() });
            return;
          case "ping":
            await reply(conn, { type: "pong", serverTime: ports.clock.now() });
            return;
          case "typing":
            if (typeof frame.convId !== "string" || frame.convId.length === 0) throw badRequest("convId is required");
            await handleTyping(conn, frame.convId);
            return;
          case "presence":
            if (typeof frame.active !== "boolean") throw badRequest("active must be true or false");
            await handlePresence(conn, frame.active);
            return;
          case "call.signal":
            await handleCallSignal(conn, frame as Extract<ClientEvent, { type: "call.signal" }>);
            return;
          default:
            throw badRequest(`unknown frame type ${frame.type}`);
        }
      } catch (e) {
        await replyError(conn, e);
      }
    },
  };
}
