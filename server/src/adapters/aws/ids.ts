/**
 * AWS adapter — id generation.
 *
 * core/ulid.ts already builds a complete IdGen on WebCrypto
 * (`globalThis.crypto.getRandomValues`), which Node 22 provides natively, so
 * the AWS adapter simply instantiates it once per container. ULIDs are
 * monotonic within the process.
 */
import type { IdGen } from "../../core/ports.js";
import { createIdGen as createCoreIdGen } from "../../core/ulid.js";

export function createIdGen(): IdGen {
  return createCoreIdGen();
}
