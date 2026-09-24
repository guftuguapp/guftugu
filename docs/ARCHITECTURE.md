# Guftugu — Architecture

Guftugu ("conversation" in Urdu/Hindi) is a private, self-hosted chat app for a
family or small group. One person deploys the server to their own cloud, hands
out invite codes, and everyone installs the APK directly (no app store).

```
┌─────────────────────────┐        HTTPS (REST)         ┌──────────────────────────────┐
│  Android app (Kotlin,   │ ─────────────────────────▶ │  Server (TypeScript, portable)│
│  Jetpack Compose)       │ ◀───────────────────────── │   core/   pure business logic │
│                         │        WSS (realtime)       │   adapters/aws  Lambda+Dynamo │
│  Keystore keys,         │                             │   adapters/<other cloud> ...  │
│  BiometricPrompt        │ ◀── presigned PUT/GET ──▶   │  Blob store (S3 / …)          │
└─────────────────────────┘                             └──────────────────────────────┘
        ▲            ▲
        └── WebRTC ──┘  (peer-to-peer media for calls; only signalling goes via server)
```

## Principles

1. **The protocol is the product.** The app only speaks the HTTP + WebSocket
   contract in [PROTOCOL.md](PROTOCOL.md). Any server that implements it works —
   the reference implementation runs on AWS, but nothing in the app knows that.
2. **Core / adapter split on the server.** `server/src/core` contains all the
   rules (auth, rooms, messages, calls) written against *ports* (interfaces) and
   Web-standard APIs only (`fetch`, `Request`/`Response`, WebCrypto). Cloud
   specifics live in `server/src/adapters/<cloud>`. Porting to Supabase, Azure or
   GCP means writing one adapter folder, not touching `core/`.
3. **Pay only for use.** The AWS adapter uses API Gateway (HTTP + WebSocket),
   Lambda, DynamoDB on-demand and S3. Idle cost is effectively zero.
4. **Keys stay on the phone.** Device identity and the biometric-gated login key
   are generated inside the Android Keystore and never leave it. Biometric
   templates are handled by the OS; the server only ever sees public keys and
   signatures. A reinstall or new phone re-enrols. See [SECURITY.md](SECURITY.md).
5. **The server is blind.** Messages, attachments and call signalling are
   end-to-end encrypted with per-conversation keys that are wrapped for each
   member device; the server stores ciphertext it cannot open.
6. **Hit the database as little as possible.** The phone's local DB is the
   source of truth for the UI and only syncs deltas; the server reads hot data
   through an in-memory cache and writes once per user action.
7. **Buildable by anyone.** Single Gradle module, single `sam deploy`, one admin
   CLI. No Google Play services required (no FCM, no Play Integrity).

## Repository layout

```
Guftugu/
├── docs/                 architecture, protocol, data model, security, deploy guides
├── server/
│   ├── src/protocol/     DTO types shared by every adapter (mirrors PROTOCOL.md)
│   ├── src/core/         ports.ts, services, router, crypto — cloud-agnostic
│   ├── src/adapters/aws/ DynamoDB / S3 / API Gateway adapters + Lambda entrypoints
│   ├── src/adapters/memory/  in-memory ports for tests and local dev
│   ├── template.yaml     AWS SAM stack (infrastructure as code)
│   ├── admin/            admin CLI (invites, users, groups, ICE config)
│   └── tests/            vitest suites running core against memory adapters
└── android/              Kotlin + Jetpack Compose app (single :app module)
```

## Server: request lifecycle

```
Lambda event ──▶ adapters/aws/lambda-http.ts ──▶ Request (web standard)
   ──▶ core/app.ts (router + auth middleware) ──▶ core/services/*.ts
   ──▶ ports (db, blobs, realtime) ──▶ adapters/aws/{dynamo,s3,apigw-ws}.ts
   ◀── Response (web standard) ◀── back to API Gateway result
```

Realtime fan-out: after a message is stored, `core` asks the `Database` port for
all live connections of every member and calls `Realtime.send(connectionId,
event)` for each. On AWS that is `PostToConnection`; a Supabase adapter would
instead publish on a Realtime channel.

## Android app: layers

```
ui/            Compose screens + ViewModels (join, unlock, chats, chat, media, calls, settings)
domain/        plain Kotlin models
data/
  api/         GuftuguApi — OkHttp + kotlinx.serialization, speaks PROTOCOL.md
  ws/          RealtimeClient — WebSocket with ping/reconnect/backoff
  db/          Room cache: conversations, messages, users, outbox (pending sends/uploads)
  repo/        AuthRepository, ConversationRepository, MessageRepository, MediaRepository, CallRepository
  prefs/       ServerConfigStore (DataStore), SecureStore (Keystore-wrapped secrets)
core/crypto/   KeystoreKeys (device + biometric keys), DER→P1363 signature conversion
core/auth/     BiometricGate (BiometricPrompt + CryptoObject)
service/       RealtimeService (foreground, keeps WS alive), CallService (foreground during calls)
calls/         WebRTC PeerConnectionManager + CallManager state machine
di/            AppGraph — hand-written singletons (no Hilt)
```

core/e2ee/     ConversationKeyManager (generate/wrap/unwrap/rotate), ContentCipher, AttachmentCipher

Offline-first: the Room cache is the source of truth for the UI. The network
layer writes into Room; screens observe Room `Flow`s. Sends go into an outbox
and are retried with the message's `clientId` as idempotency key.

## Caching & sync (why most requests never touch the database)

Phone side
- Everything the user sees comes from Room (conversations, decrypted messages,
  users, keys). Opening a chat is a local read; scrolling history is local.
- The WebSocket pushes new events; after every (re)connect and every 15 min
  (WorkManager, if the foreground service is off) the app does one
  `GET /conversations` and a `GET …/messages?after=` only for conversations
  whose `lastMsgId` moved. Typical sync = 1 request.
- Read receipts are coalesced (one `PUT …/read` per conversation when leaving
  it or every 5 s). Typing indicators and presence never hit the DB.
- Media bytes go phone ↔ blob store directly and are cached decrypted on disk.

Server side
- A `Cache` port (per-container memory on Lambda; Redis optional elsewhere)
  fronts sessions, devices, users, conversation membership and settings, so an
  authenticated request costs 0 reads when warm.
- Sending a message = one transactional write (message + idempotency marker)
  plus one small update of the conversation's `lastMsgId`. Fan-out reads the
  members' live connections (cached 5 s) and pushes; no read-back.
- The server computes no previews or unread counts (it can't read content
  anyway) — the phone derives them locally.
- Why not Redis by default: on AWS, ElastiCache is an always-on bill plus a VPC
  for Lambda; that contradicts pay-per-use. The port makes it a drop-in for
  anyone hosting on a persistent server.

## Realtime without push notifications

There is no FCM dependency. Instead the app runs a foreground service
(`RealtimeService`, type `specialUse`) that holds the WebSocket open, pings every
4 minutes (API Gateway idles out at 10) and reconnects when the 2-hour hard limit
closes it. Incoming messages and call invites arrive on that socket and are
surfaced as local notifications. The user can turn the service off in Settings;
then messages sync on next open and calls can't ring. A `PushProvider` interface
in `service/` is the seam where FCM/UnifiedPush can be added by a fork.

## Calls

1:1 audio/video via WebRTC. The server only relays signalling (`call.*` events)
and stores call logs. ICE servers come from `GET /config`; the default is public
STUN. Anyone hosting can add TURN credentials with the admin CLI
(`config set-ice`) — no app rebuild needed. Group calls would need an SFU and are
out of scope for the serverless design.

## Extending to another cloud

Implement `server/src/core/ports.ts`:

| Port         | AWS impl              | Supabase equivalent          | Azure equivalent            |
|--------------|-----------------------|------------------------------|-----------------------------|
| `Database`   | DynamoDB single table | Postgres tables              | Cosmos DB / Postgres        |
| `BlobStore`  | S3 presigned URLs     | Storage signed URLs          | Blob SAS URLs               |
| `Realtime`   | API GW WebSocket      | Realtime broadcast channel   | Web PubSub                  |
| entrypoints  | Lambda handlers       | Edge Function (Deno) handler | Azure Function handler      |

Then wire them in an `app.ts`-style entrypoint. `core/` and `protocol/` are
untouched, and the existing test-suite (which runs against the memory adapter)
doubles as a conformance suite for the new adapter.
