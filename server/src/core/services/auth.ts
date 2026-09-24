/**
 * Login (PROTOCOL.md §4): challenge–response signed by a Keystore key,
 * biometric (authPublicKey) or password (devicePublicKey + PBKDF2 check with lockout).
 */
import type { AuthResponse, Session } from "../../protocol/types.js";
import { hashSessionToken, resolveSession } from "../auth.js";
import { utf8Encode } from "../base64url.js";
import { getDevice, getUser, invalidateDevice, invalidateUser } from "../cached.js";
import { isValidSpkiP256, verifyEcdsaP256, verifyPassword } from "../crypto.js";
import { HttpError } from "../errors.js";
import { json, noContent, readJson } from "../http.js";
import { DEFAULT_TTLS, type DeviceRecord, type Ports, type UserRecord } from "../ports.js";
import { toDevice, toMe } from "../dto.js";
import { requireEnum, requireString } from "../validate.js";
import { getClientConfig } from "./config.js";

const AUTH_MESSAGE_PREFIX = "guftugu-auth:v1:";
const AUTH_KEY_MESSAGE_PREFIX = "guftugu-authkey:v1:";

/** Mint a session: 32 random bytes; only sha256(token) is stored. */
export async function createSession(ports: Ports, userId: string, deviceId: string): Promise<Session> {
  const now = ports.clock.now();
  const token = ports.ids.randomBase64Url(32);
  const expiresAt = now + ports.config.sessionTtlMs;
  await ports.db.sessions.put({ tokenHash: await hashSessionToken(token), userId, deviceId, createdAt: now, expiresAt });
  return { token, expiresAt };
}

export async function buildAuthResponse(ports: Ports, user: UserRecord, device: DeviceRecord, session: Session): Promise<AuthResponse> {
  return { user: toMe(user), device: toDevice(device), session, config: await getClientConfig(ports) };
}

/** Device must exist and be unrevoked; its user must be active. */
async function loadAuthDevice(ports: Ports, deviceId: string): Promise<{ device: DeviceRecord; user: UserRecord }> {
  const device = await getDevice(ports, deviceId);
  if (!device || device.revokedAt) throw new HttpError("device_revoked", "unknown or revoked device");
  const user = await getUser(ports, device.userId);
  if (!user || user.status === "disabled") throw new HttpError("user_disabled", "this account is disabled");
  return { device, user };
}

// ---------- handlers ----------

export async function challenge(ports: Ports, req: Request): Promise<Response> {
  const body = await readJson<Record<string, unknown>>(req);
  const deviceId = requireString(body, "deviceId", 256);
  await loadAuthDevice(ports, deviceId);

  const nonce = ports.ids.randomBase64Url(32);
  const expiresAt = ports.clock.now() + ports.config.challengeTtlMs;
  await ports.db.challenges.put({ nonce, deviceId, expiresAt });
  return json({ nonce, expiresAt });
}

export async function verify(ports: Ports, req: Request): Promise<Response> {
  const body = await readJson<Record<string, unknown>>(req);
  const deviceId = requireString(body, "deviceId", 256);
  const nonce = requireString(body, "nonce", 256);
  const method = requireEnum(body, "method", ["biometric", "password", "device"] as const);
  const signature = requireString(body, "signature", 1024);
  const password = method === "password" ? requireString(body, "password", 1024) : null;
  const now = ports.clock.now();

  // Nonces are single use whatever happens next.
  const ch = await ports.db.challenges.consume(nonce);
  if (!ch || ch.expiresAt <= now || ch.deviceId !== deviceId) {
    throw new HttpError("challenge_invalid", "challenge missing, expired or for another device");
  }

  const { device, user } = await loadAuthDevice(ports, deviceId);
  const message = utf8Encode(`${AUTH_MESSAGE_PREFIX}${deviceId}:${nonce}`);

  if (method === "biometric") {
    if (!device.authPublicKey || !(await verifyEcdsaP256(device.authPublicKey, message, signature))) {
      throw new HttpError("bad_signature", "biometric signature did not verify");
    }
  } else if (method === "device") {
    // The phone itself is the credential only while the account has neither a passcode nor a
    // fingerprint key on this device: it can never bypass either lock once one exists.
    if (user.passwordHash || device.authPublicKey) {
      throw new HttpError("bad_credentials", "this account unlocks with its passcode or fingerprint");
    }
    if (!(await verifyEcdsaP256(device.devicePublicKey, message, signature))) {
      throw new HttpError("bad_signature", "device signature did not verify");
    }
  } else {
    // The device signature proves the enrolled phone; the password proves the person.
    if (!(await verifyEcdsaP256(device.devicePublicKey, message, signature))) {
      throw new HttpError("bad_signature", "device signature did not verify");
    }
    if (device.lockedUntil && device.lockedUntil > now) {
      throw new HttpError("password_locked", "too many failed attempts; try again later");
    }
    const ok = user.passwordHash !== null && (await verifyPassword(password ?? "", user.passwordHash));
    if (!ok) {
      const attempts = device.failedPasswordAttempts + 1;
      if (attempts >= DEFAULT_TTLS.passwordLockAfter) {
        await ports.db.devices.update(deviceId, { failedPasswordAttempts: 0, lockedUntil: now + DEFAULT_TTLS.passwordLockMs });
        await invalidateDevice(ports, deviceId);
        throw new HttpError("password_locked", "too many failed attempts; try again later");
      }
      await ports.db.devices.update(deviceId, { failedPasswordAttempts: attempts });
      await invalidateDevice(ports, deviceId);
      throw new HttpError("bad_credentials", "wrong password");
    }
  }

  const patch: Parameters<Ports["db"]["devices"]["update"]>[1] = { lastSeenAt: now };
  if (device.failedPasswordAttempts !== 0 || device.lockedUntil) {
    patch.failedPasswordAttempts = 0;
    patch.lockedUntil = null;
  }
  const updatedDevice = await ports.db.devices.update(deviceId, patch);
  await invalidateDevice(ports, deviceId);
  const updatedUser = await ports.db.users.update(user.userId, { lastSeenAt: now });
  await invalidateUser(ports, user.userId);

  const session = await createSession(ports, user.userId, deviceId);
  return json(await buildAuthResponse(ports, updatedUser, updatedDevice, session));
}

export async function logout(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  await ports.db.sessions.delete(p.tokenHash);
  await ports.cache.del(`sess:${p.tokenHash}`);
  return noContent();
}

/**
 * `PUT /me/device/auth-key` — turn on (or replace) fingerprint unlock for the calling device.
 *
 * A session alone is not enough: the request must also carry a fresh challenge signed by the
 * device's hardware key (so a stolen session token cannot plant an attacker's key) and by the new
 * biometric key itself (proof of possession, which on the phone requires a BiometricPrompt).
 */
export async function registerAuthKey(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const nonce = requireString(body, "nonce", 256);
  const authPublicKey = requireString(body, "authPublicKey", 1024);
  const deviceSignature = requireString(body, "deviceSignature", 1024);
  const authSignature = requireString(body, "authSignature", 1024);
  const now = ports.clock.now();

  const ch = await ports.db.challenges.consume(nonce);
  if (!ch || ch.expiresAt <= now || ch.deviceId !== p.deviceId) {
    throw new HttpError("challenge_invalid", "challenge missing, expired or for another device");
  }
  if (!(await isValidSpkiP256(authPublicKey))) throw new HttpError("invalid_request", "authPublicKey is not a P-256 SPKI key");

  const { device } = await loadAuthDevice(ports, p.deviceId);
  const message = utf8Encode(`${AUTH_KEY_MESSAGE_PREFIX}${p.deviceId}:${nonce}:${authPublicKey}`);
  if (!(await verifyEcdsaP256(device.devicePublicKey, message, deviceSignature))) {
    throw new HttpError("bad_signature", "device signature did not verify");
  }
  if (!(await verifyEcdsaP256(authPublicKey, message, authSignature))) {
    throw new HttpError("bad_signature", "auth key signature did not verify");
  }
  const updated = await ports.db.devices.update(p.deviceId, { authPublicKey, lastSeenAt: now });
  await invalidateDevice(ports, p.deviceId);
  return json(toDevice(updated));
}
