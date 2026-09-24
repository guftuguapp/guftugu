# Guftugu — Security design

## Goals

1. Only phones the admin invited can talk to the server at all ("the app
   authenticates itself").
2. Opening the app requires the *person*, proven by fingerprint/face, with a
   password as the fallback.
3. Nothing biometric ever leaves the phone. Biometric templates are handled by
   Android; the server only sees public keys and signatures. A reinstall or a
   new phone must re-enrol — no private key is ever backed up or synced.
4. **End-to-end encryption**: the server (and whoever runs it) stores and
   relays only ciphertext for messages, attachments and call signalling.
5. Losing a phone is recoverable: revoke that device; its keys are useless and
   every conversation it was in rotates to a new key.

## Keys on the phone

| Alias / name          | Where                              | Algorithm | User-auth binding | Purpose |
|-----------------------|------------------------------------|-----------|-------------------|---------|
| `guftugu_device`      | Android Keystore (TEE/StrongBox)   | EC P-256 sign | none          | proves *this installed app instance*; signs key-wraps |
| `guftugu_auth`        | Android Keystore                   | EC P-256 sign | `setUserAuthenticationRequired(true)`, `BIOMETRIC_STRONG`, `setInvalidatedByBiometricEnrollment(true)` | proves *the person*; only usable right after BiometricPrompt |
| `guftugu_wrap`        | Android Keystore                   | AES-256-GCM   | none          | encrypts the two software secrets below at rest |
| identity ECDH key     | app-private file, wrapped by `guftugu_wrap` | EC P-256 ECDH | —   | receives wrapped conversation keys (ECIES) |
| conversation keys     | Room table, each wrapped by `guftugu_wrap`  | AES-256   | —         | encrypt/decrypt content of one conversation (one per key epoch) |

Keystore keys are non-exportable. The ECDH key is software because Keystore
`KeyAgreement` only exists from Android 12; it is still protected at rest by a
hardware-backed AES key and by the app sandbox + file-based encryption.

**Owner's rule (latest): no passcode at join, WhatsApp-style reminders.** Joining needs only
the invite. A phone with a registered fingerprint locks Guftugu with it; a phone without one
(including phones with no screen lock at all) is identified by its hardware device key alone,
like WhatsApp where the phone is the identity (`POST /auth/verify` method `device`, allowed
only while the account has no passcode and the device no fingerprint key). About a week after
the first chat opened or message sent, the app asks the person to **create a passcode**
("Later" for up to two more weeks); from then on it asks for it **every 30 days**
(`PasscodePolicy`). A passcode-less phone therefore never locks its owner out, and once a
passcode exists the device key alone is no longer enough.

**Fingerprint first, password optional.** The owner's rule: people unlock with
their fingerprint; a password is only a backup ("second option"). Enrolment
creates `guftugu_auth` and confirms it with one BiometricPrompt before the
account is created, so nobody ends up with a key their phone cannot use. A
passcode (≥ 6 characters) is required only on phones without a usable
fingerprint sensor, and those phones stay signed in for the 30-day session
lifetime instead of asking on every launch — the owner's choice for an app that
is only ever given to known family members, never published to an app store.

If the phone has a sensor but no fingerprint registered in Android Settings, the
app says so and opens the phone's fingerprint settings instead of silently
falling back to a password. Fingerprint unlock can be turned on later for an
already-enrolled phone with `PUT /me/device/auth-key` (device-key signature +
proof of possession of the new key over a fresh challenge).

Adding a new fingerprint to the phone invalidates `guftugu_auth` permanently
(`KeyPermanentlyInvalidatedException`) — deliberately, so someone who learns the
phone's PIN cannot add their own finger and get in. Recovery: unlock with the
backup password (if one was set) and turn fingerprint unlock on again, or enrol
the phone again with a link code from another of the person's phones or a new
admin invite.

## Enrollment (PROTOCOL §3)

Invite codes: `GFT-XXXX-XXXX`, 8 Crockford-base32 chars (40 bits) from a CSPRNG,
single use, 72 h default expiry, API Gateway throttling per IP. Enrollment
registers three public keys on a new `Device`. The password is hashed with
**PBKDF2-HMAC-SHA256, 600 000 iterations, 16-byte random salt** (WebCrypto —
identical on Node, Deno and Workers), stored as `pbkdf2-sha256$600000$<salt>$<hash>`.

## Login (PROTOCOL §4)

Challenge–response, so nothing reusable crosses the wire:

```
phone                                            server
  │ POST /auth/challenge {deviceId}                │
  │ ◀── {nonce}  random 32 B, 120 s, single use    │
  │ BiometricPrompt(CryptoObject(Signature))       │
  │ sig = ECDSA_P256(guftugu_auth,                 │
  │       "guftugu-auth:v1:"+deviceId+":"+nonce)   │
  │ POST /auth/verify {nonce, sig, method} ───────▶│ verify(sig, authPublicKey) → session
```

Password path (only for accounts that set a backup password): same, signed with
`guftugu_device` and carrying the password.
The device signature stops credential stuffing from anything that is not the
enrolled phone; the password proves the person. 5 failures lock the password
path for 15 min. Sessions are 32 random bytes; the server keeps `SHA-256(token)`
with a 30-day TTL. The app holds the token in memory and in a
`guftugu_wrap`-encrypted file, and re-authenticates on every cold start (that
is the unlock screen). Offline, BiometricPrompt still gates the UI locally.

## End-to-end encryption (PROTOCOL §7–§9)

Design: **one symmetric key per conversation epoch, distributed by ECIES to
every member device, content encrypted with AES-256-GCM.** This is the
"sender key"/Megolm-style approach without the ratchet: simple to audit, cheap
on a serverless backend, no server-side prekey machinery.

- Conversation key `K` (32 B, CSPRNG) generated by the member's phone that
  creates the conversation or rotates the key.
- For each member device: ephemeral P-256 → ECDH with the device's
  `encryptionPublicKey` → HKDF-SHA256 (info binds both public keys) → AES-GCM
  wrap of `K` with AAD `convId:keyId:recipientDeviceId`. The whole wrap is
  ECDSA-signed by the sender's `guftugu_device` key so a malicious server
  cannot inject keys. Recipients verify the signature against the sender
  device's public key.
- Messages: `AES-256-GCM(K, random 12 B iv, JSON content, aad = convId:senderId:clientId)`.
  The AAD binds ciphertext to its sender and prevents the server from
  re-attributing or replaying a message into another conversation.
- Attachments: fresh 32 B `fileKey`; the file is encrypted in 1 MiB chunks
  (`iv = baseIv ‖ chunkIndex`, AAD `objectKey:chunkIndex`) so phones can stream
  large videos without holding them in memory; `sha256` of the ciphertext lets
  the receiver detect truncation. `fileKey` travels inside the message envelope.
- Call signalling (SDP, ICE) is encrypted under `K`, so the server cannot swap
  DTLS fingerprints and MITM the WebRTC media (which is itself DTLS-SRTP).
- Rotation: removing a member or revoking a device sets
  `keyRotationRequired`; the next sending device mints a new epoch and
  distributes it. Old epochs are kept on devices to read history; removed
  members cannot read anything sent after rotation.

What the server *can* see (metadata): who is in which conversation, group
names/avatars, message timestamps and sizes, who called whom and for how long,
device names/models, connection times. Documented so hosters and users know.

Key hand-over needs a key holder online: a new member (or new phone) receives a
group's current key only when another member's phone that holds it is online to
wrap it. If every phone holding the key is gone without being revoked (uninstalled,
lost), newcomers wait for keys indefinitely. Fix: revoke those devices
(`admin/cli.ts revoke-device …`); revocation flags `keyRotationRequired`, and the next
sender mints a fresh key for everyone still present (history from the lost epoch stays
unreadable for the newcomer — by design).

Known limits of v1 (documented, not hidden)
- No forward secrecy within an epoch: compromise of `K` reveals that epoch's
  messages. Rotation is per membership change only; a fork can add periodic rotation.
- Public-key trust is server-mediated (TOFU). Safety-number/QR verification
  between phones is a planned extension; the identity keys needed already exist.
- Group names, avatars and member lists are not encrypted.

## Data at rest

- DynamoDB: server-side encryption on (AWS-owned key); items hold ciphertext
  envelopes, wrapped keys, public keys, PBKDF2 hashes, hashed session tokens.
- S3: bucket default encryption SSE-S3, public access blocked, objects are
  client-encrypted ciphertext anyway, presigned URLs 15 min, single object.
- Phone: Room DB in app-private storage (Android file-based encryption);
  conversation keys and the ECDH private key additionally wrapped by the
  hardware-backed `guftugu_wrap` AES key. Decrypted media cache is app-private
  and cleared on logout/revocation.

## Transport

TLS everywhere (API Gateway, S3). No certificate pinning by default so any
self-hoster's certificate works; `GuftuguApi` has a single place to add pins.
WebSocket auth uses the `Authorization` header (query param only for browsers).

## Admin surface

`X-Admin-Key` is a deploy-time parameter (≥ 16 chars), compared in constant
time, used only by the admin CLI on the owner's computer. Admin can create
invites, disable users, revoke devices, manage groups and ICE config — but
cannot read any content.

## Threats considered

| Threat | Mitigation |
|---|---|
| Stranger finds the server URL | Nothing works without an enrolled device key; enrollment needs an invite |
| APK reverse-engineered | No secrets inside; keys are per-install in hardware keystore |
| Phone stolen, locked | Keys need biometric or password; admin/user revokes device → key rotation |
| Phone stolen, unlocked, app open | App locks on background after N min (setting); revoke device |
| Server DB leaks | Ciphertext + public keys + PBKDF2 hashes + hashed tokens only |
| Malicious server operator | Cannot read content, cannot inject keys (signed wraps), cannot MITM calls (encrypted SDP) — can see metadata |
| Replay of a captured login | Single-use nonces bound to deviceId, 120 s |
| Message replay / re-attribution | GCM AAD binds convId, senderId, clientId |
| Brute-force password | Requires device signature; lockout after 5 tries |
| Token in logs | Bearer header only; never logged |
| Media abuse | Membership check, size cap, presign expiry, private bucket |
