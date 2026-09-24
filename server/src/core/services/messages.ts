/**
 * Messages (PROTOCOL.md §8) — opaque envelopes plus server-written
 * system/call entries. `appendMessage` is the single write path used by every
 * service, so the conversation's lastMsgId and the message.new fan-out always
 * happen together.
 */
import { isBlockedBy } from "./friends.js";
import type { Envelope, Message, SystemInfo } from "../../protocol/types.js";
import { resolveSession, type Principal } from "../auth.js";
import { isBase64Url } from "../base64url.js";
import { getConversation, invalidateConversation, type LoadedConversation } from "../cached.js";
import { HttpError, badRequest, forbidden, notFound } from "../errors.js";
import { isObject, json, noContent, query, readJson } from "../http.js";
import { DEFAULT_TTLS, type NewMessage, type Ports } from "../ports.js";
import { sendToUsers } from "../realtime/fanout.js";
import type { Params } from "../router.js";
import { requireNumber, requireString } from "../validate.js";

const DEFAULT_LIMIT = 50;
const MAX_LIMIT = 200;

// ---------- shared helpers ----------

/** Conversation + the caller's membership. Missing -> not_found; not a member -> forbidden. */
export async function requireMember(
  ports: Ports,
  convId: string,
  userId: string,
): Promise<LoadedConversation & { member: LoadedConversation["members"][number] }> {
  const loaded = await getConversation(ports, convId);
  if (!loaded) throw notFound("conversation not found");
  const member = loaded.members.find((m) => m.userId === userId);
  if (!member) throw forbidden("not a member of this conversation");
  return { ...loaded, member };
}

/** Validate an envelope's shape and return a copy containing only the protocol fields. */
export function validateEnvelope(v: unknown): Envelope {
  if (!isObject(v)) throw badRequest("envelope must be an object");
  if (v.v !== 1) throw badRequest("envelope.v must be 1");
  const keyId = v.keyId;
  if (typeof keyId !== "string" || keyId.length === 0) throw badRequest("envelope.keyId must be a non-empty string");
  if (!isBase64Url(v.iv)) throw badRequest("envelope.iv must be base64url");
  if (!isBase64Url(v.ct)) throw badRequest("envelope.ct must be base64url");
  return { v: 1, keyId, iv: v.iv, ct: v.ct };
}

/**
 * Store a message; on a genuine (non-duplicate) append also bump the
 * conversation's lastMsgId/lastMessageAt and push message.new to every member.
 */
export async function appendMessage(ports: Ports, msg: NewMessage): Promise<{ message: Message; duplicate: boolean }> {
  const result = await ports.db.messages.append(msg, ports.clock.now());
  if (!result.duplicate) {
    await ports.db.conversations.update(msg.convId, {
      lastMsgId: result.message.msgId,
      lastMessageAt: result.message.createdAt,
    });
    await invalidateConversation(ports, msg.convId);
    const loaded = await getConversation(ports, msg.convId);
    if (loaded) {
      await sendToUsers(ports, loaded.members.map((m) => m.userId), { type: "message.new", message: result.message });
    }
  }
  return result;
}

/** A membership/rename event written by the server on behalf of `actorId`. */
export async function appendSystemMessage(ports: Ports, convId: string, actorId: string, system: SystemInfo): Promise<Message> {
  const { message } = await appendMessage(ports, {
    convId,
    senderId: actorId,
    senderDeviceId: null,
    clientId: null,
    kind: "system",
    envelope: null,
    call: null,
    system,
    sentAt: ports.clock.now(),
  });
  return message;
}

// ---------- handlers ----------

export async function listMessages(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  await requireMember(ports, convId, p.userId);

  const after = query(req, "after");
  const before = query(req, "before");
  if (after !== null && before !== null) throw badRequest("use either after or before, not both");
  if (after === "" || before === "") throw badRequest("after/before must not be empty");

  const rawLimit = query(req, "limit");
  let limit = DEFAULT_LIMIT;
  if (rawLimit !== null) {
    if (!/^\d+$/.test(rawLimit)) throw badRequest("limit must be a positive integer");
    limit = Number(rawLimit);
    if (limit < 1 || limit > MAX_LIMIT) throw badRequest(`limit must be between 1 and ${MAX_LIMIT}`);
  }

  const page =
    after !== null
      ? await ports.db.messages.listAfter(convId, after, limit)
      : await ports.db.messages.listBefore(convId, before, limit);
  return json({ items: page.items, hasMore: page.hasMore });
}

export async function sendMessage(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const member = await requireMember(ports, convId, p.userId);
  if (member.conv.type === "direct") {
    const other = member.members.find((m) => m.userId !== p.userId);
    if (other && (await isBlockedBy(ports, other.userId, p.userId))) {
      throw new HttpError("blocked", "this person is not accepting messages from you");
    }
  }

  const body = await readJson<Record<string, unknown>>(req);
  const clientId = requireString(body, "clientId", 256);
  const sentAt = requireNumber(body, "sentAt");
  const envelope = validateEnvelope(body.envelope);
  // Attachments go to blob storage; envelopes stay small.
  if (new TextEncoder().encode(JSON.stringify(envelope)).length > DEFAULT_TTLS.maxEnvelopeBytes) {
    throw new HttpError("payload_too_large", `envelope exceeds ${DEFAULT_TTLS.maxEnvelopeBytes} bytes`);
  }

  const { message, duplicate } = await appendMessage(ports, {
    convId,
    senderId: p.userId,
    senderDeviceId: p.deviceId,
    clientId,
    kind: "e2e",
    envelope,
    call: null,
    system: null,
    sentAt,
  });
  return json({ message }, duplicate ? 200 : 201);
}

function canDelete(p: Principal, loaded: LoadedConversation, message: Message): boolean {
  if (message.senderId === p.userId) return true;
  if (p.user.role === "admin") return true;
  const me = loaded.members.find((m) => m.userId === p.userId);
  return loaded.conv.type === "group" && me?.role === "owner";
}

export async function deleteMessage(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const msgId = params.msgId ?? "";
  const loaded = await requireMember(ports, convId, p.userId);

  const existing = await ports.db.messages.get(convId, msgId);
  if (!existing) throw notFound("message not found");
  if (existing.deletedAt) return json({ message: existing }); // already a tombstone: idempotent
  if (!canDelete(p, loaded, existing)) throw forbidden("only the sender, the group owner or an admin can delete");

  const message = await ports.db.messages.markDeleted(convId, msgId, ports.clock.now());
  await sendToUsers(ports, loaded.members.map((m) => m.userId), {
    type: "message.deleted",
    convId,
    msgId,
    deletedAt: message.deletedAt ?? ports.clock.now(),
  });
  return json({ message });
}

export async function markRead(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const loaded = await requireMember(ports, convId, p.userId);
  const body = await readJson<Record<string, unknown>>(req);
  const msgId = requireString(body, "msgId", 256);

  await ports.db.conversations.setLastRead(convId, p.userId, msgId);
  await invalidateConversation(ports, convId);
  await sendToUsers(ports, loaded.members.map((m) => m.userId), {
    type: "conversation.read",
    convId,
    userId: p.userId,
    msgId,
    at: ports.clock.now(),
  });
  return noContent();
}
