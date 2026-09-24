import { describe, expect, it } from "vitest";
import { call, directConversation, enrollUser, errorCode, makeApp } from "./helpers.js";

describe("media urls", () => {
  it("upload-url: membership for attachments/thumbnails, avatars for anyone, size cap", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const x = await enrollUser(t.app, "X");
    const d = await directConversation(t.app, a, b);

    const up = await call(t.app, "POST", "/media/upload-url", {
      token: a.token,
      body: { convId: d.convId, kind: "attachment", mime: "application/octet-stream", sizeBytes: 1234 },
    });
    expect(up.status).toBe(200);
    expect(up.body.key).toMatch(new RegExp(`^conv/${d.convId}/[0-9A-HJKMNP-TV-Z]{26}\\.bin$`));
    expect(up.body.uploadUrl).toBe(`memory://upload/${up.body.key}`);
    expect(up.body.method).toBe("PUT");
    expect(up.body.headers).toEqual({ "Content-Type": "application/octet-stream" });
    expect(up.body.expiresAt).toBe(t.ports.clock.now() + 15 * 60_000);

    const thumb = await call(t.app, "POST", "/media/upload-url", { token: b.token, body: { convId: d.convId, kind: "thumbnail", mime: "application/octet-stream", sizeBytes: 10 } });
    expect(thumb.body.key).toMatch(/^conv\//);

    const outsider = await call(t.app, "POST", "/media/upload-url", { token: x.token, body: { convId: d.convId, kind: "attachment", mime: "a/b", sizeBytes: 10 } });
    expect(outsider.status).toBe(403);
    expect(errorCode(await call(t.app, "POST", "/media/upload-url", { token: x.token, body: { kind: "attachment", mime: "a/b", sizeBytes: 10 } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "POST", "/media/upload-url", { token: x.token, body: { kind: "video", mime: "a/b", sizeBytes: 10 } }))).toBe("invalid_request");
    expect(errorCode(await call(t.app, "POST", "/media/upload-url", { token: x.token, body: { kind: "avatar", mime: "a/b", sizeBytes: 0 } }))).toBe("invalid_request");

    const avatar = await call(t.app, "POST", "/media/upload-url", { token: x.token, body: { kind: "avatar", mime: "image/jpeg", sizeBytes: 10 } });
    expect(avatar.status).toBe(200);
    expect(avatar.body.key).toMatch(new RegExp(`^avatars/${x.userId}/[0-9A-HJKMNP-TV-Z]{26}$`));

    const big = await call(t.app, "POST", "/media/upload-url", { token: a.token, body: { kind: "avatar", mime: "image/jpeg", sizeBytes: 100 * 1024 * 1024 + 1 } });
    expect(big.status).toBe(413);
    expect(errorCode(big)).toBe("payload_too_large");

    // Admin can lower the cap.
    await call(t.app, "PUT", "/admin/config", { admin: true, body: { maxUploadBytes: 1000 } });
    expect((await call(t.app, "POST", "/media/upload-url", { token: a.token, body: { kind: "avatar", mime: "image/jpeg", sizeBytes: 1001 } })).status).toBe(413);
    expect((await call(t.app, "GET", "/config", { token: a.token })).body.maxUploadBytes).toBe(1000);
  });

  it("download-url: conv keys need membership, avatars are open, anything else is forbidden", async () => {
    const t = makeApp();
    const a = await enrollUser(t.app, "A");
    const b = await enrollUser(t.app, "B");
    const x = await enrollUser(t.app, "X");
    const d = await directConversation(t.app, a, b);
    const key = `conv/${d.convId}/01ARZ3NDEKTSV4RRFFQ69G5FAV.bin`;

    const ok = await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent(key)}`, { token: b.token });
    expect(ok.status).toBe(200);
    expect(ok.body.downloadUrl).toBe(`memory://download/${key}`);
    expect(ok.body.expiresAt).toBe(t.ports.clock.now() + 15 * 60_000);

    expect((await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent(key)}`, { token: x.token })).status).toBe(403);
    expect((await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent(`avatars/${a.userId}/abc`)}`, { token: x.token })).status).toBe(200);
    expect((await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent("other/thing")}`, { token: a.token })).status).toBe(403);
    expect((await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent("conv/../x/y")}`, { token: a.token })).status).toBe(400);
    expect((await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent("conv/c_missing/x")}`, { token: a.token })).status).toBe(404);
    expect((await call(t.app, "GET", "/media/download-url", { token: a.token })).status).toBe(400);
    expect((await call(t.app, "GET", `/media/download-url?key=${encodeURIComponent(key)}`)).status).toBe(401);
  });
});
