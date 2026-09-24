/**
 * Request authentication: bearer sessions and the admin key.
 */
import { encodeBase64Url } from "./base64url.js";
import { CACHE_TTL, getDevice, getUser } from "./cached.js";
import { sha256, timingSafeEqual } from "./crypto.js";
import { HttpError, unauthorized } from "./errors.js";
import { getBearer } from "./http.js";
import type { DeviceRecord, Ports, SessionRecord, UserRecord } from "./ports.js";

export interface Principal {
  userId: string;
  deviceId: string;
  user: UserRecord;
  device: DeviceRecord;
  /** sha256 of the bearer token; used to delete the session on logout. */
  tokenHash: string;
}

/** Sessions are stored under `sha256(token)` (base64url); the token itself is never persisted. */
export async function hashSessionToken(token: string): Promise<string> {
  return encodeBase64Url(await sha256(token));
}

/**
 * Resolve a bearer token to its user + device. Throws
 * unauthorized (missing/expired), device_revoked or user_disabled.
 */
export async function resolveSessionByToken(ports: Ports, token: string): Promise<Principal> {
  const tokenHash = await hashSessionToken(token);
  const cacheKey = `sess:${tokenHash}`;
  const now = ports.clock.now();

  let session = await ports.cache.get<SessionRecord>(cacheKey);
  if (!session) {
    session = (await ports.db.sessions.get(tokenHash)) ?? undefined;
    if (session) await ports.cache.set(cacheKey, session, CACHE_TTL.sessionMs);
  }
  if (!session || session.expiresAt <= now) throw unauthorized("invalid or expired session");

  const device = await getDevice(ports, session.deviceId);
  if (!device) throw unauthorized("unknown device");
  if (device.revokedAt) throw new HttpError("device_revoked", "this device has been revoked");

  const user = await getUser(ports, session.userId);
  if (!user) throw unauthorized("unknown user");
  if (user.status === "disabled") throw new HttpError("user_disabled", "this account is disabled");

  return { userId: user.userId, deviceId: device.deviceId, user, device, tokenHash };
}

export async function resolveSession(ports: Ports, req: Request): Promise<Principal> {
  const token = getBearer(req);
  if (!token) throw unauthorized("missing bearer token");
  return resolveSessionByToken(ports, token);
}

/** `X-Admin-Key` must equal the deploy-time admin key (constant-time compare). */
export async function requireAdmin(ports: Ports, req: Request): Promise<void> {
  const provided = req.headers.get("x-admin-key") ?? "";
  const expected = ports.config.adminKey;
  // An empty configured key disables the admin API entirely.
  if (expected.length === 0 || !timingSafeEqual(provided, expected)) {
    throw unauthorized("invalid admin key");
  }
}
