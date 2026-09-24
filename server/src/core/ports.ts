/**
 * Ports — the only things core/ knows about the outside world.
 *
 * Every cloud adapter implements these interfaces (see docs/DATA_MODEL.md for
 * the semantics each method must honour). core/ never imports a cloud SDK.
 */
import type {
  Call,
  CallInfo,
  Conversation,
  ConversationType,
  Envelope,
  Invite,
  Member,
  Message,
  MessageKind,
  ServerEvent,
  ServerSettings,
  SystemInfo,
  User,
  WrappedKey,
} from "../protocol/types.js";

// ---------- stored records (protocol type + private fields) ----------

export interface UserRecord extends User {
  /** `pbkdf2-sha256$<iters>$<salt b64url>$<hash b64url>` */
  passwordHash: string | null;
  /** "admin" (family circle: everyone the admin invited sees each other) or the inviting userId. Absent = admin. */
  invitedBy?: string | null;
}

export interface DeviceRecord {
  deviceId: string;
  userId: string;
  name: string;
  model?: string | null;
  os?: string | null;
  appVersion?: string | null;
  devicePublicKey: string;
  authPublicKey: string | null;
  encryptionPublicKey: string;
  enrolledAt: number;
  lastSeenAt?: number | null;
  revokedAt?: number | null;
  failedPasswordAttempts: number;
  lockedUntil?: number | null;
}

export interface InviteRecord extends Omit<Invite, "link"> {
  createdBy: string; // "admin" or a userId
}

/** My row about another person: friendship and/or my block. Stored per direction. */
export interface FriendRecord {
  userId: string;
  friendId: string;
  /** When we became friends; null = not friends (a block-only row). */
  since: number | null;
  /** The invite code that made us friends (audit). */
  viaCode?: string | null;
  /** Set when *userId* blocked *friendId*. */
  blockedAt?: number | null;
}

export interface SessionRecord {
  tokenHash: string;
  userId: string;
  deviceId: string;
  createdAt: number;
  expiresAt: number;
}

export interface ChallengeRecord {
  nonce: string;
  deviceId: string;
  expiresAt: number;
}

export interface ConversationRecord {
  convId: string;
  type: ConversationType;
  name?: string | null;
  avatarKey?: string | null;
  createdBy: string;
  createdAt: number;
  autoJoin: boolean;
  currentKeyId: string | null;
  keyRotationRequired: boolean;
  lastMsgId: string | null;
  lastMessageAt: number | null;
}

export interface MembershipRecord extends Member {
  convId: string;
}

export interface ConnectionRecord {
  connectionId: string;
  userId: string;
  deviceId: string;
  connectedAt: number;
}

export interface CallRecord extends Call {}

export interface NewMessage {
  convId: string;
  senderId: string;
  senderDeviceId: string | null;
  clientId: string | null;
  kind: MessageKind;
  envelope: Envelope | null;
  call: CallInfo | null;
  system: SystemInfo | null;
  sentAt: number;
}

export interface MessagePage {
  items: Message[];
  hasMore: boolean;
}

// ---------- repositories ----------

export interface UserRepo {
  get(userId: string): Promise<UserRecord | null>;
  put(user: UserRecord): Promise<void>;
  update(userId: string, patch: Partial<Pick<UserRecord, "displayName" | "avatarKey" | "role" | "status" | "passwordHash" | "lastSeenAt">>): Promise<UserRecord>;
  list(): Promise<UserRecord[]>;
}

export interface DeviceRepo {
  get(deviceId: string): Promise<DeviceRecord | null>;
  put(device: DeviceRecord): Promise<void>;
  update(deviceId: string, patch: Partial<Pick<DeviceRecord, "name" | "lastSeenAt" | "revokedAt" | "failedPasswordAttempts" | "lockedUntil" | "authPublicKey" | "appVersion" | "os">>): Promise<DeviceRecord>;
  listByUser(userId: string): Promise<DeviceRecord[]>;
}

export interface InviteRepo {
  get(code: string): Promise<InviteRecord | null>;
  put(invite: InviteRecord): Promise<void>;
  /** Atomically mark used; returns null if missing, already used, or expired at `now`. */
  consume(code: string, usedByUserId: string, now: number): Promise<InviteRecord | null>;
  delete(code: string): Promise<void>;
  list(): Promise<InviteRecord[]>;
}

export interface SessionRepo {
  get(tokenHash: string): Promise<SessionRecord | null>;
  put(session: SessionRecord): Promise<void>;
  delete(tokenHash: string): Promise<void>;
}

export interface ChallengeRepo {
  put(challenge: ChallengeRecord): Promise<void>;
  /** Atomically fetch-and-delete. Null if missing. Caller checks expiry. */
  consume(nonce: string): Promise<ChallengeRecord | null>;
}

export interface ConversationRepo {
  get(convId: string): Promise<ConversationRecord | null>;
  /** Create a group (or admin-created) conversation with initial members in one shot. */
  create(conv: ConversationRecord, members: MembershipRecord[]): Promise<void>;
  /** Idempotent direct conversation between two users; returns the existing record if any. */
  createDirect(conv: ConversationRecord, members: [MembershipRecord, MembershipRecord]): Promise<ConversationRecord>;
  update(convId: string, patch: Partial<Pick<ConversationRecord, "name" | "avatarKey" | "autoJoin" | "keyRotationRequired" | "lastMsgId" | "lastMessageAt">>): Promise<ConversationRecord>;
  /** Only moves forward: no-op (returns current) if keyId <= currentKeyId. Clears keyRotationRequired when it advances. */
  setCurrentKey(convId: string, keyId: string): Promise<ConversationRecord>;
  listAll(): Promise<ConversationRecord[]>;
  listAutoJoin(): Promise<ConversationRecord[]>;

  members(convId: string): Promise<MembershipRecord[]>;
  getMember(convId: string, userId: string): Promise<MembershipRecord | null>;
  addMembers(convId: string, members: MembershipRecord[]): Promise<void>;
  removeMember(convId: string, userId: string): Promise<void>;
  setLastRead(convId: string, userId: string, msgId: string): Promise<void>;
  /** Conversation ids the user belongs to. */
  listByUser(userId: string): Promise<MembershipRecord[]>;
}

export interface KeyRepo {
  /** Idempotent per (convId, keyId, recipientDeviceId). */
  put(key: WrappedKey): Promise<void>;
  listForDevice(convId: string, deviceId: string): Promise<WrappedKey[]>;
  /** Device ids that have a wrap for this key. */
  recipientsOf(convId: string, keyId: string): Promise<string[]>;
}

export interface MessageRepo {
  /**
   * Store a message and assign its msgId (monotonic within a conversation).
   * If `clientId` was already used by `senderDeviceId` within 7 days, return the
   * original with `duplicate: true` and store nothing.
   */
  append(msg: NewMessage, now: number): Promise<{ message: Message; duplicate: boolean }>;
  get(convId: string, msgId: string): Promise<Message | null>;
  /** Ascending, exclusive of `after`. */
  listAfter(convId: string, after: string | null, limit: number): Promise<MessagePage>;
  /** Descending, exclusive of `before` (null = latest). */
  listBefore(convId: string, before: string | null, limit: number): Promise<MessagePage>;
  /** Tombstone: clears envelope, sets deletedAt. */
  markDeleted(convId: string, msgId: string, now: number): Promise<Message>;
}

export interface ConnectionRepo {
  put(conn: ConnectionRecord): Promise<void>;
  get(connectionId: string): Promise<ConnectionRecord | null>;
  remove(connectionId: string): Promise<void>;
  listByUser(userId: string): Promise<ConnectionRecord[]>;
  count(): Promise<number>;
}

export interface CallRepo {
  get(callId: string): Promise<CallRecord | null>;
  put(call: CallRecord): Promise<void>;
  update(callId: string, patch: Partial<Pick<CallRecord, "state" | "calleeDeviceId" | "answeredAt" | "endedAt" | "endReason">>): Promise<CallRecord>;
}

export interface SettingsRepo {
  get(): Promise<ServerSettings | null>;
  put(settings: ServerSettings): Promise<void>;
}

export interface FriendRepo {
  get(userId: string, friendId: string): Promise<FriendRecord | null>;
  put(record: FriendRecord): Promise<void>;
  list(userId: string): Promise<FriendRecord[]>;
  remove(userId: string, friendId: string): Promise<void>;
}

export interface Database {
  friends: FriendRepo;
  users: UserRepo;
  devices: DeviceRepo;
  invites: InviteRepo;
  sessions: SessionRepo;
  challenges: ChallengeRepo;
  conversations: ConversationRepo;
  keys: KeyRepo;
  messages: MessageRepo;
  connections: ConnectionRepo;
  calls: CallRepo;
  settings: SettingsRepo;
}

// ---------- other ports ----------

/** Presigned-URL blob storage. The server never proxies bytes. */
export interface BlobStore {
  presignUpload(key: string, mime: string, sizeBytes: number, ttlMs: number): Promise<{ url: string; method: "PUT"; headers: Record<string, string> }>;
  presignDownload(key: string, ttlMs: number): Promise<{ url: string }>;
  delete(key: string): Promise<void>;
}

/** Push one event to one live connection. */
export interface Realtime {
  /** Returns "gone" if the connection no longer exists (caller removes it). */
  send(connectionId: string, event: ServerEvent): Promise<"ok" | "gone">;
}

/**
 * Small TTL cache. On Lambda: per-container memory. Elsewhere: Redis, etc.
 * Semantics: best-effort; a miss must be harmless.
 */
export interface Cache {
  get<T>(key: string): Promise<T | undefined>;
  set<T>(key: string, value: T, ttlMs: number): Promise<void>;
  del(key: string): Promise<void>;
}

export interface Clock {
  now(): number;
}

export interface IdGen {
  /** Time-ordered unique id (ULID). Monotonic within a process. */
  ulid(now: number): string;
  /** `n` cryptographically random bytes as base64url. */
  randomBase64Url(n: number): string;
  /** Human invite code, e.g. GFT-7K3M-Q9XD. */
  inviteCode(): string;
}

export interface ServerConfig {
  serverName: string;
  apiUrl: string;
  wsUrl: string;
  adminKey: string;
  /** Default 30 days. */
  sessionTtlMs: number;
  /** Default 120 s. */
  challengeTtlMs: number;
  /** Default 72 h. */
  inviteTtlMs: number;
  /** Default 100 MiB. */
  maxUploadBytes: number;
  /** Default 15 min. */
  presignTtlMs: number;
}

export interface Ports {
  db: Database;
  blobs: BlobStore;
  realtime: Realtime;
  cache: Cache;
  clock: Clock;
  ids: IdGen;
  config: ServerConfig;
}

export const DEFAULT_TTLS = {
  sessionTtlMs: 30 * 24 * 60 * 60 * 1000,
  challengeTtlMs: 120 * 1000,
  inviteTtlMs: 72 * 60 * 60 * 1000,
  presignTtlMs: 15 * 60 * 1000,
  idempotencyTtlMs: 7 * 24 * 60 * 60 * 1000,
  connectionTtlMs: 3 * 60 * 60 * 1000,
  maxUploadBytes: 100 * 1024 * 1024,
  maxEnvelopeBytes: 64 * 1024,
  passwordLockAfter: 5,
  passwordLockMs: 15 * 60 * 1000,
} as const;
