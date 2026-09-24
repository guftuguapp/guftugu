import { describe, expect, it } from "vitest";
import { decodeBase64Url, encodeBase64Url, isBase64Url } from "../src/core/base64url.js";
import {
  derToRawSignature,
  hashPassword,
  isValidSpkiP256,
  timingSafeEqual,
  verifyEcdsaP256,
  verifyPassword,
} from "../src/core/crypto.js";
import { createUlidGenerator, inviteCode } from "../src/core/ulid.js";
import { genEcdhPublicKey, genSigningKey, rawToDer, signBytes } from "./helpers.js";

describe("base64url", () => {
  it("round-trips arbitrary bytes without padding", () => {
    for (const n of [0, 1, 2, 3, 4, 31, 32, 33, 100]) {
      const bytes = crypto.getRandomValues(new Uint8Array(n));
      const s = encodeBase64Url(bytes);
      expect(s).not.toMatch(/[=+/]/);
      expect([...decodeBase64Url(s)]).toEqual([...bytes]);
    }
  });

  it("rejects padding, bad characters, impossible lengths and non-canonical bits", () => {
    expect(() => decodeBase64Url("AA==")).toThrow();
    expect(() => decodeBase64Url("A+B")).toThrow();
    expect(() => decodeBase64Url("A")).toThrow();
    expect([...decodeBase64Url("AQ")]).toEqual([1]); // one byte, canonical
    expect(() => decodeBase64Url("AB")).toThrow(); // trailing bits set
    expect(isBase64Url("")).toBe(false);
    expect(isBase64Url(encodeBase64Url(new Uint8Array([255, 254, 253, 0])))).toBe(true);
    expect(encodeBase64Url(new Uint8Array([255, 254, 253, 0]))).toBe("__79AA");
  });
});

describe("crypto", () => {
  it("timingSafeEqual compares bytes and strings", () => {
    expect(timingSafeEqual("abc", "abc")).toBe(true);
    expect(timingSafeEqual("abc", "abd")).toBe(false);
    expect(timingSafeEqual("abc", "abcd")).toBe(false);
    expect(timingSafeEqual(new Uint8Array([1, 2]), new Uint8Array([1, 2]))).toBe(true);
    expect(timingSafeEqual("", "")).toBe(true);
  });

  it("verifies raw and DER ECDSA signatures and rejects garbage", async () => {
    const key = await genSigningKey();
    const msg = new TextEncoder().encode("guftugu-auth:v1:d_x:nonce");
    const raw = await signBytes(key.privateKey, msg);
    expect(raw.length).toBe(64);
    expect(await verifyEcdsaP256(key.publicKey, msg, encodeBase64Url(raw))).toBe(true);
    const der = rawToDer(raw);
    expect(der[0]).toBe(0x30);
    expect(await verifyEcdsaP256(key.publicKey, msg, encodeBase64Url(der))).toBe(true);
    expect([...(derToRawSignature(der) ?? [])]).toEqual([...raw]);

    const other = await genSigningKey();
    expect(await verifyEcdsaP256(other.publicKey, msg, encodeBase64Url(raw))).toBe(false);
    expect(await verifyEcdsaP256(key.publicKey, new TextEncoder().encode("tampered"), encodeBase64Url(raw))).toBe(false);
    expect(await verifyEcdsaP256(key.publicKey, msg, "not base64url!")).toBe(false);
    expect(await verifyEcdsaP256(key.publicKey, msg, encodeBase64Url(new Uint8Array(10)))).toBe(false);
    expect(await verifyEcdsaP256("AAAA", msg, encodeBase64Url(raw))).toBe(false);
    expect(derToRawSignature(new Uint8Array([0x30, 0x02, 0x02, 0x00]))).toBeNull();
  });

  it("validates P-256 SPKI keys for ECDSA and ECDH", async () => {
    expect(await isValidSpkiP256((await genSigningKey()).publicKey)).toBe(true);
    expect(await isValidSpkiP256(await genEcdhPublicKey())).toBe(true);
    expect(await isValidSpkiP256("AAAA")).toBe(false);
    expect(await isValidSpkiP256("")).toBe(false);
    expect(await isValidSpkiP256("*bad*")).toBe(false);
  });

  it("hashes and verifies passwords in the documented format", async () => {
    const stored = await hashPassword("hunter22", 1_000);
    expect(stored).toMatch(/^pbkdf2-sha256\$1000\$[A-Za-z0-9_-]{22}\$[A-Za-z0-9_-]{43}$/);
    expect(await verifyPassword("hunter22", stored)).toBe(true);
    expect(await verifyPassword("hunter23", stored)).toBe(false);
    expect(await verifyPassword("hunter22", "garbage")).toBe(false);
    expect(await verifyPassword("hunter22", "pbkdf2-sha256$x$AA$AA")).toBe(false);
  });
});

describe("ulid", () => {
  it("is 26 Crockford chars, sorts by time and stays monotonic within a millisecond", () => {
    const gen = createUlidGenerator();
    const a = gen(1_000_000);
    const b = gen(1_000_000);
    const c = gen(1_000_001);
    const d = gen(999_999); // clock went backwards
    for (const id of [a, b, c, d]) expect(id).toMatch(/^[0-9A-HJKMNP-TV-Z]{26}$/);
    expect(a < b).toBe(true);
    expect(b < c).toBe(true);
    expect(c < d).toBe(true);
    expect(a.slice(0, 10)).toBe(b.slice(0, 10));
  });

  it("makes GFT-XXXX-XXXX invite codes", () => {
    for (let i = 0; i < 50; i++) expect(inviteCode()).toMatch(/^GFT-[0-9A-HJKMNP-TV-Z]{4}-[0-9A-HJKMNP-TV-Z]{4}$/);
  });
});
