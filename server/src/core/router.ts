/**
 * Minimal exact-segment router. Patterns look like
 * "/conversations/:id/messages"; ":name" segments become params.
 */
import type { RequestContext } from "./app-contract.js";
import { badRequest, notFound } from "./errors.js";

export type Params = Record<string, string>;
export type Handler = (req: Request, params: Params, ctx: RequestContext) => Promise<Response>;

interface Route {
  method: string;
  segments: string[];
  handler: Handler;
}

export class Router {
  private readonly routes: Route[] = [];

  add(method: string, pattern: string, handler: Handler): this {
    this.routes.push({ method: method.toUpperCase(), segments: splitPath(pattern), handler });
    return this;
  }

  /** Dispatch or throw not_found. Errors from handlers propagate to the caller. */
  async handle(req: Request, ctx: RequestContext = {}): Promise<Response> {
    const method = req.method.toUpperCase();
    const segments = splitPath(new URL(req.url).pathname);
    for (const route of this.routes) {
      if (route.method !== method) continue;
      const params = match(route.segments, segments);
      if (params) return route.handler(req, params, ctx);
    }
    throw notFound(`no route for ${method} ${new URL(req.url).pathname}`);
  }
}

function splitPath(path: string): string[] {
  return path.split("/").filter((s) => s.length > 0);
}

function match(pattern: string[], actual: string[]): Params | null {
  if (pattern.length !== actual.length) return null;
  const params: Params = {};
  for (let i = 0; i < pattern.length; i++) {
    const p = pattern[i] ?? "";
    const a = actual[i] ?? "";
    if (p.startsWith(":")) {
      try {
        params[p.slice(1)] = decodeURIComponent(a);
      } catch {
        throw badRequest("malformed path segment");
      }
    } else if (p !== a) {
      return null;
    }
  }
  return params;
}
