/**
 * Conversation keys (PROTOCOL.md §7). The server stores ECIES-wrapped blobs
 * it cannot open and tracks which device already holds the current epoch.
 */
import type { KeyRecipient, KeyRecipientsResponse, KeysResponse, WrappedKey } from "../../protocol/types.js";
import { resolveSession } from "../auth.js";
import { invalidateConversation, getConversation } from "../cached.js";
import { badRequest, forbidden } from "../errors.js";
import { isObject, json, readJson } from "../http.js";
import type { DeviceRecord, Ports } from "../ports.js";
import { sendToDevices, sendToUsers } from "../realtime/fanout.js";
import type { Params } from "../router.js";
import { toConversation, toPublicDevice } from "../dto.js";
import { requireString } from "../validate.js";
import { requireMember } from "./messages.js";

const MAX_WRAPS_PER_POST = 500;

/** Non-revoked devices of every current member, keyed by deviceId. */
async function memberDevices(ports: Ports, memberIds: string[]): Promise<Map<string, DeviceRecord>> {
  const out = new Map<string, DeviceRecord>();
  for (const userId of memberIds) {
    for (const d of await ports.db.devices.listByUser(userId)) {
      if (!d.revokedAt) out.set(d.deviceId, d);
    }
  }
  return out;
}

export async function listKeys(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const loaded = await requireMember(ports, convId, p.userId);
  const items = await ports.db.keys.listForDevice(convId, p.deviceId);
  const body: KeysResponse = { currentKeyId: loaded.conv.currentKeyId ?? null, items };
  return json(body);
}

export async function keyRecipients(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const loaded = await requireMember(ports, convId, p.userId);
  const currentKeyId = loaded.conv.currentKeyId ?? null;
  const holders = new Set(currentKeyId ? await ports.db.keys.recipientsOf(convId, currentKeyId) : []);
  const devices: KeyRecipient[] = [];
  for (const d of (await memberDevices(ports, loaded.members.map((m) => m.userId))).values()) {
    devices.push({ ...toPublicDevice(d), hasCurrentKey: holders.has(d.deviceId) });
  }
  const body: KeyRecipientsResponse = { currentKeyId, keyRotationRequired: loaded.conv.keyRotationRequired, devices };
  return json(body);
}

export async function postKeys(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const loaded = await requireMember(ports, convId, p.userId);
  const body = await readJson<Record<string, unknown>>(req);
  const keyId = requireString(body, "keyId", 256);
  const wrappedRaw = body.wrapped;
  if (!Array.isArray(wrappedRaw) || wrappedRaw.length === 0) throw badRequest("wrapped must be a non-empty array");
  if (wrappedRaw.length > MAX_WRAPS_PER_POST) throw badRequest("too many wraps in one request");

  const devices = await memberDevices(ports, loaded.members.map((m) => m.userId));
  const now = ports.clock.now();
  const wraps: WrappedKey[] = [];
  for (const w of wrappedRaw) {
    if (!isObject(w)) throw badRequest("each wrap must be an object");
    const wrap: WrappedKey = {
      keyId: requireString(w, "keyId", 256),
      convId: requireString(w, "convId", 256),
      recipientDeviceId: requireString(w, "recipientDeviceId", 256),
      senderDeviceId: requireString(w, "senderDeviceId", 256),
      ephemeralPublicKey: requireString(w, "ephemeralPublicKey", 1024),
      iv: requireString(w, "iv", 256),
      ciphertext: requireString(w, "ciphertext", 4096),
      signature: requireString(w, "signature", 1024),
      createdAt: now,
    };
    if (wrap.keyId !== keyId) throw badRequest("wrap.keyId must match keyId");
    if (wrap.convId !== convId) throw badRequest("wrap.convId must match the conversation");
    // Only the calling device may sign wraps, and only current members' devices may receive them.
    if (wrap.senderDeviceId !== p.deviceId) throw forbidden("senderDeviceId must be the calling device");
    if (!devices.has(wrap.recipientDeviceId)) throw forbidden(`recipient ${wrap.recipientDeviceId} is not a member device`);
    wraps.push(wrap);
  }

  for (const wrap of wraps) await ports.db.keys.put(wrap);

  const before = loaded.conv.currentKeyId ?? null;
  const updated = await ports.db.conversations.setCurrentKey(convId, keyId);
  await sendToDevices(ports, wraps.map((w) => w.recipientDeviceId), { type: "conversation.keys", convId, keyId });
  if (updated.currentKeyId !== before) {
    await invalidateConversation(ports, convId);
    const fresh = await getConversation(ports, convId);
    if (fresh) {
      await sendToUsers(ports, fresh.members.map((m) => m.userId), { type: "conversation.updated", conversation: toConversation(fresh) });
    }
  }
  return json({ currentKeyId: updated.currentKeyId }, 201);
}
