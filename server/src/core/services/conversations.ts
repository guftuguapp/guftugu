/**
 * Conversations (PROTOCOL.md §6). Membership changes write a system message
 * and push conversation.updated; removals also flag key rotation.
 */
import type { Conversation } from "../../protocol/types.js";
import { resolveSession, type Principal } from "../auth.js";
import { getConversation, getUser, invalidateConversation, type LoadedConversation } from "../cached.js";
import { HttpError, badRequest, forbidden, notFound } from "../errors.js";
import { isBlockedBy, knownUserIds } from "./friends.js";
import { json, readJson } from "../http.js";
import type { ConversationRecord, MembershipRecord, Ports } from "../ports.js";
import { sendToUsers } from "../realtime/fanout.js";
import type { Params } from "../router.js";
import { toConversation } from "../dto.js";
import { optionalString, optionalStringArray, requireEnum, requireString } from "../validate.js";
import { appendSystemMessage, requireMember } from "./messages.js";

// ---------- shared helpers ----------

/** Group owner or an admin-role user may change metadata and membership. */
function canManage(p: Principal, loaded: LoadedConversation): boolean {
  if (p.user.role === "admin") return true;
  return loaded.members.some((m) => m.userId === p.userId && m.role === "owner");
}

async function requireGroupManager(ports: Ports, p: Principal, convId: string) {
  const loaded = await requireMember(ports, convId, p.userId);
  if (loaded.conv.type !== "group") throw badRequest("only groups can be changed");
  if (!canManage(p, loaded)) throw forbidden("only the group owner or an admin can do that");
  return loaded;
}

/** Every id must be an existing, active user. */
export async function requireActiveUsers(ports: Ports, userIds: Iterable<string>): Promise<void> {
  for (const id of userIds) {
    const user = await getUser(ports, id);
    if (!user || user.status !== "active") throw badRequest(`unknown or inactive user: ${id}`);
  }
}

async function loadOrThrow(ports: Ports, convId: string): Promise<LoadedConversation> {
  const loaded = await getConversation(ports, convId);
  if (!loaded) throw notFound("conversation not found");
  return loaded;
}

async function pushUpdated(ports: Ports, loaded: LoadedConversation, extraUserIds: string[] = []): Promise<Conversation> {
  const conversation = toConversation(loaded);
  await sendToUsers(ports, [...loaded.members.map((m) => m.userId), ...extraUserIds], { type: "conversation.updated", conversation });
  return conversation;
}

export function newConversationRecord(ports: Ports, type: ConversationRecord["type"], createdBy: string, name: string | null, autoJoin: boolean): ConversationRecord {
  const now = ports.clock.now();
  return {
    convId: `c_${ports.ids.ulid(now)}`,
    type,
    name,
    avatarKey: null,
    createdBy,
    createdAt: now,
    autoJoin,
    currentKeyId: null,
    keyRotationRequired: false,
    lastMsgId: null,
    lastMessageAt: null,
  };
}

/**
 * The one direct conversation between a and b (created on first use). If either of them left it
 * earlier, they are added back and the key must rotate (the leaver's old key is no longer valid).
 */
export async function ensureDirect(ports: Ports, a: string, b: string): Promise<{ loaded: LoadedConversation; created: boolean }> {
  const now = ports.clock.now();
  const record = newConversationRecord(ports, "direct", a, null, false);
  const [x, y] = [a, b].sort();
  const member = (userId: string): MembershipRecord => ({ convId: record.convId, userId, role: "member", joinedAt: now, lastReadMsgId: null });
  const stored = await ports.db.conversations.createDirect(record, [member(x ?? ""), member(y ?? "")]);
  let loaded = await loadOrThrow(ports, stored.convId);
  const missing = [a, b].filter((id) => !loaded.members.some((m) => m.userId === id));
  if (missing.length > 0) {
    await ports.db.conversations.addMembers(
      stored.convId,
      missing.map((userId): MembershipRecord => ({ convId: stored.convId, userId, role: "member", joinedAt: now, lastReadMsgId: null })),
    );
    await ports.db.conversations.update(stored.convId, { keyRotationRequired: true });
    await invalidateConversation(ports, stored.convId);
    await appendSystemMessage(ports, stored.convId, a, { event: "member_added", userIds: missing });
    loaded = await loadOrThrow(ports, stored.convId);
  }
  const created = stored.convId === record.convId;
  if (created || missing.length > 0) await pushUpdated(ports, loaded);
  return { loaded, created };
}

/** Add *userId* to a group (no-op if already there), announced as added by *actorId*. */
export async function joinGroup(ports: Ports, convId: string, userId: string, actorId: string): Promise<LoadedConversation> {
  const loaded = await loadOrThrow(ports, convId);
  if (loaded.members.some((m) => m.userId === userId)) return loaded;
  const now = ports.clock.now();
  await ports.db.conversations.addMembers(convId, [{ convId, userId, role: "member", joinedAt: now, lastReadMsgId: null }]);
  await invalidateConversation(ports, convId);
  await appendSystemMessage(ports, convId, actorId, { event: "member_added", userIds: [userId] });
  const after = await loadOrThrow(ports, convId);
  await pushUpdated(ports, after);
  return after;
}

// ---------- handlers ----------

export async function listConversations(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const items: Conversation[] = [];
  for (const m of await ports.db.conversations.listByUser(p.userId)) {
    const loaded = await getConversation(ports, m.convId);
    if (loaded) items.push(toConversation(loaded));
  }
  // Newest activity first.
  items.sort((a, b) => (b.lastMessageAt ?? b.createdAt) - (a.lastMessageAt ?? a.createdAt));
  return json({ items, hasMore: false });
}

export async function createConversation(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const type = requireEnum(body, "type", ["direct", "group"] as const);
  const now = ports.clock.now();

  if (type === "direct") {
    const memberId = requireString(body, "memberId", 256);
    if (memberId === p.userId) throw badRequest("cannot start a direct conversation with yourself");
    await requireActiveUsers(ports, [memberId]);
    if (p.user.role !== "admin" && !(await knownUserIds(ports, p.userId)).has(memberId)) {
      throw forbidden("you can only chat with your friends and people in your groups — send them an invite");
    }
    if (await isBlockedBy(ports, memberId, p.userId)) throw new HttpError("blocked", "this person is not accepting messages from you");
    const { loaded, created } = await ensureDirect(ports, p.userId, memberId);
    return json(toConversation(loaded), created ? 201 : 200);
  }

  const name = requireString(body, "name", 128).trim();
  if (name.length === 0) throw badRequest("name must not be blank");
  const memberIds = new Set(optionalStringArray(body, "memberIds") ?? []);
  memberIds.delete(p.userId);
  await requireActiveUsers(ports, memberIds);

  const record = newConversationRecord(ports, "group", p.userId, name, false);
  const members: MembershipRecord[] = [
    { convId: record.convId, userId: p.userId, role: "owner", joinedAt: now, lastReadMsgId: null },
    ...[...memberIds].map((userId): MembershipRecord => ({ convId: record.convId, userId, role: "member", joinedAt: now, lastReadMsgId: null })),
  ];
  await ports.db.conversations.create(record, members);
  await appendSystemMessage(ports, record.convId, p.userId, { event: "created", userIds: [p.userId] });
  const loaded = await loadOrThrow(ports, record.convId);
  return json(await pushUpdated(ports, loaded), 201);
}

export async function getConversationHandler(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const loaded = await requireMember(ports, params.id ?? "", p.userId);
  return json(toConversation(loaded));
}

export async function patchConversation(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  await requireGroupManager(ports, p, convId);
  const body = await readJson<Record<string, unknown>>(req);
  const name = optionalString(body, "name", 128);
  const avatarKey = optionalString(body, "avatarKey", 512);
  const patch: { name?: string; avatarKey?: string | null } = {};
  if (name !== undefined) {
    if (name === null || name.trim().length === 0) throw badRequest("name must be a non-empty string");
    patch.name = name.trim();
  }
  if (avatarKey !== undefined) patch.avatarKey = avatarKey;
  if (Object.keys(patch).length === 0) throw badRequest("nothing to update");

  await ports.db.conversations.update(convId, patch);
  await invalidateConversation(ports, convId);
  if (patch.name !== undefined) await appendSystemMessage(ports, convId, p.userId, { event: "renamed", userIds: [p.userId], text: patch.name });
  return json(await pushUpdated(ports, await loadOrThrow(ports, convId)));
}

export async function addMembers(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const loaded = await requireGroupManager(ports, p, convId);
  const body = await readJson<Record<string, unknown>>(req);
  const wanted = new Set(optionalStringArray(body, "userIds") ?? []);
  if (wanted.size === 0) throw badRequest("userIds must not be empty");
  const existing = new Set(loaded.members.map((m) => m.userId));
  const candidates = [...wanted].filter((id) => !existing.has(id));
  await requireActiveUsers(ports, candidates);
  // People who blocked the adder are silently skipped (a block is never revealed).
  const toAdd: string[] = [];
  for (const id of candidates) if (!(await isBlockedBy(ports, id, p.userId))) toAdd.push(id);

  if (toAdd.length > 0) {
    const now = ports.clock.now();
    await ports.db.conversations.addMembers(
      convId,
      toAdd.map((userId): MembershipRecord => ({ convId, userId, role: "member", joinedAt: now, lastReadMsgId: null })),
    );
    await invalidateConversation(ports, convId);
    await appendSystemMessage(ports, convId, p.userId, { event: "member_added", userIds: toAdd });
  }
  return json(await pushUpdated(ports, await loadOrThrow(ports, convId)));
}

export async function removeMember(ports: Ports, req: Request, params: Params): Promise<Response> {
  const p = await resolveSession(ports, req);
  const convId = params.id ?? "";
  const targetId = params.userId ?? "";
  const loaded = await requireMember(ports, convId, p.userId);
  const self = targetId === p.userId;
  if (loaded.conv.type !== "group" && !self) throw badRequest("you can only leave a direct conversation, not remove the other person");
  if (!self && !canManage(p, loaded)) throw forbidden("only the group owner or an admin can remove members");
  if (!loaded.members.some((m) => m.userId === targetId)) throw notFound("user is not a member");

  await ports.db.conversations.removeMember(convId, targetId);
  // The removed device(s) may still hold the current key: the next sender must rotate.
  await ports.db.conversations.update(convId, { keyRotationRequired: true });
  await invalidateConversation(ports, convId);
  await appendSystemMessage(ports, convId, p.userId, { event: self ? "member_left" : "member_removed", userIds: [targetId] });
  return json(await pushUpdated(ports, await loadOrThrow(ports, convId), [targetId]));
}
