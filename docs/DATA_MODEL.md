# Data model

`core/ports.ts` defines repositories and a `Cache`. The AWS adapter maps the
repositories onto **one DynamoDB table** (on-demand capacity, TTL attribute
`ttl`, one GSI). Any other adapter may use whatever schema it likes as long as
the repository semantics at the bottom hold.

## DynamoDB single-table layout (adapter `aws`)

Table `guftugu-<stage>` — keys `PK` (S), `SK` (S). GSI `GSI1` — `GSI1PK`, `GSI1SK` (both S).

| Entity        | PK                         | SK                        | GSI1PK          | GSI1SK              | notes |
|---------------|----------------------------|---------------------------|-----------------|---------------------|-------|
| User          | `USER#<userId>`            | `PROFILE`                 | `USERS`         | `<createdAt>`       | `passwordHash`, `role`, `status` |
| Device        | `DEVICE#<deviceId>`        | `META`                    | `USER#<userId>` | `DEVICE#<deviceId>` | 3 public keys, `failedPasswordAttempts`, `lockedUntil`, `revokedAt` |
| Invite        | `INVITE#<code>`            | `META`                    | `INVITES`       | `<createdAt>`       | `ttl` = expiresAt + 7 d |
| Challenge     | `CHALLENGE#<nonce>`        | `META`                    |                 |                     | `deviceId`, `ttl` 120 s |
| Session       | `SESSION#<sha256(token)>`  | `META`                    |                 |                     | `userId`, `deviceId`, `ttl` 30 d |
| Conversation  | `CONV#<convId>`            | `META`                    | `CONVS`         | `<createdAt>`       | `currentKeyId`, `keyRotationRequired`, `lastMsgId`, `lastMessageAt` |
| Membership    | `CONV#<convId>`            | `MEMBER#<userId>`         | `USER#<userId>` | `CONV#<convId>`     | `lastReadMsgId`; GSI1 lists a user's conversations |
| Direct index  | `DIRECT#<uidA>#<uidB>`     | `META`                    |                 |                     | sorted ids → `convId` |
| Wrapped key   | `CONV#<convId>`            | `KEY#<keyId>#<deviceId>`  | `DEVICE#<deviceId>` | `KEY#<convId>#<keyId>` | ECIES blob + signature; GSI1 = "keys for my device" |
| Message       | `CONV#<convId>`            | `MSG#<msgId>`             |                 |                     | envelope (opaque), ULID ⇒ time-ordered |
| Idempotency   | `IDEMP#<deviceId>#<clientId>` | `META`                 |                 |                     | → `msgId`, `ttl` 7 d |
| Connection    | `CONN#<connectionId>`      | `META`                    | `USER#<userId>` | `CONN#<connId>`     | `deviceId`, `ttl` 3 h safety net |
| Call          | `CALL#<callId>`            | `META`                    |                 |                     | state machine, `ttl` 24 h after end |
| Settings      | `SETTINGS`                 | `META`                    |                 |                     | ICE servers, limits, server name |

Access patterns

| Need | Operation |
|---|---|
| device / session / invite / call by id | GetItem |
| a user's devices / connections / conversations | Query GSI1 `GSI1PK = USER#id`, `begins_with(GSI1SK, prefix)` |
| wrapped keys for my device in a conversation | Query GSI1 `GSI1PK = DEVICE#id`, `begins_with(GSI1SK, KEY#convId#)` |
| members of a conversation | Query `PK = CONV#id`, `begins_with(SK, MEMBER#)` |
| messages after X | Query `PK = CONV#id`, `SK > MSG#X`, ascending, limit |
| messages before X / latest | Query `SK < MSG#X` (or `begins_with MSG#`), descending |
| consume nonce / invite | conditional Delete / Update (`attribute_not_exists(usedAt)`) |
| create direct conversation once | TransactWrite: Put `DIRECT#a#b` (not exists) + conv + 2 memberships |
| append message idempotently | TransactWrite: Put `MSG#` (not exists) + Put `IDEMP#` (`attribute_not_exists(PK) OR ttl <= now`, because TTL sweeps are lazy); then Update conv `lastMsgId` |

Every item carries `entity` (`user`, `device`, `invite`, `challenge`, `session`, `conversation`, `membership`, `direct`, `key`, `message`, `idempotency`, `connection`, `call`, `settings`) — not `type`, because `Conversation.type` and `Call.type` are record fields. Timestamps are epoch ms numbers; the `ttl` attribute is epoch **seconds** (DynamoDB TTL). TTL deletion is lazy (up to ~48 h), so every read of a TTL'd item also checks expiry in code.

## Caching (all adapters)

`Cache` port: `get(key)`, `set(key, value, ttlMs)`, `del(key)`. The AWS adapter
uses a per-Lambda-container LRU map (free, survives across warm invocations).
A Redis/Valkey implementation is a drop-in for hosts with a persistent server.

| cached value | key | TTL | invalidated on |
|---|---|---|---|
| session → {userId, deviceId} | `sess:<hash>` | 60 s | logout |
| device | `dev:<id>` | 60 s | revoke, password failure |
| user | `user:<id>` | 60 s | patch/disable |
| conversation META + members | `conv:<id>` | 30 s | any conversation write |
| live connections of a user | `conns:<userId>` | 5 s | connect/disconnect in this container |
| settings | `settings` | 300 s | admin PUT |

Staleness across containers is bounded by the TTL (a revoked device may keep
a session for ≤ 60 s). The phone is the *real* cache: it holds all decrypted
history in Room and only asks the server for deltas.

## Repository semantics (what any adapter must honour)

- `invites.consume(code, now)` is atomic: exactly one caller wins.
- `challenges.consume(nonce)` is atomic and removes the nonce.
- `messages.append` assigns a monotonically increasing `msgId` per conversation
  (monotonic ULIDs suffice; a Postgres adapter can use a sequence) and is
  idempotent on `(deviceId, clientId)` for 7 days.
- `conversations.createDirect(a, b)` is idempotent and returns the existing one.
- `keys.put` is idempotent per `(convId, keyId, recipientDeviceId)`;
  `conversations.setCurrentKey` only moves forward (`keyId` must sort later).
- `connections.listByUser(userId)` returns only live connections; `remove` is
  called when the realtime adapter reports a connection gone.
- `sessions.get(tokenHash)` returns null after `expiresAt`.
