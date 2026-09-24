/**
 * Stored records -> wire types (PROTOCOL.md §13). Private fields never leave here.
 */
import type { Conversation, Device, Invite, Me, Member, PublicDevice, User } from "../protocol/types.js";
import type { LoadedConversation } from "./cached.js";
import type { DeviceRecord, InviteRecord, UserRecord } from "./ports.js";

export function toUser(u: UserRecord): User {
  return {
    userId: u.userId,
    displayName: u.displayName,
    avatarKey: u.avatarKey ?? null,
    role: u.role,
    status: u.status,
    createdAt: u.createdAt,
    lastSeenAt: u.lastSeenAt ?? null,
  };
}

/** The caller's own view of their account (GET /me, AuthResponse). */
export function toMe(u: UserRecord): Me {
  return { ...toUser(u), hasPassword: u.passwordHash !== null && u.passwordHash !== undefined && u.passwordHash !== "" };
}

export function toPublicDevice(d: DeviceRecord): PublicDevice {
  return {
    deviceId: d.deviceId,
    userId: d.userId,
    name: d.name,
    devicePublicKey: d.devicePublicKey,
    encryptionPublicKey: d.encryptionPublicKey,
    enrolledAt: d.enrolledAt,
  };
}

export function toDevice(d: DeviceRecord): Device {
  return {
    ...toPublicDevice(d),
    model: d.model ?? null,
    os: d.os ?? null,
    appVersion: d.appVersion ?? null,
    hasBiometricKey: d.authPublicKey !== null && d.authPublicKey !== undefined,
    lastSeenAt: d.lastSeenAt ?? null,
    revokedAt: d.revokedAt ?? null,
  };
}

export function inviteLink(apiUrl: string, code: string): string {
  return `guftugu://join?api=${encodeURIComponent(apiUrl)}&code=${code}`;
}

export function toInvite(i: InviteRecord, apiUrl: string): Invite {
  return {
    kind: i.kind ?? (i.forUserId ? "link" : i.createdBy === "admin" ? "admin" : "friend"),
    convId: i.convId ?? null,
    invitedBy: i.createdBy !== "admin" && !i.forUserId ? i.createdBy : null,
    code: i.code,
    link: inviteLink(apiUrl, i.code),
    displayName: i.displayName ?? null,
    role: i.role,
    forUserId: i.forUserId ?? null,
    autoJoin: i.autoJoin,
    createdAt: i.createdAt,
    expiresAt: i.expiresAt,
    usedAt: i.usedAt ?? null,
    usedByUserId: i.usedByUserId ?? null,
  };
}

export function toConversation({ conv, members }: LoadedConversation): Conversation {
  const wireMembers: Member[] = members.map((m) => ({
    userId: m.userId,
    role: m.role,
    joinedAt: m.joinedAt,
    lastReadMsgId: m.lastReadMsgId ?? null,
  }));
  return {
    convId: conv.convId,
    type: conv.type,
    name: conv.name ?? null,
    avatarKey: conv.avatarKey ?? null,
    createdBy: conv.createdBy,
    createdAt: conv.createdAt,
    autoJoin: conv.autoJoin,
    members: wireMembers,
    currentKeyId: conv.currentKeyId ?? null,
    keyRotationRequired: conv.keyRotationRequired,
    lastMsgId: conv.lastMsgId ?? null,
    lastMessageAt: conv.lastMessageAt ?? null,
  };
}
