/**
 * Fingerprint-first accounts (PROTOCOL.md §3–§5): a password is optional when a biometric key is
 * registered, fingerprint unlock can be turned on later with PUT /me/device/auth-key, and a first
 * backup password can be set without an old one.
 */
import { describe, expect, it } from "vitest";
import { utf8Encode } from "../src/core/base64url.js";
import {
  call,
  challenge,
  createInvite,
  encodeBase64Url,
  enrollWithCode,
  errorCode,
  genEcdhPublicKey,
  genSigningKey,
  login,
  makeApp,
  signBytes,
  type Enrolled,
  type SigningKey,
} from "./helpers.js";

async function enrollFingerprintOnly(t: ReturnType<typeof makeApp>, name: string) {
  const invite = await createInvite(t.app, { displayName: name });
  const deviceKey = await genSigningKey();
  const authKey = await genSigningKey();
  const r = await call(t.app, "POST", "/enroll", {
    body: {
      inviteCode: invite.code,
      displayName: name,
      devicePublicKey: deviceKey.publicKey,
      authPublicKey: authKey.publicKey,
      encryptionPublicKey: await genEcdhPublicKey(),
      device: { name: "Phone" },
    },
  });
  return { r, invite, deviceKey, authKey };
}

async function authKeyBody(e: { deviceId: string }, deviceKey: SigningKey, newKey: SigningKey, nonce: string) {
  const message = utf8Encode(`guftugu-authkey:v1:${e.deviceId}:${nonce}:${newKey.publicKey}`);
  return {
    nonce,
    authPublicKey: newKey.publicKey,
    deviceSignature: encodeBase64Url(await signBytes(deviceKey.privateKey, message)),
    authSignature: encodeBase64Url(await signBytes(newKey.privateKey, message)),
  };
}

describe("fingerprint-first enrolment", () => {
  it("enrols without a password when a biometric key is registered", async () => {
    const t = makeApp();
    const { r, deviceKey, authKey } = await enrollFingerprintOnly(t, "Ammi");
    expect(r.status).toBe(201);
    expect(r.body.user.hasPassword).toBe(false);
    expect(r.body.device.hasBiometricKey).toBe(true);

    const e: Enrolled = {
      user: r.body.user, device: r.body.device, userId: r.body.user.userId, deviceId: r.body.device.deviceId,
      token: r.body.session.token, deviceKey, authKey, password: "",
    };
    expect((await login(t.app, e, "biometric")).status).toBe(200);
    // No password set: the password path can never succeed.
    expect(errorCode(await login(t.app, e, "password", "anything-at-all"))).toBe("bad_credentials");
    expect((await call(t.app, "GET", "/me", { token: e.token })).body.hasPassword).toBe(false);
  });

  it("joins with neither passcode nor fingerprint; the device key signs in until a passcode exists", async () => {
    const t = makeApp();
    const invite = await createInvite(t.app, { displayName: "NoLock" });
    const deviceKey = await genSigningKey();
    const bare = {
      inviteCode: invite.code,
      displayName: "NoLock",
      devicePublicKey: deviceKey.publicKey,
      authPublicKey: null,
      encryptionPublicKey: await genEcdhPublicKey(),
      device: { name: "Phone without a screen lock" },
    };
    // A too-short passcode is still rejected (and does not burn the invite)…
    expect(errorCode(await call(t.app, "POST", "/enroll", { body: { ...bare, password: "short" } }))).toBe("invalid_request");
    // …but no passcode at all is fine.
    const r = await call(t.app, "POST", "/enroll", { body: bare });
    expect(r.status).toBe(201);
    expect(r.body.user.hasPassword).toBe(false);
    expect(r.body.device.hasBiometricKey).toBe(false);
    const e: Enrolled = {
      user: r.body.user, device: r.body.device, userId: r.body.user.userId, deviceId: r.body.device.deviceId,
      token: r.body.session.token, deviceKey, authKey: null, password: "",
    };

    const deviceLogin = async () => {
      const ch = await challenge(t.app, e.deviceId);
      const signature = encodeBase64Url(await signBytes(deviceKey.privateKey, utf8Encode(`guftugu-auth:v1:${e.deviceId}:${ch.body.nonce}`)));
      return call(t.app, "POST", "/auth/verify", { body: { deviceId: e.deviceId, nonce: ch.body.nonce, method: "device", signature } });
    };
    expect((await deviceLogin()).status).toBe(200);

    // Once a passcode exists (created a week later in the app) the phone alone is no longer enough.
    expect((await call(t.app, "PUT", "/me/password", { token: e.token, body: { newPassword: "123456" } })).status).toBe(204);
    expect(errorCode(await deviceLogin())).toBe("bad_credentials");
    expect((await login(t.app, e, "password", "123456")).status).toBe(200);
  });

  it("never lets the device key bypass a fingerprint lock", async () => {
    const t = makeApp();
    const { r, deviceKey } = await enrollFingerprintOnly(t, "Locked");
    const deviceId = r.body.device.deviceId;
    const ch = await challenge(t.app, deviceId);
    const signature = encodeBase64Url(await signBytes(deviceKey.privateKey, utf8Encode(`guftugu-auth:v1:${deviceId}:${ch.body.nonce}`)));
    const res = await call(t.app, "POST", "/auth/verify", { body: { deviceId, nonce: ch.body.nonce, method: "device", signature } });
    expect(errorCode(res)).toBe("bad_credentials");
  });
});

describe("PUT /me/password", () => {
  it("sets a first backup password without currentPassword, then requires it to change", async () => {
    const t = makeApp();
    const { r } = await enrollFingerprintOnly(t, "Abbu");
    const token = r.body.session.token;
    expect((await call(t.app, "PUT", "/me/password", { token, body: { newPassword: "a-good-backup-pass" } })).status).toBe(204);
    expect((await call(t.app, "GET", "/me", { token })).body.hasPassword).toBe(true);
    expect(errorCode(await call(t.app, "PUT", "/me/password", { token, body: { newPassword: "another-good-pass" } }))).toBe("bad_credentials");
    expect(errorCode(await call(t.app, "PUT", "/me/password", { token, body: { currentPassword: "wrong-wrong", newPassword: "another-good-pass" } }))).toBe("bad_credentials");
    expect((await call(t.app, "PUT", "/me/password", { token, body: { currentPassword: "a-good-backup-pass", newPassword: "another-good-pass" } })).status).toBe(204);
  });
});

describe("PUT /me/device/auth-key", () => {
  it("turns on fingerprint unlock for a password-only device", async () => {
    const t = makeApp();
    const invite = await createInvite(t.app, { displayName: "Dadi" });
    const e = await enrollWithCode(t.app, invite.code, { displayName: "Dadi", withBiometric: false });
    expect(e.device.hasBiometricKey).toBe(false);

    const newKey = await genSigningKey();
    const ch = await challenge(t.app, e.deviceId);
    const res = await call(t.app, "PUT", "/me/device/auth-key", { token: e.token, body: await authKeyBody(e, e.deviceKey, newKey, ch.body.nonce) });
    expect(res.status).toBe(200);
    expect(res.body.hasBiometricKey).toBe(true);

    // The new key now unlocks the account.
    expect((await login(t.app, { ...e, authKey: newKey }, "biometric")).status).toBe(200);
    // The same nonce cannot be replayed.
    const replay = await call(t.app, "PUT", "/me/device/auth-key", { token: e.token, body: await authKeyBody(e, e.deviceKey, newKey, ch.body.nonce) });
    expect(errorCode(replay)).toBe("challenge_invalid");
  });

  it("rejects a stolen session without the device key, a key without proof, and foreign nonces", async () => {
    const t = makeApp();
    const a = await enrollWithCode(t.app, (await createInvite(t.app, { displayName: "A" })).code, { displayName: "A", withBiometric: false });
    const b = await enrollWithCode(t.app, (await createInvite(t.app, { displayName: "B" })).code, { displayName: "B", withBiometric: false });
    const attackerKey = await genSigningKey();

    // Stolen token + attacker's own keys: the device signature does not verify.
    let ch = await challenge(t.app, a.deviceId);
    let res = await call(t.app, "PUT", "/me/device/auth-key", { token: a.token, body: await authKeyBody(a, attackerKey, attackerKey, ch.body.nonce) });
    expect(errorCode(res)).toBe("bad_signature");

    // Device-signed but no proof of possession of the new key.
    ch = await challenge(t.app, a.deviceId);
    const good = await authKeyBody(a, a.deviceKey, attackerKey, ch.body.nonce);
    res = await call(t.app, "PUT", "/me/device/auth-key", { token: a.token, body: { ...good, authSignature: good.deviceSignature } });
    expect(errorCode(res)).toBe("bad_signature");

    // A nonce issued for another device.
    ch = await challenge(t.app, b.deviceId);
    res = await call(t.app, "PUT", "/me/device/auth-key", { token: a.token, body: await authKeyBody(a, a.deviceKey, attackerKey, ch.body.nonce) });
    expect(errorCode(res)).toBe("challenge_invalid");

    // No session at all.
    ch = await challenge(t.app, a.deviceId);
    res = await call(t.app, "PUT", "/me/device/auth-key", { body: await authKeyBody(a, a.deviceKey, attackerKey, ch.body.nonce) });
    expect(res.status).toBe(401);
    expect((await call(t.app, "GET", "/me/devices", { token: a.token })).body.items[0].hasBiometricKey).toBe(false);
  });
});
