# Implementation Plan and Status

This file tracks what has **actually** been built and verified. Design lives in the numbered docs
(`docs/00`–`docs/18`); the longer-term phase rationale is in [18-roadmap.md](18-roadmap.md).

Status words: **Done (verified)** = built and checked by the tests/commands listed; **Done (unverified)** =
written but not compiled or run; **Not started**; **Blocked**.

Last updated: 2026-10-24 (UTC), after Phase 2.

## Phase order

| # | Phase | Status |
|---|---|---|
| 1 | Foundation | **Done** — backend verified locally and in CI; Android builds, unit-tests and lints in CI. Not yet launched on an emulator/device |
| 2 | Authentication and Admin Console | **Done (backend verified; Android verified in CI, not yet on a device)** — see below |
| 3 | Calling | Not started — **see ordering question Q-1** |
| 4 | QR connection | Not started |
| 5 | Text chat | Not started — needs decision D-01 / D-02 |
| 6 | Security and reliability | Not started |
| 7 | Private release | Not started |

### Q-1 (needs owner approval): run QR connection before calling?

The requested order puts Calling (3) before QR connection (4). The approved design only allows a call
between users who have an accepted connection (`POST /calls` takes a `connectionId`, docs/14 §6). Building
calls first would need a temporary rule such as "the two accounts may always call each other", which is a
product change. **Recommendation:** do Phase 4 (QR connection) before Phase 3 (Calling). The calling work
itself is unchanged. Until approved, Phase 2 is unaffected and comes next either way.

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

## Next task

1. Run the app on an emulator or phone against a local backend and walk through: login with a
   temporary password → forced change → Home → log out; admin reset while logged in → app returns to login.
2. Answer **Q-1** (QR connection before calling?). Then start that phase: `qr_tokens`,
   `connection_requests`, `connections`, `conversations` tables; QR generate/redeem/accept/reject API with
   the transactions in docs/06; Android QR display (ZXing) and scanning (CameraX + ML Kit).
