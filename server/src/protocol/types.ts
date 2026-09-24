/**
 * Guftugu Protocol v1 — wire types.
 *
 * This file mirrors docs/PROTOCOL.md §13 exactly. It is the single source of
 * truth for what crosses the network; the Android app's Kotlin DTOs are a
 * transliteration of these types. Keep the three in sync.
 *
 * Nothing in here may import from core/ or adapters/.
 */

export const PROTOCOL_VERSION = 1;

// ---------- identity & auth ----------

export interface Session {
  token: string;
  expiresAt: number;
}

export type UserRole = "member" | "admin";
export type UserStatus = "active" | "disabled";

export interface User {
  userId: string;
  displayName: string;
  avatarKey?: string | null;
  role: UserRole;
  status: UserStatus;
  createdAt: number;
  lastSeenAt?: number | null;
}

/** Public part of a device: what other members need to wrap keys and verify signatures. */
export interface PublicDevice {
  deviceId: string;
  userId: string;
  name: string;
  /** base64url SPKI DER, P-256, ECDSA — proves the installed app instance, signs key wraps. */
  devicePublicKey: string;
  /** base64url SPKI DER, P-256, ECDH — receives wrapped conversation keys. */
  encryptionPublicKey: string;
  enrolledAt: number;
}

export interface Device extends PublicDevice {
  model?: string | null;
  os?: string | null;
  appVersion?: string | null;
  hasBiometricKey: boolean;
  lastSeenAt?: number | null;
  revokedAt?: number | null;
}

export interface DeviceInfo {
  name: string;
  model?: string | null;
  os?: string | null;
  appVersion?: string | null;
}

export interface EnrollRequest {
  inviteCode: string;
  displayName?: string;
  password?: string;
  devicePublicKey: string;
  authPublicKey?: string | null;
  encryptionPublicKey: string;
  device: DeviceInfo;
}

export interface AuthChallengeRequest {
  deviceId: string;
}

export interface AuthChallengeResponse {
  nonce: string;
  expiresAt: number;
}

export type AuthMethod = "biometric" | "password" | "device";

export interface AuthVerifyRequest {
  deviceId: string;
  nonce: string;
  method: AuthMethod;
  /** base64url ECDSA P-256/SHA-256 signature, raw r||s (DER also accepted). */
  signature: string;
  password?: string;
}

/** The caller's own account: `User` plus private flags (never sent to other members). */
export interface Me extends User {
  /** Whether a backup password is set (fingerprint-only accounts have none). */
  hasPassword: boolean;
}

/** `PUT /me/device/auth-key`: turn on / replace fingerprint unlock for the calling device. */
export interface RegisterAuthKeyRequest {
  /** From `POST /auth/challenge` for this device; single use. */
  nonce: string;
  /** base64url SPKI DER, P-256, biometric-gated Keystore key. */
  authPublicKey: string;
  /** Signature with the device key over `"guftugu-authkey:v1:" + deviceId + ":" + nonce + ":" + authPublicKey`. */
  deviceSignature: string;
  /** Signature with the NEW auth key over the same message (proof of possession). */
  authSignature: string;
}

/** `PUT /me/password`: `currentPassword` is required only when a password is already set. */
export interface ChangePasswordRequest {
  currentPassword?: string | null;
  newPassword: string;
}

export interface AuthResponse {
  user: Me;
  device: Device;
  session: Session;
  config: ClientConfig;
}

/** admin = created by the server admin (family circle); link = add another of my phones; friend/group = a user's invite. */
export type InviteKind = "admin" | "link" | "friend" | "group";

export interface Invite {
  kind?: InviteKind;
  /** For "group" invites: the group the invitee joins. */
  convId?: string | null;
  /** userId of the person who created a friend/group invite. */
  invitedBy?: string | null;
  code: string;
  link: string;
  displayName?: string | null;
  role: UserRole;
  forUserId?: string | null;
  autoJoin: string[];
  createdAt: number;
  expiresAt: number;
  usedAt?: number | null;
  usedByUserId?: string | null;
}

// ---------- friends ----------

/** One person in my list: a friend (since != null) and/or someone I blocked. */
export interface Friend {
  user: User;
  since: number | null;
  blocked: boolean;
}

export interface CreateInviteRequest {
  kind: "friend" | "group";
  convId?: string;
  expiresInHours?: number;
}

export interface RedeemInviteRequest {
  code: string;
}

export interface RedeemInviteResponse {
  friend: User;
  conversation: Conversation | null;
}

// ---------- conversations & keys ----------

export type ConversationType = "direct" | "group";
export type MemberRole = "owner" | "member";

export interface Member {
  userId: string;
  role: MemberRole;
  joinedAt: number;
  lastReadMsgId?: string | null;
}

export interface Conversation {
  convId: string;
  type: ConversationType;
  name?: string | null;
  avatarKey?: string | null;
  createdBy: string;
  createdAt: number;
  autoJoin?: boolean;
  members: Member[];
  currentKeyId?: string | null;
  keyRotationRequired: boolean;
  lastMsgId?: string | null;
  lastMessageAt?: number | null;
}

/** AES-256-GCM ciphertext the server cannot open. */
export interface Envelope {
  v: 1;
  keyId: string;
  /** base64url, 12 bytes */
  iv: string;
  /** base64url, ciphertext + 16-byte tag */
  ct: string;
}

/** A conversation key encrypted (ECIES) for exactly one device and signed by the sender device. */
export interface WrappedKey {
  keyId: string;
  convId: string;
  recipientDeviceId: string;
  senderDeviceId: string;
  ephemeralPublicKey: string;
  iv: string;
  ciphertext: string;
  signature: string;
  createdAt: number;
}

export interface KeysResponse {
  currentKeyId: string | null;
  items: WrappedKey[];
}

export interface KeyRecipient extends PublicDevice {
  hasCurrentKey: boolean;
}

export interface KeyRecipientsResponse {
  currentKeyId: string | null;
  keyRotationRequired: boolean;
  devices: KeyRecipient[];
}

export interface PostKeysRequest {
  keyId: string;
  wrapped: WrappedKey[];
}

// ---------- messages ----------

export type MessageKind = "e2e" | "call" | "system";

export type SystemEvent = "member_added" | "member_removed" | "member_left" | "renamed" | "created";

export interface SystemInfo {
  event: SystemEvent;
  userIds?: string[];
  text?: string;
}

export type CallOutcome = "answered" | "missed" | "rejected" | "unreachable" | "cancelled";

export interface CallInfo {
  callId: string;
  type: CallType;
  outcome: CallOutcome;
  durationMs?: number | null;
}

export interface Message {
  msgId: string;
  convId: string;
  senderId: string;
  senderDeviceId?: string | null;
  clientId?: string | null;
  kind: MessageKind;
  envelope?: Envelope | null;
  call?: CallInfo | null;
  system?: SystemInfo | null;
  sentAt: number;
  createdAt: number;
  deletedAt?: number | null;
}

export interface SendMessageRequest {
  clientId: string;
  sentAt: number;
  envelope: Envelope;
}

export interface Page<T> {
  items: T[];
  hasMore: boolean;
}

// ---------- decrypted content (never seen by the server; shared here so tests and the CLI can build it) ----------

export type ContentType = "text" | "image" | "video" | "audio" | "file";

export interface Attachment {
  key: string;
  mime: string;
  sizeBytes: number;
  fileName?: string | null;
  width?: number | null;
  height?: number | null;
  durationMs?: number | null;
  enc: "aes-256-gcm-chunked-v1";
  fileKey: string;
  baseIv: string;
  chunkSize: number;
  sha256: string;
  thumbKey?: string | null;
  thumbSha256?: string | null;
}

export interface Content {
  type: ContentType;
  text?: string | null;
  attachment?: Attachment | null;
  replyTo?: string | null;
}

export type Signal =
  | { kind: "offer" | "answer"; sdp: string }
  | { kind: "ice"; candidate: string; sdpMid: string; sdpMLineIndex: number };

// ---------- media ----------

export type MediaKind = "attachment" | "thumbnail" | "avatar";

export interface UploadUrlRequest {
  convId?: string;
  kind: MediaKind;
  mime: string;
  sizeBytes: number;
}

export interface UploadUrlResponse {
  key: string;
  uploadUrl: string;
  method: "PUT";
  headers: Record<string, string>;
  expiresAt: number;
}

export interface DownloadUrlResponse {
  downloadUrl: string;
  expiresAt: number;
}

// ---------- calls ----------

export type CallType = "audio" | "video";
export type CallState = "ringing" | "active" | "ended";
export type EndReason =
  | "hangup"
  | "timeout"
  | "failed"
  | "cancelled"
  | "rejected"
  | "unreachable"
  | "answered_elsewhere";

export interface Call {
  callId: string;
  convId: string;
  type: CallType;
  callerId: string;
  callerDeviceId: string;
  calleeId: string;
  calleeDeviceId?: string | null;
  state: CallState;
  createdAt: number;
  answeredAt?: number | null;
  endedAt?: number | null;
  endReason?: string | null;
}

// ---------- config ----------

export interface IceServer {
  urls: string[];
  username?: string;
  credential?: string;
}

export interface ClientConfig {
  serverName: string;
  iceServers: IceServer[];
  maxUploadBytes: number;
  features: { calls: boolean; media: boolean };
}

export interface ServerSettings extends ClientConfig {
  defaultAutoJoin: boolean;
  inviteTtlHours: number;
}

export interface WellKnown {
  name: string;
  protocolVersion: number;
  apiUrl: string;
  wsUrl: string;
  enrollment: "invite";
  serverTime: number;
}

// ---------- errors ----------

export type ErrorCode =
  | "invalid_request"
  | "unauthorized"
  | "forbidden"
  | "not_found"
  | "conflict"
  | "payload_too_large"
  | "upgrade_required"
  | "rate_limited"
  | "internal"
  | "invite_invalid"
  | "invite_expired"
  | "challenge_invalid"
  | "bad_signature"
  | "bad_credentials"
  | "device_revoked"
  | "user_disabled"
  | "password_locked"
  | "blocked";

export interface ErrorBody {
  error: { code: ErrorCode; message: string };
}

// ---------- websocket frames ----------

export type ServerEvent =
  | { type: "hello"; userId: string; deviceId: string; connectionId: string; serverTime: number }
  | { type: "pong"; serverTime: number }
  | { type: "message.new"; message: Message }
  | { type: "message.deleted"; convId: string; msgId: string; deletedAt: number }
  | { type: "conversation.updated"; conversation: Conversation }
  | { type: "conversation.keys"; convId: string; keyId: string }
  | { type: "conversation.read"; convId: string; userId: string; msgId: string; at: number }
  | { type: "typing"; convId: string; userId: string; at: number }
  | { type: "user.updated"; user: User }
  | { type: "call.invite"; call: Call; caller: User }
  | { type: "call.answered"; call: Call }
  | { type: "call.ended"; call: Call; reason: string }
  | { type: "call.signal"; callId: string; fromDeviceId: string; envelope: Envelope }
  | { type: "error"; code: ErrorCode; message: string };

export type ClientEvent =
  | { type: "hello" }
  | { type: "ping" }
  | { type: "typing"; convId: string }
  | { type: "call.signal"; callId: string; envelope: Envelope };
