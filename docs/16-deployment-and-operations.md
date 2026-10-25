# 16 — Deployment, Infrastructure and Operations

Status: **Proposed runbook.** Nothing has been deployed, and this documentation work did **not** touch the
live server. Steps marked **Verify** depend on the exact Coolify/LiveKit/Contabo versions in use.

## 1. Services on the VPS

| Service | How it runs in Coolify | Public? |
|---|---|---|
| Traefik (Coolify proxy) | Managed by Coolify | Yes: 80/443 |
| Backend API + Admin listener | Docker Compose resource built from this repo (`backend/Dockerfile`) | API via Traefik at `api.<domain>`; admin port bound to `127.0.0.1` only |
| LiveKit server | Same Docker Compose resource, pinned image `livekit/livekit-server:<version>` | Signalling via Traefik at `rtc.<domain>`; media ports published directly |
| PostgreSQL | Coolify-managed database resource (persistent volume) | **Never** |
| Backup job | Small container in the same Compose resource (cron + `pg_dump` + encryption + upload) | No |
| Redis | **Not deployed.** Single LiveKit node and single backend instance do not need it | — |

Putting backend and LiveKit in one Compose resource keeps them on one Docker network, so the backend
reaches LiveKit at `http://livekit:7880` and LiveKit posts webhooks to `http://backend:3000`. PostgreSQL is a
separate Coolify database resource; the Compose resource must be attached to the shared `coolify` network
to reach it by its internal hostname (**Verify** the Coolify option name in the installed version).

## 2. Repository / service layout for deployment

```
backend/Dockerfile                  # multi-stage: build TS → slim Node runtime, non-root user
deploy/docker-compose.prod.yml      # backend, livekit, backup (no secrets inside)
deploy/livekit/livekit.yaml.example # config template with placeholders
deploy/coolify/README.md            # resource-by-resource setup notes
```

Docker strategy: one image per service, pinned base images by digest, non-root users, read-only root
filesystem where possible, `restart: unless-stopped`, health checks, log rotation (`json-file`,
`max-size: 10m`, `max-file: 5`).

## 3. Domains, DNS and TLS

| Hostname (placeholder) | Points to | Purpose | TLS |
|---|---|---|---|
| `api.<domain>` | VPS public IP (A/AAAA) | REST + WebSocket | Let's Encrypt via Traefik |
| `rtc.<domain>` | VPS public IP | LiveKit signalling (WSS) | Let's Encrypt via Traefik |
| `turn.<domain>` | VPS public IP | TURN/TLS (only if enabled, §8.3) | Certificate must be available to LiveKit or a TCP TLS terminator |
| `coolify.<domain>` (optional) | VPS public IP | Coolify dashboard over HTTPS so ports 8000/6001/6002 can be closed | Let's Encrypt |

A subdomain of an existing domain costs nothing extra. Use DNS-only records (no CDN proxy) for `rtc` and
`turn` — HTTP CDNs do not carry WebRTC media and may interfere with WebSockets/TURN.

## 4. Environments and configuration

| | Development | Staging (optional, D-10) | Production |
|---|---|---|---|
| Where | Developer machine, `docker-compose.dev.yml` | Same VPS, separate Coolify project | VPS, Coolify project `vaultchat-prod` |
| LiveKit | `livekit-server --dev` (dev keys, local only) | Own keys, own ports* | Own keys |
| Database | Local container | Separate DB + credentials | Separate DB + credentials |
| Domains | `localhost` / LAN IP | `api-staging.<domain>`, `rtc-staging.<domain>` | `api.<domain>`, `rtc.<domain>` |
| Android build | `debug` flavour, `applicationIdSuffix .dev` | `staging` flavour | `release` flavour |
| FCM | Separate Firebase project or app ID | Separate | Separate |

\* Two LiveKit instances on one host need different media ports and firewall rules. If that is too much,
skip staging LiveKit and test calls against production LiveKit with staging backend keys disabled — decide in D-10.

### Backend environment variables (defined by this project — proposed names)

| Variable | Example (placeholder) | Secret? |
|---|---|---|
| `NODE_ENV` | `production` | No |
| `PORT` / `ADMIN_PORT` | `3000` / `3001` | No |
| `DATABASE_URL` | `postgresql://vc_app:<password>@<db-host>:5432/vaultchat` | **Yes** |
| `MIGRATE_DATABASE_URL` | `postgresql://vc_migrate:<password>@…` | **Yes** |
| `TRUST_PROXY_CIDR` | Docker network CIDR of Traefik | No |
| `LIVEKIT_PUBLIC_URL` | `wss://rtc.<domain>` | No |
| `LIVEKIT_API_URL` | `http://livekit:7880` | No |
| `LIVEKIT_API_KEY` / `LIVEKIT_API_SECRET` | `<livekit-key>` / `<livekit-secret>` | **Secret** |
| `ADMIN_TOTP_ENC_KEY` | 32 random bytes, base64 | **Secret** |
| `GOOGLE_APPLICATION_CREDENTIALS` | `/run/secrets/fcm-service-account.json` (mounted file) | File is **secret** |
| `LOG_LEVEL` | `info` | No |

### LiveKit environment variables (verified in `livekit/livekit` `cmd/server/main.go`, 2026-10-24)

| Variable | Use here |
|---|---|
| `LIVEKIT_CONFIG` | Full YAML config body (alternative to mounting `--config` file) |
| `LIVEKIT_KEYS` | API key/secret pairs (`"<key>: <secret>"`) — keeps the secret out of the config file |
| `NODE_IP` | Set explicitly if STUN-based public IP discovery is wrong |
| `UDP_PORT` | Alternative to `rtc.udp_port` |
| `LIVEKIT_TURN_CERT` / `LIVEKIT_TURN_KEY` | Paths to TURN TLS cert/key, if TURN/TLS is enabled |
| `REDIS_HOST` / `REDIS_PASSWORD` | **Not set** (no Redis) |

## 5. Backend deployment

1. Coolify builds the image from the Git commit.
2. Pre-deployment step: `prisma migrate deploy` with `MIGRATE_DATABASE_URL` (after the automatic pre-migration backup, §11).
3. Container starts as non-root; `/readyz` must pass before Traefik routes traffic.
4. Traefik routes `api.<domain>` → `backend:3000`, with `/internal/*` **not** routed (path rule excludes it).
5. Admin listener: compose `ports: ["127.0.0.1:3001:3001"]` — reachable only from the VPS itself (SSH tunnel).
6. On startup the backend runs reconciliation: expire stale presence leases (clear chats), close stale
   `ringing/accepted` calls, check `active` calls against LiveKit.

## 6. PostgreSQL

- Coolify database resource with a persistent volume; **no public port mapping**.
- Create roles `vc_migrate`, `vc_app`, `vc_maint` with the grants in [13 §1](13-database-design.md#1-conventions).
- `max_connections` modest (e.g. 50); backend pool ~10.
- **Coolify's built-in scheduled backup is not used for this database**, because it would include message
  data (ADR-05). Use the custom backup job (§11). If Coolify gains per-table exclusion, reconsider.

## 7. Firewall and ports

Docker-published ports bypass plain UFW rules (Coolify firewall documentation). Use the hosting provider's
network firewall if the Contabo product offers one, otherwise `ufw-docker` as Coolify recommends. Keep an SSH
session open while changing rules.

| Port | Proto | Open to | Purpose | Notes |
|---|---|---|---|---|
| 22 | TCP | Admin IPs if static, else any (key-only auth) | SSH | Password login disabled; consider fail2ban |
| 80 | TCP | Any | HTTP → HTTPS redirect, ACME | |
| 443 | TCP | Any | Traefik: API, WebSocket, LiveKit signalling | |
| 7881 | TCP | Any | LiveKit ICE/TCP (`rtc.tcp_port`) | Cannot be behind a load balancer/TLS (LiveKit sample config) |
| 7882 | UDP | Any | LiveKit media, UDP mux (`rtc.udp_port`) | Replaces the default 50000–60000 range |
| 3478 | UDP | Any | Embedded TURN/UDP (`turn.udp_port`) | |
| 5349 | TCP | Any | TURN/TLS (`turn.tls_port`) | **Only if** TURN/TLS is enabled (§8.3) |
| 8000, 6001, 6002 | TCP | Closed after Coolify is on a domain | Coolify dashboard/realtime/terminal | Per Coolify firewall docs |
| 7880 | TCP | **Not published** | LiveKit HTTP/WS | Traefik reaches it on the Docker network |
| 3001 | TCP | **Not published** (127.0.0.1 only) | Admin Console | |
| 5432 | TCP | **Never** | PostgreSQL | |
| 6789, 7070 | TCP | **Never** (and disabled) | LiveKit Prometheus / debug handler | |

After deployment, run an external port scan and confirm it matches this table exactly.

## 8. LiveKit deployment

### 8.1 Networking mode

LiveKit needs its media ports reachable on the host's public IP, and it must advertise that IP in ICE
candidates. Two options:

| Option | How | Trade-off |
|---|---|---|
| **A. Bridge network + explicit port mappings** (proposed default) | Compose `ports: ["7881:7881/tcp", "7882:7882/udp", "3478:3478/udp"]`, `rtc.use_external_ip: true` (or `NODE_IP`) | Works with Coolify's normal model; only possible because UDP mux uses one port instead of 10,001 |
| B. Host networking | `network_mode: host` | Simplest for WebRTC, but **Verify** Coolify support; loses Docker network DNS to Postgres/backend |

Signalling: Traefik router `rtc.<domain>` → `livekit:7880` (WebSocket upgrade passes through Traefik).

### 8.2 Configuration

Use the config in [08 §13](08-calling.md#13-livekit-server-configuration-proposed-values). Keys via
`LIVEKIT_KEYS`. Never run `--dev` in production. Pin the image version; read release notes before upgrading.

### 8.3 TURN

- **MVP:** embedded TURN over UDP 3478 plus ICE/TCP 7881. LiveKit generates per-participant TURN
  credentials, so no static TURN password exists.
- LiveKit's sample config describes the `turn` section as using TLS and needing a cert/key (or
  `external_tls`). **Verify** whether the pinned version starts with `turn.enabled: true` and UDP only,
  without a certificate. If a certificate is required, obtain one for `turn.<domain>` (e.g. ACME DNS
  challenge into a mounted volume) and set `LIVEKIT_TURN_CERT`/`LIVEKIT_TURN_KEY`.
- **Phase 2 (only if a real user network fails test T-NET-ONLY-443):** TURN/TLS on port 443 by adding a
  Traefik **TCP** router with `HostSNI(turn.<domain>)` that terminates TLS and forwards to LiveKit's
  `tls_port` with `turn.external_tls: true` (and, if the client address must be preserved,
  `turn.proxy_protocol: true` with trusted CIDRs limited to Traefik). This needs custom Traefik dynamic
  configuration in Coolify — **Verify** before relying on it.

### 8.4 Verifying calling works (not just the backend)

1. `wss://rtc.<domain>` reachable (LiveKit returns HTTP 200 on `/`; **Verify** health path for the pinned version).
2. Two test devices on different networks join a test call; check the candidate type in diagnostics (UDP expected).
3. Block UDP on one device's network → call still connects via TCP/TURN.
4. Server-side: `docker logs livekit` shows both participants and no ICE failures.

## 9. Android build and release

| Step | Detail |
|---|---|
| Build | `./gradlew :app:assembleRelease` on a trusted machine or CI with secrets; R8 on; `debuggable=false` |
| Flavours | `dev`, `staging`, `prod` with different `applicationIdSuffix`, API/LiveKit URLs and `google-services.json` (injected at build time, not committed) |
| Signing | Release keystore generated once, stored **offline** in two encrypted copies (e.g. password manager + encrypted USB); keystore passwords never in Git. Losing it means future updates cannot install over the old app (users would uninstall/reinstall; no on-device data is lost because none is stored) |
| Versioning | Monotonic `versionCode` per release; `versionName` semantic |
| Verification | `apksigner verify --print-certs app-release.apk`; record the signing-cert SHA-256 once; compute the APK SHA-256 |
| Publishing | Set `app.latest_version_code` and `app.latest_apk_sha256` in Admin settings; deliver the APK privately (direct transfer or a short-lived private link). No public download page |
| Install | User temporarily allows "Install unknown apps" for the file manager/browser, installs, then turns it off again; Settings → About shows the APK hash to compare |
| Forced upgrade | Raise `app.min_supported_version_code` → old builds receive `403 UPGRADE_REQUIRED` with instructions |

## 10. Secrets management

- Secrets live only in Coolify's environment variables / secret files for the resource, the offline
  keystore vault, and the admin's password manager. Never in Git, docs, Docker images, the APK, or screenshots.
- `.gitignore` covers `.env*` (except `.env.example`), `*.jks`, `*.keystore`, `google-services.json`,
  `*service-account*.json`, `livekit.yaml` (only `.example` is committed).
- CI runs a secret scanner on every push.
- Rotation:
  - LiveKit: add a second key pair to `LIVEKIT_KEYS`, switch backend to it, remove the old pair.
  - DB passwords: change role password, update Coolify env, redeploy.
  - FCM service account: create new key in Google Cloud, replace the mounted file, delete the old key.
  - `ADMIN_TOTP_ENC_KEY`: re-run `admin:reset` after changing (re-enrols TOTP).

## 11. Backups and restore

| Item | Proposal |
|---|---|
| What | `pg_dump -Fc --exclude-table-data=messages --exclude-table-data=location_shares` (schema of all tables kept) |
| When | Daily at a quiet hour (UTC time chosen per users' time zone), plus automatically before each migration |
| Encryption | Pipe through `age` (or `gpg`) with a **public** key; the private key is stored offline only |
| Where | Offsite S3-compatible bucket, private, with credentials that can write but not list/delete if the provider supports it; plus the latest copy on the VPS |
| Retention | Only the last 24 hours (D-12, 2026-10-25): `backup.sh` deletes older files after each successful backup. Apply the same rule to the offsite bucket (lifecycle rule) |
| Monitoring | Backup job writes a status file read by the backend; Admin Console Health shows last success time; alert if older than 36 h |
| Restore test | Monthly: download latest, decrypt, `pg_restore` into a scratch database, run `prisma migrate status` and a row-count sanity check; record result |
| Not backed up | Message text and location (by design); LiveKit (stateless); Redis (none) |
| Also keep | Coolify configuration export and a copy of this repo's `deploy/` files (no secrets) |

Implemented as scripts (not yet run on the VPS): `deploy/backup/backup.sh` (dump without message rows,
streamed into `age`) and `deploy/backup/restore-test.sh` (the monthly drill: restore into a new scratch
database, check, drop), packaged as the `backup` service in `deploy/docker-compose.prod.yml`. The
backup directory keeps only the last 24 hours (D-12). Consequence: a problem noticed more than a day
later cannot be restored from backup.

Restore procedure (summary): stop backend → create empty DB → `pg_restore` → run `prisma migrate deploy` →
start backend → verify `/readyz` and admin login. Restored sessions remain valid; revoke all sessions
from the Admin Console if the incident involved credential exposure.

## 12. Logs, monitoring and alerts

| What | How |
|---|---|
| Backend logs | Pino JSON to stdout with redaction; Docker log rotation (~14 days at expected volume) |
| LiveKit logs | `logging.level: info`; Docker log rotation |
| Error summaries | `error_events` table → Admin Console |
| Uptime | A free external HTTP check on `https://api.<domain>/healthz` (a monitor on the same VPS cannot detect the VPS being down) |
| Disk | Alert at 80 % (Coolify server metrics/notifications — **Verify** — or a cron script) |
| Bandwidth | `vnstat` on the host; monthly-total check script alerts at 60 % and 80 % of the plan allowance |
| Certificates | Traefik renews automatically; external monitor alerts on TLS expiry < 14 days |
| Notification channel | Coolify notifications (email/Telegram/etc. — **Verify** options) to the admin only |

No third-party analytics or APM agents.

## 13. Upgrades, rollback and restart behaviour

| Component | Upgrade | Rollback |
|---|---|---|
| Backend | Deploy new commit via Coolify; expand/contract migrations | Redeploy previous image in Coolify; schema stays compatible by design |
| LiveKit | Change pinned tag when no call is active; read release notes | Revert tag |
| PostgreSQL minor | Coolify image update during quiet time | Revert image tag (same major) |
| PostgreSQL major | Dump → new major instance → restore → switch | Keep old instance until verified |
| Coolify | Update during quiet time; check its changelog | Per Coolify docs |
| Android | New signed APK, higher `versionCode` | Re-install previous APK only by uninstalling (Android blocks downgrades) — so test releases on staging first |

Restart behaviour: all containers `restart: unless-stopped`. A server reboot drops any active call (clients
show "Call ended — connection lost"); chat leases expired during downtime are processed on startup (chats
cleared); pending pushes are not retried after restart.

## 14. Go-live checklist (for later — do not execute as part of documentation)

- [ ] VPS region confirmed acceptable (T-RTT).
- [ ] DNS records created; TLS issued for `api` and `rtc`.
- [ ] Firewall configured; external port scan matches §7.
- [ ] Coolify dashboard behind HTTPS domain; 8000/6001/6002 closed.
- [ ] Secrets set in Coolify; none in Git (scanner clean).
- [ ] Migrations applied; DB roles and grants verified.
- [ ] Admin created via CLI; TOTP enrolled; two users created; temporary passwords delivered in person.
- [ ] Backup job ran; restore test passed.
- [ ] Call test plan exit criteria met ([09 §6](09-call-quality-test-plan.md#6-exit-criteria-for-mvp-calling)).
- [ ] Uptime, disk and bandwidth alerts tested.

## 15. Sources checked

Checked on 2026-10-24:

- LiveKit server sample config: `github.com/livekit/livekit/blob/master/config-sample.yaml`
  (ports 7880/7881, `rtc.udp_port`, `port_range_start/end` 50000–60000, `use_external_ip`, `turn.*`
  defaults 3478/5349, `room.auto_create`, `congestion_control`, Redis only for distributed mode).
- LiveKit server flags/env vars: `github.com/livekit/livekit/blob/master/cmd/server/main.go`
  (`LIVEKIT_CONFIG`, `LIVEKIT_KEYS`, `NODE_IP`, `UDP_PORT`, `REDIS_HOST`, `REDIS_PASSWORD`,
  `LIVEKIT_TURN_CERT`, `LIVEKIT_TURN_KEY`).
- LiveKit Android SDK: `github.com/livekit/client-sdk-android` (README; `RoomOptions`,
  `LocalVideoTrackOptions` presets, `EncodingUtils` default simulcast layers, `LocalParticipant`
  publish defaults; README showed version 2.29.1 at the time).
- LiveKit Node server SDK: `github.com/livekit/node-sdks` (`AccessToken` default TTL 6h, `VideoGrant` fields).
- Coolify docs: `github.com/coollabsio/coolify-docs` (firewall ports, Docker/UFW warning, networking,
  host ports, database backups to S3).
- Android: developer.android.com — foreground service types (`phoneCall`, `camera`, `microphone`,
  `location` and their permissions/restrictions); Android 14 behaviour changes (full-screen intent
  permission); Jetpack core-telecom guide.
- OWASP Password Storage Cheat Sheet (Argon2id minimum parameters).
- Not reachable from the documentation environment: `docs.livekit.io`, `firebase.google.com`,
  `coolify.io/docs` — related statements are marked **Verify**.
