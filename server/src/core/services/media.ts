/**
 * Media (PROTOCOL.md §9): the server only hands out presigned URLs; bytes are
 * client-encrypted and go straight to the blob store.
 */
import type { DownloadUrlResponse, UploadUrlResponse } from "../../protocol/types.js";
import { resolveSession } from "../auth.js";
import { HttpError, badRequest, forbidden } from "../errors.js";
import { json, query, readJson } from "../http.js";
import type { Ports } from "../ports.js";
import { requireEnum, requireNumber, requireString } from "../validate.js";
import { getSettings } from "./config.js";
import { requireMember } from "./messages.js";

export async function uploadUrl(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const body = await readJson<Record<string, unknown>>(req);
  const kind = requireEnum(body, "kind", ["attachment", "thumbnail", "avatar"] as const);
  const mime = requireString(body, "mime", 256);
  const sizeBytes = requireNumber(body, "sizeBytes");
  if (!Number.isInteger(sizeBytes) || sizeBytes <= 0) throw badRequest("sizeBytes must be a positive integer");
  const settings = await getSettings(ports);
  if (sizeBytes > settings.maxUploadBytes) throw new HttpError("payload_too_large", `sizeBytes exceeds ${settings.maxUploadBytes}`);

  const now = ports.clock.now();
  let key: string;
  if (kind === "avatar") {
    key = `avatars/${p.userId}/${ports.ids.ulid(now)}`;
  } else {
    const convId = requireString(body, "convId", 256);
    await requireMember(ports, convId, p.userId);
    key = `conv/${convId}/${ports.ids.ulid(now)}.bin`;
  }

  const signed = await ports.blobs.presignUpload(key, mime, sizeBytes, ports.config.presignTtlMs);
  const res: UploadUrlResponse = {
    key,
    uploadUrl: signed.url,
    method: signed.method,
    headers: signed.headers,
    expiresAt: now + ports.config.presignTtlMs,
  };
  return json(res);
}

export async function downloadUrl(ports: Ports, req: Request): Promise<Response> {
  const p = await resolveSession(ports, req);
  const key = query(req, "key");
  if (!key) throw badRequest("key is required");
  const segments = key.split("/");
  if (segments.some((s) => s.length === 0 || s === "." || s === "..")) throw badRequest("malformed key");

  if (segments[0] === "conv") {
    if (segments.length < 3) throw badRequest("malformed key");
    await requireMember(ports, segments[1] ?? "", p.userId); // forbidden for non-members
  } else if (segments[0] === "avatars") {
    if (segments.length < 3) throw badRequest("malformed key");
    // Avatars are visible to everyone on this private server.
  } else {
    throw forbidden("unknown key prefix");
  }

  const now = ports.clock.now();
  const signed = await ports.blobs.presignDownload(key, ports.config.presignTtlMs);
  const res: DownloadUrlResponse = { downloadUrl: signed.url, expiresAt: now + ports.config.presignTtlMs };
  return json(res);
}
