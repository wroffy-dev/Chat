# Implementation Plan and Status

This file tracks what has **actually** been built and verified. Design lives in the numbered docs
(`docs/00`–`docs/18`); the longer-term phase rationale is in [18-roadmap.md](18-roadmap.md).

Status words: **Done (verified)** = built and checked by the tests/commands listed; **Done (unverified)** =
written but not compiled or run; **Not started**; **Blocked**.

Last updated: 2026-10-26 (UTC). Rows in the earlier phase tables that later work completed point to
where it was done; nothing below has been run on a phone or on the VPS.

## Phase order

| # | Phase | Status |
|---|---|---|
| 1 | Foundation | **Done** — backend verified locally and in CI; Android builds, unit-tests and lints in CI. Not yet launched on an emulator/device |
| 2 | Authentication and Admin Console | **Done (backend verified; Android verified in CI, not yet on a device)** — see below |
| 3 | QR connection (moved before calling, Q-1) | **Done (backend verified; Android logic verified on JVM and against the real backend; screens compiled in CI, not yet on a device)** |
| 4 | Calling | **In progress (slices 1–2 built; core-telecom behind a build flag)** — server + app logic verified; real media and real push untested (no LiveKit server, Firebase project or phones available here). See below |
| 5 | Text chat | **Slice 1 built** — backend verified; app logic verified on JVM and against the real backend; screens compiled in CI; not yet on a device. See below |
| 6 | Security and reliability | **Slice 1 built** — see below; deployment-side items wait for the VPS |
| 7 | Private release | Not started |

Current totals (2026-10-27): backend 121 tests against PostgreSQL 16; Android 81 JVM unit tests; CI green
on every job (backend, android incl. release build, repo secret scan, images, database-roles).

### Q-1 (decided 2026-10-24): QR connection before calling

The owner replied "Go" to the recommendation, so QR connection was built before calling, because calls
require an accepted connection. The calling design is unchanged.

## Phase 1 — Foundation

### Backend (`backend/`)

| Item | Status | Evidence |
|---|---|---|
| Project structure (TypeScript, ESM, Node ≥ 22.12) | Done (verified) | `npm run typecheck`, `npm run build` |
| Type-safe env validation (zod); errors never echo values | Done (verified) | `test/config.test.ts` |
| Fastify app: random request IDs (client IDs ignored), body limit, `trustProxy` only for configured proxies | Done (verified) | `test/health.test.ts`, `test/errors.test.ts` |
| Uniform error format; unknown fields rejected; no stack traces/internal messages | Done (verified) | `test/errors.test.ts` (400/404/413/415/409/500 cases) |
| Logging with redaction (auth headers, cookies, passwords, tokens, message text, coordinates); no query strings or client IPs in request logs | Done (verified) | `test/logging.test.ts` |
| `/healthz`, `/readyz` (DB reachability + pending-migration check, no details in public responses) | Done (verified) | Unit tests + integration tests against PostgreSQL 16 + manual run |
| Prisma 7 + `@prisma/adapter-pg`; `prisma.config.ts`; schema with **no models** (none needed yet) | Done (verified) | `prisma validate`, `prisma generate`, `prisma migrate status` against local DB |
| Graceful shutdown (SIGTERM/SIGINT, forced-exit timeout) | Done (verified) | Manual: exit code 0 in ~16 ms, also with an idle keep-alive connection open |
| Starts with DB down; `/readyz` → 503; bad config → exit 1 naming variables only | Done (verified) | Manual runs |
| ESLint (typescript-eslint) | Done (verified) | `npm run lint` |
| `.env.example` with placeholders only | Done | Reviewed |
| Dependency audit | Done (verified) | `npm audit`: 0 vulnerabilities |
| Admin listener (port 3001) | Done in Phase 2 | See Phase 2 (Admin Console) |
| Rate limiting | Done in Phase 2 | See Phase 2 (brute-force protection) |
| Dockerfile | Done in Phase 6 | See Phase 6 (`backend/Dockerfile`, CI `images` job) |

### Android (`android/`)

| Item | Status | Evidence |
|---|---|---|
| Gradle project (wrapper 8.14.3, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, minSdk 29, target/compile 35) | Done (builds in CI) | Not buildable in this environment (`dl.google.com` blocked); every Android change since CI run #1 is compiled, unit-tested and linted there |
| App entry point (`VaultChatApplication`, `MainActivity`, manual DI `AppContainer`) | Done (compiles in CI) | Not run on a device |
| Navigation (type-safe Compose routes: Login → Home) | Done (compiles in CI) | Not run on a device |
| Login screen + ViewModel (validation, loading, errors, server-reachability banner) | Done (compiles in CI) | Not run on a device |
| Sign-in | Phase 1 placeholder (never succeeded) replaced by the real `RemoteAuthRepository` in Phase 2 | See Phase 2 |
| Theme (Material 3, light/dark, single accent) and reusable components | Done (compiles in CI) | Not run on a device |
| Permission architecture (`AppPermission`, `PermissionStatusResolver`, `rememberPermissionRequest`, `openAppSettings`) | Done (unverified for Android parts) | Resolver logic verified by JVM unit tests |
| Release hardening: HTTPS-only network config, cleartext only in debug for the emulator host, no backups, R8 | Done (release APK builds in CI) | Not run on a device; see Phase 6 |
| Platform-independent code (`domain/`, `data/`) | Done (verified on plain JVM) | Compiled with Kotlin 2.1.0 + serialization plugin; 16 JUnit tests pass; `ApiClient.checkHealth()` returned `Reachable` against the real local backend |
| CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest`, `assembleDebug`, `lintDebug` on GitHub's runner (which has the Android SDK) | Done (verified) | CI run #1 (commit 0a2bb32): backend and android jobs both green |

### Development environment

| Item | Status |
|---|---|
| `deploy/docker-compose.dev.yml` (PostgreSQL 16 bound to 127.0.0.1) | Done; `docker compose config` validates. Not run (no Docker daemon in this environment) |
| CI backend job with a PostgreSQL service | Done (verified): green in CI run #1 |

### Commands used to verify (2026-10-24)

```bash
cd backend
npm ci
npm run typecheck && npm run lint
TEST_DATABASE_URL=postgresql://…@127.0.0.1:5432/vaultchat_test npm test   # 26 tests, 5 files, all passed
npm run build && npm audit                                                # 0 vulnerabilities
npx prisma validate && npx prisma migrate status
```

Android JVM-only check (scratch, outside the repo): `kotlinc` 2.1.0 (embeddable compiler) over
`app/src/main/java/app/vaultchat/{domain,data}` and `app/src/test`, then JUnit 4.13.2 → `OK (16 tests)`.

### Phase 1 exit criteria

- [x] Backend starts, validates config, serves health/readiness, shuts down cleanly — verified.
- [x] Database connectivity and migration tooling — verified against local PostgreSQL 16.
- [x] Automated backend tests — 26 passing.
- [x] **Android project builds and unit tests pass with the Android toolchain** — CI run #1 green.
- [ ] Android app launches on an emulator/device and shows the login screen — not yet done.

### Blockers

| Blocker | Impact | Needed |
|---|---|---|
| Android SDK / Google Maven unreachable from this environment | Android compiles only in CI, not here | Resolved by the CI `android` job. Library versions may still be bumped to current stable |
| No Docker daemon here | Dev compose file not exercised | Run locally |

## Phase 2 — Authentication and Admin Console

### Backend

| Item | Status | Evidence |
|---|---|---|
| Migration `phase2_accounts_sessions_audit`: `users`, `admin_accounts`, `sessions`, `admin_sessions`, `audit_events` + CHECKs (slot ∈ {1,2}, singleton admin, username format, revoke reasons) and partial indexes | Done (verified) | Applied to local PostgreSQL 16; inserting a third user (slot 3) rejected by the database |
| Argon2id (m=46 MiB, t=1, p=1) with rehash-on-login, dummy verification for unknown usernames, common-password list | Done (verified) | Unit + integration tests. Measured ≈73 ms per verify in this container — below the 100–500 ms target in docs/04; **re-benchmark on the VPS** and raise `ARGON2_TIME_COST` if similar |
| Opaque access (15 min) / refresh tokens, SHA-256 hashed at rest; rotation with reuse detection; 30-day idle / 90-day absolute | Done (verified) | `test/auth.integration.test.ts` |
| One active session per user (D-04, still *proposed*) | Done (verified) | Integration test; easy to relax if D-04 is decided otherwise |
| Restricted session until temporary password changed (`403 PASSWORD_CHANGE_REQUIRED`) | Done (verified) | Integration + end-to-end smoke |
| Brute-force protection: account backoff after 5 failures with `Retry-After`, same behaviour for unknown usernames, per-IP route limits (`@fastify/rate-limit`, in-memory) | Done (verified) | Integration tests |
| `POST /api/v1/auth/login`, `/refresh`, `/logout`, `/change-password`; `GET /api/v1/me` | Done (verified) | Integration tests; `/me` returns `connection: null` until the QR phase |
| Admin password reset / revoke sessions / disable–enable (sessions revoked) | Done (verified) | Integration tests (service + console) |
| Admin Console (loopback listener `ADMIN_PORT`): password + TOTP login (replay-protected, throttled), CSRF tokens, `HttpOnly; Secure; SameSite=Strict` cookie, strict CSP, Users / Health / Audit log pages | Done (verified) | `test/admin.integration.test.ts`; real HTTP login in smoke test |
| Server CLI: `admin:create` (TOTP enrolment, confirm code), `admin:reset`, `user:create`, `user:reset-password`, `user:list` | Done (verified) | End-to-end smoke run of the compiled CLI |
| Audit events for logins, failures, throttling, refresh reuse, resets, revocations, status changes, admin login/logout | Done (verified) | Integration tests read them back |
| Console pages for per-session revoke, settings, calls | Done later | See "Admin Console additions" |
| Console Errors page | Not started | `error_events` table does not exist; its retention is undecided |
| `UPGRADE_REQUIRED` app-version check, FCM token endpoints | Done later | See "Admin Console additions" (version control) and Phase 4 slice 2 (push tokens) |

### Android

| Item | Status | Evidence |
|---|---|---|
| `RemoteAuthRepository` (login, `/me`, change password, logout, one serialised refresh-and-retry on `TOKEN_EXPIRED`, local session cleared on revoke/disable) | Done (verified on JVM) | 8 scripted-backend tests + opt-in test that passed against the real local backend (login → forced change → `/me` → logout) |
| `KeystoreTokenStore` (AES-256-GCM, non-exportable Keystore key, ciphertext only) | Done (compiles in CI) | Needs a device/emulator to exercise the Keystore |
| Forced change-password screen, optional change from Home, Home with log out, start destination from stored session | Done (compiles in CI) | Not yet run on a device |

### End-to-end check performed (2026-10-24)

Compiled backend + real CLI against local PostgreSQL: `admin:create` (TOTP code computed from the printed
secret) → `user:create` → login with temporary password (restricted) → `/me` refused → change password →
`/me` OK → admin console login over HTTP on the loopback port (password + TOTP) → users page lists the
user. Server logs scanned afterwards: no passwords or tokens present.

### Phase 2 exit criteria

- [x] Exactly two accounts enforceable; no signup endpoint exists.
- [x] Secure login, sessions, refresh, revocation, password change and admin reset — tested.
- [x] Restricted admin access (loopback + TOTP + CSRF) — tested.
- [ ] Android app signs in on a real device/emulator against a running backend — **not yet done**.

## Phase 3 — QR connection

### Backend

| Item | Status | Evidence |
|---|---|---|
| Migration `qr_connection`: `qr_tokens`, `connection_requests`, `connections`; CHECKs, one-pending-per-pair partial unique index, canonical pair order | Done (verified) | Applied locally; integration tests |
| Connections permanent: database trigger refuses DELETE and changes to the pair | Done (verified) | Test: delete and swap both rejected by PostgreSQL |
| 256-bit single-use QR tokens (hash stored), 60 s TTL, superseded on refresh, payload `vaultchat://connect?v=1&t=…` only | Done (verified) | Integration tests |
| Atomic redemption; one generic `QR_INVALID_OR_EXPIRED` for unknown/expired/used/superseded/own token | Done (verified) | Simultaneous-scan test: exactly one 201, one 410 |
| Accept / reject / cancel with role checks (404 for wrong user), 5-minute lazy expiry, cross-scan cancellation | Done (verified) | Integration tests |
| Concurrency: both users accepting cross-scans at once | Done (verified) | Test found a **deadlock (500)**; fixed by locking all of the pair's requests in id order; test then passed 5/5 runs |
| Per-user limits: 6 QR/min, 60/h; 10 redeems/min | Done (verified) | Test: 7th QR in a minute → 429 |
| `/me` returns the connection; Admin users page shows whether the pair is connected | Done (verified) | Tests |
| Hourly cleanup of QR tokens older than 24 h | Done (unit not separately tested) | Runs in `server.ts` |
| Live pairing updates: `connection.changed {requestId}` to both people on scan, accept, reject and cancel; pairing screens re-read at once (polling 3 s / 15 s as fallback) | Done (backend verified; app logic verified on JVM; compiles, unit-tested and linted in CI run 40450293732) | `test/realtime.integration.test.ts` (fails with the signal removed), `PollWithSignalsTest`. Not run on a device |

### Android

| Item | Status | Evidence |
|---|---|---|
| `RemoteConnectionRepository`, shared `AuthorizedCaller` (refresh once, app-wide) | Done (verified on JVM) | Scripted-backend tests; **real-backend pairing test passed** (generate → redeem → accept → both see the connection) |
| QR encode/decode with ZXing on-device; strict payload parser | Done (verified on JVM) | Round-trip incl. padded camera rows; parser rejects links, other formats |
| Inbox, Show my QR (auto-refresh, accept/reject card), Scan QR (CameraX, permission states, waiting) , Home shows connection | Done (compiled, unit-tested and linted in CI run 40130443280, commit b8b6279) | Not yet run on a device — camera scanning is untested on real hardware |

### Exit criteria

- [x] QR generation/expiry/single use, requests, accept/reject, permanent connection — tested on the backend.
- [x] Edge cases from docs/06 §6 covered by tests (simultaneous scans, refresh after scan, cross-scans, expiry, wrong user).
- [ ] Two phones pair by actually scanning each other's screen — **not yet done** (needs devices).

## Phase 4 — Calling (first slice)

### Backend

| Item | Status | Evidence |
|---|---|---|
| Migration `calls`: `calls`, `call_diagnostics`; one live call per connection (partial unique index) | Done (verified) | Applied locally; tests |
| LiveKit token issuing: identity = user id, 10-min TTL, one room, camera + microphone only, no data/screen share/room create/admin | Done (verified) | Test decodes the real JWT and checks every grant |
| Backend-only room creation (`maxParticipants: 2`); `room.auto_create: false` in LiveKit config | Done (code) — LiveKit server not run here | RoomService calls go to a recording fake in tests |
| Call state machine: start (caller pre-joins), accept/decline/cancel, idempotent end, fresh token, current call | Done (verified) | Integration tests incl. both users calling at once (one 201, one 409) |
| Ring timeout 45 s → missed; accepted but nobody joined in 30 s → failed (sweep every 5 s) | Done (verified) | Tests with injected clock |
| LiveKit webhooks: signature verified on the raw body; participant_joined → active; room_finished → ended | Done (verified) | Tests sign with the real SDK; forged and tampered requests → 401 |
| Diagnostics summary (numbers/labels only, ≤ 40 keys) | Done (verified) | Nested objects rejected |
| Calling disabled cleanly without LiveKit config (`503 MEDIA_UNAVAILABLE`) | Done (verified) | Test |
| Local LiveKit in `deploy/docker-compose.dev.yml` (pinned v1.13.7, dev config, keys from `.env.dev`) | Done (config validated with `docker compose config`) — not run: no Docker daemon here | |

### Android

| Item | Status | Evidence |
|---|---|---|
| `CallController` (ringing, accept/decline, polling, remote join/leave, reconnect + 30 s give-up, join watchdog, one cleanup path, diagnostics) | Done (verified on JVM) | 10 tests with virtual time |
| `RemoteCallRepository` | Done (compiled on JVM) | |
| `LiveKitMediaSession` (adaptive stream + dynacast, mic/camera, switch camera, speaker/earpiece, video tracks) | Done (compiled, unit-tested, linted in CI run 40132109201 attempt 2; attempt 1 failed on a Maven Central 429 before compiling) | API names checked against SDK v2.29.1 source |
| Call screen (all phases, controls, permission prompts, keep-screen-on, Back = hang up), Home call buttons | Done (compiled in CI run 40132109201) | Not run on a device |

### Limitations of this slice, and where they stand

- Calls ringing only while the app is open: FCM push and the full-screen incoming-call notification were
  built in slice 2 (untested without a Firebase project). core-telecom: built in slice 2, off by default.
- No foreground service: built in slice 2.
- Quality settings and WebRTC stats: 1080p capability check and stats built in slice 2; codec choice is
  still D-13.
- **Still open: no real media test.** No LiveKit server or phones were available in this environment.
  The first real call needs a local LiveKit (`deploy/docker-compose.dev.yml`) or the VPS, plus two devices.

## Phase 4 — Calling, slice 2

| Item | Status | Evidence |
|---|---|---|
| Backend `PUT/DELETE /api/v1/devices/push-token` (token on the session, moved if re-registered, cleared on revoke/logout) | Done (verified) | `test/push.integration.test.ts` |
| Backend FCM sender (firebase-admin 14.4.0, HTTP v1) enabled by `FCM_CREDENTIALS_FILE`; opaque `{t, id}` data messages, high priority, TTL = ring timeout; dead tokens cleared | Done (verified with a recording transport) | Tests; **not sent to real FCM** (no Firebase project) |
| Wake-up on call start; `call_end` on cancel and missed | Done (verified) | Tests |
| Android: Firebase initialised from build properties only; auto-init off until login; token registration after login, deletion on logout | Done (compiles; CI run 40137301179) | Needs a Firebase project to exercise |
| Android: incoming-call notification (CallStyle + full-screen intent, generic text), Decline without opening the app, tap opens the call screen | Done (compiles; CI run 40137301179) | Not run on a device |
| Android: `CallForegroundService` (microphone/camera types) during connected calls | Done (compiles; CI run 40137301179) | Not run on a device |
| 1080p capture only on capable, cool phones without power saving (`VideoQualityPolicy` + Camera2 capability check) | Policy verified on JVM; capability check compiles (CI run 40137301179) | `VideoQualityPolicyTest` |
| core-telecom integration (headset/car/watch answer and end, system audio routing; Android draws no call screen for self-managed calls) behind `vaultchat.systemCalls=true`, **off by default** | Logic verified on JVM; platform code compiles with core-telecom 1.0.0, incl. lint and the R8 release build (CI run 40360504979) | `SystemCallSyncTest` (8 tests). **Not run on a phone**; audio hand-over from LiveKit to Telecom is spike S-2 and unproven. Limits in docs/08 §10.2 |
| WebRTC stats in diagnostics: RTT, jitter, loss, send/receive bitrate, resolution, fps, quality-limitation reason, freezes, audio concealment, path (candidate type/protocol — no addresses); sampled every 2 s, summarised (min/avg/max, totals) into the end-of-call diagnostics | Done (logic verified on JVM; SDK reading compiles, CI run 40205109174) | `CallStatsTest`, `CallControllerTest`; SDK API names checked in livekit-android 2.29.1 sources. Not yet seen on a real call |

## Phase 5 — Text chat, slice 1

Decisions applied: D-01 option D, D-02 (60 s lease renewed every 20 s; 24 h backstop).

| Item | Status | Evidence |
|---|---|---|
| Migration `chat`: `conversations`, `conversation_participants`, `messages` (CHECKs: 1–4,000 characters, lease columns set together; active-lease index). Existing connections get a conversation; accepting a request now creates it | Done (verified) | Applied locally (dev DB: 1 connection → 1 conversation, 2 participants); integration tests |
| `POST /conversations/{id}/enter`, `/heartbeat`, `/leave`, `/messages`, `/read`; `GET /conversations/{id}/messages` (docs/14 §5) | Done (verified) | `test/chat.integration.test.ts` (14 tests) |
| Exit rule (D-01 option D): an exit deletes every message its recipient has displayed, in both directions; unseen messages stay | Done (verified) | Tests, and end to end through the app's chat logic against the real backend (`RealBackendChatTest`) |
| Epoch rule: sends composed before a clear are refused (`409 CONVERSATION_CLEARED`); a retry of a message that still exists returns it; the epoch moves only when rows are deleted | Done (verified) | Tests |
| Presence lease (D-02): lapsed leases are exits — swept every 15 s, and on heartbeat/enter; backstop deletes messages older than 24 h, seen or not | Done (verified) | Tests with a controlled clock |
| Limits: text trimmed, 1–4,000 code points, NUL refused; 30 sends/min per user; 16 KB body; 200 per history page | Done (verified) | Tests |
| Message text never in logs (send errors sanitised; `text`/`body` redacted) | Done (verified) | Log-capture test; real-backend run log checked |
| Content-free `{"t":"msg"}` push to a recipient not in the chat, at most one per minute | Done (verified with a recording transport) | Test; not sent to real FCM |
| Android `ChatController`: enter, 2 s polling, 20 s heartbeat while the app is in front, idempotent sends with retry, cleared-chat resync, draft restored after a refused send, stored-and-retried exit | Done (verified on JVM) | `ChatControllerTest` (13 tests) + `RealBackendChatTest` (passed against the local backend) |
| Android chat screen, Home "Open chat", "New message" notification channel, recent-apps thumbnail hidden while chatting (API 33+) | Done (compiles and lints; CI run 40173245132) | Not run on a device |
| Fix: six string resources had `%1` without a type (would throw at runtime on Home and the pairing screens) | Done | Corrected to `%1$s` / `%1$d` |

Deviations from the original design, recorded in docs/07 and docs/14:
- Slice 1 polled every 2 s; live signals were added afterwards (see "Realtime signals" below).
- `clearedThroughSeq` was dropped: under option D a clear no longer removes everything below a sequence.
- The keyboard's "no personalised learning" flag is set through a platform `EditText` input (Compose 1.7
  has no API for it); added after slice 1, compiles (CI run 40182356147). Not checked with real keyboards.
- "In chat" presence is not shown to the other user (D-11 is still open).

## Phase 6 — Security and reliability, slice 1

| Item | Status | Evidence |
|---|---|---|
| Authentication runs before body parsing and validation (`onRequest`), so anonymous input is never processed | Done (verified) | Full backend suite |
| Route inventory test: every `/api/v1` route except login/refresh refuses anonymous, invalid and non-Bearer credentials; a must-change-password session can use only logout, change-password and push-token | Done (verified) | `test/security.integration.test.ts`; failed as expected with a planted unauthenticated route |
| Log scan (docs/12 §4): a full run of login, refresh, pairing, push token, call and chat leaves no password, token, QR code, FCM token, LiveKit token or message text in the logs | Done (verified) | Same file; failed as expected with a planted log line |
| Startup catch-up: lapsed chat leases, 24 h backstop and stale calls handled at boot, not 5–15 s later | Done (verified) | Manual run: a lease expired during downtime was cleared 3 s after restart |
| `deploy/backup/backup.sh`: `pg_dump` without `messages` rows, streamed into `age` (no plaintext on disk), status file | Done (verified locally) | A sentinel message in the live DB was absent from the decrypted dump |
| `deploy/backup/restore-test.sh`: restore into a new scratch DB, check migrations and that `messages` is empty, drop it | Done (verified locally) | Passed; wrong key fails and leaves no scratch DB |
| `backend/Dockerfile` (non-root runtime, pruned, health check; `migrate` target), backup image, `docker-compose.prod.yml` template (no secrets; admin port on the VPS loopback only) | Done (verified in CI, run 40176585222) | CI `images` job: build, non-root and content checks, starts without DB (`/healthz` 200, `/readyz` 503), compose validation. Not yet run on the VPS |
| `scripts/check-secrets.sh` in CI | Done (verified) | Passes on the repo; caught a planted key and connection string |
| Unsigned release APK built in CI (R8 shrinking) | Done (verified in CI, run 40176585222) | Builds; not yet run on a device |
| Android: backups/device transfer exclude all app data; only the launcher activity is exported; release is HTTPS-only with system CAs | Done (reviewed) | Manifest and XML rules; `adb backup` check needs a device |

| Retention (D-12, decided 2026-10-25): calls + diagnostics 24 h, audit 365 days; plus docs/13 periods for sessions (30 d), admin sessions (7 d), finished connection requests (30 d, never the one behind the connection). Startup and hourly | Done (verified) | `test/retention.integration.test.ts` |
| Backups keep only the last 24 hours (D-12): pruned after each successful backup | Done (verified locally) | 30 h-old file pruned; 2 h-old and new kept; unrelated files untouched |

| Database roles: `vc_migrate` (schema), `vc_app` (no DDL; connections and audit log append-only; migrations read-only), `vc_maint` (read for backups; deletes old audit events). `backend/prisma/grants.sql` (moved from `deploy/postgres/`); audit purge moved to `cli maint:purge-audit` | Done (verified locally and in CI, run 40182356147) | `deploy/postgres/verify-roles.sh`: 11 refusals each fail with "permission denied"; real API flows (`scripts/smoke-flows.mjs`), audit purge, backup and restore as the right roles; failed as expected when the audit-log restriction was removed |

Not done in this slice: external port scan, uptime/disk/bandwidth alerts, ASVS/MASVS self-review — need
the deployed system.

## Realtime signals (WebSocket)

| Item | Status | Evidence |
|---|---|---|
| `GET /api/v1/ws` (`@fastify/websocket` 11.3.4): Bearer auth on the upgrade (no URL tokens; restricted sessions refused), ping 25 s, 16 KB frames, flood close, close at token expiry (4401) and after logout/revocation (4403) | Done (verified) | `test/realtime.integration.test.ts` (5 tests, real `ws` client); `/ws` in the security route inventory |
| Signals `chat.changed {conversationId}` and `call.changed {callId}` — IDs only, after commit — from sends, exits, lease expiry, backstop and every call transition | Done (verified) | Same tests (no message text in frames) |
| Android `RealtimeClient` (OkHttp, data layer): reconnect with backoff, token refresh on 4401, stops on 4403; runs while signed in and in front | Done (verified against the real backend) | `RealBackendChatTest` with live signals on both sides |
| Chat re-reads at once on a signal; polls 10 s while connected (2 s otherwise); heartbeat time-based. Incoming-call check wakes on `call.changed` (3 s / 15 s polling) | Done (chat verified on JVM; app compiles and lints, CI run 40190854554) | `ChatControllerTest` (signal + fallback test) |
| The call screen re-reads its call at once on `call.changed` (10 s polling while connected, 2 s otherwise) | Done (verified on JVM) | `CallControllerTest` |
| Debug-build overlay on the call screen with live RTT, jitter, loss, resolution/fps, limitation reason, freezes and path — for the two-phone tests | Done (logic verified on JVM; compiles, CI run 40270659380) | `CallControllerTest` |

## Admin Console additions

| Item | Status | Evidence |
|---|---|---|
| Health page made accurate: LiveKit reachable (`ListRooms`), FCM configured, open app connections, last backup time (flagged after 36 h) | Done (verified) | `test/admin.integration.test.ts` |
| Calls page: last 24 h, outcome, connected time, each phone's quality summary (escaped) — for reading the call test results | Done (verified) | Same file |
| End one app session (button per session on the Users page; audited `admin.session_revoked`) | Done (verified) | `test/admin.integration.test.ts` |
| Account page: admin password change with current password + fresh TOTP code; ends every admin session; 5 wrong attempts end all sessions; audited | Done (verified) | Same file |
| Settings page (`app_settings`, migration `app_settings`): video-quality cap, ring timeout, message lifetime (1–24 h, D-02 cap), push on/off; validated, audited, applied within ~10 s | Done (verified) | `test/settings.integration.test.ts`, admin test |
| App version control: `app.min_supported_version_code` / `latest_version_code` / `latest_apk_sha256` settings; `X-App-Version` header from the app; `426 UPGRADE_REQUIRED`; public `GET /api/v1/app/version`; app shows an update-required screen and its own APK SHA-256 on Home | Done (backend verified; app logic verified on JVM; compiles, CI run 40270405733) | `test/settings.integration.test.ts`, `AppVersionHeaderTest` |
| Still not built from docs/05: Errors page (`error_events` table does not exist), TOTP re-enrolment in the browser (use `cli admin:reset`) | Not started | |

## Deployment preparation

| Item | Status | Evidence |
|---|---|---|
| `deploy/coolify/README.md`: first-deployment runbook (DNS, firewall, roles, secrets, LiveKit, routing, migrate + grants order, admin and users, scheduled jobs, Android release, go-live checks, rollback) | Written, **not executed** | Coolify UI steps marked Verify |
| Coolify-ready compose: LiveKit config inline (`LIVEKIT_CONFIG`, no file on the server); `migrate` runs on every `up` before the backend (`service_completed_successfully`) and applies `backend/prisma/grants.sql` as `vc_migrate` via `dist/db/apply-grants.js` | Done (verified locally; CI run pending) | Compose renders with the backend depending on `migrate`; livekit-server v1.13.7 binary started from the rendered config (ports 7880/7881/7882); `verify-roles.sh` passes with grants applied by `vc_migrate`, twice. Not yet run in Coolify |
| docs/16 §4 variable table corrected to the real names (`TRUST_PROXY`, `FCM_CREDENTIALS_FILE`, `BACKUP_STATUS_FILE`, …) | Done | Matches `src/config/env.ts` |
| `ADMIN_TOTP_ENC_KEY` required only by the server and admin CLI commands, so the `audit-retention` job (compose, vc_maint) holds no app secret | Done (verified) | Config test; CLI with/without the key; server refuses to start without it; verify-roles runs the job without it |
| Two-phone test on a LAN: `docs/PHONE_TEST_GUIDE.md`; dev compose `DEV_LAN_IP` publishes LiveKit on the LAN address and sets its advertised `NODE_IP` (flag checked in livekit-server v1.13.7 source); debug network config generated from the API URL (plain HTTP only to that host and the emulator host) | Written; compose validated locally and in CI; **guide not yet carried out** | CI checks the debug APK's config for the default and a LAN URL, and the dev compose binding (CI run 40370176552) |
| Test APK button: `.github/workflows/phone-apk.yml` (manual run) builds the debug-signed app for the deployed `https://fluteconsulatancy.com/` and offers it for download; no keys or secrets in it. Firebase client identifiers come from the `VAULTCHAT_FCM_*` repository variables (push off when unset); the backend reads the service-account file from `/opt/vaultchat/secrets` on the VPS | Built; FCM wiring written, **push not yet tested on phones** | Run 40702089866 built the APK (without push) |
| In-app updates (D-20): `PUT`/`GET /api/v1/app/apk` (upload token / signed-in users), `downloadAvailable` on `/app/version`, Home Update banner with download, SHA-256 check and Android package installer; workflow signs with a fixed test key from secrets and uploads | **Working on a phone** (2026-10-29): version 104 installed by hand, version 105 offered and installed through the Update button; owner confirmed "aagya version 105" | Backend: `test/app-updates.integration.test.ts` and the security route inventory; APK runs 40823006052 (v104) and 40823839575 (v105, uploaded and installed in-app) |
| App name "Vault" and new launcher icon (D-14): adaptive icon from the owner's artwork on a `#FDF2FF` background; plain ship's-wheel status-bar icon for notifications | Built in CI; **not yet seen on a phone** | — |
| Up to ten users and many 1-to-1 connections (D-21): `users.slot` 1–10 (migration `20261029060000_up_to_ten_users`), Admin Console creates up to ten and lists every connection, QR codes work while connected, `GET /connections` returns all with `unreadCount`/`lastMessageAt`, `PEER_BUSY` for a person already in another call | Backend tested (127 tests); **not yet on the server or phones** | `test/auth`, `test/admin`, `test/connections`, `test/calls`, `test/chat` integration tests |
| New look (D-22): chat list Home (initials avatars, name filter, All/Unread, unread badges, live refresh), chat screen with call buttons in the header, white bubbles with times, pill input and round send button; monochrome theme | Built in CI; **not yet seen on a phone** | Android unit test for the chat-list parsing |
| Chat screen blocks screenshots and screen recording (D-23): `FLAG_SECURE` while a chat is open, cleared when it closes | Built in CI; **not yet tried on a phone** | — |
| Video calls: the controls (mute, speaker, camera, end) and the name fade out after 4 s without a touch and return on any touch; not while TalkBack is on | Built in CI; **not yet tried on a phone** | — |
| Mute fixes: a mute chosen while ringing now holds when the call connects (it used to switch the mic back on), quick repeated taps are applied one at a time to the latest state, the mute button shows a crossed-out mic on white when muted, and "You're muted" stays visible while the controls are hidden | Built in CI; **not yet tried on a phone** | `CallControllerTest`: muted while ringing joins muted; repeated taps end muted |
| Fix (owner report 2026-10-29: no video sent, mute stuck, in v108–110): the camera/mic calls were skipped until LiveKit's `Room.state` became CONNECTED, which happens only after the media transport is up, so the camera was never published and early mute taps were dropped; now gated on the app's own "joined" flag | Built in CI; **to be confirmed on the phones** | Owner screenshots: debug overlay `send –x–`, `recv –x–` |
| Video quality during a call: "Quality" button (Auto/1080p/720p/540p/360p) with a recommendation from the measured connection (`QualityAdvisor`: bandwidth estimate, RTT, loss); a change restarts the camera track at the new size | Built in CI; **not yet tried on a phone; thresholds untuned** | `QualityAdvisorTest`, `CallControllerTest` |
| Fix (owner report 2026-10-29, v111): picking a quality left video blank (`send –x–`/`recv –x–`) because unpublish + enable re-published LiveKit's cached, already stopped default camera track; now the camera restarts in place with `restartTrack` (publication kept; a pick made with the camera off applies when it is turned on) | Built in CI; **to be confirmed on the phones** | Owner report |
| Forced quality (owner request 2026-10-29): a fixed size is sent exactly at 30 fps (single layer, degradation DISABLED, fresh camera track); Auto keeps simulcast with 30 fps lower layers. Replaces the in-place restart, which kept the original encoding and simulcast | Built in CI; **to be confirmed on the phones** | `CallControllerTest` (forced vs Auto reaches the media session) |
| Noise filter (owner choice 2026-10-29): RNNoise v0.1 (BSD-3, vendored C, built with CMake/NDK into `libvaultchat_rnnoise.so`) as LiveKit's capture post-processor, on by default, "Noise filter" on/off button in the call; passes audio through if the library is missing or the format differs (docs/08 §8) | Built in CI; JNI bridge checked locally with gcc + a JVM harness (white noise −7.9 dB); **not yet heard on a phone** | `CallControllerTest` (toggle reaches the media session) |
| Noise filter switched off (`NOISE_FILTER = false`): from v114/v115 calls stuck on "Connecting…" then "connection lost"; a v113-code build (v116/v117) connects. This build tells whether the filter or the "is muted" change is the cause | Built in CI; **to be tried on the phones** | Owner reports 2026-10-29 |
| Quality picker removed from the call screen (owner request 2026-10-29): sent video is always Auto (simulcast, adapts to the network). `MediaSession.setVideoQuality` and `QualityAdvisor` stay in the code, unused by the UI | Built in CI | Owner request |
| "<name> is muted" on the call screen (owner request 2026-10-29, while diagnosing echo with both phones muted): follows the other phone's microphone as the LiveKit server reports it (published and not muted), so it also shows whether a mute actually reached the server | Built in CI; **not yet seen on a phone** | `CallControllerTest` |

## Next task

1. **Owner: first two-phone test** following `docs/PHONE_TEST_GUIDE.md` (no Firebase needed for it);
   send back the results table. Firebase (android/README.md "Push notifications") comes after.
2. Chat on two phones: open, send both ways, Back on one phone, background for over a minute; record
   results.
3. **Phase 7 — deployment to the VPS: authorised by the owner on 2026-10-27** ("now you can do it"),
   so the two-phone test can run against the VPS instead of a local setup. Domains (D-18):
   everything on `fluteconsulatancy.com` (LiveKit under `/rtc`; changed 2026-10-28). Still open: D-10 (staging; proposed: production
   only, two users). Access: Coolify's HTTPS API, with `coolify.fluteconsulatancy.com` allowed in the
   environment's network settings and a Coolify API token in the environment variable
   `COOLIFY_API_TOKEN` (never in chat or git). SSH from the agent environment is blocked.
   Follow `deploy/coolify/README.md`; confirm with the owner before each step that changes the server
   (creating resources, setting secrets, migrations, starting services). The owner does DNS, firewall
   and the admin account (authenticator app) — guide them step by step.
4. Calling later: try the core-telecom build (`vaultchat.systemCalls=true`) on both phones — spike S-2 —
   then decide whether to switch it on; quality benchmarks (D-13).
