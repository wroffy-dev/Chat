# Implementation Plan and Status

This file tracks what has **actually** been built and verified. Design lives in the numbered docs
(`docs/00`–`docs/18`); the longer-term phase rationale is in [18-roadmap.md](18-roadmap.md).

Status words: **Done (verified)** = built and checked by the tests/commands listed; **Done (unverified)** =
written but not compiled or run; **Not started**; **Blocked**.

Last updated: 2026-10-25 (UTC), after the chat phase (slice 1).

## Phase order

| # | Phase | Status |
|---|---|---|
| 1 | Foundation | **Done** — backend verified locally and in CI; Android builds, unit-tests and lints in CI. Not yet launched on an emulator/device |
| 2 | Authentication and Admin Console | **Done (backend verified; Android verified in CI, not yet on a device)** — see below |
| 3 | QR connection (moved before calling, Q-1) | **Done (backend verified; Android logic verified on JVM and against the real backend; screens compiled in CI, not yet on a device)** |
| 4 | Calling | **In progress (slices 1–2 built)** — server + app logic verified; real media and real push untested (no LiveKit server, Firebase project or phones available here). See below |
| 5 | Text chat | **Slice 1 built** — backend verified; app logic verified on JVM and against the real backend; screens compiled in CI; not yet on a device. See below |
| 6 | Security and reliability | **Slice 1 built** — see below; deployment-side items wait for the VPS |
| 7 | Private release | Not started |

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
| Admin listener (port 3001) | Not started | Phase 2 |
| Rate limiting | Not started | Phase 2 (with auth endpoints) |
| Dockerfile | Not started | Phase 7 |

### Android (`android/`)

| Item | Status | Evidence |
|---|---|---|
| Gradle project (wrapper 8.14.3, AGP 8.7.3, Kotlin 2.1.0, Compose BOM 2024.12.01, minSdk 29, target/compile 35) | Done (unverified) | Cannot build here: `dl.google.com` (Android SDK + Google Maven) is blocked by the environment's network policy |
| App entry point (`VaultChatApplication`, `MainActivity`, manual DI `AppContainer`) | Done (unverified) | |
| Navigation (type-safe Compose routes: Login → Home) | Done (unverified) | |
| Login screen + ViewModel (validation, loading, errors, server-reachability banner) | Done (unverified) | |
| Sign-in | **Deliberately not functional**: `NotYetAvailableAuthRepository` always returns "not available" — no fake success | JVM unit test asserts it never succeeds |
| Theme (Material 3, light/dark, single accent) and reusable components | Done (unverified) | |
| Permission architecture (`AppPermission`, `PermissionStatusResolver`, `rememberPermissionRequest`, `openAppSettings`) | Done (unverified for Android parts) | Resolver logic verified by JVM unit tests |
| Release hardening: HTTPS-only network config, cleartext only in debug for the emulator host, no backups, R8 | Done (unverified) | |
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
| Android SDK / Google Maven unreachable from this environment | Android code not compiled | CI run on GitHub, or a build in Android Studio. Library versions may then be bumped to current stable |
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
| Console pages for per-session revoke, settings, error events, calls | Not started | Need `app_settings` / `error_events` / `calls` tables from later phases |
| `UPGRADE_REQUIRED` app-version check, FCM token endpoints | Not started | Notifications / release phases |

### Android

| Item | Status | Evidence |
|---|---|---|
| `RemoteAuthRepository` (login, `/me`, change password, logout, one serialised refresh-and-retry on `TOKEN_EXPIRED`, local session cleared on revoke/disable) | Done (verified on JVM) | 8 scripted-backend tests + opt-in test that passed against the real local backend (login → forced change → `/me` → logout) |
| `KeystoreTokenStore` (AES-256-GCM, non-exportable Keystore key, ciphertext only) | Done (CI compile pending) | Needs a device/emulator to exercise the Keystore |
| Forced change-password screen, optional change from Home, Home with log out, start destination from stored session | Done (CI compile pending) | Not yet run on a device |

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
| Live push of new requests (WebSocket `connection_request.created`) | **Not started** — screens poll every 3 s while visible instead | WebSocket hub comes with calling/chat |

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

### Known limitations of this slice (planned next)

- **Calls ring only while the callee's app is open** (polling `GET /calls/current` every 3 s). FCM push,
  full-screen incoming-call UI and `core-telecom` integration are not built yet.
- **No foreground service yet:** a call may be cut off if the app goes to the background.
- **No real media test yet:** no LiveKit server or phones were available in this environment. The first
  real call needs a local LiveKit (`deploy/docker-compose.dev.yml`) or the VPS, plus two devices.
- Quality settings (1080p capability check, codec choice, thermal caps) and detailed WebRTC stats are not
  wired yet; LiveKit defaults (720p capture, simulcast) apply.

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
| core-telecom integration (system call UI, Bluetooth/car controls) | **Not started** — spike S-2 | |
| WebRTC stats (RTT, jitter, loss, resolution) in diagnostics | **Not started** | |

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
- **Polling instead of WebSocket** for now: the app polls every 2 s while the chat is open, and renews
  the lease with `POST /heartbeat`. Same server rules; a WebSocket can replace polling later.
- `clearedThroughSeq` was dropped: under option D a clear no longer removes everything below a sequence.
- The keyboard's "no personalised learning" flag is **not set**: Compose 1.7 text fields expose no API
  for it. A platform `EditText` would be needed.
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
| `backend/Dockerfile` (non-root runtime, pruned, health check; `migrate` target), backup image, `deploy/docker-compose.prod.yml` template (no secrets; admin port on the VPS loopback only) | Done (CI pending) | CI `images` job: build, non-root and content checks, starts without DB (`/healthz` 200, `/readyz` 503), compose validation |
| `scripts/check-secrets.sh` in CI | Done (verified) | Passes on the repo; caught a planted key and connection string |
| Unsigned release APK built in CI (R8 shrinking) | CI pending | |
| Android: backups/device transfer exclude all app data; only the launcher activity is exported; release is HTTPS-only with system CAs | Done (reviewed) | Manifest and XML rules; `adb backup` check needs a device |

Not done in this slice: backup pruning and data-retention jobs (retention periods are open decision
D-12); separate database roles (`vc_migrate`/`vc_app`/`vc_maint`, docs/13 §1) — to be set up with the
database on the VPS; external port scan, uptime/disk/bandwidth alerts, ASVS/MASVS self-review — need
the deployed system.

## Next task

1. **Owner actions to test for real:** create a Firebase project (android/README.md "Push notifications"),
   then run PostgreSQL + LiveKit locally (`deploy/docker-compose.dev.yml`) and make a call between two
   phones; record results against docs/09 (T-SETUP-1/2, T-CLEAN, T-BG-VOICE/VIDEO).
2. Chat on two phones: open, send both ways, Back on one phone, background for over a minute; record
   results. Then chat slice 2 if wanted: WebSocket instead of polling, keyboard learning flag.
3. **Owner decision D-12** (retention periods) unblocks backup pruning and retention jobs. Then Phase 7
   (private release) — needs the VPS and your go-ahead for any deployment.
4. Calling later: core-telecom (S-2), WebRTC stats in diagnostics, quality benchmarks (D-13).
