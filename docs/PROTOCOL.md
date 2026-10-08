# Guftugu Protocol v1

This is the complete contract between the Android app and any Guftugu server.
The reference server (`server/`) implements it on AWS; a server on any other
stack that implements this document is compatible with the unmodified app.

The server is a **blind relay + store**: it never sees message text, media, or
call SDP in the clear. It authenticates devices, stores ciphertext, stores
wrapped conversation keys it cannot open, and fans events out over WebSockets.

Conventions
- Bodies are JSON (`Content-Type: application/json; charset=utf-8`).
- Timestamps are **epoch milliseconds (UTC) as JSON numbers**.
- IDs are opaque strings. The reference server uses prefixed ULIDs
  (`u_` user, `d_` device, `c_` conversation, `m_` message, `k_` call,
  `x_` key epoch) — ULIDs sort by creation time, which paging relies on.
- Binary values (keys, signatures, nonces, ciphertext) are **base64url, no padding**.
- Every request from the app carries `X-Guftugu-Protocol: 1`. A server that
  can't serve that version answers `426 { error: { code: "upgrade_required" } }`.
- Authenticated requests carry `Authorization: Bearer <sessionToken>`.
- Admin requests carry `X-Admin-Key: <adminKey>` (see §9).

Error shape (any non-2xx):
```json
{ "error": { "code": "not_found", "message": "conversation not found" } }
```
Codes: `invalid_request` 400 · `unauthorized` 401 · `forbidden` 403 ·
`not_found` 404 · `conflict` 409 · `payload_too_large` 413 · `upgrade_required` 426 ·
`rate_limited` 429 · `internal` 500. Auth-specific 4xx codes:
`invite_invalid`, `invite_expired`, `challenge_invalid`, `bad_signature`,
`bad_credentials`, `device_revoked`, `user_disabled`, `password_locked`, `blocked` (403).

Pagination: list endpoints return `{ "items": [...], "hasMore": boolean }`.

---

## 1. Discovery (no auth)

`GET /.well-known/guftugu`
```json
{ "name": "Our family", "protocolVersion": 1, "apiUrl": "https://…", "wsUrl": "wss://…",
  "enrollment": "invite", "serverTime": 1758500000000 }
```
The app calls this after reading an invite to validate the server and learn `wsUrl`.

## 2. Invite links

An invite is a code like `GFT-7K3M-Q9XD` created by the admin CLI (or by a
user's phone as a *link code*, §5). It is shown as text and as a QR of:

```
guftugu://join?api=<urlencoded apiUrl>&code=GFT-7K3M-Q9XD
```
The app accepts the link, the QR, or manual entry of `apiUrl` + code.

## 3. Enrollment — the app authenticates itself (once per install)

`POST /enroll` (no auth; the invite code is the credential)
```json
{
  "inviteCode": "GFT-7K3M-Q9XD",
  "displayName": "Ammi",
  "password": "correct horse battery staple",
  "devicePublicKey":     "<base64url SPKI DER, P-256, Keystore, not user-gated>",
  "authPublicKey":       "<base64url SPKI DER, P-256, Keystore, biometric-gated, or null>",
  "encryptionPublicKey": "<base64url SPKI DER, P-256 ECDH key for receiving wrapped conversation keys>",
  "device": { "name": "Ammi's Pixel", "model": "Pixel 8", "os": "Android 15", "appVersion": "1.0.0" }
}
```
Rules
- Code must exist, be unused and unexpired → else `invite_invalid` / `invite_expired`.
- If the invite is bound to an existing user (`forUserId` — a link code) the
  user is reused and `displayName`/`password` are ignored. Otherwise a new user
  is created.
- **Fingerprint first.** `password` is optional whenever `authPublicKey` is
  present; it is then a *backup* for when the phone's fingerprints change. It is
  required only when no biometric key is registered (phones without a usable
  sensor) — the app calls it a *passcode*. Any given password/passcode must be
  ≥ 6 characters (guessing needs the enrolled phone's device key and locks after
  5 tries). Omitted/empty → no password.
- Phones without a fingerprint key keep their session for its full lifetime
  (30 days): the app stores it (Keystore-wrapped) and does not ask for the
  passcode again until the server rejects it.
- The server stores only public keys and a PBKDF2 hash of the password.
- The invite is marked used (single use). New users join every conversation in
  the invite's `autoJoin` list. The new device has **no conversation keys yet**;
  another member's phone wraps them for it (§7) — until then the app shows
  "waiting for keys".

Response `201` → `{ "user": User, "device": Device, "session": Session, "config": ClientConfig }`

A reinstall or a new phone always enrols again (new keys); nothing biometric or
private is ever backed up.

## 4. Login — the user authenticates (every cold start / unlock)

`POST /auth/challenge` (no auth) — `{ "deviceId": "d_…" }` →
`200 { "nonce": "<base64url 32 B>", "expiresAt": … }`. Single use, 120 s.
Unknown/revoked device → `device_revoked`; disabled user → `user_disabled`.

`POST /auth/verify` (no auth)
```json
{ "deviceId": "d_…", "nonce": "…", "method": "biometric", "signature": "<base64url>" }
{ "deviceId": "d_…", "nonce": "…", "method": "password",  "signature": "<base64url>", "password": "…" }
```
- Signed message = UTF-8 bytes of `"guftugu-auth:v1:" + deviceId + ":" + nonce`.
- `biometric` → verify with `authPublicKey` (usable on the phone only after BiometricPrompt).
- `password` → verify with `devicePublicKey` (proves the enrolled phone) **and**
  check the password (accounts without a password always get `bad_credentials`).
  5 failures → `password_locked` for 15 min.
- ECDSA P-256 / SHA-256, signature as **raw `r‖s` 64 bytes (IEEE P1363)**;
  servers should also accept DER (`0x30…`).

`200 { "user": Me, "device": Device, "session": Session, "config": ClientConfig }`

`POST /auth/logout` (auth) → `204`.

### Turning on (or replacing) fingerprint unlock

`PUT /me/device/auth-key` (auth)
```json
{ "nonce": "<from POST /auth/challenge for this device>",
  "authPublicKey": "<base64url SPKI of the new biometric-gated key>",
  "deviceSignature": "<signed with the device key>",
  "authSignature": "<signed with the NEW auth key (after BiometricPrompt)>" }
```
Both signatures cover `utf8("guftugu-authkey:v1:" + deviceId + ":" + nonce + ":" + authPublicKey)`.
The session alone is not enough — the device-key signature means a stolen token
cannot plant someone else's key, and the auth-key signature proves the phone can
actually use it. → `200 Device` (`hasBiometricKey: true`). Errors: `challenge_invalid`,
`bad_signature`, `invalid_request`.

Sessions expire after 30 days server-side; the app drops its token on every
cold start and re-authenticates (that is the unlock screen).

## 5. Users, devices, self

- `GET /me` → `Me` · `PATCH /me` `{ displayName?, avatarKey? }` → `User`
- `PUT /me/password` `{ currentPassword?, newPassword }` → `204` — `currentPassword`
  is required only when a password is already set (setting a first backup password
  needs just the session).
- `GET /me/devices` → `{ items: Device[] }` · `DELETE /me/devices/{deviceId}` → `204`
  (revoke; triggers key rotation in all that user's conversations, §7)
- `POST /me/link-code` → `Invite` bound to me, 24 h — to enrol another phone
- `GET /users` → `{ items: User[] }` (everyone on this private server)
- `GET /users/{userId}/devices` → `{ items: PublicDevice[] }` (public keys, for wrapping/verifying)
- `GET /config` → `ClientConfig`

## 5a. Friends & invites from users

Invites are no longer only for the admin. Any user can invite someone:

- `POST /invites` `{ "kind": "friend" }` or `{ "kind": "group", "convId": "c_…" }` (+ optional
  `expiresInHours`, default 168, max 720) → `201 Invite` (`kind`, `invitedBy`, `convId`).
  Group invites need the caller to be a member of that group. Codes are single-use.
- Using a friend/group code — at sign-up (`POST /enroll`) or later
  (`POST /invites/redeem { "code" }` → `{ friend: User, conversation: Conversation | null }`) —
  makes the two people **friends** (both directions) and opens their 1:1 chat (`friend`) or adds
  the newcomer to the group (`group`). A newcomer invited by a user is *not* put into the
  admin's auto-join groups. Admin/new-phone codes can't be redeemed as friend codes.
- `GET /friends` → `{ items: Friend[] }` — my friends and the people I blocked.
- `POST /friends/{userId}/block` / `DELETE /friends/{userId}/block` → `204`. A blocked person
  can't message me in our 1:1 chat or call me (`403 blocked`), and is silently skipped when
  adding me to a group. Blocks are one-directional and never revealed to the other person.

**Who can see whom (`GET /users`, starting 1:1 chats):** me, my friends, people in my
conversations, and — for people the admin invited (the "family circle") — that whole circle.
Anyone else needs an invite first (`403` on `POST /conversations` direct).

**Leaving:** `DELETE /conversations/{id}/members/{myUserId}` works for groups *and* 1:1 chats
(only yourself in a 1:1). Starting the same 1:1 chat again adds both people back and forces a
new key.

## 6. Conversations

- `GET /conversations` → `{ items: Conversation[] }`, newest activity first
- `POST /conversations`
  - `{ "type": "direct", "memberId": "u_…" }` → existing or new direct conversation (idempotent)
  - `{ "type": "group", "name": "Cousins", "memberIds": ["u_…"] }` → new group; caller is owner
- `GET /conversations/{id}` → `Conversation`
- `PATCH /conversations/{id}` `{ name?, avatarKey? }` (group owner/admin) → `Conversation`
- `POST /conversations/{id}/members` `{ userIds: [] }` → `Conversation`
- `DELETE /conversations/{id}/members/{userId}` → `Conversation` (owner/admin, or self).
  Sets `keyRotationRequired: true` — the next sender must rotate the key (§7).
- `PUT /conversations/{id}/read` `{ "msgId": "m_…" }` → `204` (apps coalesce these)

Group names/avatars are metadata the server can see; message content is not.
`Conversation.lastMsgId`/`lastMessageAt` let the app decide whether to sync;
previews and unread counts are computed on the phone from decrypted messages.

## 7. Conversation keys (E2EE)

Each conversation has a current 256-bit AES-GCM key identified by `keyId`
(`x_` ULID — newer keys sort later). Keys are generated on a member's phone and
delivered to every member device **wrapped** so only that device can open them.

- `GET /conversations/{id}/keys` → `{ "currentKeyId": "x_…" | null, "items": WrappedKey[] }`
  — every wrapped key addressed to *the calling device* (all epochs, for history).
- `GET /conversations/{id}/key-recipients` →
  `{ "currentKeyId": "x_…" | null, "keyRotationRequired": boolean,
     "devices": [ { PublicDevice…, "hasCurrentKey": boolean } ] }`
  — all non-revoked devices of current members.
- `POST /conversations/{id}/keys` `{ "keyId": "x_…", "wrapped": WrappedKey[] }` → `201 { currentKeyId }`
  Stores each wrap (idempotent per `keyId`+`recipientDeviceId`). If `keyId` >
  `currentKeyId` it becomes current and `keyRotationRequired` is cleared. Only
  members may post; each `senderDeviceId` must equal the caller's device.

Wrapping (ECIES, done on the phone):
```
eph      = new P-256 keypair
shared   = ECDH(eph.priv, recipient.encryptionPublicKey).x      (32 bytes)
wrapKey  = HKDF-SHA256(ikm = shared, salt = 32 zero bytes,
                       info = "guftugu-keywrap-v1" ‖ eph.pub(SPKI) ‖ recipient.encryptionPublicKey(SPKI), L = 32)
iv       = random 12 bytes
ct       = AES-256-GCM(wrapKey, iv, plaintext = convKey (32 B),
                       aad = utf8(convId + ":" + keyId + ":" + recipientDeviceId))   // includes 16 B tag
sig      = ECDSA-P256-SHA256(senderDevice.guftugu_device,
              utf8("guftugu-keywrap:v1:" + convId + ":" + keyId + ":" + recipientDeviceId + ":" + eph.pub + ":" + iv + ":" + ct))
```
`WrappedKey = { keyId, convId, recipientDeviceId, senderDeviceId, ephemeralPublicKey, iv, ciphertext, signature, createdAt }`
Recipients verify `sig` with the sender device's `devicePublicKey`
(`GET /users/{id}/devices`) before trusting a key. Trust in those public keys is
server-mediated in v1 (trust-on-first-use; safety-number verification is a
planned extension).

When to wrap/rotate (client rules)
1. Creating a conversation → generate key, wrap for all member devices, post.
2. On `conversation.updated`, on app start, and before sending: if
   `keyRotationRequired` → generate a new key and distribute to all devices;
   else if any device has `hasCurrentKey: false` → wrap the current key for it.
3. A device with no key for a conversation waits; incoming envelopes for
   unknown `keyId` are stored and decrypted once the key arrives
   (`conversation.keys` event).

## 8. Messages

Message content travels as an **envelope** the server cannot open:

```
Envelope = { "v": 1, "keyId": "x_…", "iv": "<12 B>", "ct": "<AES-256-GCM ciphertext+tag>" }
plaintext (before encryption, UTF-8 JSON) = Content:
  { "type": "text",  "text": "hi", "replyTo": "m_…" }
  { "type": "image" | "video" | "audio" | "file", "text": "caption",
    "attachment": { "key", "mime", "sizeBytes", "fileName", "width", "height", "durationMs",
                    "enc": "aes-256-gcm-chunked-v1", "fileKey": "<32 B>", "baseIv": "<8 B>",
                    "chunkSize": 1048576, "sha256": "<of ciphertext>",
                    "thumbKey", "thumbSha256" } }
AAD for the envelope = utf8(convId + ":" + senderId + ":" + clientId)
```

- `GET /conversations/{id}/messages?after=m_…&limit=50` → ascending, exclusive
- `GET /conversations/{id}/messages?before=m_…&limit=50` → descending, exclusive
  (neither → latest `limit`, descending). `limit` ≤ 200.
- `POST /conversations/{id}/messages`
  `{ "clientId": "<uuid from the phone>", "sentAt": …, "envelope": Envelope }` → `201 { message }`
  Same `clientId` from the same device within 7 days → `200` with the original (idempotent).
  Envelope over 64 KiB → `payload_too_large` (attachments go to blob storage, not here).
- `DELETE /conversations/{id}/messages/{msgId}` → `200 { message }` (tombstone;
  envelope removed, `deletedAt` set). Sender, group owner or admin.

`Message.kind` is `"e2e"` for user content, `"call"` for server-written call
logs, `"system"` for membership events. Only `e2e` messages carry an envelope.

## 9. Media

Bytes go straight between the phone and the blob store via presigned URLs and
are **encrypted before upload** (`aes-256-gcm-chunked-v1`: split the file into
`chunkSize` pieces; chunk *i* is encrypted with AES-256-GCM using
`iv = baseIv ‖ uint32be(i)` and `aad = utf8(key + ":" + i)`, each chunk's 16 B
tag appended; a final empty chunk is not required). Thumbnails use the same
`fileKey` with chunk index starting at `0x80000000`.

- `POST /media/upload-url`
  `{ "convId": "c_…", "kind": "attachment" | "thumbnail", "mime": "application/octet-stream", "sizeBytes": 123456 }`
  or `{ "kind": "avatar", "mime": "image/jpeg", "sizeBytes": … }` (avatars are not E2E; any member can see them)
  → `200 { "key": "conv/c_…/….bin", "uploadUrl", "method": "PUT", "headers": { "Content-Type": "…" }, "expiresAt" }`
- `GET /media/download-url?key=…` → `200 { "downloadUrl", "expiresAt" }`
  Keys are `conv/<convId>/…` (member check) or `avatars/…` (any user).

## 10. Admin (X-Admin-Key)

Used by the admin CLI only.

- `POST /admin/invites` `{ displayName?, role?: "member"|"admin", forUserId?, expiresInHours?, autoJoin?: ["c_…"] }` → `201 Invite`
- `GET /admin/invites` · `DELETE /admin/invites/{code}` → `204`
- `GET /admin/users` · `PATCH /admin/users/{id}` `{ status?: "active"|"disabled", role? }` → `User`
- `GET /admin/users/{id}/devices` · `DELETE /admin/users/{id}/devices/{deviceId}` → `204`
- `GET /admin/conversations` · `POST /admin/conversations` `{ name, autoJoin?: boolean, memberIds?: [] }` → `201`
  · `PATCH /admin/conversations/{id}` `{ name?, autoJoin? }`
- `GET /admin/config` → `ServerSettings` · `PUT /admin/config` `Partial<ServerSettings>` → `ServerSettings`
- `GET /admin/stats` → `{ users, devices, conversations, connections }`

## 11. Calls (1:1, direct conversations only)

- `POST /calls` `{ "convId": "c_…", "type": "audio"|"video" }` → `201 Call`.
  If the callee has no live connection: `state: "ended", endReason: "unreachable"`
  and a `call` message is written. Otherwise `ringing` + `call.invite` to every
  callee connection.
- `POST /calls/{id}/answer` → `Call` (`active`, records `calleeDeviceId`; other
  callee devices get `call.ended {reason:"answered_elsewhere"}`; caller gets `call.answered`)
- `POST /calls/{id}/reject` → `Call` · `POST /calls/{id}/end` `{ "reason": "hangup"|"timeout"|"failed"|"cancelled" }` → `Call`
- `GET /calls/{id}` → `Call`

After `call.answered` the **caller** creates the SDP offer; both sides trickle
ICE over the WebSocket. Signalling payloads are envelopes encrypted with the
conversation's current key, so the server cannot tamper with DTLS fingerprints.
Media itself is DTLS-SRTP peer-to-peer. When a call ends the server writes a
`call` message (`outcome`: `answered`+`durationMs`, `missed`, `rejected`,
`unreachable`, `cancelled`).

## 12. WebSocket

Connect to `wsUrl` with `Authorization: Bearer <token>` (browsers: `?token=`).
Bad token → 401 at handshake. Immediately after the socket opens the client
sends `{ "type": "hello" }` and the server answers with its `hello` frame
(serverless gateways cannot push before the first client frame).

Server → client frames (`{ "type": …, …payload }`):

| type                   | payload |
|------------------------|---------|
| `hello`                | `{ userId, deviceId, connectionId, serverTime }` — reply to the client's `hello` |
| `pong`                 | `{ serverTime }` |
| `message.new`          | `{ message: Message }` |
| `message.deleted`      | `{ convId, msgId, deletedAt }` |
| `conversation.updated` | `{ conversation: Conversation }` — created/renamed/members/keys changed |
| `conversation.keys`    | `{ convId, keyId }` — a key was wrapped for *this* device; fetch `/keys` |
| `conversation.read`    | `{ convId, userId, msgId, at }` |
| `typing`               | `{ convId, userId, at }` |
| `user.updated`         | `{ user: User }` |
| `call.invite`          | `{ call: Call, caller: User }` |
| `call.answered`        | `{ call: Call }` |
| `call.ended`           | `{ call: Call, reason }` |
| `call.signal`          | `{ callId, fromDeviceId, envelope: Envelope }` |
| `error`                | `{ code, message }` |

Client → server:

| type          | payload |
|---------------|---------|
| `hello`       | `{}` — first frame after connecting |
| `ping`        | `{}` — at least every 4 min |
| `typing`      | `{ convId }` — ≤ 1 per 3 s |
| `presence`    | `{ active }` — `true` when the app comes to the foreground (and about once a minute while it stays open), `false` when it goes to the background. Sets the user's `lastSeenAt` and sends `user.updated` to everyone who shares a conversation with them (except people they blocked). The always-on background connection never sends it, so "last seen" means "last had the app open". |
| `call.signal` | `{ callId, envelope }` — relayed to the other party's negotiating device |

Decrypted `call.signal` content: `{ "kind": "offer"|"answer", "sdp" }` or
`{ "kind": "ice", "candidate", "sdpMid", "sdpMLineIndex" }`. Signalling envelopes
use the conversation's current key with `AAD = utf8(convId + ":" + callId + ":signal")`.

Connections are best-effort. After every (re)connect the app **must** reconcile:
`GET /conversations`, then for each conversation whose `lastMsgId` is newer
than the local latest, `GET …/messages?after=<local latest>`.

## 13. Types

```ts
type Session      = { token: string; expiresAt: number }
type User         = { userId: string; displayName: string; avatarKey?: string | null;
                      role: "member" | "admin"; status: "active" | "disabled";
                      createdAt: number; lastSeenAt?: number | null }
type Me           = User & { hasPassword: boolean }   // only ever sent to the account itself
type PublicDevice = { deviceId: string; userId: string; name: string;
                      devicePublicKey: string; encryptionPublicKey: string; enrolledAt: number }
type Device       = PublicDevice & { model?: string | null; os?: string | null; appVersion?: string | null;
                      hasBiometricKey: boolean; lastSeenAt?: number | null; revokedAt?: number | null }
type Friend       = { user: User; since: number | null; blocked: boolean }
type Invite       = { kind?: "admin" | "link" | "friend" | "group"; convId?: string | null; invitedBy?: string | null;
                      code: string; link: string; displayName?: string | null; role: "member" | "admin";
                      forUserId?: string | null; autoJoin: string[]; createdAt: number; expiresAt: number;
                      usedAt?: number | null; usedByUserId?: string | null }
type Member       = { userId: string; role: "owner" | "member"; joinedAt: number; lastReadMsgId?: string | null }
type Conversation = { convId: string; type: "direct" | "group"; name?: string | null; avatarKey?: string | null;
                      createdBy: string; createdAt: number; autoJoin?: boolean; members: Member[];
                      currentKeyId?: string | null; keyRotationRequired: boolean;
                      lastMsgId?: string | null; lastMessageAt?: number | null }
type Envelope     = { v: 1; keyId: string; iv: string; ct: string }
type WrappedKey   = { keyId: string; convId: string; recipientDeviceId: string; senderDeviceId: string;
                      ephemeralPublicKey: string; iv: string; ciphertext: string; signature: string; createdAt: number }
type MessageKind  = "e2e" | "call" | "system"
type Message      = { msgId: string; convId: string; senderId: string; senderDeviceId?: string | null;
                      clientId?: string | null; kind: MessageKind; envelope?: Envelope | null;
                      call?: CallInfo | null; system?: SystemInfo | null;
                      sentAt: number; createdAt: number; deletedAt?: number | null }
type SystemInfo   = { event: "member_added" | "member_removed" | "member_left" | "renamed" | "created"; userIds?: string[]; text?: string }
type CallInfo     = { callId: string; type: "audio" | "video";
                      outcome: "answered" | "missed" | "rejected" | "unreachable" | "cancelled"; durationMs?: number | null }
type Call         = { callId: string; convId: string; type: "audio" | "video";
                      callerId: string; callerDeviceId: string; calleeId: string; calleeDeviceId?: string | null;
                      state: "ringing" | "active" | "ended"; createdAt: number; answeredAt?: number | null;
                      endedAt?: number | null; endReason?: string | null }
type IceServer    = { urls: string[]; username?: string; credential?: string }
type ClientConfig = { serverName: string; iceServers: IceServer[]; maxUploadBytes: number;
                      features: { calls: boolean; media: boolean } }
type ServerSettings = ClientConfig & { defaultAutoJoin: boolean; inviteTtlHours: number }

// Decrypted content types (never seen by the server)
type Content      = { type: "text" | "image" | "video" | "audio" | "file"; text?: string | null;
                      attachment?: Attachment | null; replyTo?: string | null }
type Attachment   = { key: string; mime: string; sizeBytes: number; fileName?: string | null;
                      width?: number | null; height?: number | null; durationMs?: number | null;
                      enc: "aes-256-gcm-chunked-v1"; fileKey: string; baseIv: string; chunkSize: number; sha256: string;
                      thumbKey?: string | null; thumbSha256?: string | null }
type Signal       = { kind: "offer" | "answer"; sdp: string } | { kind: "ice"; candidate: string; sdpMid: string; sdpMLineIndex: number }
```
