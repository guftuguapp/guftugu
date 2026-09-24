/**
 * Friends & user invites (PROTOCOL.md §5a).
 *
 * Any user can invite someone: a single-use code that, when used (at sign-up or later
 * with "I have an invite code"), makes the two of them friends and opens their 1:1 chat
 * ("friend" invite) or adds the newcomer to one of the inviter's groups ("group" invite).
 * Blocking is per direction and stops messages, calls and group adds from the blocked person.
 */
import type { Friend, InviteKind } from "../../protocol/types.js";
import { resolveSession } from "../auth.js";
import { getConversation, getUser } from "../cached.js";
import { HttpError, badRequest, forbidden, notFound } from "../errors.js";
import { json, noContent, readJson } from "../http.js";
import type { FriendRecord, InviteRecord, Ports } from "../ports.js";
import type { Params } from "../router.js";
import { toConversation, toInvite, toUser } from "../dto.js";
import { requireEnum, requireString } from "../validate.js";
import { ensureDirect, joinGroup } from "./conversations.js";

const DEFAULT_INVITE_HOURS = 7 * 24;
const MAX_INVITE_HOURS = 30 * 24;

// ---------- helpers used by other services ----------

/** True when *blockerId* has blocked *userId*. */
export async function isBlockedBy(ports: Ports, blockerId: string, userId: string): Promise<boolean> {
  const row = await ports.db.friends.get(blockerId, userId);
  return !!row?.blockedAt;
}

/** Make a and b friends (both directions); clears blocks between them — using an invite is an explicit choice. */
export async function befriend(ports: Ports, a: string, b: string, viaCode: string | null): Promise<void> {
  const now = ports.clock.now();
  for (const [x, y] of [[a, b], [b, a]] as const) {
    const existing = await ports.db.friends.get(x, y);
    await ports.db.friends.put({ userId: x, friendId: y, since: existing?.since ?? now, viaCode: existing?.viaCode ?? viaCode, blockedAt: null });
  }
}

/** The admin's circle: users the admin invited (or created before invite kinds existed). */
function inAdminCircle(invitedBy: string | null | undefined): boolean {
  return !invitedBy || invitedBy === "admin";
}

/**
 * Everyone *userId* may see and start a chat with: themselves, their friends, people in
 * their conversations, and — for members of the admin's (family) circle — that whole circle.
 */
export async function knownUserIds(ports: Ports, userId: string): Promise<Set<string>> {
  const known = new Set<string>([userId]);
  for (const f of await ports.db.friends.list(userId)) if (f.since !== null) known.add(f.friendId);
  for (const m of await ports.db.conversations.listByUser(userId)) {
    const loaded = await getConversation(ports, m.convId);
    for (const member of loaded?.members ?? []) known.add(member.userId);
  }
  const me = await ports.db.users.get(userId);
  if (me && inAdminCircle(me.invitedBy)) {
    for (const u of await ports.db.users.list()) if (inAdminCircle(u.invitedBy)) known.add(u.userId);
  }
  return known;
}

/** Create a friend/group invite record for *userId* (shared by the handler and tests). */
export async function newUserInvite(ports: Ports, userId: string, kind: "friend" | "group", convId: string | null, hours: number): Promise<InviteRecord> {
  const now = ports.clock.now();
  let code = ports.ids.inviteCode();
  while (await ports.db.invites.get(code)) code = ports.ids.inviteCode();
  const invite: InviteRecord = {
    code,
    kind: kind as InviteKind,
    convId,
    displayName: null,
    role: "member",
    forUserId: null,
    autoJoin: kind === "group" && convId ? [convId] : [],
    createdAt: now,
    expiresAt: now + Math.round(hours * 3_600_000),
    usedAt: null,
    usedByUserId: null,
    createdBy: userId,
  };
  await ports.db.invites.put(invite);
  return invite;
}

/** Effects of a friend/group invite for *userId* (new or existing). Returns the conversation opened. */
export async function applyUserInvite(ports: Ports, invite: InviteRecord, userId: string) {
  const inviter = invite.createdBy;
  await befriend(ports, userId, inviter, invite.code);
  if (invite.kind === "group" && invite.convId) {
    return await joinGroup(ports, invite.convId, userId, inviter);
  }
  return (await ensureDirect(ports, inviter, userId)).loaded;
}

function isUserInvite(invite: InviteRecord): boolean {
  return invite.createdBy !== "admin" && !invite.forUserId && (invite.kind === "friend" || invite.kind === "group" || invite.kind === undefined);
}

export { isUserInvite };

// ---------- handlers ----------

/** POST /invites — any user invites a friend (1:1) or into one of their groups. */
export async function createInvite(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const kind = requireEnum(body, "kind", ["friend", "group"] as const);
  const convId = kind === "group" ? requireString(body, "convId", 256) : null;
  let hours = DEFAULT_INVITE_HOURS;
  if (body.expiresInHours !== undefined) {
    const v = body.expiresInHours;
    if (typeof v !== "number" || !Number.isFinite(v) || v <= 0) throw badRequest("expiresInHours must be a positive number");
    hours = Math.min(v, MAX_INVITE_HOURS);
  }
  if (convId) {
    const loaded = await getConversation(ports, convId);
    if (!loaded || loaded.conv.type !== "group") throw notFound("group not found");
    if (!loaded.members.some((m) => m.userId === p.userId)) throw forbidden("not a member of that group");
  }
  const invite = await newUserInvite(ports, p.userId, kind, convId, hours);
  return json(toInvite(invite, ports.config.apiUrl), 201);
}

/** POST /invites/redeem — an existing user uses someone's invite code. */
export async function redeemInvite(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const code = requireString(body, "code", 64).trim().toUpperCase();
  const now = ports.clock.now();
  const invite = await ports.db.invites.get(code);
  if (!invite || invite.usedAt) throw new HttpError("invite_invalid", "invite code is unknown or already used");
  if (invite.expiresAt <= now) throw new HttpError("invite_expired", "invite has expired");
  if (!isUserInvite(invite)) throw badRequest("this code is for setting up a new phone, not for adding a friend");
  if (invite.createdBy === p.userId) throw badRequest("that is your own invite — send it to someone else");
  const inviter = await getUser(ports, invite.createdBy);
  if (!inviter || inviter.status !== "active") throw new HttpError("invite_invalid", "the person who sent this invite is no longer here");
  const consumed = await ports.db.invites.consume(code, p.userId, now);
  if (!consumed) throw new HttpError("invite_invalid", "invite code is unknown or already used");
  const loaded = await applyUserInvite(ports, consumed, p.userId);
  return json({ friend: toUser(inviter), conversation: loaded ? toConversation(loaded) : null });
}

/** GET /friends — my friends and the people I blocked. */
export async function listFriends(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const items: Friend[] = [];
  for (const f of await ports.db.friends.list(p.userId)) {
    const user = await getUser(ports, f.friendId);
    if (!user) continue;
    items.push({ user: toUser(user), since: f.since, blocked: !!f.blockedAt });
  }
  items.sort((a, b) => a.user.displayName.localeCompare(b.user.displayName));
  return json({ items, hasMore: false });
}

async function setBlocked(ports: Ports, req: Request, params: Params, blocked: boolean): Promise<Response> {
  const p = await resolveSession(ports, req);
  const target = params.userId ?? "";
  if (target === p.userId) throw badRequest("you cannot block yourself");
  if (!(await getUser(ports, target))) throw notFound("user not found");
  const row: FriendRecord = (await ports.db.friends.get(p.userId, target)) ?? { userId: p.userId, friendId: target, since: null };
  row.blockedAt = blocked ? ports.clock.now() : null;
  if (!blocked && row.since === null) await ports.db.friends.remove(p.userId, target);
  else await ports.db.friends.put(row);
  return noContent();
}

/** POST /friends/{userId}/block */
export const block = (ports: Ports, req: Request, params: Params) => setBlocked(ports, req, params, true);
/** DELETE /friends/{userId}/block */
export const unblock = (ports: Ports, req: Request, params: Params) => setBlocked(ports, req, params, false);
