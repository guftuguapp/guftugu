/**
 * Small helpers around the web-standard Request/Response.
 */
import { PROTOCOL_VERSION } from "../protocol/types.js";
import { HttpError, badRequest } from "./errors.js";

export const JSON_CONTENT_TYPE = "application/json; charset=utf-8";

/** Default cap on request bodies; the message envelope has its own tighter cap. */
export const DEFAULT_MAX_BODY_BYTES = 1024 * 1024;

export function json(body: unknown, status = 200, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": JSON_CONTENT_TYPE, ...headers },
  });
}

export function noContent(): Response {
  return new Response(null, { status: 204 });
}

export function isObject(v: unknown): v is Record<string, unknown> {
  return typeof v === "object" && v !== null && !Array.isArray(v);
}

/**
 * Parse a JSON object body. Bad JSON, a non-object top level, or a body larger
 * than `maxBytes` -> invalid_request.
 */
export async function readJson<T>(req: Request, maxBytes = DEFAULT_MAX_BODY_BYTES): Promise<T> {
  const declared = Number(req.headers.get("content-length") ?? "0");
  if (Number.isFinite(declared) && declared > maxBytes) throw badRequest("request body too large");
  let buf: ArrayBuffer;
  try {
    buf = await req.arrayBuffer();
  } catch {
    throw badRequest("unreadable request body");
  }
  if (buf.byteLength > maxBytes) throw badRequest("request body too large");
  if (buf.byteLength === 0) return {} as T; // no body: every field is simply absent
  let parsed: unknown;
  try {
    parsed = JSON.parse(new TextDecoder().decode(buf));
  } catch {
    throw badRequest("malformed JSON body");
  }
  if (!isObject(parsed)) throw badRequest("JSON body must be an object");
  return parsed as T;
}

/** The bearer token from `Authorization`, or null when absent/malformed. Never logged. */
export function getBearer(req: Request): string | null {
  const h = req.headers.get("authorization");
  if (!h) return null;
  const m = /^Bearer\s+(\S+)$/i.exec(h.trim());
  return m?.[1] ?? null;
}

/** Paths that do not need `X-Guftugu-Protocol`: discovery and the admin CLI. */
export function isProtocolHeaderExempt(pathname: string): boolean {
  return pathname === "/.well-known/guftugu" || pathname === "/admin" || pathname.startsWith("/admin/");
}

/** 426 upgrade_required unless the request carries `X-Guftugu-Protocol: 1` (or the path is exempt). */
export function requireProtocolHeader(req: Request): void {
  const pathname = new URL(req.url).pathname;
  if (isProtocolHeaderExempt(pathname)) return;
  const v = req.headers.get("x-guftugu-protocol");
  if (v === null) throw new HttpError("upgrade_required", "missing X-Guftugu-Protocol header");
  if (v.trim() !== String(PROTOCOL_VERSION)) {
    throw new HttpError("upgrade_required", `unsupported protocol version ${v.trim()}; this server speaks ${PROTOCOL_VERSION}`);
  }
}

/** A query parameter, or null when absent. */
export function query(req: Request, name: string): string | null {
  return new URL(req.url).searchParams.get(name);
}
