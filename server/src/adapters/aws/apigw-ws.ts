/**
 * AWS adapter — Realtime over API Gateway WebSocket (PostToConnection).
 *
 * A "gone" result tells core to drop the stale connection record. Any other
 * failure is swallowed (fan-out is best-effort; the app reconciles on
 * reconnect) and only the error *name* is logged — never the payload.
 */
import { ApiGatewayManagementApiClient, PostToConnectionCommand } from "@aws-sdk/client-apigatewaymanagementapi";
import type { Realtime } from "../../core/ports.js";

interface AwsError {
  name?: string;
  $metadata?: { httpStatusCode?: number };
}

function isGone(err: unknown): boolean {
  const e = err as AwsError | null;
  return e?.name === "GoneException" || e?.$metadata?.httpStatusCode === 410;
}

export function createApiGwRealtime(endpoint: string, client?: ApiGatewayManagementApiClient): Realtime {
  const api = client ?? new ApiGatewayManagementApiClient({ endpoint });
  return {
    async send(connectionId, event) {
      try {
        await api.send(
          new PostToConnectionCommand({
            ConnectionId: connectionId,
            Data: JSON.stringify(event),
          }),
        );
        return "ok";
      } catch (err) {
        if (isGone(err)) return "gone";
        const name = (err as AwsError | null)?.name ?? "UnknownError";
        console.warn(`realtime.send failed: ${name} (type=${event.type})`);
        return "ok";
      }
    },
  };
}
