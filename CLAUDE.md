# Guftugu — project guide for Claude Code

Guftugu is a private, self-hosted chat app (text, images, video, audio, voice
notes, 1:1 audio/video calls) for a family or small group. Native Android
(Kotlin + Jetpack Compose) client, TypeScript server that runs serverless on
AWS by default. Not distributed through any app store. Eventually published on
GitHub so others can build the APK and deploy the server to their own cloud.

## Owner's decisions (do not re-litigate)

| Topic | Decision | Why |
|---|---|---|
| Client | Native Android, Kotlin, Jetpack Compose, single `:app` Gradle module | speed; simple for others to build |
| Server language | TypeScript on **Web-standard APIs only** (`fetch`, `Request`/`Response`, WebCrypto) | runs unchanged on Node (AWS/Azure/GCP), Deno (Supabase Edge), Cloudflare Workers |
| Server layout | `core/` (pure logic + ports) and `adapters/<cloud>/` | porting = one adapter folder, `core/` untouched |
| Reference cloud | AWS: API Gateway (HTTP + WebSocket), Lambda (nodejs22.x), DynamoDB **on-demand**, S3. Deployed with SAM | pay-per-use only; ~$0 when idle |
| **No fixed-cost resources — ever** | Lambdas stay **outside any VPC** (so no NAT Gateway). No ElastiCache/Redis, EC2/Lightsail, Secrets Manager, customer KMS keys, CloudWatch alarms, provisioned concurrency, Route 53 zones, DynamoDB provisioned capacity or PITR. Only services that bill $0 when idle: API Gateway, Lambda, DynamoDB on-demand, S3, CloudWatch Logs (short retention) | owner pays per use only, nothing per month |
| Caching | Phone-side Room DB is the primary cache (delta sync); server uses per-container in-memory `Cache` port; Redis impl optional for persistent hosts | see docs/ARCHITECTURE.md "Caching & sync" |
| Device authentication | First launch generates non-exportable Keystore keys; enrolment with a single-use invite code registers the public keys | "the app must authenticate itself" |
| User authentication | **No passcode or password at join, ever.** Fingerprint unlock (BiometricPrompt + Keystore key) when the phone has one registered; otherwise the phone's device key is the credential (WhatsApp-style). About a week after first use (first chat opened / message sent) the app asks the person to create a passcode, then asks for it every 30 days (`PasscodePolicy`). Never require a phone screen lock | owner: "Ditch the passcode. Like WhatsApp, remind the user after 30 days to re-enter it." |
| Reinstall / new phone | Must re-enrol (new invite, or a link-code from an already-enrolled phone). Nothing biometric is backed up or synced | keys are hardware-bound |
| E2EE | Per-conversation AES-256-GCM key, wrapped for every member device with ECDH P-256 (ECIES) and signed by the sender device. Server stores ciphertext only. Attachments encrypted client-side before upload. Call signalling encrypted under the conversation key | server operator cannot read content |
| At-rest encryption | DynamoDB + S3 server-side encryption on; content is already ciphertext | defence in depth |
| Calls | WebRTC 1:1, STUN by default, ICE servers served from `GET /config` so TURN can be added without an app rebuild | TURN can't be serverless |
| Push notifications | None (no FCM). Foreground service keeps the WebSocket alive; `PushProvider` seam for forks | no Google dependency |
| People & invites | Any user can invite (single-use code, shared via WhatsApp/SMS/email or to a phone contact). Using a code makes you friends and opens a 1:1 chat or joins the group it was for. People the admin invited form the family circle and see each other; everyone else sees only friends and people in their groups. Block (no messages/calls/group adds), leave group, leave 1:1 chat | owner's product direction: "A place for your family, friends and coworkers" |
| Server baked into builds | `guftugu.serverUrl` in `android/local.properties` → `BuildConfig.DEFAULT_SERVER_URL`; people only type the invite code | each builder runs their own server |
| Admin | `X-Admin-Key` deploy parameter + cloud-agnostic admin CLI (invites, users, groups, ICE config) | simple, portable |
| Portability | The app only speaks `docs/PROTOCOL.md`; anyone may implement the server on any stack/language | the protocol is the product |

## Toolchain (pinned)

- Android: AGP 8.13.1, Kotlin 2.3.20, KSP 2.3.12, Gradle 8.14, Compose BOM
  2026.06.01 (newer androidx needs AGP 9.1/SDK 37), minSdk 26, target/compileSdk 36, JDK 17.
  SDK path and your server URL go in `android/local.properties` (git-ignored).
- Server: Node 22 on Lambda, TypeScript, esbuild via `sam build`, vitest for tests.
  No runtime npm dependencies in `core/` (AWS SDK v3 ships in the Lambda runtime).
  Deploy with `sam deploy`; your account/region/admin key live in `server/samconfig.toml` (git-ignored).
- Machine- and deployment-specific notes (paths, test devices, live endpoints, where secrets
  are) belong in `CLAUDE.local.md` / `DEPLOYMENT.local.md` — both git-ignored, never published.

## Working rules

- Keep `docs/` in sync with code: PROTOCOL.md is the contract; if you change a
  request/response shape, change the doc, the TS types and the Kotlin DTOs together.
- `core/` must never import from `adapters/` or any cloud SDK.
- Never log tokens, keys, nonces, passwords or message content.
- Every new server feature gets a vitest test against the memory adapter.
- Prefer one DynamoDB write per user action; read through the `Cache` port.
- The Android build must stay green: `cd android && ./gradlew assembleDebug`.
- Server checks: `cd server && npm test && npm run typecheck && sam validate`.

## Current state

See **docs/STATUS.md** — what works, what has been verified, known limitations and
what's next. Update it whenever a phase completes. Anything specific to one
deployment (endpoints, invite codes, account ids, device serials, names) goes in the
git-ignored `DEPLOYMENT.local.md`, never in tracked files — this repository is public.

## Where things are

```
docs/            STATUS.md · ARCHITECTURE.md · PROTOCOL.md · SECURITY.md · DATA_MODEL.md · DESIGN.md · ANDROID_MODULES.md · DEPLOY_AWS.md · BUILD_ANDROID.md · ADMIN_CLI.md
design/          logo.svg (source of the icon and in-app medallion)
server/          TypeScript server (core + adapters), SAM template, admin CLI, tests
android/         Kotlin app
```
