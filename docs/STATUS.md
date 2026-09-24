# Project status

What works today, how it has been verified, and what's next. (Deployment-specific
details — endpoints, invite codes, account ids — never belong in this public file;
keep them in a git-ignored `DEPLOYMENT.local.md`.)

## Features

| Area | State |
|---|---|
| Joining | Invite code only (scan / paste link / type it). The builder's server is baked into the APK (`guftugu.serverUrl`), so nobody types a server address. No passcode or password at join. |
| Unlocking | Fingerprint (BiometricPrompt + Keystore key) when the phone has one; otherwise the phone's hardware device key (WhatsApp-style). A passcode is created about a week after first use and re-entered every 30 days. |
| People | Any user can invite others with a single-use code (WhatsApp / SMS / email share sheet, or text a phone contact). Using a code makes you friends and opens a 1:1 chat or joins the group it was for. The admin's invitees form a "family circle"; everyone else sees only friends and group members. Block, leave chat, leave group. |
| Messaging | Text, replies, images, video, audio files, voice notes, read receipts, typing indicators — all end-to-end encrypted (per-conversation keys wrapped for each device). |
| Calls | 1:1 audio and video over WebRTC (STUN by default; TURN configurable at runtime). |
| Server | Portable TypeScript core + adapters; AWS reference deployment (API Gateway HTTP + WebSocket, Lambda, DynamoDB on-demand, S3) with nothing billed while idle. Admin CLI for invites, users, devices, groups and ICE config. |
| Design | Hand-drawn riverbank art direction: square gold-framed logo, panoramic header, parchment cards, wax-seal avatars, paper-boat send button (docs/DESIGN.md). |

## Verification so far

- Server: 62 vitest tests against the in-memory adapter, plus an opt-in DynamoDB-Local
  conformance suite; a two-phone live smoke test against a real AWS deployment.
- Android: JVM unit tests (crypto, E2EE codec, sync rules, reducers, passcode policy…).
- Hardware: Huawei (Android 10, no Google services) and OPPO (Android 9) — enrolment,
  unlock, key hand-over to new members, real-time E2EE messages, release (R8) build.
- Android 10 emulator with a simulated fingerprint — fingerprint enrolment and unlock,
  messages both ways with a real phone, a 1:1 audio call (ICE connected).
- Not yet exercised on hardware: video calls, media upload/download, notification actions,
  the new invite/redeem, block and leave screens (server side is covered by tests).

## Known limitations

- **Key hand-over needs a key holder online.** A new member receives a group's key only when
  another member's phone that holds it is online. If every such phone is gone without being
  revoked, newcomers wait for keys; revoke the dead devices (admin CLI) to force a new key.
- No push service (by design): a foreground service keeps the WebSocket open; aggressive OEM
  battery savers can still delay messages unless the app is exempted.
- 1:1 calls only (group calls would need an SFU, which isn't serverless).
- No safety-number verification between phones yet; key trust is server-mediated (TOFU).

## Next

- Hardware pass for video calls, media, invites/block/leave.
- Safety numbers (QR compare) between phones; periodic key rotation.
- Optional push via a `PushProvider` (UnifiedPush/FCM) for forks that want it.
- Adapters for other clouds (Supabase, Azure, GCP) against `server/src/core/ports.ts`.
- CI (GitHub Actions) for server tests and the Android build.

## Developer commands

```bash
# server (Node 22)
cd server && npm install && npm test && npm run typecheck
sam build && sam deploy           # your parameters live in samconfig.toml (git-ignored)
npm run admin -- stats            # admin CLI (after `npm run admin -- login --api … --key …`)

# android (JDK 17, SDK 36)
cd android
./gradlew assembleDebug           # debug APK
./gradlew :app:testDebugUnitTest  # unit tests
./gradlew assembleRelease         # signed + minified if android/keystore.properties exists
```
