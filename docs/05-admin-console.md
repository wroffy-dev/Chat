# 05 — Admin Console

Status: **Proposed**. Not implemented.

## 1. Purpose

A small, private web console for one administrator to keep the service running and recover accounts.
It is an operations tool, not a monitoring or surveillance tool.

## 2. Access model

| Aspect | Design |
|---|---|
| Where it runs | Inside the backend process, on a **second listener** (proposed port `3001`) separate from the public API (`3000`) |
| Network exposure | Docker port published as `127.0.0.1:3001:3001` only. **Not** routed by Traefik, no public domain |
| How the admin reaches it | SSH tunnel: `ssh -L 3001:127.0.0.1:3001 <admin>@<vps>` then open `http://localhost:3001` |
| Authentication | Admin username + password + TOTP ([04 §8](04-accounts-and-authentication.md#8-administrator-authentication)) |
| Session | HttpOnly cookie, 15 min idle / 8 h absolute, CSRF token on all forms |
| UI technology | Server-rendered HTML from Fastify, plain forms, no client framework, no third-party scripts, strict CSP (`default-src 'self'`) |

Why localhost + SSH tunnel: ₹0 cost, no public attack surface, and SSH (key-only) is already the
server's strongest gate. Alternative (open decision D-05): a public `admin.<domain>` behind Traefik with
an IP allow-list — more convenient, larger attack surface.

## 3. Allowed actions

| Page | Shows | Actions | Audit event |
|---|---|---|---|
| Users | Username, slot (1/2), status (active/disabled), `must_change_password`, created date, last-seen time (minute precision), number of active sessions | Create user (only while < 2 exist) | `admin.user_created` |
| | | Reset password (generates temporary password, shown once) | `admin.user_password_reset` |
| | | Revoke all sessions | `admin.user_sessions_revoked` |
| | | Disable / enable account | `admin.user_disabled` / `admin.user_enabled` |
| Sessions | Per user: session created, last used, app version, device model (as reported by the app), revoked state | Revoke one session | `admin.session_revoked` |
| Connection | Whether the two users are connected, and since when; pending request count | None (connections cannot be deleted in v1) | — |
| Health | Backend version, uptime; DB reachable + migration version; LiveKit reachable (RoomService `ListRooms` call); FCM credential configured (yes/no); disk free; last backup time and result (if reported by backup job) | None | — |
| | **Implemented:** DB readiness and pending migrations, uptime, memory, LiveKit reachable (`ListRooms`, 2 s timeout), FCM configured, open app connections, last backup (from `BACKUP_STATUS_FILE`; flagged when older than 36 h). Not yet: disk free, backend version | | |
| Errors | Last 14 days of `error_events`: time, route, error code, count, request ID. No request bodies | Mark as acknowledged | `admin.error_acknowledged` |
| Calls | Last 24 hours (D-12): start time, who called whom, kind, outcome and end reason, connected duration, and each phone's quality summary from `call_diagnostics` | None | — |
| | **Implemented** (newest 50 calls) | | |
| Settings | Values from `app_settings` (see §5) | Edit permitted settings | `admin.setting_changed` (old → new value) |
| Audit log | `audit_events`, newest first, filter by action | None (read-only; no delete) | — |
| Account | Admin's own password and TOTP | Change password, re-enrol TOTP | `admin.password_changed`, `admin.totp_reset` |
| Location (only if [11](11-location-sharing-proposal.md) is approved) | Per user: sharing on/off, last position, accuracy, update time | View | `admin.location_viewed` |

## 4. Intentionally prohibited

The console has **no** way to:

- View any password, password hash, access/refresh token, TOTP secret, LiveKit key/secret, or FCM token.
- Read message content, message counts per conversation, or message timing.
- Join, listen to, record or view a call.
- Create a third app user, or a second admin.
- Delete or modify the users' connection.
- Impersonate a user or obtain an app session.
- Turn on location sharing for a user, or view location history (no history is stored).
- Delete or edit audit events.
- Run arbitrary SQL or shell commands.
- Export user data in bulk.

## 5. Permitted settings (`app_settings`)

Only keys in this allow-list can be changed. Each has a validated type and range.

| Key | Type / range | Default | Purpose |
|---|---|---|---|
| `call.max_video_quality` | `1080p` \| `720p` \| `540p` \| `360p` | `1080p` | Server-side cap sent to clients with call tokens |
| `call.ring_timeout_seconds` | 20–90 | 45 | How long an incoming call rings |
| `chat.unread_backstop_hours` | 1–**24** (D-02 caps it) | **24** (D-02) | Maximum lifetime of any message; can only be shortened |
| `app.min_supported_version_code` | integer | 1 | Older APKs are told to update |
| `app.latest_version_code` / `app.latest_apk_sha256` | integer / hex | — | Shown to users for manual update verification |
| `push.enabled` | boolean | true | Emergency switch to stop all FCM sends |

**Implemented:** `call.max_video_quality`, `call.ring_timeout_seconds`, `chat.unread_backstop_hours`,
`push.enabled` — table `app_settings` (allow-listed keys enforced by a CHECK), values validated, every
change audited with old → new value, applied within ~10 s. **Not yet:** the `app.*` version keys (they
need the app to report its version on every request and a `426`/`403 UPGRADE_REQUIRED` path).

## 6. Logging and privacy

- The console's own access is logged as audit events (login success/failure, logout, every action above).
- Audit metadata never contains secrets, temporary passwords, message text or precise coordinates.
- IP addresses in audit events: the admin's IP is always `127.0.0.1` through the tunnel; app-user IPs are
  stored truncated (IPv4 /24, IPv6 /48) for security events only.
- No analytics, tracking pixels or external resources in the console.

## 7. Failure behaviour

- If the database is down, the console shows a static "Database unavailable" page; health info that
  does not need the DB (process uptime, disk) is still shown.
- If LiveKit is unreachable, the Health page shows it; other pages work.
