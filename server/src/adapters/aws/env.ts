/**
 * AWS adapter — configuration from Lambda environment variables.
 *
 * Fails fast (throws) when a required variable is missing so a misdeployed
 * stack surfaces as an init error in CloudWatch instead of odd 500s later.
 * Values are never logged.
 */
import { DEFAULT_TTLS, type ServerConfig } from "../../core/ports.js";

export interface AwsConfig extends ServerConfig {
  tableName: string;
  bucketName: string;
  /** https://<wsapi>.execute-api.<region>.amazonaws.com/<stage> */
  wsManagementEndpoint: string;
}

function required(env: NodeJS.ProcessEnv, name: string): string {
  const v = env[name];
  if (v === undefined || v.trim() === "") {
    throw new Error(`Guftugu: required environment variable ${name} is not set`);
  }
  return v.trim();
}

function optionalNumber(env: NodeJS.ProcessEnv, name: string, fallback: number): number {
  const v = env[name];
  if (v === undefined || v.trim() === "") return fallback;
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) {
    throw new Error(`Guftugu: environment variable ${name} must be a positive number`);
  }
  return n;
}

/** Strip a trailing slash so URL concatenation in core is predictable. */
function trimSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

/** MAX_UPLOAD_BYTES wins; else MAX_UPLOAD_MB (the SAM parameter, CloudFormation cannot multiply); else default. */
function readMaxUploadBytes(env: NodeJS.ProcessEnv): number {
  if (env.MAX_UPLOAD_BYTES?.trim()) return Math.floor(optionalNumber(env, "MAX_UPLOAD_BYTES", DEFAULT_TTLS.maxUploadBytes));
  if (env.MAX_UPLOAD_MB?.trim()) return Math.floor(optionalNumber(env, "MAX_UPLOAD_MB", 100) * 1024 * 1024);
  return DEFAULT_TTLS.maxUploadBytes;
}

export function readConfig(env: NodeJS.ProcessEnv = process.env): AwsConfig {
  const adminKey = required(env, "ADMIN_KEY");
  if (adminKey.length < 16) {
    throw new Error("Guftugu: ADMIN_KEY must be at least 16 characters");
  }
  return {
    tableName: required(env, "TABLE_NAME"),
    bucketName: required(env, "BUCKET_NAME"),
    wsManagementEndpoint: trimSlash(required(env, "WS_MANAGEMENT_ENDPOINT")),
    apiUrl: trimSlash(required(env, "API_URL")),
    wsUrl: trimSlash(required(env, "WS_URL")),
    adminKey,
    serverName: env.SERVER_NAME?.trim() || "Guftugu",
    sessionTtlMs: optionalNumber(env, "SESSION_TTL_MS", DEFAULT_TTLS.sessionTtlMs),
    challengeTtlMs: optionalNumber(env, "CHALLENGE_TTL_MS", DEFAULT_TTLS.challengeTtlMs),
    inviteTtlMs: optionalNumber(env, "INVITE_TTL_MS", DEFAULT_TTLS.inviteTtlMs),
    maxUploadBytes: readMaxUploadBytes(env),
    presignTtlMs: optionalNumber(env, "PRESIGN_TTL_MS", DEFAULT_TTLS.presignTtlMs),
  };
}
