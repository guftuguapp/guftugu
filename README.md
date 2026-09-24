# Guftugu

**A private, self-hosted chat app for your family — messages, photos, videos,
voice notes and 1:1 audio/video calls — that you run on your own cloud and hand
out to the people you choose. No app store, no accounts with anyone else, and
the server can't read a word of it.**

*Guftugu* (گفتگو) means "conversation" in Urdu/Hindi.

```
   phone ──── HTTPS / WebSocket ────▶  your server (AWS by default, ~$0 when idle)
     │                                        stores only ciphertext
     └──────── WebRTC (peer-to-peer) ────────▶ other phone
```

## What you get

- **Native Android app** (Kotlin, Jetpack Compose) — fast, small, works on Android 8+.
- **Text, images, video, audio files, voice notes, replies, read receipts, typing.**
- **Audio & video calls** (1:1, WebRTC). Add a TURN server later without rebuilding.
- **Invite-only**: every phone is enrolled with a single-use code. A reinstall
  or a new phone must be invited again — nothing secret is ever backed up.
- **Fingerprint / face unlock** using hardware-backed keys that never leave the
  phone; password as the fallback.
- **End-to-end encryption**: per-conversation keys wrapped for each member's
  device; messages, attachments and call signalling are ciphertext to the server.
- **Serverless, pay-per-use** reference backend on AWS (API Gateway, Lambda,
  DynamoDB on-demand, S3). No NAT Gateway, no always-on instances, nothing
  billed per month.
- **Portable server**: TypeScript on Web-standard APIs with a `core/` +
  `adapters/` split. The AWS adapter is one folder; Supabase/Azure/GCP are
  another folder each. The app only speaks the documented protocol.
- **Admin CLI**: invites, users, devices, groups, ICE/TURN config.

## Quick start

1. **Deploy the server** — [docs/DEPLOY_AWS.md](docs/DEPLOY_AWS.md)
   ```bash
   cd server && npm install && sam build && sam deploy --guided
   ```
2. **Build the app** — [docs/BUILD_ANDROID.md](docs/BUILD_ANDROID.md)
   ```bash
   cd android && ./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
   ```
3. **Invite someone** — [docs/ADMIN_CLI.md](docs/ADMIN_CLI.md)
   ```bash
   cd server && npm run admin -- invite --name "Ammi"
   ```
   They install the APK, scan the QR (or paste the code), pick a password,
   enable fingerprint — done.

## Documentation

| Doc | What it covers |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | Layers, caching & sync strategy, how to port to another cloud |
| [docs/PROTOCOL.md](docs/PROTOCOL.md) | The complete HTTP + WebSocket contract (implement this on any stack) |
| [docs/SECURITY.md](docs/SECURITY.md) | Device enrolment, biometric login, E2EE design, threat model |
| [docs/DATA_MODEL.md](docs/DATA_MODEL.md) | DynamoDB single-table layout, cache policy, repository semantics |
| [docs/ANDROID_MODULES.md](docs/ANDROID_MODULES.md) | App package map and internal interfaces |
| [docs/DEPLOY_AWS.md](docs/DEPLOY_AWS.md) | Deploying, updating, costs, TURN |
| [docs/BUILD_ANDROID.md](docs/BUILD_ANDROID.md) | Building and signing the APK |
| [docs/ADMIN_CLI.md](docs/ADMIN_CLI.md) | Admin commands |
| [docs/DESIGN.md](docs/DESIGN.md) | Visual language and performance rules |
| [docs/STATUS.md](docs/STATUS.md) | Current project status and how to resume |

## Status

Early. Built for one family first; published so others can run their own.
Contributions welcome — especially adapters for other clouds (see the
`Ports` interfaces in `server/src/core/ports.ts`).

## License

MIT
