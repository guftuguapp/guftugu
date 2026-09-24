import { describe, expect, it } from "vitest";
import { call, connect, directConversation, enrollUser, enrollWithCode, errorCode, groupConversation, makeApp, type Enrolled } from "./helpers.js";

function wrap(convId: string, keyId: string, sender: Enrolled, recipientDeviceId: string) {
  return {
    keyId,
    convId,
    recipientDeviceId,
    senderDeviceId: sender.deviceId,
    ephemeralPublicKey: "ZXBo",
    iv: "aXZpdml2aXZpdg",
    ciphertext: "Y3Q",
    signature: "c2ln",
  };
}

describe("conversation keys", () => {
  it("post/list/recipients, hasCurrentKey, forward-only currentKeyId and conversation.keys events", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const lc = await call(t.app, "POST", "/me/link-code", { token: b.token });
    const b2 = await enrollWithCode(t.app, lc.body.code); // B's second phone
    const outsider = await enrollUser(t.app, "X");
    const d = await directConversation(t.app, a, b);
    const connB = await connect(t, b);
    const connB2 = await connect(t, b2);
    const connA = await connect(t, a);

    // Nothing yet.
    const empty = await call(t.app, "GET", `/conversations/${d.convId}/key-recipients`, { token: a.token });
    expect(empty.body.currentKeyId).toBeNull();
    expect(empty.body.keyRotationRequired).toBe(false);
    expect(empty.body.devices.map((x: { deviceId: string; hasCurrentKey: boolean }) => [x.deviceId, x.hasCurrentKey]).sort()).toEqual(
      [[a.deviceId, false], [b.deviceId, false], [b2.deviceId, false]].sort(),
    );

    const k1 = `x_${t.ports.ids.ulid(t.ports.clock.now())}`;
    t.ports.realtime.clear();
    const posted = await call(t.app, "POST", `/conversations/${d.convId}/keys`, {
      token: a.token,
      body: { keyId: k1, wrapped: [wrap(d.convId, k1, a, a.deviceId), wrap(d.convId, k1, a, b.deviceId)] },
    });
    expect(posted.status).toBe(201);
    expect(posted.body).toEqual({ currentKeyId: k1 });
    expect(t.ports.realtime.ofType("conversation.keys", connB)).toEqual([{ type: "conversation.keys", convId: d.convId, keyId: k1 }]);
    expect(t.ports.realtime.ofType("conversation.keys", connB2)).toHaveLength(0);
    expect(t.ports.realtime.ofType("conversation.keys", connA)).toHaveLength(1);
    expect(t.ports.realtime.ofType("conversation.updated", connB2)[0]?.conversation.currentKeyId).toBe(k1);

    const recips = await call(t.app, "GET", `/conversations/${d.convId}/key-recipients`, { token: b.token });
    expect(recips.body.currentKeyId).toBe(k1);
    expect(recips.body.devices.find((x: { deviceId: string }) => x.deviceId === b2.deviceId).hasCurrentKey).toBe(false);
    expect(recips.body.devices.find((x: { deviceId: string }) => x.deviceId === b.deviceId).hasCurrentKey).toBe(true);

    // B wraps the current key for its second phone: same keyId, not a rotation.
    t.ports.realtime.clear();
    const fill = await call(t.app, "POST", `/conversations/${d.convId}/keys`, {
      token: b.token,
      body: { keyId: k1, wrapped: [wrap(d.convId, k1, b, b2.deviceId)] },
    });
    expect(fill.status).toBe(201);
    expect(t.ports.realtime.ofType("conversation.keys", connB2)).toHaveLength(1);
    expect(t.ports.realtime.ofType("conversation.updated")).toHaveLength(0);

    const mine = await call(t.app, "GET", `/conversations/${d.convId}/keys`, { token: b2.token });
    expect(mine.body.currentKeyId).toBe(k1);
    expect(mine.body.items).toHaveLength(1);
    expect(mine.body.items[0]).toMatchObject({ keyId: k1, recipientDeviceId: b2.deviceId, senderDeviceId: b.deviceId });
    expect(typeof mine.body.items[0].createdAt).toBe("number");
    expect((await call(t.app, "GET", `/conversations/${d.convId}/keys`, { token: a.token })).body.items).toHaveLength(1);

    // Rotation to a newer key clears keyRotationRequired; an older key never becomes current.
    await t.ports.db.conversations.update(d.convId, { keyRotationRequired: true });
    await t.ports.cache.del(`conv:${d.convId}`);
    const k2 = `x_${t.ports.ids.ulid(t.ports.clock.now())}`;
    const rot = await call(t.app, "POST", `/conversations/${d.convId}/keys`, {
      token: a.token,
      body: { keyId: k2, wrapped: [wrap(d.convId, k2, a, b.deviceId), wrap(d.convId, k2, a, b2.deviceId), wrap(d.convId, k2, a, a.deviceId)] },
    });
    expect(rot.body.currentKeyId).toBe(k2);
    const afterRot = await call(t.app, "GET", `/conversations/${d.convId}/key-recipients`, { token: a.token });
    expect(afterRot.body.keyRotationRequired).toBe(false);
    expect(afterRot.body.devices.every((x: { hasCurrentKey: boolean }) => x.hasCurrentKey)).toBe(true);

    const k0 = "x_0000000000000000000000000";
    const old = await call(t.app, "POST", `/conversations/${d.convId}/keys`, { token: a.token, body: { keyId: k0, wrapped: [wrap(d.convId, k0, a, b.deviceId)] } });
    expect(old.status).toBe(201);
    expect(old.body.currentKeyId).toBe(k2);
    // All epochs are kept for history.
    expect((await call(t.app, "GET", `/conversations/${d.convId}/keys`, { token: b.token })).body.items.map((w: { keyId: string }) => w.keyId)).toEqual([k0, k1, k2]);

    // Authorization rules.
    expect((await call(t.app, "GET", `/conversations/${d.convId}/keys`, { token: outsider.token })).status).toBe(403);
    expect((await call(t.app, "POST", `/conversations/${d.convId}/keys`, { token: outsider.token, body: { keyId: k2, wrapped: [wrap(d.convId, k2, outsider, b.deviceId)] } })).status).toBe(403);
    const spoof = await call(t.app, "POST", `/conversations/${d.convId}/keys`, { token: a.token, body: { keyId: k2, wrapped: [wrap(d.convId, k2, b, b.deviceId)] } });
    expect(spoof.status).toBe(403);
    const stranger = await call(t.app, "POST", `/conversations/${d.convId}/keys`, { token: a.token, body: { keyId: k2, wrapped: [wrap(d.convId, k2, a, outsider.deviceId)] } });
    expect(stranger.status).toBe(403);
    expect(errorCode(await call(t.app, "POST", `/conversations/${d.convId}/keys`, { token: a.token, body: { keyId: k2, wrapped: [] } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "POST", `/conversations/${d.convId}/keys`, { token: a.token, body: { keyId: k2, wrapped: [wrap(d.convId, "x_other", a, b.deviceId)] } }))).toBe("invalid_request");
  });

  it("revoked devices are not key recipients, and revocation flags rotation in every conversation", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const g = await groupConversation(t.app, a, "G", [b]);
    const d = await directConversation(t.app, a, b);
    const connA = await connect(t, a);
    t.ports.realtime.clear();

    const del = await call(t.app, "DELETE", `/admin/users/${b.userId}/devices/${b.deviceId}`, { admin: true });
    expect(del.status).toBe(204);
    for (const convId of [g.convId, d.convId]) {
      const r = await call(t.app, "GET", `/conversations/${convId}/key-recipients`, { token: a.token });
      expect(r.body.keyRotationRequired).toBe(true);
      expect(r.body.devices.map((x: { deviceId: string }) => x.deviceId)).toEqual([a.deviceId]);
    }
    const updates = t.ports.realtime.ofType("conversation.updated", connA);
    expect(updates.map((u) => u.conversation.convId).sort()).toEqual([g.convId, d.convId].sort());
    expect(updates.every((u) => u.conversation.keyRotationRequired)).toBe(true);
    const adminDevices = await call(t.app, "GET", `/admin/users/${b.userId}/devices`, { admin: true });
    expect(adminDevices.body.items[0].revokedAt).toBe(t.ports.clock.now());
    expect((await call(t.app, "DELETE", `/admin/users/${a.userId}/devices/${b.deviceId}`, { admin: true })).status).toBe(404);
  });
});
