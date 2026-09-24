/**
 * The boundary between core/ and any entrypoint adapter (Lambda, Deno serve,
 * Azure Functions…). core/app.ts exports `createApp(ports): App`.
 */
import type { ClientEvent, ServerEvent } from "../protocol/types.js";
import type { Ports } from "./ports.js";

export interface RequestContext {
  /** Client IP if known (for logging/throttling only). */
  ip?: string;
  /** Adapter request id for log correlation. */
  requestId?: string;
}

export interface WsConnectResult {
  ok: boolean;
  /** Set when ok: identity bound to the connection. */
  userId?: string;
  deviceId?: string;
  /** HTTP status to return from the handshake when !ok (401/403/426). */
  status?: number;
}

export interface App {
  /** All HTTP routes (REST, well-known, admin). Web-standard Request/Response. */
  fetch(request: Request, ctx?: RequestContext): Promise<Response>;

  ws: {
    /**
     * Called by the adapter on a new WebSocket handshake. `token` is the bearer
     * token (from the Authorization header or ?token=). On success the app has
     * stored the connection. The server's `hello` frame is sent later, in
     * reply to the client's `hello` message (see onMessage).
     */
    onConnect(connectionId: string, token: string | null, ctx?: RequestContext): Promise<WsConnectResult>;
    onDisconnect(connectionId: string): Promise<void>;
    /** Raw JSON text from the client; the app parses, validates and acts. */
    onMessage(connectionId: string, raw: string): Promise<void>;
  };
}

export type CreateApp = (ports: Ports) => App;

/** Re-exported for adapters that want to type their frames. */
export type { ClientEvent, ServerEvent };
