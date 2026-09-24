/**
 * AWS adapter — `Database` port on one DynamoDB table.
 *
 * Layout is docs/DATA_MODEL.md: keys PK/SK, GSI1 (GSI1PK/GSI1SK), TTL
 * attribute `ttl` in epoch SECONDS. Records are stored as their port shape
 * plus those key attributes, and stripped back on read.
 *
 * One deliberate deviation: the per-item entity marker is stored as `entity`
 * (not `type`), because `ConversationRecord.type` ("direct"|"group") and
 * `CallRecord.type` ("audio"|"video") are record fields that must survive a
 * round trip.
 *
 * No tokens, keys, nonces or message content are ever logged here.
 */
import {
  DeleteCommand,
  GetCommand,
  PutCommand,
  QueryCommand,
  ScanCommand,
  TransactWriteCommand,
  UpdateCommand,
  type DynamoDBDocumentClient,
  type QueryCommandInput,
} from "@aws-sdk/lib-dynamodb";
import type { Message, ServerSettings, WrappedKey } from "../../protocol/types.js";
import {
  DEFAULT_TTLS,
  type CallRecord,
  type ChallengeRecord,
  type ConnectionRecord,
  type ConversationRecord,
  type Database,
  type DeviceRecord,
  type FriendRecord,
  type InviteRecord,
  type MembershipRecord,
  type MessagePage,
  type NewMessage,
  type SessionRecord,
  type UserRecord,
} from "../../core/ports.js";
import { createUlidGenerator } from "../../core/ulid.js";

/** Message ids must be monotonic per process (docs/DATA_MODEL.md). */
const ulid = createUlidGenerator();

// ---------- helpers ----------

type Item = Record<string, unknown>;

const KEY_ATTRS = ["PK", "SK", "GSI1PK", "GSI1SK", "entity", "ttl"] as const;

/** Remove table-only attributes so what comes back is exactly the port record. */
function strip<T>(item: Item | undefined): T | null {
  if (!item) return null;
  const out: Item = { ...item };
  for (const k of KEY_ATTRS) delete out[k];
  return out as T;
}

/** Drop null-valued attributes (so `attribute_not_exists(x)` works on them). */
function omitNulls(item: Item, names: readonly string[]): Item {
  const out: Item = { ...item };
  for (const n of names) if (out[n] === null || out[n] === undefined) delete out[n];
  return out;
}

/** Epoch seconds for the DynamoDB TTL attribute. */
function ttlSeconds(epochMs: number): number {
  return Math.ceil(epochMs / 1000);
}

/** Zero-padded so lexical order == numeric order in GSI1SK. */
function sortableTime(ms: number): string {
  return String(Math.max(0, Math.floor(ms))).padStart(15, "0");
}

function isConditionFailed(err: unknown): boolean {
  return (err as { name?: string } | null)?.name === "ConditionalCheckFailedException";
}

interface CancelledTx {
  name?: string;
  CancellationReasons?: Array<{ Code?: string } | null>;
}

function isTransactionCancelled(err: unknown): err is CancelledTx {
  return (err as { name?: string } | null)?.name === "TransactionCanceledException";
}

const pk = {
  user: (id: string) => `USER#${id}`,
  device: (id: string) => `DEVICE#${id}`,
  invite: (code: string) => `INVITE#${code}`,
  challenge: (nonce: string) => `CHALLENGE#${nonce}`,
  session: (hash: string) => `SESSION#${hash}`,
  conv: (id: string) => `CONV#${id}`,
  direct: (a: string, b: string) => {
    const [x, y] = [a, b].sort();
    return `DIRECT#${x}#${y}`;
  },
  idemp: (deviceId: string, clientId: string) => `IDEMP#${deviceId}#${clientId}`,
  conn: (id: string) => `CONN#${id}`,
  call: (id: string) => `CALL#${id}`,
  settings: "SETTINGS",
};

const sk = {
  profile: "PROFILE",
  meta: "META",
  member: (userId: string) => `MEMBER#${userId}`,
  key: (keyId: string, deviceId: string) => `KEY#${keyId}#${deviceId}`,
  msg: (msgId: string) => `MSG#${msgId}`,
};

export interface DynamoDatabaseOptions {
  /** Clock used for expiry checks (sessions, connections). Default `Date.now`; tests inject a manual clock. */
  now?: () => number;
}

export function createDynamoDatabase(client: DynamoDBDocumentClient, tableName: string, options: DynamoDatabaseOptions = {}): Database {
  const T = tableName;
  const now = options.now ?? (() => Date.now());

  async function getItem<R>(PK: string, SK: string): Promise<R | null> {
    const res = await client.send(new GetCommand({ TableName: T, Key: { PK, SK } }));
    return strip<R>(res.Item);
  }

  async function putItem(item: Item): Promise<void> {
    await client.send(new PutCommand({ TableName: T, Item: item }));
  }

  async function deleteItem(PK: string, SK: string): Promise<void> {
    await client.send(new DeleteCommand({ TableName: T, Key: { PK, SK } }));
  }

  /**
   * SET each key of `patch` (undefined skipped, null stored as NULL). Requires
   * the item to exist; returns the full new item.
   */
  async function updateItem<R>(PK: string, SK: string, patch: Item, extraCondition?: string): Promise<R> {
    const names: Record<string, string> = { "#PK": "PK" };
    const values: Record<string, unknown> = {};
    const sets: string[] = [];
    let i = 0;
    for (const [k, v] of Object.entries(patch)) {
      if (v === undefined) continue;
      names[`#a${i}`] = k;
      values[`:v${i}`] = v;
      sets.push(`#a${i} = :v${i}`);
      i++;
    }
    if (sets.length === 0) {
      const current = await getItem<R>(PK, SK);
      if (!current) throw new Error(`dynamo: item not found for update (${PK.split("#")[0]})`);
      return current;
    }
    try {
      const res = await client.send(
        new UpdateCommand({
          TableName: T,
          Key: { PK, SK },
          UpdateExpression: `SET ${sets.join(", ")}`,
          ConditionExpression: extraCondition ? `attribute_exists(#PK) AND (${extraCondition})` : "attribute_exists(#PK)",
          ExpressionAttributeNames: names,
          ExpressionAttributeValues: values,
          ReturnValues: "ALL_NEW",
        }),
      );
      return strip<R>(res.Attributes) as R;
    } catch (err) {
      if (isConditionFailed(err)) throw new Error(`dynamo: item not found for update (${PK.split("#")[0]})`);
      throw err;
    }
  }

  /** Run a Query to exhaustion (or until `max` items). */
  async function queryAll(input: Omit<QueryCommandInput, "TableName">, max = Infinity): Promise<Item[]> {
    const items: Item[] = [];
    let startKey: Item | undefined;
    do {
      const res = await client.send(
        new QueryCommand({ TableName: T, ...input, ExclusiveStartKey: startKey }),
      );
      for (const it of res.Items ?? []) {
        items.push(it as Item);
        if (items.length >= max) return items;
      }
      startKey = res.LastEvaluatedKey as Item | undefined;
    } while (startKey);
    return items;
  }

  function stripAll<R>(items: Item[]): R[] {
    return items.map((it) => strip<R>(it) as R);
  }

  // ---------- users ----------

  const users: Database["users"] = {
    get: (userId) => getItem<UserRecord>(pk.user(userId), sk.profile),
    put: async (user) => {
      await putItem({
        ...user,
        PK: pk.user(user.userId),
        SK: sk.profile,
        GSI1PK: "USERS",
        GSI1SK: sortableTime(user.createdAt),
        entity: "user",
      });
    },
    update: (userId, patch) => updateItem<UserRecord>(pk.user(userId), sk.profile, patch as Item),
    list: async () =>
      stripAll<UserRecord>(
        await queryAll({
          KeyConditionExpression: "GSI1PK = :p",
          IndexName: "GSI1",
          ExpressionAttributeValues: { ":p": "USERS" },
        }),
      ),
  };

  // ---------- devices ----------

  const devices: Database["devices"] = {
    get: (deviceId) => getItem<DeviceRecord>(pk.device(deviceId), sk.meta),
    put: async (device) => {
      await putItem({
        ...device,
        PK: pk.device(device.deviceId),
        SK: sk.meta,
        GSI1PK: pk.user(device.userId),
        GSI1SK: `DEVICE#${device.deviceId}`,
        entity: "device",
      });
    },
    update: (deviceId, patch) => updateItem<DeviceRecord>(pk.device(deviceId), sk.meta, patch as Item),
    listByUser: async (userId) =>
      stripAll<DeviceRecord>(
        await queryAll({
          IndexName: "GSI1",
          KeyConditionExpression: "GSI1PK = :p AND begins_with(GSI1SK, :s)",
          ExpressionAttributeValues: { ":p": pk.user(userId), ":s": "DEVICE#" },
        }),
      ),
  };

  // ---------- invites ----------

  const invites: Database["invites"] = {
    get: (code) => getItem<InviteRecord>(pk.invite(code), sk.meta),
    put: async (invite) => {
      await putItem({
        ...omitNulls(invite as unknown as Item, ["usedAt", "usedByUserId"]),
        PK: pk.invite(invite.code),
        SK: sk.meta,
        GSI1PK: "INVITES",
        GSI1SK: sortableTime(invite.createdAt),
        entity: "invite",
        ttl: ttlSeconds(invite.expiresAt + 7 * 24 * 3600 * 1000),
      });
    },
    consume: async (code, usedByUserId, now) => {
      try {
        const res = await client.send(
          new UpdateCommand({
            TableName: T,
            Key: { PK: pk.invite(code), SK: sk.meta },
            UpdateExpression: "SET usedAt = :now, usedByUserId = :uid",
            ConditionExpression:
              "attribute_exists(PK) AND (attribute_not_exists(usedAt) OR attribute_type(usedAt, :nul)) AND expiresAt > :now",
            ExpressionAttributeValues: { ":now": now, ":uid": usedByUserId, ":nul": "NULL" },
            ReturnValues: "ALL_NEW",
          }),
        );
        return strip<InviteRecord>(res.Attributes);
      } catch (err) {
        if (isConditionFailed(err)) return null;
        throw err;
      }
    },
    delete: (code) => deleteItem(pk.invite(code), sk.meta),
    list: async () =>
      stripAll<InviteRecord>(
        await queryAll({
          IndexName: "GSI1",
          KeyConditionExpression: "GSI1PK = :p",
          ExpressionAttributeValues: { ":p": "INVITES" },
        }),
      ),
  };

  // ---------- sessions ----------

  const sessions: Database["sessions"] = {
    get: async (tokenHash) => {
      const s = await getItem<SessionRecord>(pk.session(tokenHash), sk.meta);
      if (!s) return null;
      if (s.expiresAt <= now()) return null;
      return s;
    },
    put: async (session) => {
      await putItem({
        ...session,
        PK: pk.session(session.tokenHash),
        SK: sk.meta,
        entity: "session",
        ttl: ttlSeconds(session.expiresAt),
      });
    },
    delete: (tokenHash) => deleteItem(pk.session(tokenHash), sk.meta),
  };

  // ---------- challenges ----------

  const challenges: Database["challenges"] = {
    put: async (challenge) => {
      await putItem({
        ...challenge,
        PK: pk.challenge(challenge.nonce),
        SK: sk.meta,
        entity: "challenge",
        ttl: ttlSeconds(challenge.expiresAt),
      });
    },
    consume: async (nonce) => {
      const res = await client.send(
        new DeleteCommand({
          TableName: T,
          Key: { PK: pk.challenge(nonce), SK: sk.meta },
          ReturnValues: "ALL_OLD",
        }),
      );
      return strip<ChallengeRecord>(res.Attributes);
    },
  };

  // ---------- conversations & memberships ----------

  function convItem(conv: ConversationRecord): Item {
    return {
      ...omitNulls(conv as unknown as Item, ["currentKeyId"]),
      PK: pk.conv(conv.convId),
      SK: sk.meta,
      GSI1PK: "CONVS",
      GSI1SK: sortableTime(conv.createdAt),
      entity: "conversation",
    };
  }

  function memberItem(m: MembershipRecord): Item {
    return {
      ...m,
      PK: pk.conv(m.convId),
      SK: sk.member(m.userId),
      GSI1PK: pk.user(m.userId),
      GSI1SK: pk.conv(m.convId),
      entity: "membership",
    };
  }

  /** Normalise optional attributes back to the record's non-optional nulls. */
  function normaliseConv(item: Item | undefined): ConversationRecord | null {
    const c = strip<ConversationRecord>(item);
    if (!c) return null;
    return {
      ...c,
      autoJoin: c.autoJoin ?? false,
      currentKeyId: c.currentKeyId ?? null,
      keyRotationRequired: c.keyRotationRequired ?? false,
      lastMsgId: c.lastMsgId ?? null,
      lastMessageAt: c.lastMessageAt ?? null,
    };
  }

  async function getConv(convId: string): Promise<ConversationRecord | null> {
    const res = await client.send(new GetCommand({ TableName: T, Key: { PK: pk.conv(convId), SK: sk.meta } }));
    return normaliseConv(res.Item);
  }

  /** Put many items: one transaction when it fits (≤100), otherwise plain puts. */
  async function putMany(items: Item[]): Promise<void> {
    if (items.length === 0) return;
    if (items.length <= 100) {
      await client.send(
        new TransactWriteCommand({ TransactItems: items.map((Item) => ({ Put: { TableName: T, Item } })) }),
      );
      return;
    }
    for (const it of items) await putItem(it);
  }

  const conversations: Database["conversations"] = {
    get: getConv,

    create: async (conv, members) => {
      await putMany([convItem(conv), ...members.map(memberItem)]);
    },

    createDirect: async (conv, members) => {
      const [a, b] = members;
      const directPK = pk.direct(a.userId, b.userId);
      try {
        await client.send(
          new TransactWriteCommand({
            TransactItems: [
              {
                Put: {
                  TableName: T,
                  Item: { PK: directPK, SK: sk.meta, entity: "direct", convId: conv.convId, createdAt: conv.createdAt },
                  ConditionExpression: "attribute_not_exists(PK)",
                },
              },
              { Put: { TableName: T, Item: convItem(conv) } },
              { Put: { TableName: T, Item: memberItem(a) } },
              { Put: { TableName: T, Item: memberItem(b) } },
            ],
          }),
        );
        return conv;
      } catch (err) {
        if (!isTransactionCancelled(err)) throw err;
        const idx = await getItem<{ convId: string }>(directPK, sk.meta);
        if (idx?.convId) {
          const existing = await getConv(idx.convId);
          if (existing) return existing;
        }
        throw err;
      }
    },

    update: async (convId, patch) => {
      const rec = await updateItem<ConversationRecord>(pk.conv(convId), sk.meta, patch as Item);
      return normaliseConv(rec as unknown as Item) as ConversationRecord;
    },

    setCurrentKey: async (convId, keyId) => {
      try {
        const res = await client.send(
          new UpdateCommand({
            TableName: T,
            Key: { PK: pk.conv(convId), SK: sk.meta },
            UpdateExpression: "SET currentKeyId = :k, keyRotationRequired = :f",
            ConditionExpression:
              "attribute_exists(PK) AND (attribute_not_exists(currentKeyId) OR attribute_type(currentKeyId, :nul) OR currentKeyId < :k)",
            ExpressionAttributeValues: { ":k": keyId, ":f": false, ":nul": "NULL" },
            ReturnValues: "ALL_NEW",
          }),
        );
        return normaliseConv(res.Attributes) as ConversationRecord;
      } catch (err) {
        if (!isConditionFailed(err)) throw err;
        const current = await getConv(convId);
        if (!current) throw new Error("dynamo: conversation not found for setCurrentKey");
        return current;
      }
    },

    listAll: async () =>
      (
        await queryAll({
          IndexName: "GSI1",
          KeyConditionExpression: "GSI1PK = :p",
          ExpressionAttributeValues: { ":p": "CONVS" },
        })
      ).map((it) => normaliseConv(it) as ConversationRecord),

    listAutoJoin: async () => (await conversations.listAll()).filter((c) => c.autoJoin === true),

    members: async (convId) =>
      stripAll<MembershipRecord>(
        await queryAll({
          KeyConditionExpression: "PK = :p AND begins_with(SK, :s)",
          ExpressionAttributeValues: { ":p": pk.conv(convId), ":s": "MEMBER#" },
        }),
      ),

    getMember: (convId, userId) => getItem<MembershipRecord>(pk.conv(convId), sk.member(userId)),

    addMembers: async (convId, members) => {
      await putMany(members.map((m) => memberItem({ ...m, convId })));
    },

    removeMember: (convId, userId) => deleteItem(pk.conv(convId), sk.member(userId)),

    setLastRead: async (convId, userId, msgId) => {
      await updateItem<MembershipRecord>(pk.conv(convId), sk.member(userId), { lastReadMsgId: msgId });
    },

    listByUser: async (userId) =>
      stripAll<MembershipRecord>(
        await queryAll({
          IndexName: "GSI1",
          KeyConditionExpression: "GSI1PK = :p AND begins_with(GSI1SK, :s)",
          ExpressionAttributeValues: { ":p": pk.user(userId), ":s": "CONV#" },
        }),
      ),
  };

  // ---------- wrapped keys ----------

  const keys: Database["keys"] = {
    put: async (key) => {
      await putItem({
        ...key,
        PK: pk.conv(key.convId),
        SK: sk.key(key.keyId, key.recipientDeviceId),
        GSI1PK: pk.device(key.recipientDeviceId),
        GSI1SK: `KEY#${key.convId}#${key.keyId}`,
        entity: "key",
      });
    },
    listForDevice: async (convId, deviceId) =>
      stripAll<WrappedKey>(
        await queryAll({
          IndexName: "GSI1",
          KeyConditionExpression: "GSI1PK = :p AND begins_with(GSI1SK, :s)",
          ExpressionAttributeValues: { ":p": pk.device(deviceId), ":s": `KEY#${convId}#` },
        }),
      ),
    recipientsOf: async (convId, keyId) =>
      (
        await queryAll({
          KeyConditionExpression: "PK = :p AND begins_with(SK, :s)",
          ExpressionAttributeValues: { ":p": pk.conv(convId), ":s": `KEY#${keyId}#` },
          ProjectionExpression: "recipientDeviceId",
        })
      )
        .map((it) => it.recipientDeviceId)
        .filter((id): id is string => typeof id === "string"),
  };

  // ---------- messages ----------

  function msgItem(m: Message): Item {
    return { ...m, PK: pk.conv(m.convId), SK: sk.msg(m.msgId), entity: "message" };
  }

  async function getMessage(convId: string, msgId: string): Promise<Message | null> {
    return getItem<Message>(pk.conv(convId), sk.msg(msgId));
  }

  async function pageQuery(input: Omit<QueryCommandInput, "TableName">, limit: number, excludeSK?: string): Promise<MessagePage> {
    const res = await client.send(new QueryCommand({ TableName: T, ...input, Limit: limit + (excludeSK ? 2 : 1) }));
    let items = (res.Items ?? []) as Item[];
    if (excludeSK) items = items.filter((it) => it.SK !== excludeSK);
    // LastEvaluatedKey also covers the 1 MB page cap (200 x 64 KiB envelopes can hit it).
    const hasMore = items.length > limit || Boolean(res.LastEvaluatedKey);
    return { items: stripAll<Message>(items.slice(0, limit)), hasMore };
  }

  const messages: Database["messages"] = {
    append: async (msg, now) => {
      const idempPK = msg.senderDeviceId && msg.clientId ? pk.idemp(msg.senderDeviceId, msg.clientId) : null;

      for (let attempt = 0; attempt < 3; attempt++) {
        const msgId = `m_${ulid(now)}`;
        const message: Message = {
          msgId,
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
        const tx: NonNullable<ConstructorParameters<typeof TransactWriteCommand>[0]["TransactItems"]> = [
          { Put: { TableName: T, Item: msgItem(message), ConditionExpression: "attribute_not_exists(SK)" } },
        ];
        if (idempPK) {
          tx.push({
            Put: {
              TableName: T,
              Item: {
                PK: idempPK,
                SK: sk.meta,
                entity: "idempotency",
                convId: msg.convId,
                msgId,
                createdAt: now,
                ttl: ttlSeconds(now + DEFAULT_TTLS.idempotencyTtlMs),
              },
              // TTL deletion is lazy (up to ~48 h), so an expired marker must
              // not block a resend after the 7-day window. `ttl` is reserved.
              ConditionExpression: "attribute_not_exists(PK) OR #ttl <= :nowSec",
              ExpressionAttributeNames: { "#ttl": "ttl" },
              ExpressionAttributeValues: { ":nowSec": Math.floor(now / 1000) },
            },
          });
        }
        try {
          await client.send(new TransactWriteCommand({ TransactItems: tx }));
          return { message, duplicate: false };
        } catch (err) {
          if (!isTransactionCancelled(err)) throw err;
          const reasons = err.CancellationReasons ?? [];
          const idempFailed = idempPK !== null && reasons[1]?.Code === "ConditionalCheckFailed";
          const msgFailed = reasons[0]?.Code === "ConditionalCheckFailed";
          if (idempPK && (idempFailed || !msgFailed)) {
            const marker = await getItem<{ msgId: string; convId: string }>(idempPK, sk.meta);
            if (marker?.msgId) {
              const original = await getMessage(marker.convId ?? msg.convId, marker.msgId);
              if (original) return { message: original, duplicate: true };
            }
          }
          if (msgFailed) continue; // ulid collision — regenerate and retry
          throw err;
        }
      }
      throw new Error("dynamo: could not append message after retries");
    },

    get: getMessage,

    listAfter: async (convId, after, limit) => {
      if (after === null) {
        return pageQuery(
          {
            KeyConditionExpression: "PK = :p AND begins_with(SK, :s)",
            ExpressionAttributeValues: { ":p": pk.conv(convId), ":s": "MSG#" },
            ScanIndexForward: true,
          },
          limit,
        );
      }
      // Every other SK prefix in a CONV# partition sorts before "MSG#", so an
      // open upper bound is safe.
      return pageQuery(
        {
          KeyConditionExpression: "PK = :p AND SK > :a",
          ExpressionAttributeValues: { ":p": pk.conv(convId), ":a": sk.msg(after) },
          ScanIndexForward: true,
        },
        limit,
      );
    },

    listBefore: async (convId, before, limit) => {
      if (before === null) {
        return pageQuery(
          {
            KeyConditionExpression: "PK = :p AND begins_with(SK, :s)",
            ExpressionAttributeValues: { ":p": pk.conv(convId), ":s": "MSG#" },
            ScanIndexForward: false,
          },
          limit,
        );
      }
      // BETWEEN is inclusive, so fetch one extra and drop `before` itself.
      const hi = sk.msg(before);
      return pageQuery(
        {
          KeyConditionExpression: "PK = :p AND SK BETWEEN :lo AND :hi",
          ExpressionAttributeValues: { ":p": pk.conv(convId), ":lo": "MSG#", ":hi": hi },
          ScanIndexForward: false,
        },
        limit,
        hi,
      );
    },

    markDeleted: async (convId, msgId, now) =>
      updateItem<Message>(pk.conv(convId), sk.msg(msgId), { envelope: null, deletedAt: now }),
  };

  // ---------- connections ----------

  const connections: Database["connections"] = {
    put: async (conn) => {
      await putItem({
        ...conn,
        PK: pk.conn(conn.connectionId),
        SK: sk.meta,
        GSI1PK: pk.user(conn.userId),
        GSI1SK: `CONN#${conn.connectionId}`,
        entity: "connection",
        ttl: ttlSeconds(conn.connectedAt + DEFAULT_TTLS.connectionTtlMs),
      });
    },
    get: async (connectionId) => {
      const res = await client.send(new GetCommand({ TableName: T, Key: { PK: pk.conn(connectionId), SK: sk.meta } }));
      if (!res.Item || isExpired(res.Item as Item)) return null;
      return strip<ConnectionRecord>(res.Item);
    },
    remove: (connectionId) => deleteItem(pk.conn(connectionId), sk.meta),
    listByUser: async (userId) =>
      stripAll<ConnectionRecord>(
        (
          await queryAll({
            IndexName: "GSI1",
            KeyConditionExpression: "GSI1PK = :p AND begins_with(GSI1SK, :s)",
            ExpressionAttributeValues: { ":p": pk.user(userId), ":s": "CONN#" },
          })
        ).filter((it) => !isExpired(it)),
      ),
    count: async () => {
      // Admin-only stat; a filtered Scan is fine at family scale. Like
      // get/listByUser, ignore rows whose TTL passed but were not yet swept.
      let total = 0;
      let startKey: Item | undefined;
      const nowSec = Math.floor(now() / 1000);
      do {
        const res = await client.send(
          new ScanCommand({
            TableName: T,
            Select: "COUNT",
            FilterExpression: "entity = :c AND #ttl > :nowSec",
            ExpressionAttributeNames: { "#ttl": "ttl" },
            ExpressionAttributeValues: { ":c": "connection", ":nowSec": nowSec },
            ExclusiveStartKey: startKey,
          }),
        );
        total += res.Count ?? 0;
        startKey = res.LastEvaluatedKey as Item | undefined;
      } while (startKey);
      return total;
    },
  };

  /** TTL deletion lags by up to ~48 h; treat expired items as gone. */
  function isExpired(item: Item): boolean {
    const t = item.ttl;
    return typeof t === "number" && t * 1000 <= now();
  }

  // ---------- calls ----------

  const calls: Database["calls"] = {
    get: (callId) => getItem<CallRecord>(pk.call(callId), sk.meta),
    put: async (call) => {
      const item: Item = { ...call, PK: pk.call(call.callId), SK: sk.meta, entity: "call" };
      if (call.state === "ended") item.ttl = ttlSeconds((call.endedAt ?? now()) + 24 * 3600 * 1000);
      await putItem(item);
    },
    update: async (callId, patch) => {
      const p: Item = { ...patch };
      if (patch.state === "ended") {
        p.ttl = ttlSeconds((patch.endedAt ?? now()) + 24 * 3600 * 1000);
      }
      return updateItem<CallRecord>(pk.call(callId), sk.meta, p);
    },
  };

  // ---------- settings ----------

  const settings: Database["settings"] = {
    get: () => getItem<ServerSettings>(pk.settings, sk.meta),
    put: async (s) => {
      await putItem({ ...s, PK: pk.settings, SK: sk.meta, entity: "settings" });
    },
  };

  // ---------- friends: PK USER#me, SK FRIEND#them (one row per direction) ----------

  const friends: Database["friends"] = {
    get: (userId, friendId) => getItem<FriendRecord>(pk.user(userId), `FRIEND#${friendId}`),
    put: async (rec) => {
      await putItem({ ...rec, PK: pk.user(rec.userId), SK: `FRIEND#${rec.friendId}`, entity: "friend" });
    },
    list: async (userId) =>
      stripAll<FriendRecord>(
        await queryAll({
          KeyConditionExpression: "PK = :p AND begins_with(SK, :s)",
          ExpressionAttributeValues: { ":p": pk.user(userId), ":s": "FRIEND#" },
        }),
      ),
    remove: (userId, friendId) => deleteItem(pk.user(userId), `FRIEND#${friendId}`),
  };

  return { friends, users, devices, invites, sessions, challenges, conversations, keys, messages, connections, calls, settings };
}
