import { describe, expect, it } from "vitest";
import { ADMIN_KEY, call, enrollUser, errorCode, makeApp } from "./helpers.js";

describe("discovery & protocol", () => {
  it("serves /.well-known/guftugu without auth or protocol header", async () => {
    const { app, ports } = makeApp();
    const r = await call(app, "GET", "/.well-known/guftugu", { noProtocol: true });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({
      name: "Test family",
      protocolVersion: 1,
      apiUrl: "https://api.test",
      wsUrl: "wss://ws.test",
      enrollment: "invite",
      serverTime: ports.clock.now(),
    });
  });

  it("answers 426 upgrade_required when the protocol header is missing or wrong", async () => {
    const { app } = makeApp();
    const missing = await call(app, "GET", "/me", { noProtocol: true });
    expect(missing.status).toBe(426);
    expect(errorCode(missing)).toBe("upgrade_required");
    const wrong = await call(app, "GET", "/me", { noProtocol: true, headers: { "X-Guftugu-Protocol": "2" } });
    expect(wrong.status).toBe(426);
  });

  it("returns 404 not_found for unknown routes and 401 for missing bearer", async () => {
    const { app } = makeApp();
    const r = await call(app, "GET", "/nope");
    expect(r.status).toBe(404);
    expect(errorCode(r)).toBe("not_found");
    const me = await call(app, "GET", "/me");
    expect(me.status).toBe(401);
    expect(errorCode(me)).toBe("unauthorized");
  });

  it("rejects malformed JSON bodies with invalid_request", async () => {
    const { app } = makeApp();
    const r = await call(app, "POST", "/auth/challenge", { rawBody: "{not json" });
    expect(r.status).toBe(400);
    expect(errorCode(r)).toBe("invalid_request");
    const arr = await call(app, "POST", "/auth/challenge", { rawBody: "[1,2]" });
    expect(arr.status).toBe(400);
  });

  it("GET /config returns ClientConfig with STUN defaults", async () => {
    const { app } = makeApp();
    const u = await enrollUser(app, "Ammi");
    const r = await call(app, "GET", "/config", { token: u.token });
    expect(r.status).toBe(200);
    expect(r.body).toEqual({
      serverName: "Test family",
      iceServers: [{ urls: ["stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"] }],
      maxUploadBytes: 100 * 1024 * 1024,
      features: { calls: true, media: true },
    });
  });
});

describe("admin auth", () => {
  it("rejects missing, wrong, prefix and longer keys", async () => {
    const { app } = makeApp();
    for (const key of [undefined, "", "wrong", ADMIN_KEY.slice(0, -1), `${ADMIN_KEY}x`]) {
      const headers: Record<string, string> = key === undefined ? {} : { "X-Admin-Key": key };
      const r = await call(app, "GET", "/admin/stats", { noProtocol: true, headers });
      expect(r.status).toBe(401);
      expect(errorCode(r)).toBe("unauthorized");
    }
    const ok = await call(app, "GET", "/admin/stats", { admin: true, noProtocol: true });
    expect(ok.status).toBe(200);
    expect(ok.body).toEqual({ users: 0, devices: 0, conversations: 0, connections: 0 });
  });

  it("is disabled entirely when the configured key is empty", async () => {
    const { app } = makeApp({ config: { adminKey: "" } });
    const r = await call(app, "GET", "/admin/stats", { headers: { "X-Admin-Key": "" } });
    expect(r.status).toBe(401);
  });
});
