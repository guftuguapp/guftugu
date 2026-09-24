/**
 * WebCrypto helpers — identical on Node, Deno and Workers. No node:* imports.
 *
 * Nothing here ever logs its inputs: they are keys, passwords and signatures.
 */
import { decodeBase64Url, encodeBase64Url, utf8Encode } from "./base64url.js";

const subtle = globalThis.crypto.subtle;

/** PBKDF2-HMAC-SHA256 iteration count (docs/SECURITY.md). */
export const DEFAULT_PBKDF2_ITERATIONS = 600_000;
let defaultIterations = DEFAULT_PBKDF2_ITERATIONS;

/**
 * Tests only: lower the cost of new hashes. Verification always uses the
 * iteration count stored inside the hash string, so nothing else changes.
 */
export function setDefaultPbkdf2Iterations(n: number): void {
  if (!Number.isInteger(n) || n < 1) throw new Error("iterations must be a positive integer");
  defaultIterations = n;
}

export async function sha256(data: Uint8Array | string): Promise<Uint8Array<ArrayBuffer>> {
  const bytes = typeof data === "string" ? utf8Encode(data) : data;
  return new Uint8Array(await subtle.digest("SHA-256", bytes as BufferSource));
}

export function randomBytes(n: number): Uint8Array<ArrayBuffer> {
  return globalThis.crypto.getRandomValues(new Uint8Array(n));
}

export function randomBase64Url(n: number): string {
  return encodeBase64Url(randomBytes(n));
}

/** Constant-time comparison; strings are compared as UTF-8 bytes. Length differences still return false. */
export function timingSafeEqual(a: Uint8Array | string, b: Uint8Array | string): boolean {
  const x = typeof a === "string" ? utf8Encode(a) : a;
  const y = typeof b === "string" ? utf8Encode(b) : b;
  let diff = x.length ^ y.length;
  const n = Math.max(x.length, y.length);
  for (let i = 0; i < n; i++) diff |= (x[i] ?? 0) ^ (y[i] ?? 0);
  return diff === 0;
}

/**
 * DER `SEQUENCE { INTEGER r, INTEGER s }` -> raw 64-byte `r || s` (IEEE P1363).
 * Returns null on any structural problem instead of throwing.
 */
export function derToRawSignature(der: Uint8Array): Uint8Array<ArrayBuffer> | null {
  let p = 0;
  const readLen = (): number | null => {
    const b = der[p++];
    if (b === undefined) return null;
    if (b < 0x80) return b;
    if (b === 0x81) {
      const l = der[p++];
      return l === undefined ? null : l;
    }
    if (b === 0x82) {
      const hi = der[p++];
      const lo = der[p++];
      return hi === undefined || lo === undefined ? null : (hi << 8) | lo;
    }
    return null;
  };
  const readInt = (): Uint8Array | null => {
    if (der[p++] !== 0x02) return null;
    const len = readLen();
    if (len === null || len === 0 || p + len > der.length) return null;
    let v = der.subarray(p, p + len);
    p += len;
    let i = 0;
    while (i < v.length - 1 && v[i] === 0) i++; // strip leading zero bytes (sign padding)
    v = v.subarray(i);
    if (v.length > 32) return null;
    const out = new Uint8Array(32);
    out.set(v, 32 - v.length);
    return out;
  };
  if (der[p++] !== 0x30) return null;
  const seqLen = readLen();
  if (seqLen === null || p + seqLen !== der.length) return null;
  const r = readInt();
  const s = readInt();
  if (!r || !s || p !== der.length) return null;
  const raw = new Uint8Array(64);
  raw.set(r, 0);
  raw.set(s, 32);
  return raw;
}

/**
 * Verify an ECDSA P-256 / SHA-256 signature over `message`.
 * Accepts raw `r||s` (64 bytes) and DER (`0x30…`). Never throws: malformed input -> false.
 */
export async function verifyEcdsaP256(spkiBase64url: string, message: Uint8Array, sigBase64url: string): Promise<boolean> {
  try {
    const spki = decodeBase64Url(spkiBase64url);
    let sig: Uint8Array<ArrayBuffer> = decodeBase64Url(sigBase64url);
    if (sig.length !== 64) {
      if (sig[0] !== 0x30) return false;
      const raw = derToRawSignature(sig);
      if (!raw) return false;
      sig = raw;
    }
    const key = await subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, false, ["verify"]);
    return await subtle.verify({ name: "ECDSA", hash: "SHA-256" }, key, sig, message as BufferSource);
  } catch {
    return false;
  }
}

/** True if the base64url SPKI is a P-256 public key usable for ECDSA or ECDH. */
export async function isValidSpkiP256(spkiBase64url: string): Promise<boolean> {
  let spki: Uint8Array<ArrayBuffer>;
  try {
    spki = decodeBase64Url(spkiBase64url);
  } catch {
    return false;
  }
  if (spki.length === 0) return false;
  try {
    await subtle.importKey("spki", spki, { name: "ECDSA", namedCurve: "P-256" }, true, ["verify"]);
    return true;
  } catch {
    /* try ECDH */
  }
  try {
    await subtle.importKey("spki", spki, { name: "ECDH", namedCurve: "P-256" }, true, []);
    return true;
  } catch {
    return false;
  }
}

async function pbkdf2(password: string, salt: Uint8Array<ArrayBuffer>, iterations: number): Promise<Uint8Array<ArrayBuffer>> {
  const key = await subtle.importKey("raw", utf8Encode(password), "PBKDF2", false, ["deriveBits"]);
  const bits = await subtle.deriveBits({ name: "PBKDF2", hash: "SHA-256", salt, iterations }, key, 256);
  return new Uint8Array(bits);
}

/** -> `pbkdf2-sha256$<iters>$<salt b64url>$<hash b64url>` (16-byte salt, 32-byte hash). */
export async function hashPassword(password: string, iterations: number = defaultIterations): Promise<string> {
  const salt = randomBytes(16);
  const hash = await pbkdf2(password, salt, iterations);
  return `pbkdf2-sha256$${iterations}$${encodeBase64Url(salt)}$${encodeBase64Url(hash)}`;
}

/** Constant-time check of `password` against a stored hash string. Malformed stored value -> false. */
export async function verifyPassword(password: string, stored: string): Promise<boolean> {
  const parts = typeof stored === "string" ? stored.split("$") : [];
  if (parts.length !== 4 || parts[0] !== "pbkdf2-sha256") return false;
  const iterations = Number(parts[1]);
  if (!Number.isInteger(iterations) || iterations < 1) return false;
  let salt: Uint8Array<ArrayBuffer>;
  let expected: Uint8Array<ArrayBuffer>;
  try {
    salt = decodeBase64Url(parts[2] ?? "");
    expected = decodeBase64Url(parts[3] ?? "");
  } catch {
    return false;
  }
  const actual = await pbkdf2(password, salt, iterations);
  return timingSafeEqual(actual, expected);
}
