# 12 — Security, Privacy and Threat Model

Status: **Planned controls.** Nothing is implemented, so **no control below is "Implemented"**.
Each control is labelled **Planned** (designed, to be built) or **Verify** (depends on configuration or
third-party behaviour that must be tested). No system is completely secure; this document lists the
residual risks that remain.

Reference frameworks: OWASP ASVS (backend/API/admin) and OWASP MASVS (Android). Mapping in §5.

## 1. Assets

| Asset | Sensitivity |
|---|---|
| User credentials (password hashes, session tokens) | High |
| Admin credentials + TOTP secret | Critical |
| LiveKit API secret | Critical (anyone with it can mint tokens to any room) |
| FCM service-account key | High (can push to the app) |
| APK signing key | Critical (can publish "updates" the phones will accept) |
| Message content (until deleted) | High |
| Call media (in transit) | High |
| Location (if D-06 approved) | High |
| Call metadata, audit log | Medium |
| Database backups | High |

## 2. Threat model

| # | Threat | Example | Controls | Status | Residual risk |
|---|---|---|---|---|---|
| T1 | Unauthorized access | Stranger installs the APK and tries to log in | No signup; 2 accounts; strong passwords; throttling | Planned | Weak password chosen by admin/user |
| T2 | Password attacks | Online guessing, credential stuffing | Argon2id; per-IP + per-account throttling; min length 12; common-password list; generic errors | Planned | Targeted guessing of a weak password over a long time |
| T3 | Offline cracking | DB dump stolen | Argon2id (46 MiB, t=1) per OWASP | Planned | Weak passwords still crackable |
| T4 | Session theft | Token copied from a rooted phone or logs | Keystore-encrypted storage; tokens never logged; 15-min access tokens; refresh rotation + reuse detection; one session per user; admin revoke | Planned | Malware with root on the phone |
| T5 | Token replay | Reuse an old refresh token or LiveKit token | Rotation + reuse → revoke session; LiveKit tokens 10 min, room-scoped, rooms deleted after call | Planned | Replay within 10 min to a still-active room (room max 2 participants; same identity replaces the original) |
| T6 | QR abuse | Photo of QR, replay, brute force | 256-bit, 60 s, single-use, superseded, owner must accept, rate limited | Planned | None significant |
| T7 | Broken authorization (IDOR) | User sends another call/conversation ID | User ID only from session; membership checks on every path ID; 404 for non-members | Planned | Implementation bugs → tests required (§4) |
| T8 | API abuse | Floods, oversized bodies | Rate limits; body limits; schema validation with `additionalProperties: false` | Planned | Distributed floods (see T19) |
| T9 | Database exposure | Postgres port reachable from internet | Never published; internal Docker network only; separate least-privilege role (no DELETE on `connections`, no DDL at runtime) | Planned / Verify | Server root compromise |
| T10 | Secret leakage | Secret in Git, APK, logs, screenshots | Secrets only in Coolify env/secret files; `.gitignore`; secret scanning in CI; APK contains no secrets; log redaction | Planned | Human error |
| T11 | Admin compromise | Stolen admin password | Localhost-only console via SSH tunnel; TOTP; short sessions; audit; no message/media access by design | Planned | SSH key + admin password + TOTP all compromised |
| T12 | WebRTC/media attacks | Eavesdropping on network | DTLS-SRTP (mandatory in WebRTC); WSS signalling | Planned | SFU (server) can access decrypted media — not E2EE (§6) |
| T13 | TURN misuse | Using the TURN server as an open relay | LiveKit-generated per-participant TURN credentials with short TTL; private/restricted peer CIDRs denied by default; per-user allocation limit | Planned / Verify | Abuse by an authenticated user (only 2 exist) |
| T14 | Message restoration | Deleted messages reappear | Hard delete in a transaction; epoch check rejects stale sends; no device storage; backups exclude messages | Planned | Disk forensics on the VPS; screenshots (§ [07 §8](07-chat-and-disappearing-messages.md#8-limits-of-deletion-stated-honestly)) |
| T15 | Location privacy | Covert tracking | Feature not built without approval; consent, foreground-only, no history ([11](11-location-sharing-proposal.md)) | Planned | See doc 11 |
| T16 | APK tampering | Modified APK sent to a user | Private distribution; publish SHA-256 of each release; users verify signing certificate; Android refuses updates signed with a different key | Planned | User installs a tampered APK from a fake source; no Play Integrity for sideloaded apps |
| T17 | Dependency vulnerabilities | Vulnerable npm/Gradle package | Lockfiles; `npm audit` and Gradle dependency checks in CI; Dependabot/Renovate; pin LiveKit versions | Planned | Zero-days |
| T18 | Excessive logs | Tokens/messages/locations in logs | Pino redaction paths; no request-body logging on sensitive routes; log retention 14 days | Planned | Developer mistakes → log review in code review checklist |
| T19 | Denial of service | Flood API or LiveKit ports | Rate limits; Traefik limits; firewall exposes only required ports | Planned | A volumetric attack would take the single VPS down; accepted for a personal app |
| T20 | Backup exposure | Offsite bucket leaked | Client-side encryption before upload; private bucket; separate credentials; message/location data excluded | Planned / Verify | Encryption key loss = backups unusable (store key offline) |
| T21 | Lost/stolen phone | Thief has unlocked phone | Admin revokes sessions; app relies on device lock screen; messages not stored on device | Planned | Thief with unlocked phone and open app before revocation |
| T22 | Server compromise | Attacker gains root on VPS | Key-only SSH, updates, minimal exposed services, Coolify dashboard behind domain/TLS with ports 8000/6001/6002 closed | Planned / Verify | Full compromise exposes everything on the server |

## 3. Controls by area

| Area | Control | Status |
|---|---|---|
| Authentication | Username + password, Argon2id, throttling, admin TOTP | Planned |
| Authorization | Server-side only; session-derived user ID; membership checks; role separation (user vs admin listener) | Planned |
| Password hashing | Argon2id m=46 MiB t=1 p=1, benchmarked; rehash on parameter change | Planned |
| Session revocation | `revoked_at` checked on every request; revocation closes WebSocket and clears FCM token | Planned |
| Input validation | Fastify JSON Schemas on every route; `additionalProperties: false`; length limits; UUID formats | Planned |
| Rate limiting | `@fastify/rate-limit` (in-memory, single instance) + DB-backed account throttling | Planned |
| TLS | Traefik with Let's Encrypt; HTTPS only; HSTS; Android `network_security_config` with `cleartextTrafficPermitted="false"` | Planned / Verify |
| Certificate pinning | **Not in MVP** — Let's Encrypt key rotation can brick the app; reconsider later with backup pins | Decision |
| Secret management | Coolify environment variables / secret files; never in Git or APK; rotation procedure in [16](16-deployment-and-operations.md) | Planned |
| Database access | Internal network only; app role without DDL; migration role used only during deploy; no DELETE on `connections` or `audit_events` | Planned |
| CORS | Not needed: Android app is not a browser. Public API sends no CORS headers (browsers blocked by default). Admin Console is same-origin | Planned |
| Request size limits | Global 64 KB; chat 16 KB; WebSocket frame 16 KB | Planned |
| Security headers | `@fastify/helmet` on admin pages; strict CSP; `X-Content-Type-Options` | Planned |
| Dependency auditing | CI: `npm audit --audit-level=high`, Gradle dependency verification, OWASP dependency-check (optional) | Planned |
| Logging & redaction | Pino with redact paths (`req.headers.authorization`, `*.password`, `*.token`, `*.body`, `*.text`, `*.lat`, `*.lng`, `*.fcmToken`) | Planned |
| Backup encryption | Encrypt dumps (e.g. `age` public key) before upload; private key kept offline | Planned / Verify |
| Admin auditing | Every admin action and login → `audit_events`; append-only for the app role | Planned |
| Error handling | Uniform error JSON with `requestId`; no stack traces in responses; details only in server logs | Planned |
| Production config | `NODE_ENV=production`; LiveKit not in `--dev` mode; debug/pprof ports disabled; Prometheus port not published | Planned / Verify |
| Android | `allowBackup=false` (or exclusion rules); R8 obfuscation; no secrets; exported components minimised; debuggable=false in release; WebView not used | Planned |

## 4. Verification activities

- Unit/integration tests for every authorization rule (IDOR tests with the other user's IDs).
- Race tests for QR redemption, message send vs clear, simultaneous accept.
- Log-scan test: run the full test suite, then grep logs for test passwords, tokens and message strings.
- External port scan of the VPS (`nmap` from outside) matches the firewall table exactly.
- `adb backup` / device-transfer check that tokens are excluded.
- Review against ASVS Level 2 and MASVS-L1 checklists before go-live (lightweight, self-assessed).

## 5. OWASP mapping (summary)

| Framework area | Covered in |
|---|---|
| ASVS V2/V3 (Authentication, Session) | [04](04-accounts-and-authentication.md) |
| ASVS V4 (Access control) | §2 T7, [14](14-api-contracts.md) |
| ASVS V5 (Validation) | [14](14-api-contracts.md) |
| ASVS V7 (Errors, logging) | §3, [16](16-deployment-and-operations.md) |
| ASVS V8 (Data protection) | [07](07-chat-and-disappearing-messages.md), [13](13-database-design.md) |
| ASVS V9 (Communications) | §3 TLS, [08](08-calling.md) |
| MASVS-STORAGE | [04 §4](04-accounts-and-authentication.md#token-storage-on-android), [07 §4](07-chat-and-disappearing-messages.md#4-device-side-rules) |
| MASVS-CRYPTO | Keystore AES-GCM |
| MASVS-AUTH | [04](04-accounts-and-authentication.md) |
| MASVS-NETWORK | §3 TLS, cleartext disabled |
| MASVS-PLATFORM | Permissions ([08 §10](08-calling.md#10-android-integration)), exported components |
| MASVS-PRIVACY | [07](07-chat-and-disappearing-messages.md), [10](10-notifications.md), [11](11-location-sharing-proposal.md) |
| MASVS-RESILIENCE | Limited: R8 only; no root detection claims |

## 6. Transport encryption vs end-to-end encryption

- **Transport encryption (what the MVP has):** TLS for API/WebSocket; DTLS-SRTP for media between each
  phone and the LiveKit server. Network eavesdroppers cannot read content. **The server can**: the SFU
  handles decrypted media packets, and the backend stores message text until deletion.
- **End-to-end encryption (not in the MVP):** only the two phones would hold keys. LiveKit supports E2EE
  (`RoomOptions.e2eeOptions`), but security depends entirely on how keys are created and exchanged. A key
  delivered by our own backend would not be true E2EE. A credible design would exchange device public keys
  during QR pairing and verify them on both phones. This needs a separate design, review and testing,
  and has CPU/battery cost.
- **The app and documentation must not claim E2EE** unless it is implemented, reviewed and tested.

## 7. Screenshots and recording

- The chat screen sets `FLAG_SECURE` (D-23, 2026-10-29): ordinary screenshots, screen recordings and casting
  show it blank. Other screens are not covered.
- The app cannot prevent screenshots, screen recording, rooted-device capture, or a second camera
  pointed at the screen, and will not claim to.
- The app never records calls and never captures camera/microphone outside a call the user started or accepted.
- Android shows privacy indicators while camera/mic are in use; tests confirm they turn off after calls.

## 8. Secrets never stored in Git, docs, client builds or screenshots

All examples use placeholders such as `<livekit-secret>`. `.env*` files (except `.env.example` with
placeholders), keystores (`*.jks`, `*.keystore`), `google-services.json` and service-account JSON files are
git-ignored. CI runs a secret scanner (e.g. gitleaks) on every push.
