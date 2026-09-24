# Guftugu server

The reference server for the [Guftugu protocol](../docs/PROTOCOL.md): a
TypeScript core written against Web-standard APIs (`Request`/`Response`,
WebCrypto) with cloud adapters. The AWS adapter runs it serverless on API
Gateway + Lambda + DynamoDB on-demand + S3 and costs nothing while idle.

## Layout

```
server/
├── src/protocol/        types.ts — wire types, mirrors docs/PROTOCOL.md (shared, do not fork)
├── src/core/            cloud-agnostic logic; imports nothing from adapters/ or any SDK
│   ├── ports.ts         Database / BlobStore / Realtime / Cache / Clock / IdGen interfaces
│   ├── app-contract.ts  createApp(ports) -> { fetch(Request), ws.onConnect/onDisconnect/onMessage }
│   ├── app.ts           router + auth middleware wiring
│   ├── services/        enroll, auth, users, conversations, keys, messages, media, calls, admin, config
│   ├── realtime/        WebSocket frame handling and fan-out
│   └── crypto.ts, ulid.ts, base64url.ts, errors.ts, http.ts, router.ts, cached.ts, dto.ts, validate.ts
├── src/adapters/memory/ in-process ports used by the tests and for local runs
├── src/adapters/aws/    DynamoDB single-table, S3 presigned URLs, API Gateway WebSocket,
│                        per-container cache, Lambda entrypoints (lambda-http.ts, lambda-ws.ts)
├── admin/cli.ts         admin CLI (invites, users, devices, groups, ICE/config) — see docs/ADMIN_CLI.md
├── tests/               vitest suites: core against the memory adapter (+ opt-in DynamoDB Local conformance)
├── template.yaml        AWS SAM stack — every resource bills per use only
└── samconfig.example.toml  copy to samconfig.toml (git-ignored) and fill in
```

Rules that keep it portable: `core/` never imports from `adapters/` or a cloud
SDK; a new cloud = one new folder under `adapters/` implementing `ports.ts`.
The contract docs are `../docs/ARCHITECTURE.md`, `PROTOCOL.md`, `SECURITY.md`,
`DATA_MODEL.md`.

## Scripts

Node 22 is required (`node --version`).

| Command | What it does |
|---|---|
| `npm ci` | install dependencies |
| `npm run typecheck` | `tsc --noEmit` over `src/`, `tests/` and `admin/` |
| `npm test` | run the vitest suites once |
| `npm run test:watch` | vitest in watch mode |
| `npm run admin -- <command>` | run the admin CLI (`tsx admin/cli.ts`) |
| `npm run build` | `sam build` (esbuild bundles the two Lambda entrypoints) |
| `npm run deploy` | `sam build && sam deploy` |

The full pre-commit check is `npm test && npm run typecheck && sam validate --lint`.

## Tests

`npm test` is hermetic: every suite runs `core/` against the in-memory
adapter (`tests/helpers.ts` → `makeApp()`), so no AWS account, network or
Docker is needed. Covered: discovery/426, enrollment and invites, biometric and
password login (lockout, revocation, disabling, session expiry), users and
devices, direct/group conversations and membership rules, wrapped keys and
rotation, messages (idempotency, paging, size cap, tombstones), media presign
authorization, calls, the WebSocket protocol, and the admin API.

`tests/dynamo-adapter.test.ts` is an opt-in conformance test of the DynamoDB
adapter against a real engine. It is skipped unless `DYNAMODB_LOCAL_ENDPOINT`
is set:

```bash
# once: download https://d1ni2b6xgvw0s0.cloudfront.net/v2.x/dynamodb_local_latest.tar.gz and unpack it
java -Djava.library.path=./DynamoDBLocal_lib -jar DynamoDBLocal.jar -inMemory -port 8000 &
DYNAMODB_LOCAL_ENDPOINT=http://127.0.0.1:8000 npm test -- tests/dynamo-adapter.test.ts
```

The same memory-adapter suites double as a conformance suite for any new
adapter: swap the `db` in `makeApp()` and they should still pass.

## Deploying

Everything is in **[docs/DEPLOY_AWS.md](../docs/DEPLOY_AWS.md)**. In short:

```bash
cd server
npm ci
cp samconfig.example.toml samconfig.toml   # set stack name, region, AdminKey, ServerName
sam build
sam deploy                                  # first time: sam deploy --guided
```

`sam validate --lint` and `sam build` need SAM CLI >= 1.130 (for
`nodejs22.x`). The stack creates only pay-per-use resources: API Gateway (HTTP
+ WebSocket), two Lambda functions outside any VPC, one DynamoDB table
(PAY_PER_REQUEST, TTL, one GSI), one private S3 bucket and two CloudWatch log
groups with short retention. No NAT Gateway, VPC, ElastiCache, EC2, Secrets
Manager, KMS keys, alarms, provisioned concurrency, PITR or custom domains —
grep `template.yaml` for any of those names and nothing matches.

After the first deploy, create an invite with the admin CLI
(`npm run admin -- login --api <apiUrl> --key <adminKey>`, then `npm run admin -- invite`); see
[docs/ADMIN_CLI.md](../docs/ADMIN_CLI.md).
