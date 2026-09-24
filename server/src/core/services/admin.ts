/**
 * Admin API (PROTOCOL.md §10) — used only by the admin CLI with X-Admin-Key.
 * These routes are exempt from the protocol header check.
 */
import type { Conversation, IceServer, ServerSettings } from "../../protocol/types.js";
import { requireAdmin } from "../auth.js";
import { getConversation, getUser, invalidateConversation, invalidateUser } from "../cached.js";
import { badRequest, notFound } from "../errors.js";
import { isObject, json, noContent, readJson } from "../http.js";
import type { InviteRecord, MembershipRecord, Ports } from "../ports.js";
import { sendToUsers } from "../realtime/fanout.js";
import type { Params } from "../router.js";
import { toConversation, toDevice, toInvite, toUser } from "../dto.js";
import { optionalBoolean, optionalEnum, optionalString, optionalStringArray, requireString } from "../validate.js";
import { getSettings, saveSettings } from "./config.js";
import { newConversationRecord, requireActiveUsers } from "./conversations.js";
import { coMemberIds, revokeDevice } from "./users.js";

// ---------- invites ----------

export async function createInvite(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const displayName = optionalString(body, "displayName", 128) ?? null;
  const role = optionalEnum(body, "role", ["member", "admin"] as const) ?? "member";
  const forUserId = optionalString(body, "forUserId", 256) ?? null;
  const autoJoin = optionalStringArray(body, "autoJoin", 100) ?? [];
  const settings = await getSettings(ports);
  let expiresInHours = settings.inviteTtlHours;
  if (body.expiresInHours !== undefined) {
    const v = body.expiresInHours;
    if (typeof v !== "number" || !Number.isFinite(v) || v <= 0) throw badRequest("expiresInHours must be a positive number");
    expiresInHours = v;
  }
  if (forUserId !== null && !(await getUser(ports, forUserId))) throw notFound("forUserId does not exist");
  for (const convId of autoJoin) {
    if (!(await ports.db.conversations.get(convId))) throw badRequest(`autoJoin conversation not found: ${convId}`);
  }

  const now = ports.clock.now();
  let code = ports.ids.inviteCode();
  while (await ports.db.invites.get(code)) code = ports.ids.inviteCode();
  const invite: InviteRecord = {
    code,
    kind: forUserId ? "link" : "admin",
    displayName,
    role,
    forUserId,
    autoJoin,
    createdAt: now,
    expiresAt: now + Math.round(expiresInHours * 3_600_000),
    usedAt: null,
    usedByUserId: null,
    createdBy: "admin",
  };
  await ports.db.invites.put(invite);
  return json(toInvite(invite, ports.config.apiUrl), 201);
}

export async function listInvites(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const invites = (await ports.db.invites.list()).sort((a, b) => b.createdAt - a.createdAt);
  return json({ items: invites.map((i) => toInvite(i, ports.config.apiUrl)), hasMore: false });
}

export async function deleteInvite(ports: Ports, req: Request, params: Params): Promise<Response> {
  await requireAdmin(ports, req);
  const code = (params.code ?? "").toUpperCase();
  if (!(await ports.db.invites.get(code))) throw notFound("invite not found");
  await ports.db.invites.delete(code);
  return noContent();
}

// ---------- users & devices ----------

export async function listUsers(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const users = await ports.db.users.list();
  return json({ items: users.map(toUser), hasMore: false });
}

export async function patchUser(ports: Ports, req: Request, params: Params): Promise<Response> {
  await requireAdmin(ports, req);
  const userId = params.id ?? "";
  if (!(await ports.db.users.get(userId))) throw notFound("user not found");
  const body = await readJson<Record<string, unknown>>(req);
  const status = optionalEnum(body, "status", ["active", "disabled"] as const);
  const role = optionalEnum(body, "role", ["member", "admin"] as const);
  const patch: { status?: "active" | "disabled"; role?: "member" | "admin" } = {};
  if (status !== undefined) patch.status = status;
  if (role !== undefined) patch.role = role;
  if (Object.keys(patch).length === 0) throw badRequest("nothing to update");

  // A disabled user keeps their sessions, but every request now fails with user_disabled.
  const user = await ports.db.users.update(userId, patch);
  await invalidateUser(ports, userId);
  await sendToUsers(ports, await coMemberIds(ports, userId), { type: "user.updated", user: toUser(user) });
  return json(toUser(user));
}

export async function listUserDevices(ports: Ports, req: Request, params: Params): Promise<Response> {
  await requireAdmin(ports, req);
  const userId = params.id ?? "";
  if (!(await ports.db.users.get(userId))) throw notFound("user not found");
  const devices = await ports.db.devices.listByUser(userId);
  return json({ items: devices.map(toDevice), hasMore: false });
}

export async function revokeUserDevice(ports: Ports, req: Request, params: Params): Promise<Response> {
  await requireAdmin(ports, req);
  const device = await ports.db.devices.get(params.deviceId ?? "");
  if (!device || device.userId !== (params.id ?? "")) throw notFound("device not found");
  await revokeDevice(ports, device);
  return noContent();
}

// ---------- conversations ----------

export async function listConversations(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const items: Conversation[] = [];
  for (const conv of await ports.db.conversations.listAll()) {
    const loaded = await getConversation(ports, conv.convId);
    if (loaded) items.push(toConversation(loaded));
  }
  items.sort((a, b) => b.createdAt - a.createdAt);
  return json({ items, hasMore: false });
}

export async function createConversation(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const name = requireString(body, "name", 128).trim();
  if (name.length === 0) throw badRequest("name must not be blank");
  const settings = await getSettings(ports);
  const autoJoin = optionalBoolean(body, "autoJoin") ?? settings.defaultAutoJoin;
  const memberIds = new Set(optionalStringArray(body, "memberIds") ?? []);
  await requireActiveUsers(ports, memberIds);

  const record = newConversationRecord(ports, "group", "admin", name, autoJoin);
  const now = ports.clock.now();
  const members = [...memberIds].map((userId): MembershipRecord => ({ convId: record.convId, userId, role: "member", joinedAt: now, lastReadMsgId: null }));
  await ports.db.conversations.create(record, members);
  const loaded = await getConversation(ports, record.convId);
  if (!loaded) throw notFound("conversation not found");
  const conversation = toConversation(loaded);
  await sendToUsers(ports, memberIds, { type: "conversation.updated", conversation });
  return json(conversation, 201);
}

export async function patchConversation(ports: Ports, req: Request, params: Params): Promise<Response> {
  await requireAdmin(ports, req);
  const convId = params.id ?? "";
  if (!(await ports.db.conversations.get(convId))) throw notFound("conversation not found");
  const body = await readJson<Record<string, unknown>>(req);
  const name = optionalString(body, "name", 128);
  const autoJoin = optionalBoolean(body, "autoJoin");
  const patch: { name?: string; autoJoin?: boolean } = {};
  if (name !== undefined) {
    if (name === null || name.trim().length === 0) throw badRequest("name must be a non-empty string");
    patch.name = name.trim();
  }
  if (autoJoin !== undefined) patch.autoJoin = autoJoin;
  if (Object.keys(patch).length === 0) throw badRequest("nothing to update");

  await ports.db.conversations.update(convId, patch);
  await invalidateConversation(ports, convId);
  const loaded = await getConversation(ports, convId);
  if (!loaded) throw notFound("conversation not found");
  const conversation = toConversation(loaded);
  await sendToUsers(ports, loaded.members.map((m) => m.userId), { type: "conversation.updated", conversation });
  return json(conversation);
}

// ---------- settings & stats ----------

export async function getConfig(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  return json(await getSettings(ports));
}

function validateIceServers(v: unknown): IceServer[] {
  if (!Array.isArray(v)) throw badRequest("iceServers must be an array");
  return v.map((s): IceServer => {
    if (!isObject(s) || !Array.isArray(s.urls) || s.urls.length === 0 || s.urls.some((u) => typeof u !== "string" || u.length === 0)) {
      throw badRequest("each ice server needs a non-empty urls array");
    }
    const out: IceServer = { urls: s.urls as string[] };
    if (s.username !== undefined) {
      if (typeof s.username !== "string") throw badRequest("ice username must be a string");
      out.username = s.username;
    }
    if (s.credential !== undefined) {
      if (typeof s.credential !== "string") throw badRequest("ice credential must be a string");
      out.credential = s.credential;
    }
    return out;
  });
}

export async function putConfig(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const current = await getSettings(ports);
  const next: ServerSettings = { ...current, features: { ...current.features } };

  const serverName = optionalString(body, "serverName", 128);
  if (serverName !== undefined) {
    if (serverName === null || serverName.trim().length === 0) throw badRequest("serverName must be a non-empty string");
    next.serverName = serverName.trim();
  }
  if (body.iceServers !== undefined) next.iceServers = validateIceServers(body.iceServers);
  if (body.maxUploadBytes !== undefined) {
    const v = body.maxUploadBytes;
    if (typeof v !== "number" || !Number.isInteger(v) || v <= 0) throw badRequest("maxUploadBytes must be a positive integer");
    next.maxUploadBytes = v;
  }
  if (body.features !== undefined) {
    if (!isObject(body.features)) throw badRequest("features must be an object");
    const calls = optionalBoolean(body.features, "calls");
    const media = optionalBoolean(body.features, "media");
    if (calls !== undefined) next.features.calls = calls;
    if (media !== undefined) next.features.media = media;
  }
  const defaultAutoJoin = optionalBoolean(body, "defaultAutoJoin");
  if (defaultAutoJoin !== undefined) next.defaultAutoJoin = defaultAutoJoin;
  if (body.inviteTtlHours !== undefined) {
    const v = body.inviteTtlHours;
    if (typeof v !== "number" || !Number.isFinite(v) || v <= 0) throw badRequest("inviteTtlHours must be a positive number");
    next.inviteTtlHours = v;
  }

  await saveSettings(ports, next);
  return json(next);
}

export async function stats(ports: Ports, req: Request): Promise<Response> {
  await requireAdmin(ports, req);
  const users = await ports.db.users.list();
  let devices = 0;
  for (const u of users) devices += (await ports.db.devices.listByUser(u.userId)).length;
  const conversations = (await ports.db.conversations.listAll()).length;
  const connections = await ports.db.connections.count();
  return json({ users: users.length, devices, conversations, connections });
}
