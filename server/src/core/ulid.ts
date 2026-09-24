/**
 * ULIDs (Crockford base32, 26 chars, time-ordered) and invite codes.
 *
 * The generator is monotonic within a process: ids minted in the same
 * millisecond (or after the clock moved backwards) still sort after the
 * previous one, which is what message paging relies on.
 */
import { encodeBase64Url } from "./base64url.js";
import { randomBytes } from "./crypto.js";
import type { IdGen } from "./ports.js";

/** Crockford alphabet: no I, L, O, U. */
const CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ";

function encodeTime(ms: number): string {
  let t = ms;
  let out = "";
  for (let i = 0; i < 10; i++) {
    out = CROCKFORD[t % 32] + out;
    t = Math.floor(t / 32);
  }
  return out;
}

/** Pack `bytes` into base32 chars, 5 bits per char (bytes.length * 8 must be divisible by 5). */
function encodeBase32(bytes: Uint8Array): string {
  let out = "";
  let acc = 0;
  let bits = 0;
  for (let i = 0; i < bytes.length; i++) {
    acc = (acc << 8) | (bytes[i] ?? 0);
    bits += 8;
    while (bits >= 5) {
      bits -= 5;
      out += CROCKFORD[(acc >> bits) & 31];
      acc &= (1 << bits) - 1;
    }
  }
  return out;
}

export function createUlidGenerator(): (now: number) => string {
  let lastTime = -1;
  const random = new Uint8Array(10); // 80 random bits

  return (now: number): string => {
    // Never go backwards, even if the clock does.
    const t = Math.max(Math.floor(now), lastTime);
    if (t === lastTime) {
      // Same millisecond: increment the random part by one.
      let i = 9;
      while (i >= 0) {
        random[i] = ((random[i] ?? 0) + 1) & 0xff;
        if (random[i] !== 0) break;
        i--;
      }
      if (i < 0) {
        // Wrapped around (astronomically unlikely): move to the next millisecond.
        lastTime = t + 1;
        globalThis.crypto.getRandomValues(random);
      }
    } else {
      lastTime = t;
      globalThis.crypto.getRandomValues(random);
    }
    return encodeTime(lastTime) + encodeBase32(random);
  };
}

/** `GFT-XXXX-XXXX` — 40 random bits as 8 Crockford characters. */
export function inviteCode(): string {
  const s = encodeBase32(randomBytes(5));
  return `GFT-${s.slice(0, 4)}-${s.slice(4, 8)}`;
}

export function createIdGen(): IdGen {
  const ulid = createUlidGenerator();
  return {
    ulid,
    randomBase64Url: (n) => encodeBase64Url(randomBytes(n)),
    inviteCode,
  };
}
