/**
 * Enrollment (PROTOCOL.md §3): an invite code registers a new device's three
 * public keys, creating the user unless the invite is a link code bound to one.
 */
import { getConversation, invalidateConversation } from "../cached.js";
import { hashPassword, isValidSpkiP256 } from "../crypto.js";
import { HttpError, badRequest } from "../errors.js";
import { isObject, json, readJson } from "../http.js";
import type { DeviceRecord, InviteRecord, MembershipRecord, Ports, UserRecord } from "../ports.js";
import { sendToUsers } from "../realtime/fanout.js";
import { toConversation } from "../dto.js";
import { optionalString, requireString } from "../validate.js";
import { buildAuthResponse, createSession } from "./auth.js";
import { appendSystemMessage } from "./messages.js";
import { applyUserInvite, isUserInvite } from "./friends.js";

/** A passcode may be 6+ characters: guessing needs the enrolled phone (device key) and locks after 5 tries. */
const MIN_PASSWORD_LENGTH = 6;

function inviteError(invite: InviteRecord | null, now: number): HttpError {
  if (invite && !invite.usedAt && invite.expiresAt <= now) return new HttpError("invite_expired", "invite has expired");
  return new HttpError("invite_invalid", "invite code is unknown or already used");
}

/** Add `userId` to each conversation it is not already in, with a system message and fan-out. */
export async function joinConversations(ports: Ports, userId: string, convIds: Iterable<string>): Promise<void> {
  const now = ports.clock.now();
  for (const convId of new Set(convIds)) {
    const loaded = await getConversation(ports, convId);
    if (!loaded || loaded.members.some((m) => m.userId === userId)) continue;
    const member: MembershipRecord = { convId, userId, role: "member", joinedAt: now, lastReadMsgId: null };
    await ports.db.conversations.addMembers(convId, [member]);
    await invalidateConversation(ports, convId);
    await appendSystemMessage(ports, convId, userId, { event: "member_added", userIds: [userId] });
    const after = await getConversation(ports, convId);
    if (after) {
      await sendToUsers(ports, after.members.map((m) => m.userId), { type: "conversation.updated", conversation: toConversation(after) });
    }
  }
}

export async function enroll(ports: Ports, req: Request): Promise<Response> {
  const body = await readJson<Record<string, unknown>>(req);
  const now = ports.clock.now();

  const inviteCode = requireString(body, "inviteCode", 64).trim().toUpperCase();
  const devicePublicKey = requireString(body, "devicePublicKey", 1024);
  const encryptionPublicKey = requireString(body, "encryptionPublicKey", 1024);
  const authPublicKey = optionalString(body, "authPublicKey", 1024) ?? null;
  if (!isObject(body.device)) throw badRequest("device must be an object");
  const deviceInfo = body.device;
  const deviceName = requireString(deviceInfo, "name", 128);
  const model = optionalString(deviceInfo, "model", 128) ?? null;
  const os = optionalString(deviceInfo, "os", 128) ?? null;
  const appVersion = optionalString(deviceInfo, "appVersion", 64) ?? null;

  // Validate everything we can before burning the single-use invite.
  const invite = await ports.db.invites.get(inviteCode);
  if (!invite || invite.usedAt || invite.expiresAt <= now) throw inviteError(invite, now);

  if (!(await isValidSpkiP256(devicePublicKey))) throw badRequest("devicePublicKey is not a P-256 SPKI key");
  if (!(await isValidSpkiP256(encryptionPublicKey))) throw badRequest("encryptionPublicKey is not a P-256 SPKI key");
  if (authPublicKey !== null && !(await isValidSpkiP256(authPublicKey))) throw badRequest("authPublicKey is not a P-256 SPKI key");

  let existingUser: UserRecord | null = null;
  let userId: string;
  let displayName = "";
  let password: string | null = null;
  if (invite.forUserId) {
    // Link code: a second phone for an existing account. displayName/password are ignored.
    existingUser = await ports.db.users.get(invite.forUserId);
    if (!existingUser) throw new HttpError("invite_invalid", "invite is bound to an unknown user");
    if (existingUser.status === "disabled") throw new HttpError("user_disabled", "this account is disabled");
    userId = existingUser.userId;
  } else {
    displayName = (optionalString(body, "displayName", 128) ?? invite.displayName ?? "").trim();
    if (displayName.length === 0) throw badRequest("displayName is required");
    // Joining never needs a passcode (owner's rule, WhatsApp-style): the app asks the person to
    // create one about a week later. A phone without a fingerprint is then identified by its
    // hardware device key alone until a passcode exists (see auth.verify "device").
    password = optionalString(body, "password", 1024) ?? null;
    if (password === "") password = null;
    if (password !== null && password.length < MIN_PASSWORD_LENGTH) {
      throw badRequest(`password must be at least ${MIN_PASSWORD_LENGTH} characters`);
    }
    userId = `u_${ports.ids.ulid(now)}`;
  }

  // Exactly one enrollment wins the invite.
  const consumed = await ports.db.invites.consume(inviteCode, userId, now);
  if (!consumed) throw inviteError(await ports.db.invites.get(inviteCode), now);

  let user: UserRecord;
  if (existingUser) {
    user = existingUser;
  } else {
    user = {
      userId,
      displayName,
      avatarKey: null,
      role: invite.role,
      status: "active",
      createdAt: now,
      lastSeenAt: now,
      passwordHash: password !== null ? await hashPassword(password) : null,
      // Admin invites form the family circle; a friend's invite links the newcomer to that friend only.
      invitedBy: isUserInvite(consumed) ? consumed.createdBy : "admin",
    };
    await ports.db.users.put(user);
  }

  const device: DeviceRecord = {
    deviceId: `d_${ports.ids.ulid(now)}`,
    userId,
    name: deviceName,
    model,
    os,
    appVersion,
    devicePublicKey,
    authPublicKey,
    encryptionPublicKey,
    enrolledAt: now,
    lastSeenAt: now,
    revokedAt: null,
    failedPasswordAttempts: 0,
    lockedUntil: null,
  };
  await ports.db.devices.put(device);

  if (!existingUser && isUserInvite(consumed)) {
    // A friend's invite: become friends and open the 1:1 chat, or join the group it was for.
    await applyUserInvite(ports, consumed, userId);
  } else {
    // Admin invites: the invite's groups plus every group flagged autoJoin (the family circle).
    const convIds = [...invite.autoJoin];
    if (!existingUser) {
      for (const conv of await ports.db.conversations.listAutoJoin()) convIds.push(conv.convId);
    }
    await joinConversations(ports, userId, convIds);
  }

  const session = await createSession(ports, userId, device.deviceId);
  return json(await buildAuthResponse(ports, user, device, session), 201);
}
