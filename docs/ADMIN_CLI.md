# Admin CLI

`server/admin/cli.ts` is a small, cloud-agnostic command-line tool that
manages a Guftugu server through the admin endpoints of
[PROTOCOL.md §10](PROTOCOL.md#10-admin-x-admin-key). It works against any
server that implements the protocol — AWS, Supabase, a laptop — because it
only needs the API URL and the admin key.

It is deliberately dependency-light: Node 22's built-in `fetch` plus
`qrcode-terminal` for printing invite QR codes.

## Running

```bash
cd server
npm ci                              # once
npm run admin -- <command> [args]   # or: npx tsx admin/cli.ts <command> [args]
```

(The `--` after `npm run admin` passes the rest to the CLI.)

## Credentials

Resolution order, first match wins:

1. flags `--api URL --key KEY` on any command
2. environment `GUFTUGU_API_URL` and `GUFTUGU_ADMIN_KEY`
3. `~/.guftugu/admin.json` (`{ "apiUrl": "...", "adminKey": "..." }`, mode 600)

Save them once with:

```bash
npm run admin -- login --api https://abc123.execute-api.us-west-2.amazonaws.com --key 'YOUR-ADMIN-KEY'
```

`login` calls `/admin/stats` first, so a wrong key or URL is rejected instead
of saved. The admin key is never printed.

`ApiUrl` and the key come from the deploy: see [DEPLOY_AWS.md](DEPLOY_AWS.md).

## Commands

### Setup / status

| Command | What it does |
|---|---|
| `login --api URL --key KEY` | verify and save credentials to `~/.guftugu/admin.json` |
| `well-known` | print `GET /.well-known/guftugu` (no key needed) |
| `stats` | `{ users, devices, conversations, connections }` — live WebSocket count included |

### Invites

| Command | What it does |
|---|---|
| `invite [--name N] [--role member\|admin] [--for-user u_x] [--hours 72] [--join c_a,c_b]` | create an invite and print the code, the `guftugu://join?...` link and a terminal QR |
| `invites` | list invites with expiry and who used them |
| `revoke-invite CODE` | delete an unused invite |

- `--name` pre-fills the display name the person sees on the join screen (they can change it).
- `--role admin` lets that user create/rename groups from the phone. The first person you enrol should be an admin.
- `--for-user u_x` makes a **link code**: the new phone is added as another device of that existing user (no new account). Users can also make these themselves in the app.
- `--hours` overrides the default 72 h expiry.
- `--join c_a,c_b` adds the new user to those conversations on enrolment (in addition to any group with auto-join on).

Example:

```
$ npm run admin -- invite --name "Ammi" --role admin

  Invite code : GFT-7K3M-Q9XD
  Role        : admin
  Name        : Ammi
  Expires     : 2026-09-25 10:00:00 UTC
  Link        : guftugu://join?api=https%3A%2F%2Fabc123.execute-api.us-west-2.amazonaws.com&code=GFT-7K3M-Q9XD

  █▀▀▀▀▀█ ...   (scan with the app)
```

### Users and devices

| Command | What it does |
|---|---|
| `users` | list users (id, name, role, status, created, last seen) |
| `disable-user USER_ID` | the user can no longer log in or connect; their data stays |
| `enable-user USER_ID` | undo |
| `set-role USER_ID member\|admin` | change role |
| `devices USER_ID` | list that user's phones (model, OS, app version, biometric key, revoked) |
| `revoke-device USER_ID DEVICE_ID` | lost/stolen phone: its keys become useless and every conversation it was in rotates its key on the next send |

### Groups

Group names, member lists and avatars are server-visible metadata; message
content is end-to-end encrypted and never visible here.

| Command | What it does |
|---|---|
| `groups` | list conversations (id, type, name, member count, auto-join, last activity) |
| `create-group NAME [--auto-join] [--members u_a,u_b]` | create a group owned by the server; `--auto-join` adds every future enrolee |
| `rename-group CONV_ID NAME` | rename |
| `set-auto-join CONV_ID true\|false` | toggle auto-join |

Note: a group created here has no conversation key until a member's phone
opens it and distributes one (PROTOCOL.md §7). That happens automatically.

### Server config

| Command | What it does |
|---|---|
| `config` | print `ServerSettings` (name, ICE servers, upload cap, features, defaults) |
| `config set-name NAME` | name shown in the app and in `/.well-known/guftugu` |
| `config set-ice '<json IceServer[]>'` | ICE servers phones use for calls; add TURN here (see below) |
| `config set-max-upload MB` | maximum attachment size |

Changes apply to phones on their next login (`GET /config`); no app rebuild
and no redeploy.

```bash
npm run admin -- config set-ice '[{"urls":["stun:stun.l.google.com:19302"]},{"urls":["turn:turn.example.com:3478"],"username":"guftugu","credential":"SECRET"}]'
npm run admin -- config set-max-upload 250
```

## Errors and exit codes

- Any API error prints `error: HTTP <status> <code>: <message>` (the shape from PROTOCOL.md) and exits with code 1.
- Missing credentials, bad arguments and network failures also exit 1 with a one-line explanation.
- Success exits 0, so the CLI is safe to use in scripts (`--api`/`--key` flags or env vars for non-interactive use).
