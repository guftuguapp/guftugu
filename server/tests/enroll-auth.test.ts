import { describe, expect, it } from "vitest";
import {
  call,
  challenge,
  connect,
  createInvite,
  enrollUser,
  enrollWithCode,
  errorCode,
  genEcdhPublicKey,
  genSigningKey,
  login,
  makeApp,
  rawToDer,
  signBytes,
  decodeBase64Url,
  encodeBase64Url,
} from "./helpers.js";

describe("enrollment", () => {
  it("admin invite -> enroll creates user, device and session; invite is single use", async () => {
    const { app, ports } = makeApp();
    const invite = await createInvite(app, { displayName: "Ammi", role: "admin" });
    expect(invite.code).toMatch(/^GFT-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
    expect(invite.link).toBe(`guftugu://join?api=${encodeURIComponent("https://api.test")}&code=${invite.code}`);
    expect(invite.expiresAt - invite.createdAt).toBe(72 * 3_600_000);

    const e = await enrollWithCode(app, invite.code, { displayName: "Ammi" });
    expect(e.user.role).toBe("admin");
    expect(e.user.displayName).toBe("Ammi");
    expect(e.device.hasBiometricKey).toBe(true);
    expect(e.device.revokedAt).toBeNull();
    expect(e.userId).toMatch(/^u_/);
    expect(e.deviceId).toMatch(/^d_/);
    expect(e.token.length).toBeGreaterThan(30);

    // The server holds only a PBKDF2 hash and the public keys.
    const stored = await ports.db.users.get(e.userId);
    expect(stored?.passwordHash).toMatch(/^pbkdf2-sha256\$/);
    expect(JSON.stringify(e.user)).not.toContain("pbkdf2");

    const me = await call(app, "GET", "/me", { token: e.token });
    expect(me.status).toBe(200);
    expect(me.body.userId).toBe(e.userId);

    // Single use.
    const again = await call(app, "POST", "/enroll", {
      body: {
        inviteCode: invite.code,
        displayName: "X",
        password: "password123",
        devicePublicKey: (await genSigningKey()).publicKey,
        authPublicKey: null,
        encryptionPublicKey: await genEcdhPublicKey(),
        device: { name: "other" },
      },
    });
    expect(again.status).toBe(400);
    expect(errorCode(again)).toBe("invite_invalid");

    const listed = await call(app, "GET", "/admin/invites", { admin: true });
    expect(listed.body.items[0].usedByUserId).toBe(e.userId);
  });

  it("rejects unknown and expired invites, short passwords and bad keys", async () => {
    const { app, ports } = makeApp();
    const valid = async (code: string, extra: Record<string, unknown> = {}) =>
      call(app, "POST", "/enroll", {
        body: {
          inviteCode: code,
          displayName: "X",
          password: "password123",
          devicePublicKey: (await genSigningKey()).publicKey,
          authPublicKey: null,
          encryptionPublicKey: await genEcdhPublicKey(),
          device: { name: "phone" },
          ...extra,
        },
      });

    expect(errorCode(await valid("GFT-0000-0000"))).toBe("invite_invalid");

    const short = await createInvite(app, { expiresInHours: 1 });
    ports.clock.advance(3_600_001);
    const expired = await valid(short.code);
    expect(expired.status).toBe(410);
    expect(errorCode(expired)).toBe("invite_expired");

    const inv = await createInvite(app);
    expect(errorCode(await valid(inv.code, { password: "short" }))).toBe("invalid_request");
    expect(errorCode(await valid(inv.code, { devicePublicKey: "AAAA" }))).toBe("invalid_request");
    expect(errorCode(await valid(inv.code, { authPublicKey: "AAAA" }))).toBe("invalid_request");
    expect(errorCode(await valid(inv.code, { displayName: "" }))).toBe("invalid_request");
    // None of the failures consumed the invite.
    expect((await valid(inv.code)).status).toBe(201);
  });

  it("link code enrols a second phone for the same user and ignores displayName/password", async () => {
    const { app } = makeApp();
    const first = await enrollUser(app, "Abbu");
    const lc = await call(app, "POST", "/me/link-code", { token: first.token });
    expect(lc.status).toBe(201);
    expect(lc.body.forUserId).toBe(first.userId);
    expect(lc.body.expiresAt - lc.body.createdAt).toBe(24 * 3_600_000);

    const second = await enrollWithCode(app, lc.body.code, { displayName: "Ignored", password: "x" });
    expect(second.userId).toBe(first.userId);
    expect(second.user.displayName).toBe("Abbu");
    expect(second.deviceId).not.toBe(first.deviceId);

    const devices = await call(app, "GET", "/me/devices", { token: first.token });
    expect(devices.body.items.map((d: { deviceId: string }) => d.deviceId).sort()).toEqual([first.deviceId, second.deviceId].sort());
  });

  it("new users auto-join autoJoin conversations and invite.autoJoin lists, with member_added system messages", async () => {
    const { app, ports } = makeApp();
    const family = await call(app, "POST", "/admin/conversations", { admin: true, body: { name: "Family", autoJoin: true } });
    const cousins = await call(app, "POST", "/admin/conversations", { admin: true, body: { name: "Cousins", autoJoin: false } });
    expect(family.status).toBe(201);
    expect(family.body.autoJoin).toBe(true);

    const u = await enrollUser(app, "Bhai", { autoJoin: [cousins.body.convId] });
    const convs = await call(app, "GET", "/conversations", { token: u.token });
    expect(convs.body.items.map((c: { name: string }) => c.name).sort()).toEqual(["Cousins", "Family"]);

    const msgs = await call(app, "GET", `/conversations/${family.body.convId}/messages`, { token: u.token });
    expect(msgs.body.items).toHaveLength(1);
    expect(msgs.body.items[0].kind).toBe("system");
    expect(msgs.body.items[0].system).toEqual({ event: "member_added", userIds: [u.userId] });

    // Existing members were told.
    const listed = await ports.db.conversations.members(family.body.convId);
    expect(listed.map((m) => m.userId)).toEqual([u.userId]);
  });
});

describe("login", () => {
  it("challenge is single use and bound to the device; biometric verify accepts raw and DER signatures", async () => {
    const { app, ports } = makeApp();
    const e = await enrollUser(app, "Ammi");

    const ch = await challenge(app, e.deviceId);
    expect(ch.status).toBe(200);
    expect(decodeBase64Url(ch.body.nonce).length).toBe(32);
    expect(ch.body.expiresAt).toBe(ports.clock.now() + 120_000);

    const msg = new TextEncoder().encode(`guftugu-auth:v1:${e.deviceId}:${ch.body.nonce}`);
    const raw = await signBytes(e.authKey!.privateKey, msg);
    const ok = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch.body.nonce, method: "biometric", signature: encodeBase64Url(raw) },
    });
    expect(ok.status).toBe(200);
    expect(ok.body.session.token).not.toBe(e.token);
    expect(ok.body.session.expiresAt).toBe(ports.clock.now() + 30 * 24 * 3_600_000);
    expect(ok.body.config.serverName).toBe("Test family");

    // Same nonce again -> consumed.
    const replay = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch.body.nonce, method: "biometric", signature: encodeBase64Url(raw) },
    });
    expect(errorCode(replay)).toBe("challenge_invalid");

    // DER path.
    const ch2 = await challenge(app, e.deviceId);
    const der = rawToDer(await signBytes(e.authKey!.privateKey, new TextEncoder().encode(`guftugu-auth:v1:${e.deviceId}:${ch2.body.nonce}`)));
    const okDer = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch2.body.nonce, method: "biometric", signature: encodeBase64Url(der) },
    });
    expect(okDer.status).toBe(200);

    // Wrong key -> bad_signature.
    const ch3 = await challenge(app, e.deviceId);
    const wrong = await signBytes(e.deviceKey.privateKey, new TextEncoder().encode(`guftugu-auth:v1:${e.deviceId}:${ch3.body.nonce}`));
    const bad = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch3.body.nonce, method: "biometric", signature: encodeBase64Url(wrong) },
    });
    expect(bad.status).toBe(401);
    expect(errorCode(bad)).toBe("bad_signature");

    // Nonce for another device -> challenge_invalid; expired -> challenge_invalid.
    const other = await enrollUser(app, "Other");
    const ch4 = await challenge(app, other.deviceId);
    const mismatch = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch4.body.nonce, method: "biometric", signature: encodeBase64Url(raw) },
    });
    expect(errorCode(mismatch)).toBe("challenge_invalid");
    const ch5 = await challenge(app, e.deviceId);
    ports.clock.advance(120_001);
    const late = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch5.body.nonce, method: "biometric", signature: encodeBase64Url(raw) },
    });
    expect(errorCode(late)).toBe("challenge_invalid");
  });

  it("biometric without an auth key -> bad_signature; unknown device -> device_revoked", async () => {
    const { app } = makeApp();
    const e = await enrollUser(app, "NoBio", { withBiometric: false });
    expect(e.device.hasBiometricKey).toBe(false);
    const ch = await challenge(app, e.deviceId);
    const sig = encodeBase64Url(await signBytes(e.deviceKey.privateKey, new TextEncoder().encode(`guftugu-auth:v1:${e.deviceId}:${ch.body.nonce}`)));
    const r = await call(app, "POST", "/auth/verify", { body: { deviceId: e.deviceId, nonce: ch.body.nonce, method: "biometric", signature: sig } });
    expect(errorCode(r)).toBe("bad_signature");
    const unknown = await challenge(app, "d_nope");
    expect(unknown.status).toBe(403);
    expect(errorCode(unknown)).toBe("device_revoked");
  });

  it("password path: device signature + password, lockout after 5 failures for 15 minutes", async () => {
    const { app, ports } = makeApp();
    const e = await enrollUser(app, "Ammi", { password: "correct horse battery staple" });

    const good = await login(app, e, "password");
    expect(good.status).toBe(200);

    for (let i = 1; i <= 4; i++) {
      const r = await login(app, e, "password", "wrong password");
      expect(r.status).toBe(401);
      expect(errorCode(r)).toBe("bad_credentials");
    }
    const fifth = await login(app, e, "password", "wrong password");
    expect(fifth.status).toBe(429);
    expect(errorCode(fifth)).toBe("password_locked");

    // Even the right password is locked out now...
    expect(errorCode(await login(app, e, "password"))).toBe("password_locked");
    // ...but biometric still works.
    expect((await login(app, e, "biometric")).status).toBe(200);

    ports.clock.advance(15 * 60_000 + 1);
    const after = await login(app, e, "password");
    expect(after.status).toBe(200);
    expect((await ports.db.devices.get(e.deviceId))?.failedPasswordAttempts).toBe(0);

    // Wrong device signature never reaches the password check.
    const ch = await challenge(app, e.deviceId);
    const sig = encodeBase64Url(await signBytes(e.authKey!.privateKey, new TextEncoder().encode(`guftugu-auth:v1:${e.deviceId}:${ch.body.nonce}`)));
    const r = await call(app, "POST", "/auth/verify", {
      body: { deviceId: e.deviceId, nonce: ch.body.nonce, method: "password", signature: sig, password: e.password },
    });
    expect(errorCode(r)).toBe("bad_signature");
  });

  it("revoked device -> device_revoked everywhere; its live connections are dropped", async () => {
    const t = makeApp();
    const { app } = t;
    const e = await enrollUser(app, "Ammi");
    const lc = await call(app, "POST", "/me/link-code", { token: e.token });
    const second = await enrollWithCode(app, lc.body.code);
    const conn = await connect(t, second);

    const del = await call(app, "DELETE", `/me/devices/${second.deviceId}`, { token: e.token });
    expect(del.status).toBe(204);
    expect(await t.ports.db.connections.get(conn)).toBeNull();

    const me = await call(app, "GET", "/me", { token: second.token });
    expect(me.status).toBe(403);
    expect(errorCode(me)).toBe("device_revoked");
    expect(errorCode(await challenge(app, second.deviceId))).toBe("device_revoked");
    expect((await t.app.ws.onConnect("c2", second.token)).status).toBe(403);
    expect(errorCode(await call(app, "DELETE", `/me/devices/${e.deviceId}x`, { token: e.token }))).toBe("not_found");
    // Cannot revoke someone else's device.
    const other = await enrollUser(app, "Other");
    expect((await call(app, "DELETE", `/me/devices/${e.deviceId}`, { token: other.token })).status).toBe(404);
  });

  it("disabled user -> user_disabled; re-enabled works again", async () => {
    const { app } = makeApp();
    const e = await enrollUser(app, "Ammi");
    const patched = await call(app, "PATCH", `/admin/users/${e.userId}`, { admin: true, body: { status: "disabled" } });
    expect(patched.status).toBe(200);
    expect(patched.body.status).toBe("disabled");
    const me = await call(app, "GET", "/me", { token: e.token });
    expect(me.status).toBe(403);
    expect(errorCode(me)).toBe("user_disabled");
    expect(errorCode(await challenge(app, e.deviceId))).toBe("user_disabled");
    await call(app, "PATCH", `/admin/users/${e.userId}`, { admin: true, body: { status: "active" } });
    expect((await call(app, "GET", "/me", { token: e.token })).status).toBe(200);
  });

  it("logout invalidates the session; sessions expire after 30 days", async () => {
    const { app, ports } = makeApp();
    const e = await enrollUser(app, "Ammi");
    expect((await call(app, "POST", "/auth/logout", { token: e.token })).status).toBe(204);
    const me = await call(app, "GET", "/me", { token: e.token });
    expect(me.status).toBe(401);
    expect(errorCode(me)).toBe("unauthorized");

    const fresh = await login(app, e, "biometric");
    const token = fresh.body.session.token;
    expect((await call(app, "GET", "/me", { token })).status).toBe(200);
    ports.clock.advance(30 * 24 * 3_600_000 + 1);
    expect(errorCode(await call(app, "GET", "/me", { token }))).toBe("unauthorized");
  });

  it("PUT /me/password verifies the current password", async () => {
    const { app } = makeApp();
    const e = await enrollUser(app, "Ammi");
    const bad = await call(app, "PUT", "/me/password", { token: e.token, body: { currentPassword: "nope nope", newPassword: "new password 1" } });
    expect(errorCode(bad)).toBe("bad_credentials");
    const short = await call(app, "PUT", "/me/password", { token: e.token, body: { currentPassword: e.password, newPassword: "short" } });
    expect(errorCode(short)).toBe("invalid_request");
    const ok = await call(app, "PUT", "/me/password", { token: e.token, body: { currentPassword: e.password, newPassword: "new password 1" } });
    expect(ok.status).toBe(204);
    expect((await login(app, e, "password", "new password 1")).status).toBe(200);
    expect(errorCode(await login(app, e, "password", e.password))).toBe("bad_credentials");
  });

  it("PATCH /me updates the profile and notifies co-members", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    await call(t.app, "POST", "/conversations", { token: a.token, body: { type: "direct", memberId: b.userId } });
    const connB = await connect(t, b);
    t.ports.realtime.clear();
    const r = await call(t.app, "PATCH", "/me", { token: a.token, body: { displayName: "Ammi", avatarKey: `avatars/${a.userId}/x` } });
    expect(r.status).toBe(200);
    expect(r.body.displayName).toBe("Ammi");
    const ev = t.ports.realtime.ofType("user.updated", connB);
    expect(ev).toHaveLength(1);
    expect(ev[0]?.user.avatarKey).toBe(`avatars/${a.userId}/x`);
    const users = await call(t.app, "GET", "/users", { token: b.token });
    expect(users.body.items.find((u: { userId: string }) => u.userId === a.userId).displayName).toBe("Ammi");
    const devs = await call(t.app, "GET", `/users/${a.userId}/devices`, { token: b.token });
    expect(devs.body.items).toHaveLength(1);
    expect(Object.keys(devs.body.items[0]).sort()).toEqual(["deviceId", "devicePublicKey", "encryptionPublicKey", "enrolledAt", "name", "userId"]);
  });
});
