# 14 — API Contracts

Status: **Proposed contracts. None of these endpoints are implemented.**

## 1. Conventions

| Item | Rule |
|---|---|
| Base URL | `https://api.<domain>/api/v1` (public listener, port 3000 behind Traefik) |
| Admin base | `http://localhost:3001/admin` (localhost listener via SSH tunnel; HTML pages + form posts) |
| Format | JSON, UTF-8. Field names `camelCase`. Timestamps ISO-8601 UTC (`2026-10-24T10:15:00Z`) |
| Auth header | `Authorization: Bearer <access token>` |
| IDs | UUID strings. **Never trusted for identity** — the acting user is always taken from the session |
| Validation | Fastify JSON Schema per route; unknown fields rejected (`additionalProperties: false`) |
| Body limit | 64 KB default; 16 KB on chat routes |
| Request ID | Every response has `X-Request-Id`; also included in error bodies |
| Versioning | Path prefix `/v1`; breaking changes → `/v2` |

### Error format

```json
{ "error": { "code": "QR_INVALID_OR_EXPIRED", "message": "This code is no longer valid.", "requestId": "01J…" } }
```

No stack traces, SQL errors or internal names are ever returned.

| HTTP | Codes |
|---|---|
| 400 | `VALIDATION_FAILED` (with `details: [{path, rule}]`), `BAD_REQUEST` (unreadable body, e.g. malformed JSON) |
| 401 | `UNAUTHENTICATED`, `INVALID_CREDENTIALS`, `TOKEN_EXPIRED`, `SESSION_REVOKED` |
| 403 | `FORBIDDEN`, `ACCOUNT_DISABLED`, `PASSWORD_CHANGE_REQUIRED`, `UPGRADE_REQUIRED` |
| 404 | `NOT_FOUND` (also used when the resource exists but the caller is not a member) |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `CONFLICT` (generic), `ALREADY_CONNECTED`, `REQUEST_ALREADY_PENDING`, `REQUEST_NOT_PENDING`, `CONVERSATION_CLEARED`, `CALL_IN_PROGRESS`, `CALL_NOT_ACTIVE`, `SHARING_DISABLED`, `PEER_NOT_IN_CHAT` (only if D-01 = option C) |
| 410 | `QR_INVALID_OR_EXPIRED` |
| 413 | `PAYLOAD_TOO_LARGE` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` (request body is not JSON) |
| 422 | `MESSAGE_TOO_LONG`, `WEAK_PASSWORD` |
| 429 | `RATE_LIMITED` (+ `Retry-After` header) |
| 500 / 503 | `INTERNAL_ERROR`, `SERVICE_UNAVAILABLE`, `MEDIA_UNAVAILABLE` |

Common to every authenticated route: `401` if token missing/expired/revoked; `403 ACCOUNT_DISABLED`;
`403 PASSWORD_CHANGE_REQUIRED` for restricted sessions; `403 UPGRADE_REQUIRED` if
`X-App-Version-Code` < `app.min_supported_version_code`; `429`. These are not repeated below.

## 2. Authentication

### POST /auth/login
- **Auth:** none. **Rate limit:** 10/min per IP; account backoff ([04 §5](04-accounts-and-authentication.md#5-brute-force-protection)).
- **Request:** `{ "username": string(3..32), "password": string(1..128), "device": { "model": string(..64), "appVersionCode": int } }`
- **Response 200:** `{ "accessToken", "accessExpiresAt", "refreshToken", "user": { "id", "username", "mustChangePassword": bool } }`
- **Errors:** `401 INVALID_CREDENTIALS` (unknown user or wrong password — identical), `403 ACCOUNT_DISABLED`, `429`.
- **Side effects:** creates session (restricted if `mustChangePassword`); revokes the user's other sessions (D-04); resets failure counter.
- **Audit:** `auth.login_succeeded` / `auth.login_failed` / `auth.account_throttled`.

### POST /auth/refresh
- **Auth:** none (refresh token in body). **Rate limit:** 30/min per IP.
- **Request:** `{ "refreshToken": string }`
- **Response 200:** same shape as login (new access + new refresh token).
- **Errors:** `401 SESSION_REVOKED` (unknown, expired, or reused token).
- **Side effects:** rotation; reuse of a previous token revokes the session. **Audit:** `auth.refresh_reuse_detected` on reuse.

### POST /auth/logout
- **Auth:** user (restricted allowed). **Request:** `{}`. **Response:** `204`.
- **Side effects:** revokes session, clears FCM token, closes WebSocket. **Audit:** `auth.logout`.

### POST /auth/change-password
- **Auth:** user (restricted allowed). **Rate limit:** 5/15 min per user.
- **Request:** `{ "currentPassword": string, "newPassword": string(12..128) }`
- **Response 200:** new token pair (current session continues, unrestricted).
- **Errors:** `401 INVALID_CREDENTIALS`, `422 WEAK_PASSWORD`.
- **Side effects:** new hash; `mustChangePassword=false`; all **other** sessions revoked. **Audit:** `user.password_changed`.

### GET /me
- **Auth:** user. **Response:** `{ "id", "username", "mustChangePassword", "connection": { "id", "peer": { "id", "username" }, "conversationId", "since" } | null, "settings": { "maxVideoQuality" } }`

## 3. Devices and app version

### PUT /devices/push-token
- **Auth:** user. **Request:** `{ "fcmToken": string(1..4096) }`. **Response:** `204`.
- **Side effects:** stored on the current session; removed from any other session holding the same token.

### DELETE /devices/push-token
- **Auth:** user. **Response:** `204`. Clears the current session's token.

### GET /app/version
- **Auth:** none. **Rate limit:** 30/min per IP.
- **Response:** `{ "minSupportedVersionCode", "latestVersionCode", "latestApkSha256" }`.

## 4. Connection (QR)

### POST /connection-qr
- **Auth:** user. **Rate limit:** 6/min, 60/h per user.
- **Request:** `{}`. **Response 201:** `{ "token": string(43), "qrPayload": "vaultchat://connect?v=1&t=…", "expiresAt" }`
- **Errors:** `409 ALREADY_CONNECTED`.
- **Side effects:** supersedes previous unconsumed token. **Audit:** none (high volume, low value).

### POST /connection-requests
- **Auth:** user. **Rate limit:** 10/min per user.
- **Request:** `{ "token": string(43, base64url) }`
- **Response 201:** `{ "id", "status": "pending", "expiresAt" }`
- **Errors:** `410 QR_INVALID_OR_EXPIRED`, `409 ALREADY_CONNECTED`, `409 REQUEST_ALREADY_PENDING`.
- **Side effects:** consumes token; notifies owner (WS `connection_request.created`, FCM `conn_req`). **Audit:** `connection.request_created`.

### GET /connection-requests?direction=incoming|outgoing&status=pending
- **Auth:** user. **Response:** `{ "items": [ { "id", "direction", "peer": { "username" }, "status", "createdAt", "expiresAt" } ] }`. Only requests where the caller is requester or owner.

### POST /connection-requests/{id}/accept · /reject
- **Auth:** user, must be the **owner**. **Request:** `{}`.
- **Response 200:** accept → `{ "connectionId", "conversationId" }`; reject → `{ "status": "rejected" }`.
- **Errors:** `404 NOT_FOUND` (not owner or unknown), `409 REQUEST_NOT_PENDING`, `409 ALREADY_CONNECTED`.
- **Side effects:** accept creates `connections` + `conversations` + 2 `conversation_participants`; cancels other pending requests; notifies both. **Audit:** `connection.accepted` / `connection.rejected`.

### POST /connection-requests/{id}/cancel
- **Auth:** user, must be the **requester**. **Response:** `{ "status": "cancelled" }`. **Errors:** `404`, `409 REQUEST_NOT_PENDING`.

### GET /connections
- **Auth:** user. **Response:** `{ "items": [ { "id", "peer": { "id", "username" }, "conversationId", "createdAt" } ] }` (0 or 1 item). No delete endpoint exists.

## 5. Chat

All routes require the caller to be a participant of `{id}` (else `404`).

### POST /conversations/{id}/enter
- **Request:** `{}`. **Response 200:** `{ "leaseId", "leaseTtlSeconds": 60, "heartbeatSeconds": 20, "epoch", "peerInChat": bool }`
- **Side effects:** sets presence lease; notifies peer `chat.presence`.

### POST /conversations/{id}/leave
- **Request:** `{ "leaseId": uuid|null, "leaveId": uuid }`. **Response 200:** `{ "epoch", "clearedThroughSeq" }`.
- **Side effects:** the clear transaction ([07 §3.2](07-chat-and-disappearing-messages.md#32-leaving-the-chat-the-clear--post-apiv1conversationsidleave)); WS `conversation.cleared` to both. Idempotent by `leaveId`.
- **Audit:** none per clear (would leak chat-activity timing); a daily count is kept in memory metrics only.

### GET /conversations/{id}/messages?afterSeq={n}&epoch={e}&limit={1..200}
- **Response 200:** `{ "epoch", "clearedThroughSeq", "cleared": bool, "items": [ { "id", "seq", "senderId", "text", "createdAt" } ], "hasMore": bool }`
- `cleared: true` means the client's `epoch` is stale and it must discard local state.

### POST /conversations/{id}/messages
- **Rate limit:** 30/min per user. **Body limit:** 16 KB.
- **Request:** `{ "clientMessageId": uuid, "epoch": int, "text": string(1..4000 code points, trimmed) }`
- **Response 201:** `{ "id", "seq", "epoch", "createdAt" }` (200 with the same body on idempotent retry).
- **Errors:** `409 CONVERSATION_CLEARED` (body includes current `epoch`), `422 MESSAGE_TOO_LONG`, `409 PEER_NOT_IN_CHAT` (only if D-01 = C).
- **Side effects:** WS `message.created` to both; FCM `msg` to recipient if no socket (subject to D-01).

### POST /conversations/{id}/read
- **Request:** `{ "seq": int }` (monotonic; lower values ignored). **Response:** `204`. WS `message.read` to peer.

## 6. Calls

All routes require the caller to be the caller or callee of `{id}` (else `404`).

### POST /calls
- **Request:** `{ "connectionId": uuid, "kind": "video"|"voice" }`
- **Response 201:** `{ "callId", "status": "ringing", "livekitUrl": "wss://rtc.<domain>", "livekitToken", "maxVideoQuality", "ringTimeoutSeconds" }`
- **Errors:** `404` (not a member of the connection), `409 CALL_IN_PROGRESS` (the app then reads `GET /calls/current`), `503 MEDIA_UNAVAILABLE` (LiveKit not configured or unreachable).
- **Side effects:** creates LiveKit room (max 2), call row; WS `call.incoming` + FCM `call` to callee. **Audit:** none (calls table is the record).
- **Rate limit:** 10/min per user.

### POST /calls/{id}/accept
- **Callee only.** **Response 200:** `{ "livekitUrl", "livekitToken", "maxVideoQuality" }`. **Errors:** `409 CALL_NOT_ACTIVE` (already cancelled/missed/answered).
- **Side effects:** `accepted`; WS `call.accepted` to caller.

### POST /calls/{id}/decline
- **Callee only.** `{}` → `200 { "status": "declined" }`; room deleted; WS `call.declined`.

### POST /calls/{id}/cancel
- **Caller only, while ringing.** → `cancelled`; room deleted; WS + FCM `call_end` to callee.

### POST /calls/{id}/end
- **Either participant.** **Request:** `{ "reason": "hangup"|"network_lost"|"error" }` → `200 { "status": "ended" }`. Idempotent.
- **Side effects:** room deleted; WS `call.ended` to both.

### POST /calls/{id}/token
- Only while `accepted`/`active` (caller also while `ringing`). Returns a fresh `livekitToken`. **Rate limit:** 10/min per user.

### GET /calls/current
- Returns `{ "call": <call> | null }` — the user's ringing/accepted/active call. Polled by the app while open until push notifications exist.

### GET /calls/{id}
- **Response:** `{ "id", "kind", "status", "callerId", "calleeId", "createdAt", "acceptedAt", "endedAt", "endReason" }`.

### POST /calls/{id}/diagnostics
- **Request:** `{ "summary": object (schema-validated, ≤ 8 KB) }` once per user per call. **Response:** `204`.

### POST /internal/livekit/webhook
- **Auth:** LiveKit-signed `Authorization` header verified with `WebhookReceiver`; reachable only on the
  internal Docker network (Traefik does not route `/internal/*`).
- Handles `participant_joined`, `participant_left`, `room_finished`. Unknown rooms ignored. **Response:** `200`.

## 7. Location sharing (proposed — only if D-06 approved)

See [11 §5](11-location-sharing-proposal.md#5-api-proposed): `GET /location-sharing`, `PUT /location-sharing`,
`PUT /location-sharing/current`. Admin: `GET /admin/users/{id}/location`.

## 8. Health

| Route | Auth | Response |
|---|---|---|
| `GET /healthz` | none | `200 {"status":"ok"}` if the process is running (liveness) |
| `GET /readyz` | none | `200` if DB reachable and migrations current; `503` otherwise. No version or dependency details exposed publicly |

Detailed health (LiveKit reachability, FCM config, disk, backups) is shown only in the Admin Console.

## 9. Admin Console routes (localhost listener)

Server-rendered pages; POST forms with CSRF token; session cookie. All require admin session + TOTP-completed login.

| Method & route | Action | Audit |
|---|---|---|
| `GET/POST /admin/login` | Username + password + TOTP | `admin.login_succeeded` / `admin.login_failed` |
| `POST /admin/logout` | | `admin.logout` |
| `GET /admin/users` | List users | — |
| `POST /admin/users` | Create user (only if < 2) → shows temporary password once | `admin.user_created` |
| `POST /admin/users/{id}/reset-password` | Temporary password shown once; sessions revoked | `admin.user_password_reset` |
| `POST /admin/users/{id}/revoke-sessions` | | `admin.user_sessions_revoked` |
| `POST /admin/users/{id}/disable` · `/enable` | | `admin.user_disabled` / `admin.user_enabled` |
| `POST /admin/sessions/{id}/revoke` | | `admin.session_revoked` |
| `GET /admin/health` | Health page | — |
| `GET /admin/errors`, `POST /admin/errors/{id}/ack` | | `admin.error_acknowledged` |
| `GET /admin/calls` | Last 30 days metadata + diagnostics | — |
| `GET/POST /admin/settings` | Allow-listed keys only | `admin.setting_changed` |
| `GET /admin/audit` | Read-only | — |
| `POST /admin/account/password`, `POST /admin/account/totp` | | `admin.password_changed`, `admin.totp_reset` |

## 10. WebSocket contract — `GET /api/v1/ws`

- Upgrade request carries `Authorization: Bearer <access token>` (OkHttp supports headers on the
  handshake). Tokens are **not** put in the URL (URLs end up in logs).
- On access-token expiry the server sends `{"type":"auth.expiring"}` 60 s before; the client refreshes and
  sends `{"type":"auth.renew","accessToken":"…"}`. If not renewed, the server closes with code `4401`.
- Revoked session → close `4403`. Server ping every 25 s; client reconnects with backoff (1, 2, 4 … max 30 s).
- Max frame 16 KB; max 20 client messages/second.

Envelope: `{ "type": string, "id": uuid, "ts": iso8601, "data": object }`

| Direction | Type | Data |
|---|---|---|
| S→C | `connection_request.created` | `{ requestId }` |
| S→C | `connection_request.updated` | `{ requestId, status }` |
| S→C | `connection.created` | `{ connectionId, conversationId }` |
| S→C | `message.created` | `{ conversationId, id, seq, epoch, senderId, text, createdAt }` |
| S→C | `message.read` | `{ conversationId, seq }` |
| S→C | `conversation.cleared` | `{ conversationId, epoch, clearedThroughSeq }` |
| S→C | `chat.presence` | `{ conversationId, userId, inChat }` |
| S→C | `call.incoming` | `{ callId, kind, callerId }` |
| S→C | `call.accepted` / `call.declined` / `call.cancelled` / `call.missed` / `call.active` / `call.ended` | `{ callId, reason? }` |
| S→C | `session.revoked` | `{}` then close `4403` |
| C→S | `chat.heartbeat` | `{ conversationId, leaseId }` |
| C→S | `auth.renew` | `{ accessToken }` |

The socket never accepts state-changing commands other than heartbeats and auth renewal; all actions go
through REST, so validation, rate limiting and audit live in one place.
