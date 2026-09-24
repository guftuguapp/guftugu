/**
 * AWS adapter — Lambda entrypoint for the WebSocket API.
 *
 * Routes: $connect (auth handshake), $disconnect, $default (every client
 * frame; RouteSelectionExpression is "$request.body.type" but all types fall
 * through to $default and core parses them).
 */
import type { APIGatewayProxyWebsocketEventV2, Context } from "aws-lambda";
import { createApp } from "../../core/app.js";
import type { App } from "../../core/app-contract.js";
import { buildPorts } from "./ports.js";

/** $connect events also carry the handshake headers (not in the base type). */
export type WsEvent = APIGatewayProxyWebsocketEventV2 & {
  headers?: Record<string, string | undefined>;
  queryStringParameters?: Record<string, string | undefined>;
  requestContext: APIGatewayProxyWebsocketEventV2["requestContext"] & { identity?: { sourceIp?: string } };
};

export interface WsResult {
  statusCode: number;
  body?: string;
}

let app: App | undefined;

function getApp(): App {
  if (!app) app = createApp(buildPorts());
  return app;
}

function bearerToken(event: WsEvent): string | null {
  const headers = event.headers ?? {};
  for (const [k, v] of Object.entries(headers)) {
    if (k.toLowerCase() === "authorization" && typeof v === "string") {
      const m = /^Bearer\s+(.+)$/i.exec(v.trim());
      if (m?.[1]) return m[1].trim();
    }
  }
  const q = event.queryStringParameters?.token;
  return typeof q === "string" && q.length > 0 ? q : null;
}

export async function handler(event: WsEvent, _context?: Context): Promise<WsResult> {
  const { routeKey, connectionId, requestId } = event.requestContext;

  switch (routeKey) {
    case "$connect": {
      try {
        const result = await getApp().ws.onConnect(connectionId, bearerToken(event), {
          ip: event.requestContext.identity?.sourceIp,
          requestId,
        });
        if (result.ok) return { statusCode: 200 };
        return { statusCode: result.status ?? 401 };
      } catch (err) {
        console.error("ws $connect failed:", (err as Error)?.name ?? "Error");
        return { statusCode: 500 };
      }
    }
    case "$disconnect": {
      try {
        await getApp().ws.onDisconnect(connectionId);
      } catch (err) {
        console.error("ws $disconnect failed:", (err as Error)?.name ?? "Error");
      }
      return { statusCode: 200 };
    }
    default: {
      // $default and any typed route: always 200 so API Gateway does not
      // surface handler errors to the client; the app sends its own
      // `error` frames.
      try {
        const raw = event.isBase64Encoded && event.body ? Buffer.from(event.body, "base64").toString("utf8") : event.body ?? "";
        await getApp().ws.onMessage(connectionId, raw);
      } catch (err) {
        console.error("ws message failed:", (err as Error)?.name ?? "Error");
      }
      return { statusCode: 200 };
    }
  }
}
