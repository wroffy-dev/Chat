# Implementation Plan and Status

This file tracks what has **actually** been built and verified. Design lives in the numbered docs
(`docs/00`–`docs/18`); the longer-term phase rationale is in [18-roadmap.md](18-roadmap.md).

Status words: **Done (verified)** = built and checked by the tests/commands listed; **Done (unverified)** =
written but not compiled or run; **Not started**; **Blocked**.

Last updated: 2026-10-24 (UTC).

## Phase order

| # | Phase | Status |
|---|---|---|
| 1 | Foundation | **In progress** — backend verified; Android written but not compiled (see blockers) |
| 2 | Authentication and Admin Console | Not started |
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
| CI (`.github/workflows/ci.yml`) runs `testDebugUnitTest`, `assembleDebug`, `lintDebug` on GitHub's runner (which has the Android SDK) | Added, **not yet run** | Will be the first real Android build |

### Development environment

| Item | Status |
|---|---|
| `deploy/docker-compose.dev.yml` (PostgreSQL 16 bound to 127.0.0.1) | Done; `docker compose config` validates. Not run (no Docker daemon in this environment) |
| CI backend job with a PostgreSQL service | Added, not yet run |

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
- [ ] **Android project builds and unit tests pass with the Android toolchain** — blocked here; to be
      confirmed by CI or a local Android Studio build.
- [ ] Android app launches on an emulator/device and shows the login screen — not yet done.

### Blockers

| Blocker | Impact | Needed |
|---|---|---|
| Android SDK / Google Maven unreachable from this environment | Android code not compiled | CI run on GitHub, or a build in Android Studio. Library versions may then be bumped to current stable |
| No Docker daemon here | Dev compose file not exercised | Run locally |

## Next task

1. Get the Android build green (CI or local) and fix any compile errors; launch on an emulator.
2. Phase 2, first slice: `users`, `sessions` tables + migration; `POST /api/v1/auth/login`, `/auth/refresh`,
   `/auth/logout` with Argon2id, rate limiting and throttling; server CLI `user:create` / `admin:create`;
   replace `NotYetAvailableAuthRepository` with the real client and Keystore-encrypted token storage.
