/**
 * Read-through cache for the hot records (docs/DATA_MODEL.md "Caching").
 * Every write path must call the matching `invalidate*` so a warm container
 * never serves a stale record it just changed itself.
 */
import type { ConversationRecord, DeviceRecord, MembershipRecord, Ports, UserRecord } from "./ports.js";

export const CACHE_TTL = {
  sessionMs: 60_000,
  deviceMs: 60_000,
  userMs: 60_000,
  conversationMs: 30_000,
  connectionsMs: 5_000,
  settingsMs: 300_000,
} as const;

export interface LoadedConversation {
  conv: ConversationRecord;
  members: MembershipRecord[];
}

export async function getUser(ports: Ports, userId: string): Promise<UserRecord | null> {
  const key = `user:${userId}`;
  const hit = await ports.cache.get<UserRecord>(key);
  if (hit) return hit;
  const user = await ports.db.users.get(userId);
  if (user) await ports.cache.set(key, user, CACHE_TTL.userMs);
  return user;
}

export async function invalidateUser(ports: Ports, userId: string): Promise<void> {
  await ports.cache.del(`user:${userId}`);
}

export async function getDevice(ports: Ports, deviceId: string): Promise<DeviceRecord | null> {
  const key = `dev:${deviceId}`;
  const hit = await ports.cache.get<DeviceRecord>(key);
  if (hit) return hit;
  const device = await ports.db.devices.get(deviceId);
  if (device) await ports.cache.set(key, device, CACHE_TTL.deviceMs);
  return device;
}

export async function invalidateDevice(ports: Ports, deviceId: string): Promise<void> {
  await ports.cache.del(`dev:${deviceId}`);
}

/** Conversation META + members, cached together. */
export async function getConversation(ports: Ports, convId: string): Promise<LoadedConversation | null> {
  const key = `conv:${convId}`;
  const hit = await ports.cache.get<LoadedConversation>(key);
  if (hit) return hit;
  const conv = await ports.db.conversations.get(convId);
  if (!conv) return null;
  const members = await ports.db.conversations.members(convId);
  const loaded = { conv, members };
  await ports.cache.set(key, loaded, CACHE_TTL.conversationMs);
  return loaded;
}

export async function invalidateConversation(ports: Ports, convId: string): Promise<void> {
  await ports.cache.del(`conv:${convId}`);
}
