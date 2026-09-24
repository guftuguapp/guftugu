/**
 * HttpError carries a PROTOCOL.md error code; app.ts turns it into
 * `{ error: { code, message } }` with the mapped HTTP status.
 */
import type { ErrorCode } from "../protocol/types.js";

export const ERROR_STATUS: Record<ErrorCode, number> = {
  invalid_request: 400,
  unauthorized: 401,
  forbidden: 403,
  not_found: 404,
  conflict: 409,
  payload_too_large: 413,
  upgrade_required: 426,
  rate_limited: 429,
  internal: 500,
  // auth-specific 4xx
  invite_invalid: 400,
  invite_expired: 410,
  challenge_invalid: 400,
  bad_signature: 401,
  bad_credentials: 401,
  device_revoked: 403,
  user_disabled: 403,
  password_locked: 429,
  blocked: 403,
};

export class HttpError extends Error {
  readonly code: ErrorCode;
  readonly status: number;

  constructor(code: ErrorCode, message?: string, status?: number) {
    super(message ?? code);
    this.name = "HttpError";
    this.code = code;
    this.status = status ?? ERROR_STATUS[code];
  }
}

export const badRequest = (message = "invalid request"): HttpError => new HttpError("invalid_request", message);
export const unauthorized = (message = "unauthorized"): HttpError => new HttpError("unauthorized", message);
export const forbidden = (message = "forbidden"): HttpError => new HttpError("forbidden", message);
export const notFound = (message = "not found"): HttpError => new HttpError("not_found", message);
export const conflict = (message = "conflict"): HttpError => new HttpError("conflict", message);
