/**
 * Server settings (admin-editable) and the two public views of them:
 * `/.well-known/guftugu` and `GET /config`.
 */
import type { ClientConfig, IceServer, ServerSettings, WellKnown } from "../../protocol/types.js";
import { resolveSession } from "../auth.js";
import { CACHE_TTL } from "../cached.js";
import { json } from "../http.js";
import type { Ports } from "../ports.js";
import { PROTOCOL_VERSION } from "../../protocol/types.js";

export const DEFAULT_ICE_SERVERS: IceServer[] = [
  { urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] },
];

const SETTINGS_CACHE_KEY = "settings";

export function defaultSettings(ports: Ports): ServerSettings {
  return {
    serverName: ports.config.serverName,
    iceServers: DEFAULT_ICE_SERVERS,
    maxUploadBytes: ports.config.maxUploadBytes,
    features: { calls: true, media: true },
    defaultAutoJoin: true,
    inviteTtlHours: Math.round(ports.config.inviteTtlMs / 3_600_000) || 72,
  };
}

/** Stored settings merged over the defaults, cached 5 min. */
export async function getSettings(ports: Ports): Promise<ServerSettings> {
  const hit = await ports.cache.get<ServerSettings>(SETTINGS_CACHE_KEY);
  if (hit) return hit;
  const stored = await ports.db.settings.get();
  const defaults = defaultSettings(ports);
  const merged: ServerSettings = {
    ...defaults,
    ...(stored ?? {}),
    features: { ...defaults.features, ...(stored?.features ?? {}) },
  };
  await ports.cache.set(SETTINGS_CACHE_KEY, merged, CACHE_TTL.settingsMs);
  return merged;
}

export async function saveSettings(ports: Ports, settings: ServerSettings): Promise<void> {
  await ports.db.settings.put(settings);
  await ports.cache.set(SETTINGS_CACHE_KEY, settings, CACHE_TTL.settingsMs);
}

export function toClientConfig(s: ServerSettings): ClientConfig {
  return {
    serverName: s.serverName,
    iceServers: s.iceServers,
    maxUploadBytes: s.maxUploadBytes,
    features: { calls: s.features.calls, media: s.features.media },
  };
}

export async function getClientConfig(ports: Ports): Promise<ClientConfig> {
  return toClientConfig(await getSettings(ports));
}

// ---------- handlers ----------

export async function wellKnown(ports: Ports): Promise<Response> {
  const settings = await getSettings(ports);
  const body: WellKnown = {
    name: settings.serverName,
    protocolVersion: PROTOCOL_VERSION,
    apiUrl: ports.config.apiUrl,
    wsUrl: ports.config.wsUrl,
    enrollment: "invite",
    serverTime: ports.clock.now(),
  };
  return json(body);
}

export async function getConfig(ports: Ports, req: Request): Promise<Response> {
  await resolveSession(ports, req);
  return json(await getClientConfig(ports));
}
