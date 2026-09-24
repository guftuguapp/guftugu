/**
 * In-memory ports for tests and local development.
 *
 * Every repository honours the semantics in docs/DATA_MODEL.md "Repository
 * semantics". Records are deep-copied on the way in and out so core/ can never
 * mutate stored state by accident. The clock is manual so tests can advance it.
 */
import type { Message, ServerEvent, ServerSettings, WrappedKey } from "../../protocol/types.js";
import {
  DEFAULT_TTLS,
  type BlobStore,
  type Cache,
  type CallRecord,
  type ChallengeRecord,
  type Clock,
  type ConnectionRecord,
  type ConversationRecord,
  type Database,
  type DeviceRecord,
  type FriendRecord,
  type IdGen,
  type InviteRecord,
  type MembershipRecord,
  type MessagePage,
  type NewMessage,
  type Ports,
  type Realtime,
  type ServerConfig,
  type SessionRecord,
  type UserRecord,
} from "../../core/ports.js";
import { createIdGen } from "../../core/ulid.js";

const clone = <T>(v: T): T => structuredClone(v);

// ---------- clock ----------

export interface ManualClock extends Clock {
  advance(ms: number): void;
  set(ms: number): void;
}

/** Starts at a fixed instant (2025-09-22T00:13:20Z) so ids and timestamps are reproducible. */
export function createManualClock(start = 1_758_500_000_000): ManualClock {
  let now = start;
  return {
    now: () => now,
    advance: (ms) => {
      now += ms;
    },
    set: (ms) => {
      now = ms;
    },
  };
}

// ---------- cache ----------

export class MemoryCache implements Cache {
  private readonly entries = new Map<string, { value: unknown; expiresAt: number }>();
  constructor(private readonly clock: Clock) {}

  async get<T>(key: string): Promise<T | undefined> {
    const e = this.entries.get(key);
    if (!e) return undefined;
    if (e.expiresAt <= this.clock.now()) {
      this.entries.delete(key);
      return undefined;
    }
    return clone(e.value as T);
  }

  async set<T>(key: string, value: T, ttlMs: number): Promise<void> {
    this.entries.set(key, { value: clone(value), expiresAt: this.clock.now() + ttlMs });
  }

  async del(key: string): Promise<void> {
    this.entries.delete(key);
  }

  clear(): void {
    this.entries.clear();
  }
}

// ---------- realtime ----------

export interface SentEvent {
  connectionId: string;
  event: ServerEvent;
}

/** Records every push; ids in `goneSet` behave like a closed socket. */
export class MemoryRealtime implements Realtime {
  readonly sent: SentEvent[] = [];
  readonly goneSet = new Set<string>();

  async send(connectionId: string, event: ServerEvent): Promise<"ok" | "gone"> {
    if (this.goneSet.has(connectionId)) return "gone";
    this.sent.push({ connectionId, event: clone(event) });
    return "ok";
  }

  eventsFor(connectionId: string): ServerEvent[] {
    return this.sent.filter((s) => s.connectionId === connectionId).map((s) => s.event);
  }

  ofType<T extends ServerEvent["type"]>(type: T, connectionId?: string): Extract<ServerEvent, { type: T }>[] {
    return this.sent
      .filter((s) => s.event.type === type && (connectionId === undefined || s.connectionId === connectionId))
      .map((s) => s.event as Extract<ServerEvent, { type: T }>);
  }

  clear(): void {
    this.sent.length = 0;
  }
}

// ---------- blobs ----------

export class MemoryBlobStore implements BlobStore {
  readonly deleted: string[] = [];

  async presignUpload(key: string, mime: string): Promise<{ url: string; method: "PUT"; headers: Record<string, string> }> {
    return { url: `memory://upload/${key}`, method: "PUT", headers: { "Content-Type": mime } };
  }

  async presignDownload(key: string): Promise<{ url: string }> {
    return { url: `memory://download/${key}` };
  }

  async delete(key: string): Promise<void> {
    this.deleted.push(key);
  }
}

// ---------- database ----------

function directKey(a: string, b: string): string {
  const [x, y] = [a, b].sort();
  return `${x}#${y}`;
}

export class MemoryDatabase implements Database {
  private readonly userMap = new Map<string, UserRecord>();
  private readonly deviceMap = new Map<string, DeviceRecord>();
  private readonly inviteMap = new Map<string, InviteRecord>();
  private readonly sessionMap = new Map<string, SessionRecord>();
  private readonly challengeMap = new Map<string, ChallengeRecord>();
  private readonly convMap = new Map<string, ConversationRecord>();
  /** convId -> userId -> membership, in join order */
  private readonly memberMap = new Map<string, Map<string, MembershipRecord>>();
  private readonly directIndex = new Map<string, string>();
  /** `${convId}|${keyId}|${deviceId}` */
  private readonly keyMap = new Map<string, WrappedKey>();
  /** convId -> messages in msgId order (append-only) */
  private readonly messageMap = new Map<string, Message[]>();
  /** `${deviceId}|${clientId}` -> original message location */
  private readonly idempotency = new Map<string, { convId: string; msgId: string; expiresAt: number }>();
  private readonly connectionMap = new Map<string, ConnectionRecord>();
  private readonly callMap = new Map<string, CallRecord>();
  private readonly friendMap = new Map<string, FriendRecord>();
  private settingsRecord: ServerSettings | null = null;

  constructor(
    private readonly clock: Clock,
    private readonly ids: IdGen,
  ) {}

  readonly users: Database["users"] = {
    get: async (userId) => clone(this.userMap.get(userId) ?? null),
    put: async (user) => {
      this.userMap.set(user.userId, clone(user));
    },
    update: async (userId, patch) => {
      const u = this.userMap.get(userId);
      if (!u) throw new Error(`user ${userId} not found`);
      Object.assign(u, clone(patch));
      return clone(u);
    },
    list: async () => [...this.userMap.values()].sort((a, b) => a.createdAt - b.createdAt).map(clone),
  };

  readonly devices: Database["devices"] = {
    get: async (deviceId) => clone(this.deviceMap.get(deviceId) ?? null),
    put: async (device) => {
      this.deviceMap.set(device.deviceId, clone(device));
    },
    update: async (deviceId, patch) => {
      const d = this.deviceMap.get(deviceId);
      if (!d) throw new Error(`device ${deviceId} not found`);
      Object.assign(d, clone(patch));
      return clone(d);
    },
    listByUser: async (userId) => [...this.deviceMap.values()].filter((d) => d.userId === userId).map(clone),
  };

  readonly invites: Database["invites"] = {
    get: async (code) => clone(this.inviteMap.get(code) ?? null),
    put: async (invite) => {
      this.inviteMap.set(invite.code, clone(invite));
    },
    // Single-threaded, so the check-and-set below is atomic.
    consume: async (code, usedByUserId, now) => {
      const inv = this.inviteMap.get(code);
      if (!inv || inv.usedAt || inv.expiresAt <= now) return null;
      inv.usedAt = now;
      inv.usedByUserId = usedByUserId;
      return clone(inv);
    },
    delete: async (code) => {
      this.inviteMap.delete(code);
    },
    list: async () => [...this.inviteMap.values()].map(clone),
  };

  readonly sessions: Database["sessions"] = {
    get: async (tokenHash) => {
      const s = this.sessionMap.get(tokenHash);
      if (!s) return null;
      if (s.expiresAt <= this.clock.now()) {
        this.sessionMap.delete(tokenHash);
        return null;
      }
      return clone(s);
    },
    put: async (session) => {
      this.sessionMap.set(session.tokenHash, clone(session));
    },
    delete: async (tokenHash) => {
      this.sessionMap.delete(tokenHash);
    },
  };

  readonly challenges: Database["challenges"] = {
    put: async (challenge) => {
      this.challengeMap.set(challenge.nonce, clone(challenge));
    },
    consume: async (nonce) => {
      const c = this.challengeMap.get(nonce);
      if (!c) return null;
      this.challengeMap.delete(nonce);
      return clone(c);
    },
  };

  private membersOf(convId: string): Map<string, MembershipRecord> {
    let m = this.memberMap.get(convId);
    if (!m) {
      m = new Map();
      this.memberMap.set(convId, m);
    }
    return m;
  }

  readonly conversations: Database["conversations"] = {
    get: async (convId) => clone(this.convMap.get(convId) ?? null),
    create: async (conv, members) => {
      if (this.convMap.has(conv.convId)) throw new Error(`conversation ${conv.convId} exists`);
      this.convMap.set(conv.convId, clone(conv));
      const map = this.membersOf(conv.convId);
      for (const m of members) map.set(m.userId, clone(m));
    },
    createDirect: async (conv, members) => {
      const key = directKey(members[0].userId, members[1].userId);
      const existingId = this.directIndex.get(key);
      const existing = existingId ? this.convMap.get(existingId) : undefined;
      if (existing) return clone(existing);
      this.directIndex.set(key, conv.convId);
      await this.conversations.create(conv, members);
      return clone(conv);
    },
    update: async (convId, patch) => {
      const c = this.convMap.get(convId);
      if (!c) throw new Error(`conversation ${convId} not found`);
      Object.assign(c, clone(patch));
      return clone(c);
    },
    setCurrentKey: async (convId, keyId) => {
      const c = this.convMap.get(convId);
      if (!c) throw new Error(`conversation ${convId} not found`);
      // Key epochs are ULIDs: a later key sorts later. Never move backwards.
      if (c.currentKeyId === null || keyId > c.currentKeyId) {
        c.currentKeyId = keyId;
        c.keyRotationRequired = false;
      }
      return clone(c);
    },
    listAll: async () => [...this.convMap.values()].map(clone),
    listAutoJoin: async () => [...this.convMap.values()].filter((c) => c.autoJoin).map(clone),

    members: async (convId) => [...this.membersOf(convId).values()].map(clone),
    getMember: async (convId, userId) => clone(this.membersOf(convId).get(userId) ?? null),
    addMembers: async (convId, members) => {
      const map = this.membersOf(convId);
      for (const m of members) map.set(m.userId, clone(m));
    },
    removeMember: async (convId, userId) => {
      this.membersOf(convId).delete(userId);
    },
    setLastRead: async (convId, userId, msgId) => {
      const m = this.membersOf(convId).get(userId);
      if (m) m.lastReadMsgId = msgId;
    },
    listByUser: async (userId) => {
      const out: MembershipRecord[] = [];
      for (const map of this.memberMap.values()) {
        const m = map.get(userId);
        if (m) out.push(clone(m));
      }
      return out;
    },
  };

  readonly keys: Database["keys"] = {
    put: async (key) => {
      const id = `${key.convId}|${key.keyId}|${key.recipientDeviceId}`;
      if (!this.keyMap.has(id)) this.keyMap.set(id, clone(key)); // idempotent: first wrap wins
    },
    listForDevice: async (convId, deviceId) =>
      [...this.keyMap.values()]
        .filter((k) => k.convId === convId && k.recipientDeviceId === deviceId)
        .sort((a, b) => (a.keyId < b.keyId ? -1 : a.keyId > b.keyId ? 1 : 0))
        .map(clone),
    recipientsOf: async (convId, keyId) =>
      [...this.keyMap.values()].filter((k) => k.convId === convId && k.keyId === keyId).map((k) => k.recipientDeviceId),
  };

  private messagesOf(convId: string): Message[] {
    let list = this.messageMap.get(convId);
    if (!list) {
      list = [];
      this.messageMap.set(convId, list);
    }
    return list;
  }

  readonly messages: Database["messages"] = {
    append: async (msg: NewMessage, now: number) => {
      const idemKey = msg.senderDeviceId && msg.clientId ? `${msg.senderDeviceId}|${msg.clientId}` : null;
      if (idemKey) {
        const seen = this.idempotency.get(idemKey);
        if (seen && seen.expiresAt > now) {
          const original = this.messagesOf(seen.convId).find((m) => m.msgId === seen.msgId);
          if (original) return { message: clone(original), duplicate: true };
        }
      }
      const message: Message = {
        msgId: `m_${this.ids.ulid(now)}`,
        convId: msg.convId,
        senderId: msg.senderId,
        senderDeviceId: msg.senderDeviceId,
        clientId: msg.clientId,
        kind: msg.kind,
        envelope: msg.envelope,
        call: msg.call,
        system: msg.system,
        sentAt: msg.sentAt,
        createdAt: now,
        deletedAt: null,
      };
      this.messagesOf(msg.convId).push(clone(message));
      if (idemKey) this.idempotency.set(idemKey, { convId: msg.convId, msgId: message.msgId, expiresAt: now + DEFAULT_TTLS.idempotencyTtlMs });
      return { message, duplicate: false };
    },
    get: async (convId, msgId) => clone(this.messagesOf(convId).find((m) => m.msgId === msgId) ?? null),
    listAfter: async (convId, after, limit): Promise<MessagePage> => {
      const all = this.messagesOf(convId).filter((m) => after === null || m.msgId > after);
      return { items: all.slice(0, limit).map(clone), hasMore: all.length > limit };
    },
    listBefore: async (convId, before, limit): Promise<MessagePage> => {
      const all = this.messagesOf(convId)
        .filter((m) => before === null || m.msgId < before)
        .reverse();
      return { items: all.slice(0, limit).map(clone), hasMore: all.length > limit };
    },
    markDeleted: async (convId, msgId, now) => {
      const m = this.messagesOf(convId).find((x) => x.msgId === msgId);
      if (!m) throw new Error(`message ${msgId} not found`);
      m.envelope = null;
      m.deletedAt = now;
      return clone(m);
    },
  };

  readonly connections: Database["connections"] = {
    put: async (conn) => {
      this.connectionMap.set(conn.connectionId, clone(conn));
    },
    get: async (connectionId) => clone(this.connectionMap.get(connectionId) ?? null),
    remove: async (connectionId) => {
      this.connectionMap.delete(connectionId);
    },
    listByUser: async (userId) => [...this.connectionMap.values()].filter((c) => c.userId === userId).map(clone),
    count: async () => this.connectionMap.size,
  };

  readonly friends: Database["friends"] = {
    get: async (userId, friendId) => clone(this.friendMap.get(`${userId}|${friendId}`) ?? null),
    put: async (rec) => {
      this.friendMap.set(`${rec.userId}|${rec.friendId}`, clone(rec));
    },
    list: async (userId) => [...this.friendMap.values()].filter((f) => f.userId === userId).map(clone),
    remove: async (userId, friendId) => {
      this.friendMap.delete(`${userId}|${friendId}`);
    },
  };

  readonly calls: Database["calls"] = {
    get: async (callId) => clone(this.callMap.get(callId) ?? null),
    put: async (call) => {
      this.callMap.set(call.callId, clone(call));
    },
    update: async (callId, patch) => {
      const c = this.callMap.get(callId);
      if (!c) throw new Error(`call ${callId} not found`);
      Object.assign(c, clone(patch));
      return clone(c);
    },
  };

  readonly settings: Database["settings"] = {
    get: async () => clone(this.settingsRecord),
    put: async (settings) => {
      this.settingsRecord = clone(settings);
    },
  };
}

// ---------- ports ----------

export interface MemoryPorts extends Ports {
  db: MemoryDatabase;
  blobs: MemoryBlobStore;
  realtime: MemoryRealtime;
  cache: MemoryCache;
  clock: ManualClock;
}

export interface MemoryPortsOverrides {
  config?: Partial<ServerConfig>;
  clock?: ManualClock;
  ids?: IdGen;
}

export const MEMORY_CONFIG: ServerConfig = {
  serverName: "Test family",
  apiUrl: "https://api.test",
  wsUrl: "wss://ws.test",
  adminKey: "test-admin-key-0123456789",
  sessionTtlMs: DEFAULT_TTLS.sessionTtlMs,
  challengeTtlMs: DEFAULT_TTLS.challengeTtlMs,
  inviteTtlMs: DEFAULT_TTLS.inviteTtlMs,
  maxUploadBytes: DEFAULT_TTLS.maxUploadBytes,
  presignTtlMs: DEFAULT_TTLS.presignTtlMs,
};

export function createMemoryPorts(overrides: MemoryPortsOverrides = {}): MemoryPorts {
  const clock = overrides.clock ?? createManualClock();
  const ids = overrides.ids ?? createIdGen();
  return {
    db: new MemoryDatabase(clock, ids),
    blobs: new MemoryBlobStore(),
    realtime: new MemoryRealtime(),
    cache: new MemoryCache(clock),
    clock,
    ids,
    config: { ...MEMORY_CONFIG, ...(overrides.config ?? {}) },
  };
}
