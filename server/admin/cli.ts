#!/usr/bin/env -S npx tsx
/**
 * Guftugu admin CLI — talks to any Guftugu server over the admin HTTP API
 * (docs/PROTOCOL.md §10). Cloud-agnostic: only needs the API URL and the
 * admin key. Run with `npm run admin -- <command>` or `npx tsx admin/cli.ts`.
 *
 * Config resolution: --api/--key flags > GUFTUGU_API_URL / GUFTUGU_ADMIN_KEY
 * env > ~/.guftugu/admin.json (written by `login`).
 *
 * The admin key is never printed or logged.
 */
import { chmodSync, existsSync, mkdirSync, readFileSync, writeFileSync } from "node:fs";
import { homedir } from "node:os";
import { join } from "node:path";
import qrcode from "qrcode-terminal";
import type { Conversation, Invite, IceServer, ServerSettings, User, Device, WellKnown } from "../src/protocol/types.js";

// ---------- arg parsing ----------

interface Args {
  positional: string[];
  flags: Record<string, string | true>;
}

function parseArgs(argv: string[]): Args {
  const positional: string[] = [];
  const flags: Record<string, string | true> = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i] ?? "";
    if (a.startsWith("--")) {
      const eq = a.indexOf("=");
      if (eq > 0) {
        flags[a.slice(2, eq)] = a.slice(eq + 1);
        continue;
      }
      const name = a.slice(2);
      const next = argv[i + 1];
      if (next !== undefined && !next.startsWith("--")) {
        flags[name] = next;
        i++;
      } else {
        flags[name] = true;
      }
    } else {
      positional.push(a);
    }
  }
  return { positional, flags };
}

function flagStr(args: Args, name: string): string | undefined {
  const v = args.flags[name];
  return typeof v === "string" ? v : undefined;
}

function flagBool(args: Args, name: string): boolean {
  return args.flags[name] !== undefined;
}

function csv(v: string | undefined): string[] | undefined {
  if (!v) return undefined;
  return v
    .split(",")
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
}

// ---------- config ----------

interface AdminConfig {
  apiUrl: string;
  adminKey: string;
}

const CONFIG_DIR = join(homedir(), ".guftugu");
const CONFIG_FILE = join(CONFIG_DIR, "admin.json");

function readSavedConfig(): Partial<AdminConfig> {
  try {
    if (!existsSync(CONFIG_FILE)) return {};
    const parsed = JSON.parse(readFileSync(CONFIG_FILE, "utf8")) as Partial<AdminConfig>;
    return parsed && typeof parsed === "object" ? parsed : {};
  } catch {
    return {};
  }
}

function writeSavedConfig(cfg: AdminConfig): void {
  mkdirSync(CONFIG_DIR, { recursive: true, mode: 0o700 });
  writeFileSync(CONFIG_FILE, JSON.stringify(cfg, null, 2) + "\n", { mode: 0o600 });
  chmodSync(CONFIG_FILE, 0o600);
}

function trimSlash(url: string): string {
  return url.replace(/\/+$/, "");
}

function resolveConfig(args: Args, opts: { needKey: boolean }): AdminConfig {
  const saved = readSavedConfig();
  const apiUrl = flagStr(args, "api") ?? process.env.GUFTUGU_API_URL ?? saved.apiUrl;
  const adminKey = flagStr(args, "key") ?? process.env.GUFTUGU_ADMIN_KEY ?? saved.adminKey;
  if (!apiUrl) {
    fail("No API URL. Pass --api URL, set GUFTUGU_API_URL, or run: admin login --api URL --key KEY");
  }
  if (opts.needKey && !adminKey) {
    fail("No admin key. Pass --key KEY, set GUFTUGU_ADMIN_KEY, or run: admin login --api URL --key KEY");
  }
  return { apiUrl: trimSlash(apiUrl), adminKey: adminKey ?? "" };
}

// ---------- http ----------

class ApiError extends Error {
  constructor(
    public status: number,
    public code: string,
    message: string,
  ) {
    super(message);
  }
}

async function api<T>(cfg: AdminConfig, method: string, path: string, body?: unknown, opts: { admin?: boolean } = {}): Promise<T> {
  const headers: Record<string, string> = {
    Accept: "application/json",
    "X-Guftugu-Protocol": "1",
  };
  if (opts.admin !== false) headers["X-Admin-Key"] = cfg.adminKey;
  if (body !== undefined) headers["Content-Type"] = "application/json; charset=utf-8";

  let res: Response;
  try {
    res = await fetch(cfg.apiUrl + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch (err) {
    fail(`Could not reach ${cfg.apiUrl}: ${(err as Error).message}`);
  }
  const text = await res.text();
  let json: unknown = undefined;
  if (text) {
    try {
      json = JSON.parse(text);
    } catch {
      json = undefined;
    }
  }
  if (!res.ok) {
    const e = (json as { error?: { code?: string; message?: string } } | undefined)?.error;
    throw new ApiError(res.status, e?.code ?? "http_error", e?.message ?? (text || res.statusText));
  }
  return json as T;
}

// ---------- output helpers ----------

function fail(msg: string): never {
  console.error(`error: ${msg}`);
  process.exit(1);
}

function fmtTime(ms: number | null | undefined): string {
  if (ms === null || ms === undefined) return "-";
  return new Date(ms).toISOString().replace("T", " ").slice(0, 19);
}

function table(rows: Array<Record<string, string | number | boolean | null | undefined>>): void {
  if (rows.length === 0) {
    console.log("(none)");
    return;
  }
  const cols = Object.keys(rows[0] ?? {});
  const cell = (v: unknown) => (v === null || v === undefined ? "-" : String(v));
  const widths = cols.map((c) => Math.max(c.length, ...rows.map((r) => cell(r[c]).length)));
  const line = (vals: string[]) => vals.map((v, i) => v.padEnd(widths[i] ?? 0)).join("  ");
  console.log(line(cols));
  console.log(line(widths.map((w) => "-".repeat(w))));
  for (const r of rows) console.log(line(cols.map((c) => cell(r[c]))));
}

function printJson(v: unknown): void {
  console.log(JSON.stringify(v, null, 2));
}

function printInvite(inv: Invite): void {
  console.log("");
  console.log(`  Invite code : ${inv.code}`);
  console.log(`  Role        : ${inv.role}${inv.forUserId ? `  (link code for ${inv.forUserId})` : ""}`);
  if (inv.displayName) console.log(`  Name        : ${inv.displayName}`);
  if (inv.autoJoin.length) console.log(`  Auto-join   : ${inv.autoJoin.join(", ")}`);
  console.log(`  Expires     : ${fmtTime(inv.expiresAt)} UTC`);
  console.log(`  Link        : ${inv.link}`);
  console.log("");
  qrcode.generate(inv.link, { small: true }, (qr) => console.log(qr));
  console.log("Scan the QR with the Guftugu app, open the link on the phone, or type the code with the server URL.");
}

// ---------- commands ----------

const USAGE = `Guftugu admin CLI

usage: npm run admin -- <command> [args] [--api URL] [--key KEY]
   or: npx tsx admin/cli.ts <command> ...

setup
  login --api URL --key KEY               save credentials to ~/.guftugu/admin.json (chmod 600)
  well-known                              fetch /.well-known/guftugu (no key needed)
  stats                                   users / devices / conversations / live connections

invites
  invite [--name N] [--role member|admin] [--for-user u_x] [--hours 72] [--join c_a,c_b]
                                          create an invite; prints code, link and QR
  invites                                 list invites
  revoke-invite CODE                      delete an invite

users & devices
  users                                   list users
  disable-user USER_ID                    block a user (devices keep their keys but cannot log in)
  enable-user USER_ID                     re-enable a user
  set-role USER_ID member|admin           change a user's role
  devices USER_ID                         list a user's devices
  revoke-device USER_ID DEVICE_ID         revoke one phone (rotates that user's conversation keys)

groups
  groups                                  list conversations (server-visible metadata only)
  create-group NAME [--auto-join] [--members u_a,u_b]
  rename-group CONV_ID NAME
  set-auto-join CONV_ID true|false

config
  config                                  print server settings
  config set-name NAME                    server name shown in the app
  config set-ice '<json IceServer[]>'     e.g. '[{"urls":["turn:turn.example.com:3478"],"username":"u","credential":"p"}]'
  config set-max-upload MB                attachment size cap

Credentials: flags > GUFTUGU_API_URL / GUFTUGU_ADMIN_KEY env > ~/.guftugu/admin.json
`;

type Command = (args: Args) => Promise<void>;

const commands: Record<string, Command> = {
  async help() {
    console.log(USAGE);
  },

  async login(args) {
    const apiUrl = flagStr(args, "api");
    const adminKey = flagStr(args, "key");
    if (!apiUrl || !adminKey) fail("usage: login --api URL --key KEY");
    if (adminKey.length < 16) fail("admin key must be at least 16 characters");
    const cfg: AdminConfig = { apiUrl: trimSlash(apiUrl), adminKey };
    // Verify before saving so a typo does not get persisted.
    const stats = await api<{ users: number }>(cfg, "GET", "/admin/stats");
    writeSavedConfig(cfg);
    console.log(`Saved ${CONFIG_FILE} (server has ${stats.users} user(s)).`);
  },

  async "well-known"(args) {
    const cfg = resolveConfig(args, { needKey: false });
    printJson(await api<WellKnown>(cfg, "GET", "/.well-known/guftugu", undefined, { admin: false }));
  },

  async stats(args) {
    const cfg = resolveConfig(args, { needKey: true });
    printJson(await api(cfg, "GET", "/admin/stats"));
  },

  async invite(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const role = flagStr(args, "role");
    if (role !== undefined && role !== "member" && role !== "admin") fail("--role must be member or admin");
    const hours = flagStr(args, "hours");
    const expiresInHours = hours === undefined ? undefined : Number(hours);
    if (expiresInHours !== undefined && (!Number.isFinite(expiresInHours) || expiresInHours <= 0)) fail("--hours must be a positive number");
    const body = {
      displayName: flagStr(args, "name"),
      role,
      forUserId: flagStr(args, "for-user"),
      expiresInHours,
      autoJoin: csv(flagStr(args, "join")),
    };
    printInvite(await api<Invite>(cfg, "POST", "/admin/invites", body));
  },

  async invites(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const res = await api<{ items: Invite[] }>(cfg, "GET", "/admin/invites");
    table(
      res.items.map((i) => ({
        code: i.code,
        role: i.role,
        name: i.displayName ?? "-",
        forUser: i.forUserId ?? "-",
        expires: fmtTime(i.expiresAt),
        used: i.usedAt ? `${fmtTime(i.usedAt)} by ${i.usedByUserId ?? "?"}` : "-",
      })),
    );
  },

  async "revoke-invite"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const code = args.positional[0];
    if (!code) fail("usage: revoke-invite CODE");
    await api(cfg, "DELETE", `/admin/invites/${encodeURIComponent(code)}`);
    console.log(`Invite ${code} revoked.`);
  },

  async users(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const res = await api<{ items: User[] }>(cfg, "GET", "/admin/users");
    table(
      res.items.map((u) => ({
        userId: u.userId,
        name: u.displayName,
        role: u.role,
        status: u.status,
        created: fmtTime(u.createdAt),
        lastSeen: fmtTime(u.lastSeenAt),
      })),
    );
  },

  async "disable-user"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const id = args.positional[0];
    if (!id) fail("usage: disable-user USER_ID");
    const u = await api<User>(cfg, "PATCH", `/admin/users/${encodeURIComponent(id)}`, { status: "disabled" });
    console.log(`${u.displayName} (${u.userId}) is now ${u.status}.`);
  },

  async "enable-user"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const id = args.positional[0];
    if (!id) fail("usage: enable-user USER_ID");
    const u = await api<User>(cfg, "PATCH", `/admin/users/${encodeURIComponent(id)}`, { status: "active" });
    console.log(`${u.displayName} (${u.userId}) is now ${u.status}.`);
  },

  async "set-role"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const [id, role] = args.positional;
    if (!id || (role !== "member" && role !== "admin")) fail("usage: set-role USER_ID member|admin");
    const u = await api<User>(cfg, "PATCH", `/admin/users/${encodeURIComponent(id)}`, { role });
    console.log(`${u.displayName} (${u.userId}) is now ${u.role}.`);
  },

  async devices(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const id = args.positional[0];
    if (!id) fail("usage: devices USER_ID");
    const res = await api<{ items: Device[] }>(cfg, "GET", `/admin/users/${encodeURIComponent(id)}/devices`);
    table(
      res.items.map((d) => ({
        deviceId: d.deviceId,
        name: d.name,
        model: d.model ?? "-",
        os: d.os ?? "-",
        app: d.appVersion ?? "-",
        biometric: d.hasBiometricKey,
        enrolled: fmtTime(d.enrolledAt),
        lastSeen: fmtTime(d.lastSeenAt),
        revoked: d.revokedAt ? fmtTime(d.revokedAt) : "-",
      })),
    );
  },

  async "revoke-device"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const [userId, deviceId] = args.positional;
    if (!userId || !deviceId) fail("usage: revoke-device USER_ID DEVICE_ID");
    await api(cfg, "DELETE", `/admin/users/${encodeURIComponent(userId)}/devices/${encodeURIComponent(deviceId)}`);
    console.log(`Device ${deviceId} revoked. Conversation keys will rotate on the next send.`);
  },

  async groups(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const res = await api<{ items: Conversation[] }>(cfg, "GET", "/admin/conversations");
    table(
      res.items.map((c) => ({
        convId: c.convId,
        type: c.type,
        name: c.name ?? "-",
        members: c.members.length,
        autoJoin: c.autoJoin ?? false,
        created: fmtTime(c.createdAt),
        lastMessage: fmtTime(c.lastMessageAt),
      })),
    );
  },

  async "create-group"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const name = args.positional[0];
    if (!name) fail("usage: create-group NAME [--auto-join] [--members u_a,u_b]");
    const c = await api<Conversation>(cfg, "POST", "/admin/conversations", {
      name,
      autoJoin: flagBool(args, "auto-join") ? true : undefined,
      memberIds: csv(flagStr(args, "members")),
    });
    console.log(`Created group "${c.name}" (${c.convId}) with ${c.members.length} member(s)${c.autoJoin ? ", auto-join on" : ""}.`);
  },

  async "rename-group"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const [id, ...rest] = args.positional;
    const name = rest.join(" ");
    if (!id || !name) fail("usage: rename-group CONV_ID NAME");
    const c = await api<Conversation>(cfg, "PATCH", `/admin/conversations/${encodeURIComponent(id)}`, { name });
    console.log(`Renamed ${c.convId} to "${c.name}".`);
  },

  async "set-auto-join"(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const [id, v] = args.positional;
    if (!id || (v !== "true" && v !== "false")) fail("usage: set-auto-join CONV_ID true|false");
    const c = await api<Conversation>(cfg, "PATCH", `/admin/conversations/${encodeURIComponent(id)}`, { autoJoin: v === "true" });
    console.log(`${c.convId} auto-join: ${c.autoJoin ?? false}.`);
  },

  async config(args) {
    const cfg = resolveConfig(args, { needKey: true });
    const [sub, ...rest] = args.positional;
    if (!sub) {
      printJson(await api<ServerSettings>(cfg, "GET", "/admin/config"));
      return;
    }
    let patch: Partial<ServerSettings>;
    switch (sub) {
      case "set-name": {
        const name = rest.join(" ").trim();
        if (!name) fail("usage: config set-name NAME");
        patch = { serverName: name };
        break;
      }
      case "set-ice": {
        const raw = rest.join(" ").trim();
        if (!raw) fail("usage: config set-ice '<json array of IceServer>'");
        let servers: unknown;
        try {
          servers = JSON.parse(raw);
        } catch {
          fail("set-ice: value is not valid JSON");
        }
        if (!Array.isArray(servers) || !servers.every((s) => s && typeof s === "object" && Array.isArray((s as IceServer).urls))) {
          fail('set-ice: expected an array like [{"urls":["stun:stun.l.google.com:19302"]}]');
        }
        patch = { iceServers: servers as IceServer[] };
        break;
      }
      case "set-max-upload": {
        const mb = Number(rest[0]);
        if (!Number.isFinite(mb) || mb <= 0) fail("usage: config set-max-upload MB");
        patch = { maxUploadBytes: Math.floor(mb * 1024 * 1024) };
        break;
      }
      default:
        fail(`unknown config subcommand "${sub}" (set-name | set-ice | set-max-upload)`);
    }
    printJson(await api<ServerSettings>(cfg, "PUT", "/admin/config", patch));
  },
};

// ---------- main ----------

async function main(): Promise<void> {
  const argv = process.argv.slice(2);
  const args = parseArgs(argv);
  const name = args.positional.shift();
  if (!name || name === "help" || flagBool(args, "help")) {
    console.log(USAGE);
    process.exit(name ? 0 : 1);
  }
  const cmd = commands[name];
  if (!cmd) {
    console.error(`error: unknown command "${name}"\n`);
    console.log(USAGE);
    process.exit(1);
  }
  await cmd(args);
}

main().catch((err: unknown) => {
  if (err instanceof ApiError) {
    console.error(`error: HTTP ${err.status} ${err.code}: ${err.message}`);
  } else {
    console.error(`error: ${(err as Error)?.message ?? String(err)}`);
  }
  process.exit(1);
});
