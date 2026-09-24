/**
 * Conformance test for the AWS DynamoDB adapter against DynamoDB Local.
 *
 * Opt-in: skipped unless DYNAMODB_LOCAL_ENDPOINT is set, so `npm test` stays
 * hermetic. To run it:
 *
 *   java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -inMemory -port 8000
 *   DYNAMODB_LOCAL_ENDPOINT=http://127.0.0.1:8000 npm test -- tests/dynamo-adapter.test.ts
 *
 * (Download: https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.tar.gz)
 * A fresh table with the docs/DATA_MODEL.md schema is created per test.
 */
import { CreateTableCommand, DynamoDBClient } from "@aws-sdk/client-dynamodb";
import { DynamoDBDocumentClient } from "@aws-sdk/lib-dynamodb";
import { describe, expect, it } from "vitest";
import { createDynamoDatabase } from "../src/adapters/aws/dynamo.js";
import type { ConversationRecord, Database, MembershipRecord, NewMessage } from "../src/core/ports.js";

const ENDPOINT = process.env.DYNAMODB_LOCAL_ENDPOINT;

let clock = 1_758_500_000_000;
const now = () => clock;
let counter = 0;

async function db(): Promise<Database> {
  const raw = new DynamoDBClient({ region: "local", endpoint: ENDPOINT, credentials: { accessKeyId: "local", secretAccessKey: "local" } });
  const name = `guftugu-test-${process.pid}-${Date.now()}-${counter++}`;
  await raw.send(
    new CreateTableCommand({
      TableName: name,
      BillingMode: "PAY_PER_REQUEST",
      AttributeDefinitions: [
        { AttributeName: "PK", AttributeType: "S" },
        { AttributeName: "SK", AttributeType: "S" },
        { AttributeName: "GSI1PK", AttributeType: "S" },
        { AttributeName: "GSI1SK", AttributeType: "S" },
      ],
      KeySchema: [
        { AttributeName: "PK", KeyType: "HASH" },
        { AttributeName: "SK", KeyType: "RANGE" },
      ],
      GlobalSecondaryIndexes: [
        {
          IndexName: "GSI1",
          KeySchema: [
            { AttributeName: "GSI1PK", KeyType: "HASH" },
            { AttributeName: "GSI1SK", KeyType: "RANGE" },
          ],
          Projection: { ProjectionType: "ALL" },
        },
      ],
    }),
  );
  const doc = DynamoDBDocumentClient.from(raw, { marshallOptions: { removeUndefinedValues: true } });
  return createDynamoDatabase(doc, name, { now });
}
const conv = (id: string, extra: Partial<ConversationRecord> = {}): ConversationRecord => ({
  convId: id, type: "group", name: id, avatarKey: null, createdBy: "u_a", createdAt: now(), autoJoin: false,
  currentKeyId: null, keyRotationRequired: false, lastMsgId: null, lastMessageAt: null, ...extra,
});
const mem = (convId: string, userId: string): MembershipRecord => ({ convId, userId, role: "member", joinedAt: now(), lastReadMsgId: null });
const newMsg = (convId: string, clientId: string | null, i: number): NewMessage => ({
  convId, senderId: "u_a", senderDeviceId: clientId ? "d_a" : null, clientId, kind: "e2e",
  envelope: { v: 1, keyId: "x_1", iv: "aa", ct: `c${i}` }, call: null, system: null, sentAt: now(),
});

describe.skipIf(!ENDPOINT)("dynamo adapter (DynamoDB Local)", () => {
  it("users: put/get/update/list via GSI, nulls preserved", async () => {
    const d = await db();
    await d.users.put({ userId: "u_1", displayName: "A", avatarKey: null, role: "admin", status: "active", createdAt: 1, lastSeenAt: null, passwordHash: "h" });
    await d.users.put({ userId: "u_2", displayName: "B", avatarKey: null, role: "member", status: "active", createdAt: 2, lastSeenAt: null, passwordHash: null });
    expect(await d.users.get("u_1")).toEqual({ userId: "u_1", displayName: "A", avatarKey: null, role: "admin", status: "active", createdAt: 1, lastSeenAt: null, passwordHash: "h" });
    const u = await d.users.update("u_1", { status: "disabled", lastSeenAt: 5 });
    expect(u.status).toBe("disabled"); expect(u.lastSeenAt).toBe(5); expect(u.passwordHash).toBe("h");
    expect((await d.users.list()).map((x) => x.userId)).toEqual(["u_1", "u_2"]);
    expect(await d.users.get("nope")).toBeNull();
    await expect(d.users.update("nope", { status: "active" })).rejects.toThrow();
  });

  it("invites: consume exactly once, not when expired, list via GSI", async () => {
    const d = await db();
    await d.invites.put({ code: "GFT-AAAA-AAAA", role: "member", autoJoin: [], createdAt: now(), expiresAt: now() + 1000, createdBy: "admin", displayName: null, forUserId: null, usedAt: null, usedByUserId: null });
    await d.invites.put({ code: "GFT-BBBB-BBBB", role: "member", autoJoin: [], createdAt: now() + 1, expiresAt: now() + 1000, createdBy: "admin" });
    expect((await d.invites.get("GFT-AAAA-AAAA"))?.usedAt ?? null).toBeNull();
    const [r1, r2] = await Promise.all([d.invites.consume("GFT-AAAA-AAAA", "u_1", now()), d.invites.consume("GFT-AAAA-AAAA", "u_2", now())]);
    expect([r1, r2].filter(Boolean)).toHaveLength(1);
    expect((r1 ?? r2)?.usedByUserId).toMatch(/^u_/);
    expect(await d.invites.consume("GFT-BBBB-BBBB", "u_1", now() + 1000)).toBeNull(); // expired (expiresAt > now fails)
    expect(await d.invites.consume("GFT-NOPE-NOPE", "u_1", now())).toBeNull();
    expect((await d.invites.list()).map((i) => i.code)).toEqual(["GFT-AAAA-AAAA", "GFT-BBBB-BBBB"]);
    await d.invites.delete("GFT-BBBB-BBBB");
    expect(await d.invites.get("GFT-BBBB-BBBB")).toBeNull();
  });

  it("sessions expire; challenges consume once", async () => {
    const d = await db();
    await d.sessions.put({ tokenHash: "h1", userId: "u", deviceId: "d", createdAt: now(), expiresAt: now() + 100 });
    expect((await d.sessions.get("h1"))?.userId).toBe("u");
    clock += 100;
    expect(await d.sessions.get("h1")).toBeNull();
    await d.challenges.put({ nonce: "n1", deviceId: "d", expiresAt: now() + 100 });
    expect((await d.challenges.consume("n1"))?.deviceId).toBe("d");
    expect(await d.challenges.consume("n1")).toBeNull();
  });

  it("conversations: createDirect idempotent, setCurrentKey forward-only, members, listByUser, autoJoin", async () => {
    const d = await db();
    const c1 = conv("c_1", { type: "direct" });
    const first = await d.conversations.createDirect(c1, [mem("c_1", "u_b"), mem("c_1", "u_a")]);
    expect(first.convId).toBe("c_1");
    const again = await d.conversations.createDirect(conv("c_2", { type: "direct" }), [mem("c_2", "u_a"), mem("c_2", "u_b")]);
    expect(again.convId).toBe("c_1");
    expect(await d.conversations.get("c_2")).toBeNull();
    expect((await d.conversations.get("c_1"))?.currentKeyId).toBeNull();

    const k1 = await d.conversations.setCurrentKey("c_1", "x_0002");
    expect(k1.currentKeyId).toBe("x_0002");
    await d.conversations.update("c_1", { keyRotationRequired: true });
    const k0 = await d.conversations.setCurrentKey("c_1", "x_0001"); // older: no-op
    expect(k0.currentKeyId).toBe("x_0002"); expect(k0.keyRotationRequired).toBe(true);
    const k3 = await d.conversations.setCurrentKey("c_1", "x_0003");
    expect(k3.currentKeyId).toBe("x_0003"); expect(k3.keyRotationRequired).toBe(false);

    await d.conversations.create(conv("c_g", { autoJoin: true }), [mem("c_g", "u_a")]);
    await d.conversations.addMembers("c_g", [mem("c_g", "u_c"), mem("c_g", "u_d")]);
    expect((await d.conversations.members("c_g")).map((m) => m.userId).sort()).toEqual(["u_a", "u_c", "u_d"]);
    await d.conversations.removeMember("c_g", "u_d");
    expect(await d.conversations.getMember("c_g", "u_d")).toBeNull();
    await d.conversations.setLastRead("c_g", "u_c", "m_1");
    expect((await d.conversations.getMember("c_g", "u_c"))?.lastReadMsgId).toBe("m_1");
    expect((await d.conversations.listByUser("u_a")).map((m) => m.convId).sort()).toEqual(["c_1", "c_g"]);
    expect((await d.conversations.listAll()).map((c) => c.convId).sort()).toEqual(["c_1", "c_g"]);
    expect((await d.conversations.listAutoJoin()).map((c) => c.convId)).toEqual(["c_g"]);
    const upd = await d.conversations.update("c_g", { name: "New", lastMsgId: "m_9", lastMessageAt: 9 });
    expect(upd).toMatchObject({ name: "New", lastMsgId: "m_9", lastMessageAt: 9, autoJoin: true });
  });

  it("keys: idempotent put, listForDevice via GSI, recipientsOf", async () => {
    const d = await db();
    const w = (keyId: string, dev: string) => ({ keyId, convId: "c_1", recipientDeviceId: dev, senderDeviceId: "d_s", ephemeralPublicKey: "e", iv: "i", ciphertext: "c", signature: "s", createdAt: now() });
    await d.keys.put(w("x_1", "d_1")); await d.keys.put(w("x_1", "d_1")); await d.keys.put(w("x_1", "d_2")); await d.keys.put(w("x_2", "d_1"));
    await d.keys.put({ ...w("x_1", "d_1"), convId: "c_other" });
    expect((await d.keys.listForDevice("c_1", "d_1")).map((k) => k.keyId).sort()).toEqual(["x_1", "x_2"]);
    expect((await d.keys.recipientsOf("c_1", "x_1")).sort()).toEqual(["d_1", "d_2"]);
    expect(await d.keys.recipientsOf("c_1", "x_9")).toEqual([]);
  });

  it("messages: monotonic ids, idempotent append, paging both ways, tombstone", async () => {
    const d = await db();
    await d.conversations.create(conv("c_1"), [mem("c_1", "u_a")]); // META + MEMBER items must not leak into pages
    await d.keys.put({ keyId: "x_1", convId: "c_1", recipientDeviceId: "d_1", senderDeviceId: "d_s", ephemeralPublicKey: "e", iv: "i", ciphertext: "c", signature: "s", createdAt: now() });
    const ids: string[] = [];
    for (let i = 0; i < 5; i++) {
      const r = await d.messages.append(newMsg("c_1", `cid${i}`, i), now());
      expect(r.duplicate).toBe(false); ids.push(r.message.msgId);
    }
    expect([...ids].sort()).toEqual(ids);
    const dup = await d.messages.append(newMsg("c_1", "cid2", 99), now() + 5);
    expect(dup.duplicate).toBe(true); expect(dup.message.msgId).toBe(ids[2]); expect(dup.message.envelope?.ct).toBe("c2");
    const sys = await d.messages.append({ ...newMsg("c_1", null, 7), kind: "system", envelope: null, system: { event: "created" } }, now());
    expect(sys.duplicate).toBe(false);
    const sys2 = await d.messages.append({ ...newMsg("c_1", null, 8), kind: "system", envelope: null, system: { event: "created" } }, now());
    expect(sys2.message.msgId).not.toBe(sys.message.msgId);

    const all = ids.concat([sys.message.msgId, sys2.message.msgId]);
    const p1 = await d.messages.listAfter("c_1", null, 3);
    expect(p1.items.map((m) => m.msgId)).toEqual(all.slice(0, 3)); expect(p1.hasMore).toBe(true);
    const p2 = await d.messages.listAfter("c_1", all[2]!, 3);
    expect(p2.items.map((m) => m.msgId)).toEqual(all.slice(3, 6)); expect(p2.hasMore).toBe(true);
    const p3 = await d.messages.listAfter("c_1", all[5]!, 3);
    expect(p3.items.map((m) => m.msgId)).toEqual(all.slice(6)); expect(p3.hasMore).toBe(false);
    const latest = await d.messages.listBefore("c_1", null, 2);
    expect(latest.items.map((m) => m.msgId)).toEqual([all[6], all[5]]); expect(latest.hasMore).toBe(true);
    const older = await d.messages.listBefore("c_1", all[5]!, 4);
    expect(older.items.map((m) => m.msgId)).toEqual([all[4], all[3], all[2], all[1]]); expect(older.hasMore).toBe(true);
    const oldest = await d.messages.listBefore("c_1", all[1]!, 4);
    expect(oldest.items.map((m) => m.msgId)).toEqual([all[0]]); expect(oldest.hasMore).toBe(false);
    const exact = await d.messages.listBefore("c_1", null, 7);
    expect(exact.items).toHaveLength(7); expect(exact.hasMore).toBe(false);

    const t = await d.messages.markDeleted("c_1", ids[0]!, 123);
    expect(t.envelope).toBeNull(); expect(t.deletedAt).toBe(123);
    expect((await d.messages.get("c_1", ids[0]!))?.deletedAt).toBe(123);
    expect(await d.messages.get("c_1", "m_nope")).toBeNull();
  });

  it("connections: live only, count via scan, remove", async () => {
    const d = await db();
    await d.connections.put({ connectionId: "k1", userId: "u_a", deviceId: "d_1", connectedAt: now() });
    await d.connections.put({ connectionId: "k2", userId: "u_a", deviceId: "d_2", connectedAt: now() - 4 * 3600 * 1000 }); // past safety-net ttl
    await d.connections.put({ connectionId: "k3", userId: "u_b", deviceId: "d_3", connectedAt: now() });
    expect((await d.connections.listByUser("u_a")).map((c) => c.connectionId)).toEqual(["k1"]);
    expect((await d.connections.get("k1"))?.deviceId).toBe("d_1");
    expect(await d.connections.get("k2")).toBeNull();
    expect(await d.connections.count()).toBe(2); // k2 is past its ttl: not live, so not counted
    await d.connections.remove("k1");
    expect(await d.connections.listByUser("u_a")).toEqual([]);
  });

  it("devices, calls, settings", async () => {
    const d = await db();
    const dev = { deviceId: "d_1", userId: "u_a", name: "P", model: null, os: null, appVersion: null, devicePublicKey: "pk", authPublicKey: null, encryptionPublicKey: "ek", enrolledAt: now(), lastSeenAt: null, revokedAt: null, failedPasswordAttempts: 0, lockedUntil: null };
    await d.devices.put(dev); await d.devices.put({ ...dev, deviceId: "d_2" });
    expect(await d.devices.get("d_1")).toEqual(dev);
    expect((await d.devices.listByUser("u_a")).map((x) => x.deviceId)).toEqual(["d_1", "d_2"]);
    expect((await d.devices.update("d_1", { failedPasswordAttempts: 3, revokedAt: 9 })).failedPasswordAttempts).toBe(3);
    await d.calls.put({ callId: "k_1", convId: "c_1", type: "audio", callerId: "u_a", callerDeviceId: "d_1", calleeId: "u_b", calleeDeviceId: null, state: "ringing", createdAt: now(), answeredAt: null, endedAt: null, endReason: null });
    const ended = await d.calls.update("k_1", { state: "ended", endedAt: now(), endReason: "hangup" });
    expect(ended.state).toBe("ended"); expect((ended as any).ttl).toBeUndefined();
    expect(await d.settings.get()).toBeNull();
    const s = { serverName: "F", iceServers: [{ urls: ["stun:x"] }], maxUploadBytes: 5, features: { calls: true, media: false }, defaultAutoJoin: true, inviteTtlHours: 72 };
    await d.settings.put(s);
    expect(await d.settings.get()).toEqual(s);
  });
});
