# 04 — Accounts and Authentication

Status: **Proposed**. No authentication code exists.

## 1. Rules

- Exactly **two** app user accounts. No public signup, no email or phone login, no user search.
- Login is **username + password** only.
- Accounts are created only by the administrator (Admin Console or server CLI).
- No profile photos, public profiles, or personal data beyond a username.
- Passwords are hashed with **Argon2id**. Never stored or logged in plaintext.
- No password, LiveKit secret, signing key, or backend secret is ever placed in the APK.
- All authorization is enforced by the backend.

## 2. Identity model

| Identity | Table | Logs in to | Count |
|---|---|---|---|
| App user | `users` | Android app (`/api/v1/auth/*`) | Max 2, enforced by DB constraint (`slot` ∈ {1, 2}, unique) |
| Administrator | `admin_accounts` | Admin Console only (`/admin/...` on the localhost listener) | Exactly 1 (DB constraint) |

**The administrator is a separate operational identity, not a third app user.** An admin credential
cannot obtain an app session, start chats or calls, or read messages. An app credential cannot access
the Admin Console. If the same person is both an app user and the administrator, they have two separate
credentials (and should use different passwords).

Why separate: a compromised phone must not grant admin powers, and admin actions must be auditable as
admin actions. (Open decision D-03 records this recommendation for sign-off.)

## 3. Usernames and passwords

| Field | Rule |
|---|---|
| Username | 3–32 chars, `[a-z0-9._-]`, case-insensitive unique (stored lowercase). Not secret, but never shown outside the app and Admin Console. |
| Password (users) | Min 12 chars, max 128 chars, any Unicode. Checked against a small local list of common passwords. No composition rules (per NIST SP 800-63B guidance). |
| Password (admin) | Min 16 chars, plus TOTP (see §8). |

### Hashing (Argon2id)

OWASP Password Storage Cheat Sheet (checked 2026-10-24) minimum Argon2id configurations include
`m=19456 KiB (19 MiB), t=2, p=1` and `m=47104 KiB (46 MiB), t=1, p=1`.

Proposed: **m = 47104 KiB (46 MiB), t = 1, p = 1**, benchmarked on the VPS before go-live
(target 100–500 ms per hash). Only two users exist, so memory cost is not a concern. Parameters are
stored inside the encoded hash (`$argon2id$v=19$m=...,t=...,p=...$salt$hash`) so they can be raised
later; hashes are upgraded on next successful login when parameters change.

Library: the `argon2` npm package (native binding) — **verify** it builds in the chosen Node base image.

A login attempt for a username that does not exist still runs one Argon2id verification against a dummy
hash, so response time does not reveal whether a username exists.

## 4. Sessions

Opaque random tokens, stored only as SHA-256 hashes in the `sessions` table
([13](13-database-design.md#33-sessions)).

| Token | Format | Lifetime | Where the app keeps it |
|---|---|---|---|
| Access token | 32 random bytes, base64url, prefix `vca_` | 15 minutes | Memory + encrypted store |
| Refresh token | 32 random bytes, base64url, prefix `vcr_` | Rotates on every use; session idle timeout 30 days, absolute lifetime 90 days | Encrypted store |

- **Refresh rotation:** each `POST /auth/refresh` issues a new access + refresh pair and invalidates the
  old refresh token. The previous refresh hash is kept on the session; if a **previously used refresh
  token is presented again**, the whole session is revoked (theft detection) and an audit event recorded.
- **One active session per user** (proposed, D-04): a new login revokes the user's other sessions. This
  matches "one phone per person" and limits stolen-session lifetime. Phone replacement = log in on the new phone.
- **Revocation** sets `revoked_at` and `revoke_reason`; the next request with that token gets `401`.
  Revocation also closes that session's WebSocket and removes its FCM token.
- **Logout** revokes the current session and deletes the FCM token.

### Token storage on Android

- Generate an AES-256-GCM key in the **Android Keystore** (non-exportable). Encrypt the token bundle and
  store the ciphertext in DataStore.
- `androidx.security:security-crypto` (`EncryptedSharedPreferences`) has been deprecated by Google —
  **verify the current status**; do not build on it.
- Tokens are excluded from Android Auto Backup (`android:allowBackup="false"` or explicit
  `dataExtractionRules` excluding the store) so they are not copied to cloud or device-transfer backups.
- If the Keystore key becomes invalid (e.g. device reset of lock screen in some configurations), the app
  discards stored tokens and shows Login.

## 5. Brute-force protection

| Control | Proposed value |
|---|---|
| Per-IP limit on `/auth/login` | 10 requests / minute (`@fastify/rate-limit`, in-memory store; single instance) |
| Per-username limit | 5 failed attempts → exponential delay: 1 min, 2 min, 4 min … capped at 30 min (`users.failed_login_count`, `users.throttled_until`) |
| Global limit on `/auth/*` | 60 requests / minute |
| Response | Same generic `401 INVALID_CREDENTIALS` for unknown username and wrong password; `429 RATE_LIMITED` with `Retry-After` when throttled |
| Audit | `auth.login_failed` (username hash only, no password), `auth.account_throttled` |

A permanent lockout is **not** used because with only two users it would let an attacker lock a real
user out indefinitely. Throttling plus a strong password achieves the goal without that DoS risk.

Note: Traefik sits in front of Fastify, so Fastify must trust the proxy's `X-Forwarded-For` **only from
Traefik's network** (Fastify `trustProxy` set to the Docker network CIDR), otherwise per-IP limits can be bypassed.

## 6. Password change and reset

| Flow | Who | Effect |
|---|---|---|
| Change own password | App user, `POST /auth/change-password` with current password | New hash; **all other sessions revoked**; current session kept; audit `user.password_changed` |
| Admin reset | Administrator, Admin Console | Admin enters or generates a temporary password (shown once, never stored in plaintext or logged); `must_change_password = true`; **all of that user's sessions revoked**; audit `admin.user_password_reset` |
| Forced change after reset | App user | Login succeeds with a restricted session that can only call `POST /auth/change-password` and `POST /auth/logout`; any other endpoint returns `403 PASSWORD_CHANGE_REQUIRED` |

The temporary password is handed to the user out of band (in person or another trusted channel).
There is no email/SMS reset by design.

## 7. First-time setup and recovery

No default passwords exist anywhere. The admin and users are created by explicit commands.

### 7.1 First-time administrator setup

1. Deploy the backend. With no admin account, the Admin Console shows only "Not initialised — run the
   setup command on the server." There is **no web-based admin registration**.
2. Open a shell into the backend container (Coolify → resource → Terminal, or `docker exec` over SSH).
3. Run `node dist/cli.js admin:create --username <name>`. The command prompts for the password twice
   (no echo), refuses weak passwords, and refuses to run if an admin already exists.
4. The command prints a TOTP enrolment secret / `otpauth://` URI **once** for the admin's authenticator
   app, then asks for one valid TOTP code to confirm.
5. Audit event `admin.created` (actor `system:cli`).

Trust anchor: anyone with root/SSH access to the VPS can do this. That is acceptable because such a
person can already read the database; SSH access must therefore be protected (key-only, no password login).

### 7.2 Provisioning the two users

Admin Console → Users → "Create user" (available only while fewer than 2 users exist) or CLI
`node dist/cli.js user:create --username <name>`. A temporary password is generated and shown once;
`must_change_password = true`. Audit `admin.user_created`.

### 7.3 Administrator recovery (lost password or TOTP device)

Server shell → `node dist/cli.js admin:reset --username <name>` → sets a new password and new TOTP
secret, revokes all admin sessions, audit `admin.recovered` (actor `system:cli`).

### 7.4 Emergency account recovery for an app user

Use the admin reset (§6). If the Admin Console is unreachable, CLI `user:reset-password --username <name>`
does the same with the same audit event and session revocation.

### 7.5 Lost SSH access

Use the Contabo customer panel's rescue/console access to restore an SSH key. This is the final
recovery path; keep Contabo account credentials and 2FA safe and separate.

## 8. Administrator authentication

- Username + password (Argon2id, same parameters) **plus TOTP** (RFC 6238, 30 s, 6 digits). TOTP secret
  is stored encrypted with a server-side key (`ADMIN_TOTP_ENC_KEY`), not in plaintext.
- Admin sessions: opaque token in cookie `vc_admin` with `HttpOnly; Secure; SameSite=Strict; Path=/`
  (current Chrome and Firefox accept `Secure` cookies on `http://localhost` — verify with the browser
  actually used); stored hashed in `admin_sessions`; idle timeout 15 minutes, absolute 8 hours.
- CSRF token on every state-changing form.
- Login throttling: 5 failures → 15-minute delay (single admin, no DoS concern from real users).
- Reachable only on the localhost-bound admin listener via SSH tunnel ([05](05-admin-console.md#2-access-model)).

## 9. Request authentication and authorization

- `Authorization: Bearer <access token>` on every `/api/v1` request except `/auth/login`, `/auth/refresh`,
  `/app/version` and health endpoints.
- The backend resolves the token to `(session, user)`. **The user ID used for every authorization
  decision comes from the session, never from the request body or path.** Path IDs (e.g. a conversation
  or call ID) are checked for membership: the authenticated user must be a participant.
- Disabled users (`users.status = 'disabled'`) get `403 ACCOUNT_DISABLED` and their sessions are revoked.
- Every request body is validated against a JSON Schema (Fastify route schemas, `additionalProperties: false`).

## 10. What the app never receives

- Other users' password hashes or session data.
- LiveKit API key/secret (it receives only a short-lived, room-scoped LiveKit *access token*).
- FCM server credentials.
- The administrator's existence or identity.
