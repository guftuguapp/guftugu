# Deploying the Guftugu server on AWS

The reference server runs entirely on pay-per-use services: **API Gateway**
(HTTP + WebSocket), **Lambda** (arm64, Node 22), **DynamoDB on-demand**, **S3**
and **CloudWatch Logs** with short retention. There is nothing in the stack
that bills while idle — no VPC, no NAT Gateway, no ElastiCache, no EC2, no
Secrets Manager, no customer KMS keys, no alarms, no provisioned concurrency,
no point-in-time recovery, no custom domain. A family that sends a few
thousand messages a month should land inside the free tier or a few cents.

Everything is defined in `server/template.yaml` (AWS SAM) and deployed with
one command.

## Prerequisites

| Tool | Version | Notes |
|---|---|---|
| AWS account + IAM user/role | – | needs permission to create CloudFormation, Lambda, API Gateway, DynamoDB, S3, IAM roles, CloudWatch Logs |
| AWS CLI | v2 | `aws configure` with that user's keys, default region e.g. `us-west-2` |
| AWS SAM CLI | **>= 1.130** | earlier versions do not know the `nodejs22.x` runtime. `sam --version` |
| Node.js | 22 | `sam build` runs `npm install` + esbuild on your machine |
| Docker | **not required** | the build uses esbuild natively; no `--use-container` |

`esbuild` is a *production* dependency in `server/package.json` on purpose:
`sam build` installs with `--omit=dev`, so a dev dependency would be invisible
to it. It is bundled into nothing — Lambda only receives the ~20 KB output.

## 1. First deploy

```bash
cd server
npm ci
sam validate --lint          # template sanity check
sam build                    # bundles src/adapters/aws/lambda-{http,ws}.ts with core/
sam deploy --guided
```

`--guided` asks a few questions; answer like this:

| Prompt | Answer |
|---|---|
| Stack Name | `guftugu` |
| AWS Region | `us-west-2` (any region with API Gateway WebSockets) |
| Parameter Stage | `prod` |
| Parameter AdminKey | a random secret, **>= 16 chars** (`openssl rand -base64 24`). This is what the admin CLI sends as `X-Admin-Key`. |
| Parameter ServerName | the name members see, e.g. `Our family` |
| Parameter MaxUploadMB | `100` |
| Parameter LogRetentionDays | `14` |
| Confirm changes before deploy | `Y` |
| Allow SAM CLI IAM role creation | `Y` (the two Lambda execution roles) |
| Disable rollback | `N` |
| Save arguments to configuration file | `Y` → writes `samconfig.toml` (git-ignored — it holds the AdminKey) |

`server/samconfig.example.toml` shows the resulting file if you prefer to
write it by hand.

Deployment takes 2–3 minutes. When it finishes, CloudFormation prints the
**Outputs**:

| Output | Meaning |
|---|---|
| `ApiUrl` | base URL of the REST API — this is the server address you hand out |
| `WsUrl` | WebSocket URL (the app learns it from `/.well-known/guftugu`, you never type it) |
| `WellKnownUrl` | open it in a browser to confirm the server is alive |
| `TableName` / `BucketName` | your data (retained even if you delete the stack) |
| `HttpFunctionName` / `WsFunctionName` | for `sam logs` |

```bash
curl "$(aws cloudformation describe-stacks --stack-name guftugu \
  --query "Stacks[0].Outputs[?OutputKey=='WellKnownUrl'].OutputValue" --output text)"
# {"name":"Our family","protocolVersion":1,"apiUrl":"https://…","wsUrl":"wss://…","enrollment":"invite","serverTime":…}
```

## 2. Create the first invite

The admin CLI (`server/admin/cli.ts`, see [ADMIN_CLI.md](ADMIN_CLI.md)) talks
to the server over HTTPS with the AdminKey. Save the credentials once:

```bash
cd server
npm run admin -- login --api https://abc123.execute-api.us-west-2.amazonaws.com --key 'YOUR-ADMIN-KEY'
npm run admin -- invite --name "Ammi" --role admin
```

That prints an invite code (`GFT-XXXX-XXXX`), a `guftugu://join?...` link and
a QR code. Scan the QR with the Guftugu app (or type the server URL + code).
The first person you enrol should be an `admin` so they can create groups
from the phone. Invite everyone else with `--role member` (default).

Optional: a group everyone joins automatically when they enrol:

```bash
npm run admin -- create-group "Family" --auto-join
npm run admin -- invite --name "Abbu"            # will land in "Family"
```

## 3. Updating the server

```bash
cd server
git pull
npm ci
sam build && sam deploy       # uses the saved samconfig.toml; shows a changeset first
```

To change a parameter (e.g. rotate the admin key or rename the server):

```bash
sam deploy --parameter-overrides AdminKey='NEW-KEY' ServerName='New name'
```

Server name, ICE servers and upload cap can also be changed at runtime with
the admin CLI (`config set-name`, `config set-ice`, `config set-max-upload`)
without a redeploy; those values live in the DynamoDB `SETTINGS` item and
override the deploy parameters.

## 4. Logs

```bash
sam logs --stack-name guftugu --name guftugu-http --tail       # REST API
sam logs --stack-name guftugu --name guftugu-ws --tail         # WebSocket
sam logs --stack-name guftugu --name guftugu-http --start-time '30min ago' --filter 'ERROR'
```

Logs contain request ids, routes, status codes and error names only. The
server never logs tokens, keys, nonces, passwords or message content (which
it cannot read anyway). Retention is `LogRetentionDays` (default 14) so log
storage never accumulates.

## 5. Deleting the stack

```bash
sam delete --stack-name guftugu
```

This removes the APIs, functions, roles and log groups. The **DynamoDB table
and the S3 bucket are retained** (`DeletionPolicy: Retain`) so a mistaken
delete never loses messages. To really remove everything:

```bash
aws dynamodb delete-table --table-name guftugu-prod
aws s3 rb s3://<BucketName from the outputs> --force
```

Because the table has a fixed name (`<stack>-<stage>`), redeploying a stack
whose table you retained will fail until you delete or rename that table.

## 6. What it costs

Everything is metered per request / per GB; the idle cost is $0.

| Service | Billed for | Free tier (always free unless noted) |
|---|---|---|
| Lambda (arm64, 512 MB) | requests + GB-seconds | 1 M requests, 400 000 GB-s per month |
| API Gateway HTTP API | per million requests | 1 M/month for 12 months |
| API Gateway WebSocket | per million messages + connection-minutes | 1 M messages + 750 000 connection-minutes for 12 months |
| DynamoDB on-demand | read/write request units, storage | 25 GB storage + 2.5 M reads and 1 M writes/month (approx.) |
| S3 | storage, PUT/GET requests, egress | 5 GB, 20 000 GET, 2 000 PUT for 12 months |
| CloudWatch Logs | ingestion + storage | 5 GB ingestion/month |
| S3 (SAM deployment bucket) | a few MB of artifacts | negligible |

A realistic family (5 phones, always-connected WebSockets, a few hundred
messages and some photos a day) is typically **well under $1/month** after
the 12-month free tier, dominated by WebSocket connection-minutes and S3
storage for media.

Intentionally **not** used, because each bills per hour or per month
regardless of traffic:

- **NAT Gateway** (~$32/month + per GB) — the Lambdas run outside any VPC, so none is ever needed
- **VPC** endpoints / interface endpoints
- **ElastiCache / Redis** — the server uses a per-container memory cache instead
- **EC2 / Lightsail / Fargate** — nothing runs continuously
- **Secrets Manager** ($0.40/secret/month) — the admin key is a NoEcho stack parameter
- **Customer-managed KMS keys** ($1/key/month) — DynamoDB and S3 use AWS-owned default encryption
- **CloudWatch alarms / dashboards**
- **Provisioned concurrency**, DynamoDB provisioned capacity, **point-in-time recovery**
- **Route 53 hosted zones / custom domains / ACM** — the API Gateway URL is used directly

If you want an audit, `template.yaml` is ~250 lines; grep it for `VpcConfig`,
`NatGateway`, `ElastiCache`, `SecretsManager`, `KMS`, `Alarm`,
`ProvisionedConcurrency`, `PointInTimeRecovery` — none appear.

## 7. Adding TURN for calls

Calls are peer-to-peer WebRTC with public STUN by default. Phones behind
symmetric NAT or strict carrier-grade NAT may fail to connect; a TURN relay
fixes that but cannot be serverless (it must run continuously). Options:

- a managed TURN service (Twilio Network Traversal, Metered.ca, Cloudflare Calls TURN) with per-GB billing, or
- your own `coturn` on the cheapest VPS you can find.

Either way, publish the credentials without rebuilding the app:

```bash
npm run admin -- config set-ice '[
  {"urls":["stun:stun.l.google.com:19302"]},
  {"urls":["turn:turn.example.com:3478?transport=udp","turns:turn.example.com:5349"],
   "username":"guftugu","credential":"SECRET"}
]'
```

Phones fetch `GET /config` on every login, so the new ICE servers apply on
the next app start. TURN credentials are visible to every enrolled member
(they are needed on the phone); use a TURN server that supports short-lived
credentials or rotate them when someone leaves.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| `'nodejs22.x' runtime is not supported` during `sam build` | SAM CLI too old; upgrade to >= 1.130 |
| `Cannot find esbuild` | run `npm ci` in `server/` (esbuild is in `dependencies`) |
| `sam validate --lint` complains about `nodejs22.x` | same: old cfn-lint bundled with an old SAM CLI |
| 401 from the admin CLI | wrong key or trailing whitespace; `login` re-verifies before saving |
| App says "cannot reach server" | open `WellKnownUrl` in a browser; check `sam logs --name guftugu-http` |
| Calls never connect | add TURN (§7) |
| Stack update fails with "table already exists" | you deleted the stack earlier and the retained table kept the name; delete/rename it |
