/**
 * Body validation helpers. Every failure is an invalid_request naming the field.
 */
import { badRequest } from "./errors.js";

type Obj = Record<string, unknown>;

export function requireString(body: Obj, key: string, maxLen = 4096): string {
  const v = body[key];
  if (typeof v !== "string" || v.length === 0) throw badRequest(`${key} must be a non-empty string`);
  if (v.length > maxLen) throw badRequest(`${key} is too long`);
  return v;
}

/** Returns undefined when the key is absent; null when explicitly null. */
export function optionalString(body: Obj, key: string, maxLen = 4096): string | null | undefined {
  const v = body[key];
  if (v === undefined) return undefined;
  if (v === null) return null;
  if (typeof v !== "string") throw badRequest(`${key} must be a string`);
  if (v.length > maxLen) throw badRequest(`${key} is too long`);
  return v;
}

export function requireNumber(body: Obj, key: string): number {
  const v = body[key];
  if (typeof v !== "number" || !Number.isFinite(v)) throw badRequest(`${key} must be a number`);
  return v;
}

export function optionalBoolean(body: Obj, key: string): boolean | undefined {
  const v = body[key];
  if (v === undefined) return undefined;
  if (typeof v !== "boolean") throw badRequest(`${key} must be a boolean`);
  return v;
}

export function requireEnum<T extends string>(body: Obj, key: string, allowed: readonly T[]): T {
  const v = body[key];
  if (typeof v !== "string" || !(allowed as readonly string[]).includes(v)) {
    throw badRequest(`${key} must be one of ${allowed.join(", ")}`);
  }
  return v as T;
}

export function optionalEnum<T extends string>(body: Obj, key: string, allowed: readonly T[]): T | undefined {
  if (body[key] === undefined) return undefined;
  return requireEnum(body, key, allowed);
}

export function optionalStringArray(body: Obj, key: string, maxItems = 1000): string[] | undefined {
  const v = body[key];
  if (v === undefined) return undefined;
  if (!Array.isArray(v) || v.some((x) => typeof x !== "string" || x.length === 0)) {
    throw badRequest(`${key} must be an array of non-empty strings`);
  }
  if (v.length > maxItems) throw badRequest(`${key} has too many items`);
  return v as string[];
}

export function requireStringArray(body: Obj, key: string, maxItems = 1000): string[] {
  const v = optionalStringArray(body, key, maxItems);
  if (v === undefined) throw badRequest(`${key} is required`);
  return v;
}
