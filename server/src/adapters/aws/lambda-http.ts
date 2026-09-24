/**
 * AWS adapter — Lambda entrypoint for the HTTP API (payload format 2.0).
 *
 * Converts the API Gateway event into a Web-standard Request, hands it to
 * core's `app.fetch`, and converts the Response back. The app is created once
 * per container.
 */
import type { APIGatewayProxyEventV2, APIGatewayProxyHandlerV2, APIGatewayProxyStructuredResultV2 } from "aws-lambda";
import { createApp } from "../../core/app.js";
import type { App } from "../../core/app-contract.js";
import { buildPorts } from "./ports.js";

let app: App | undefined;

function getApp(): App {
  if (!app) app = createApp(buildPorts());
  return app;
}

const BODYLESS_METHODS = new Set(["GET", "HEAD"]);

export function toRequest(event: APIGatewayProxyEventV2): Request {
  const method = event.requestContext.http.method.toUpperCase();
  const query = event.rawQueryString ? `?${event.rawQueryString}` : "";
  const url = `https://${event.requestContext.domainName}${event.rawPath}${query}`;

  const headers = new Headers();
  for (const [k, v] of Object.entries(event.headers ?? {})) {
    if (typeof v === "string") headers.set(k, v);
  }
  if (event.cookies?.length) headers.set("cookie", event.cookies.join("; "));

  let body: BodyInit | null = null;
  if (event.body !== undefined && event.body !== null && !BODYLESS_METHODS.has(method)) {
    body = event.isBase64Encoded ? Buffer.from(event.body, "base64") : event.body;
  }

  return new Request(url, { method, headers, body });
}

export async function toResult(res: Response): Promise<APIGatewayProxyStructuredResultV2> {
  const headers: Record<string, string> = {};
  res.headers.forEach((v, k) => {
    headers[k] = v;
  });
  const body = await res.text();
  return { statusCode: res.status, headers, body, isBase64Encoded: false };
}

export const handler: APIGatewayProxyHandlerV2 = async (event) => {
  try {
    const res = await getApp().fetch(toRequest(event), {
      ip: event.requestContext.http.sourceIp,
      requestId: event.requestContext.requestId,
    });
    return await toResult(res);
  } catch (err) {
    // Never log request bodies/headers here (they may carry tokens).
    console.error("http handler failed:", (err as Error)?.name ?? "Error", (err as Error)?.message ?? "");
    return {
      statusCode: 500,
      headers: { "content-type": "application/json; charset=utf-8" },
      body: JSON.stringify({ error: { code: "internal", message: "internal error" } }),
      isBase64Encoded: false,
    };
  }
};
