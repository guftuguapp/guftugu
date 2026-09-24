/**
 * base64url (RFC 4648 §5) without padding — the only binary encoding on the wire.
 *
 * `decodeBase64Url` is strict: it rejects padding, characters outside the
 * alphabet, impossible lengths (len % 4 === 1) and non-canonical trailing bits,
 * so two different strings can never decode to the same bytes.
 */

const ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";

const LOOKUP = new Int16Array(128).fill(-1);
for (let i = 0; i < ALPHABET.length; i++) LOOKUP[ALPHABET.charCodeAt(i)] = i;

export function encodeBase64Url(bytes: Uint8Array): string {
  let out = "";
  let acc = 0;
  let bits = 0;
  for (let i = 0; i < bytes.length; i++) {
    acc = (acc << 8) | (bytes[i] ?? 0);
    bits += 8;
    while (bits >= 6) {
      bits -= 6;
      out += ALPHABET[(acc >> bits) & 63];
      acc &= (1 << bits) - 1;
    }
  }
  if (bits > 0) out += ALPHABET[(acc << (6 - bits)) & 63];
  return out;
}

export function decodeBase64Url(s: string): Uint8Array<ArrayBuffer> {
  if (typeof s !== "string" || s.length % 4 === 1) throw new Error("invalid base64url");
  const out = new Uint8Array(Math.floor((s.length * 6) / 8));
  let acc = 0;
  let bits = 0;
  let n = 0;
  for (let i = 0; i < s.length; i++) {
    const c = s.charCodeAt(i);
    const v = c < 128 ? (LOOKUP[c] ?? -1) : -1;
    if (v < 0) throw new Error("invalid base64url");
    acc = (acc << 6) | v;
    bits += 6;
    if (bits >= 8) {
      bits -= 8;
      out[n++] = (acc >> bits) & 0xff;
      acc &= (1 << bits) - 1;
    }
  }
  // Leftover bits (at most 4) must be zero, otherwise the encoding is not canonical.
  if (acc !== 0) throw new Error("invalid base64url");
  return out;
}

/** True if `s` is a non-empty, strictly valid base64url string. */
export function isBase64Url(s: unknown): s is string {
  if (typeof s !== "string" || s.length === 0) return false;
  try {
    decodeBase64Url(s);
    return true;
  } catch {
    return false;
  }
}

export function utf8Encode(s: string): Uint8Array<ArrayBuffer> {
  return new TextEncoder().encode(s);
}

export function utf8Decode(bytes: Uint8Array): string {
  return new TextDecoder().decode(bytes);
}
