/**
 * Self, users and devices (PROTOCOL.md §5).
 */
import { resolveSession } from "../auth.js";
import { getConversation, getUser, invalidateConversation, invalidateDevice, invalidateUser } from "../cached.js";
import { hashPassword, verifyPassword } from "../crypto.js";
import { HttpError, badRequest, notFound } from "../errors.js";
import { json, noContent, readJson } from "../http.js";
import type { DeviceRecord, InviteRecord, Ports } from "../ports.js";
import { invalidateConnections, sendToUsers } from "../realtime/fanout.js";
import type { Params } from "../router.js";
import { toConversation, toDevice, toInvite, toMe, toPublicDevice, toUser } from "../dto.js";
import { knownUserIds } from "./friends.js";
import { optionalString, requireString } from "../validate.js";

const LINK_CODE_TTL_MS = 24 * 60 * 60 * 1000;
const MIN_PASSWORD_LENGTH = 6;

// ---------- shared helpers ----------

/** Every user who shares a conversation with `userId`, including the user (their other devices). */
export async function coMemberIds(ports: Ports, userId: string): Promise<Set<string>> {
  const ids = new Set<string>([userId]);
  for (const m of await ports.db.conversations.listByUser(userId)) {
    const loaded = await getConversation(ports, m.convId);
    for (const member of loaded?.members ?? []) ids.add(member.userId);
  }
  return ids;
}

/**
 * Revoke a device: its sessions stop working (device_revoked), its live
 * connections are forgotten, and every conversation the user is in must
 * rotate its key because that device may still hold the current one.
 */
export async function revokeDevice(ports: Ports, device: DeviceRecord): Promise<void> {
  if (device.revokedAt) return;
  const now = ports.clock.now();
  await ports.db.devices.update(device.deviceId, { revokedAt: now });
  await invalidateDevice(ports, device.deviceId);

  for (const conn of await ports.db.connections.listByUser(device.userId)) {
    if (conn.deviceId === device.deviceId) await ports.db.connections.remove(conn.connectionId);
  }
  await invalidateConnections(ports, device.userId);

  for (const m of await ports.db.conversations.listByUser(device.userId)) {
    await ports.db.conversations.update(m.convId, { keyRotationRequired: true });
    await invalidateConversation(ports, m.convId);
    const loaded = await getConversation(ports, m.convId);
    if (loaded) {
      await sendToUsers(ports, loaded.members.map((x) => x.userId), { type: "conversation.updated", conversation: toConversation(loaded) });
    }
  }
}

/** Create a 24 h invite bound to an existing user (a "link code" for a second phone). */
export async function createLinkCode(ports: Ports, userId: string): Promise<InviteRecord> {
  const user = await getUser(ports, userId);
  if (!user) throw notFound("user not found");
  const now = ports.clock.now();
  let code = ports.ids.inviteCode();
  while (await ports.db.invites.get(code)) code = ports.ids.inviteCode(); // 40-bit codes: collisions are rare but cheap to dodge
  const invite: InviteRecord = {
    code,
    kind: "link",
    displayName: user.displayName,
    role: user.role,
    forUserId: userId,
    autoJoin: [],
    createdAt: now,
    expiresAt: now + LINK_CODE_TTL_MS,
    usedAt: null,
    usedByUserId: null,
    createdBy: userId,
  };
  await ports.db.invites.put(invite);
  return invite;
}

// ---------- handlers ----------

export async function getMe(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  return json(toMe(p.user));
}

export async function patchMe(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const displayName = optionalString(body, "displayName", 128);
  const avatarKey = optionalString(body, "avatarKey", 512);
  const patch: { displayName?: string; avatarKey?: string | null } = {};
  if (displayName !== undefined) {
    if (displayName === null || displayName.trim().length === 0) throw badRequest("displayName must be a non-empty string");
    patch.displayName = displayName.trim();
  }
  if (avatarKey !== undefined) patch.avatarKey = avatarKey;
  if (Object.keys(patch).length === 0) throw badRequest("nothing to update");

  const user = await ports.db.users.update(p.userId, patch);
  await invalidateUser(ports, p.userId);
  await sendToUsers(ports, await coMemberIds(ports, p.userId), { type: "user.updated", user: toUser(user) });
  return json(toUser(user));
}

export async function changePassword(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const newPassword = requireString(body, "newPassword", 1024);
  if (newPassword.length < MIN_PASSWORD_LENGTH) throw badRequest(`newPassword must be at least ${MIN_PASSWORD_LENGTH} characters`);
  // Setting a first (backup) password needs only the session; changing one needs the old one.
  if (p.user.passwordHash) {
    const currentPassword = optionalString(body, "currentPassword", 1024) ?? "";
    if (!(await verifyPassword(currentPassword, p.user.passwordHash))) {
      throw new HttpError("bad_credentials", "current password is wrong");
    }
  }
  await ports.db.users.update(p.userId, { passwordHash: await hashPassword(newPassword) });
  await invalidateUser(ports, p.userId);
  return noContent();
}

export async function listMyDevices(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const devices = await ports.db.devices.listByUser(p.userId);
  return json({ items: devices.map(toDevice), hasMore: false });
}

export async function revokeMyDevice(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const device = await ports.db.devices.get(params.deviceId ?? "");
  if (!device || device.userId !== p.userId) throw notFound("device not found");
  await revokeDevice(ports, device);
  return noContent();
}

export async function linkCode(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const invite = await createLinkCode(ports, p.userId);
  return json(toInvite(invite, ports.config.apiUrl), 201);
}

/** GET /users — only the people I know: friends, people in my conversations, and my (family) circle. */
export async function listUsers(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const known = await knownUserIds(ports, p.userId);
  const users = (await ports.db.users.list()).filter((u) => known.has(u.userId));
  return json({ items: users.map(toUser), hasMore: false });
}

/** Non-revoked devices only: these are the keys other phones wrap conversation keys for. */
export async function listUserDevices(ports: Ports, req: Request, params: Params): Promise<Response> {
  await resolveSession(ports, req);
  const userId = params.userId ?? "";
  if (!(await getUser(ports, userId))) throw notFound("user not found");
  const devices = (await ports.db.devices.listByUser(userId)).filter((d) => !d.revokedAt);
  return json({ items: devices.map(toPublicDevice), hasMore: false });
}
