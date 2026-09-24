/**
 * createApp(ports): every HTTP route plus the WebSocket handlers, all against
 * the ports. Adapters only translate their event format to Request/Response.
 */
import type { ErrorBody } from "../protocol/types.js";
import type { App, CreateApp, RequestContext } from "./app-contract.js";
import { HttpError } from "./errors.js";
import { json, requireProtocolHeader } from "./http.js";
import type { Ports } from "./ports.js";
import { createWsHandlers } from "./realtime/ws.js";
import { Router, type Params } from "./router.js";
import * as admin from "./services/admin.js";
import * as auth from "./services/auth.js";
import * as friends from "./services/friends.js";
import * as calls from "./services/calls.js";
import * as config from "./services/config.js";
import * as conversations from "./services/conversations.js";
import * as enroll from "./services/enroll.js";
import * as keys from "./services/keys.js";
import * as media from "./services/media.js";
import * as messages from "./services/messages.js";
import * as users from "./services/users.js";

type ServiceHandler = (ports: Ports, req: Request, params: Params, ctx: RequestContext) => Promise<Response>;

export function errorResponse(e: unknown): Response {
  if (e instanceof HttpError) {
    const body: ErrorBody = { error: { code: e.code, message: e.message } };
    return json(body, e.status);
  }
  // Message only — never the request, headers or body.
  console.error(`unhandled error: ${e instanceof Error ? e.message : "unknown error"}`);
  const body: ErrorBody = { error: { code: "internal", message: "internal error" } };
  return json(body, 500);
}

export const createApp: CreateApp = (ports) => {
  const router = new Router();
  const route = (method: string, pattern: string, handler: ServiceHandler) =>
    router.add(method, pattern, (req, params, ctx) => handler(ports, req, params, ctx));

  // §1 discovery
  route("GET", "/.well-known/guftugu", (p) => config.wellKnown(p));

  // §3–4 enrollment & login
  route("POST", "/enroll", (p, req) => enroll.enroll(p, req));
  route("POST", "/auth/challenge", (p, req) => auth.challenge(p, req));
  route("POST", "/auth/verify", (p, req) => auth.verify(p, req));
  route("POST", "/auth/logout", (p, req) => auth.logout(p, req));

  // §5 self, users, devices, config
  route("GET", "/me", (p, req) => users.getMe(p, req));
  route("PATCH", "/me", (p, req) => users.patchMe(p, req));
  route("PUT", "/me/password", (p, req) => users.changePassword(p, req));
  route("PUT", "/me/device/auth-key", (p, req) => auth.registerAuthKey(p, req));
  route("GET", "/me/devices", (p, req) => users.listMyDevices(p, req));
  route("DELETE", "/me/devices/:deviceId", users.revokeMyDevice);
  route("POST", "/me/link-code", (p, req) => users.linkCode(p, req));
  route("GET", "/users", (p, req) => users.listUsers(p, req));
  route("POST", "/invites", (p, req) => friends.createInvite(p, req));
  route("POST", "/invites/redeem", (p, req) => friends.redeemInvite(p, req));
  route("GET", "/friends", (p, req) => friends.listFriends(p, req));
  route("POST", "/friends/:userId/block", friends.block);
  route("DELETE", "/friends/:userId/block", friends.unblock);
  route("GET", "/users/:userId/devices", users.listUserDevices);
  route("GET", "/config", (p, req) => config.getConfig(p, req));

  // §6 conversations
  route("GET", "/conversations", (p, req) => conversations.listConversations(p, req));
  route("POST", "/conversations", (p, req) => conversations.createConversation(p, req));
  route("GET", "/conversations/:id", conversations.getConversationHandler);
  route("PATCH", "/conversations/:id", conversations.patchConversation);
  route("POST", "/conversations/:id/members", conversations.addMembers);
  route("DELETE", "/conversations/:id/members/:userId", conversations.removeMember);
  route("PUT", "/conversations/:id/read", messages.markRead);

  // §7 keys
  route("GET", "/conversations/:id/keys", keys.listKeys);
  route("GET", "/conversations/:id/key-recipients", keys.keyRecipients);
  route("POST", "/conversations/:id/keys", keys.postKeys);

  // §8 messages
  route("GET", "/conversations/:id/messages", messages.listMessages);
  route("POST", "/conversations/:id/messages", messages.sendMessage);
  route("DELETE", "/conversations/:id/messages/:msgId", messages.deleteMessage);

  // §9 media
  route("POST", "/media/upload-url", (p, req) => media.uploadUrl(p, req));
  route("GET", "/media/download-url", (p, req) => media.downloadUrl(p, req));

  // §10 admin
  route("POST", "/admin/invites", (p, req) => admin.createInvite(p, req));
  route("GET", "/admin/invites", (p, req) => admin.listInvites(p, req));
  route("DELETE", "/admin/invites/:code", admin.deleteInvite);
  route("GET", "/admin/users", (p, req) => admin.listUsers(p, req));
  route("PATCH", "/admin/users/:id", admin.patchUser);
  route("GET", "/admin/users/:id/devices", admin.listUserDevices);
  route("DELETE", "/admin/users/:id/devices/:deviceId", admin.revokeUserDevice);
  route("GET", "/admin/conversations", (p, req) => admin.listConversations(p, req));
  route("POST", "/admin/conversations", (p, req) => admin.createConversation(p, req));
  route("PATCH", "/admin/conversations/:id", admin.patchConversation);
  route("GET", "/admin/config", (p, req) => admin.getConfig(p, req));
  route("PUT", "/admin/config", (p, req) => admin.putConfig(p, req));
  route("GET", "/admin/stats", (p, req) => admin.stats(p, req));

  // §11 calls
  route("POST", "/calls", (p, req) => calls.startCall(p, req));
  route("GET", "/calls/:id", calls.getCall);
  route("POST", "/calls/:id/answer", calls.answerCall);
  route("POST", "/calls/:id/reject", calls.rejectCall);
  route("POST", "/calls/:id/end", calls.endCallHandler);

  const app: App = {
    async fetch(request, ctx = {}) {
      try {
        requireProtocolHeader(request);
        return await router.handle(request, ctx);
      } catch (e) {
        return errorResponse(e);
      }
    },
    ws: createWsHandlers(ports),
  };
  return app;
};
