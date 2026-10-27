# First deployment — runbook

Status: **Not yet executed.** Written from the repository's tested pieces (images, compose template,
grants, backup scripts — all checked in CI) and docs/16. Steps that depend on Coolify's current UI are
marked **Verify**: check them against Coolify's documentation at the time. Nothing here has been run
against the real VPS.

Never paste a secret into this file, an issue, a chat or a screenshot. Placeholders look like `<this>`.

## 0. Before you start

| Need | Where it is decided |
|---|---|
| The VPS region is acceptable for latency | D-07 (decide after test T-RTT) |
| Domain names | D-18 (`api.<domain>`, `rtc.<domain>`; optional `coolify.<domain>`) |
| Admin Console access by SSH tunnel | D-05 (proposed; this runbook assumes it) |
| Staging or not | D-10 (this runbook covers production only) |
| TURN/TLS on 443 | D-15 (not in MVP; UDP 3478 + ICE/TCP 7881 only) |
| Both phones have Google Play services (for push) | D-08 |
| Offsite backup provider | D-17 (until decided, backups stay on the VPS only) |

Have ready, **offline**: a password manager entry for each secret below, and an `age` key pair made on
your own computer (`age-keygen -o vaultchat-backup.key`). Only the public key (`age1…`) goes to the server.

## 1. DNS

Create A (and AAAA if the VPS has IPv6) records pointing at the VPS: `api.<domain>`, `rtc.<domain>`.
DNS-only, **no CDN proxy** (a CDN would break WebRTC and can interfere with WebSockets).

## 2. Firewall

Keep an SSH session open while changing rules. Docker-published ports bypass plain UFW rules, so use the
provider's network firewall, or `ufw-docker` as Coolify's documentation recommends (**Verify**).

Open exactly: 22/tcp (SSH, key-only), 80/tcp, 443/tcp, 7881/tcp, 7882/udp, 3478/udp.
Everything else closed — in particular 5432 (PostgreSQL), 3001 (Admin Console), 7880 (LiveKit API),
6789/7070, and Coolify's 8000/6001/6002 once its dashboard has a domain. Full table: docs/16 §7.

## 3. Database

1. In Coolify, add a PostgreSQL 16 database resource **without** a public port (**Verify** the option name).
2. Open a `psql` shell on it as the superuser (Coolify's terminal or `docker exec`) and create the roles.
   Type each password at the prompt; never put it in a command line or file:
   ```sql
   CREATE ROLE vc_migrate LOGIN;  \password vc_migrate
   CREATE ROLE vc_app LOGIN;      \password vc_app
   CREATE ROLE vc_maint LOGIN;    \password vc_maint
   CREATE DATABASE vaultchat OWNER vc_migrate;
   ```
3. Note the internal hostname of the database on the Docker network (shown by Coolify).

## 4. Secrets to generate (on your own computer)

| Secret | How | Goes to |
|---|---|---|
| `ADMIN_TOTP_ENC_KEY` | `openssl rand -base64 32` | backend env |
| LiveKit key | `openssl rand -hex 8` → e.g. `APIxxxxxxxxxxxxxxxx` style name | backend env + `LIVEKIT_KEYS` + `livekit.yaml` webhook key |
| LiveKit secret | `openssl rand -base64 36` (must be 32+ characters) | backend env + `LIVEKIT_KEYS` only |
| `age` public key | from step 0 | backup env |
| FCM service-account file (optional) | Google Cloud console → service account with FCM send permission → JSON key | mounted read-only file |

## 5. LiveKit configuration

Copy `deploy/livekit/livekit.yaml.example` to `deploy/livekit/livekit.yaml` **on the server only** (it is
git-ignored), set `webhook.api_key` to the LiveKit key, keep `use_external_ip: true`. The key/secret pair
itself goes in `LIVEKIT_KEYS` (`"<key>: <secret>"`), not in the file.

## 6. Application resource

1. In Coolify, create a **Docker Compose** resource from this Git repository using
   `deploy/docker-compose.prod.yml` (**Verify** how Coolify selects a compose file and whether it honours
   profiles; `migrate` and `backup` are profile services meant to run on demand).
2. Set the environment variables (Coolify marks them secret): those required by the compose file —
   `TRUST_PROXY`, `DATABASE_URL` (vc_app), `MIGRATE_DATABASE_URL` (vc_migrate), `ADMIN_TOTP_ENC_KEY`,
   `LIVEKIT_PUBLIC_URL=wss://rtc.<domain>`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_KEYS`,
   `BACKUP_PG*` and `MAINT_DATABASE_URL` (both vc_maint), `AGE_RECIPIENT`; optionally `FCM_CREDENTIALS_FILE` with the mounted JSON.
   Variable reference: docs/16 §4 and `backend/.env.example`.
3. Domains / routing (**Verify** in Coolify): `api.<domain>` → `backend:3000`, `rtc.<domain>` →
   `livekit:7880`, both HTTPS with Let's Encrypt and WebSocket upgrade allowed. **Do not route
   `/internal/*`** on `api.<domain>` (the LiveKit webhook reaches the backend over the Docker network).
   The Admin Console port stays on `127.0.0.1:3001` (compose file) and gets no domain.

## 7. First start

Order matters: schema, then privileges, then the app.

```sh
docker compose -f deploy/docker-compose.prod.yml --profile migrate run --rm migrate
psql "<superuser connection>" -v ON_ERROR_STOP=1 -d vaultchat -f deploy/postgres/grants.sql
docker compose -f deploy/docker-compose.prod.yml up -d backend livekit
```

Re-run the `migrate` + `grants.sql` pair before every later deployment that brings a migration.
Check: `https://api.<domain>/healthz` → 200 and `/readyz` → 200 (`/readyz` 503 means the database or a
pending migration — see the backend log).

## 8. Administrator and the two users

```sh
docker compose -f deploy/docker-compose.prod.yml exec backend node dist/cli.js admin:create --username <admin-name>
```
It asks for a password (16+ characters) and shows a TOTP secret for your authenticator app once.
Then open the Admin Console through the tunnel — `ssh -L 3001:127.0.0.1:3001 <you>@<vps>` → open
`http://localhost:3001/admin` — and create the two users there (or with `cli user:create`). Hand each
temporary password over in person; each user must change it at first login.

## 9. Scheduled jobs

| Job | Command | When |
|---|---|---|
| Backup (keeps the last 24 h, D-12) | `docker compose -f deploy/docker-compose.prod.yml --profile backup run --rm backup` | Daily, quiet hour |
| Audit-log retention (365 d, D-12) | `docker compose -f deploy/docker-compose.prod.yml --profile maint run --rm audit-retention` (vc_maint only; needs `MAINT_DATABASE_URL`) | Daily |
| Restore drill | `deploy/backup/restore-test.sh <newest .age file> <age private key>` on a machine that has the private key | Monthly |

Use Coolify's scheduled tasks or cron (**Verify**). The Admin Console health page shows the last backup
time once `BACKUP_STATUS_FILE` is mounted (the compose file does this).

## 10. Android release build

On your own trusted machine (never in CI, never committed):
1. Create the signing key once: `keytool -genkeypair -v -keystore vaultchat-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias vaultchat`. Keep two encrypted offline copies.
2. In `~/.gradle/gradle.properties`: `vaultchat.apiBaseUrl.release=https://api.<domain>/`, the
   `vaultchat.signing.*` values (android/README.md), and the `vaultchat.fcm.*` values if push is used.
3. `./gradlew :app:assembleRelease`, then `apksigner verify --print-certs` and `sha256sum` the APK; record both.
4. Deliver the APK privately; install with "unknown apps" allowed only for the moment of install.

## 11. Before calling it live

Go through docs/16 §14 (go-live checklist) and record results:
- external `nmap` from another network matches the firewall table exactly;
- backup ran and the restore drill passed;
- the call test plan's exit criteria (docs/09 §6), read from the Admin Console Calls page;
- chat on two phones: send both ways, Back on one phone, background one for over a minute.

## 12. Rollback

Redeploy the previous commit in Coolify. Migrations are written to stay backward compatible; never roll
the database back by restoring a backup unless data is lost (restoring loses everything since the backup,
and backups keep only 24 h).
