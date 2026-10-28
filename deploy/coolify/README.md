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
| Domain names | D-18: one domain, `<domain>` (LiveKit under `<domain>/rtc`); optional `coolify.<domain>` |
| Admin Console access by SSH tunnel | D-05 (proposed; this runbook assumes it) |
| Staging or not | D-10 (this runbook covers production only) |
| TURN/TLS on 443 | D-15 (not in MVP; UDP 3478 + ICE/TCP 7881 only) |
| Both phones have Google Play services (for push) | D-08 |
| Offsite backup provider | D-17 (until decided, backups stay on the VPS only) |

Have ready, **offline**: a password manager entry for each secret below, and an `age` key pair made on
your own computer (`age-keygen -o vaultchat-backup.key`). Only the public key (`age1…`) goes to the server.

## 1. DNS

Create an A (and AAAA if the VPS has IPv6) record for `<domain>` (`@`) pointing at the VPS.
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
| LiveKit key | `API` + `openssl rand -hex 8` | `LIVEKIT_API_KEY` + `LIVEKIT_KEYS` |
| LiveKit secret | `openssl rand -base64 36` (must be 32+ characters) | backend env + `LIVEKIT_KEYS` only |
| `age` public key | from step 0 | backup env |
| FCM service-account file (optional) | Firebase console → Project settings → Service accounts → Generate new private key | `/opt/vaultchat/secrets/fcm-service-account.json` on the VPS (`chown 1000`, `chmod 400`), mounted read-only |

## 5. LiveKit configuration

Nothing to do on the server: LiveKit's whole config is in `docker-compose.prod.yml`
(`LIVEKIT_CONFIG`; `use_external_ip: true`, webhook to `backend`). The key/secret pair goes in
`LIVEKIT_KEYS` (`"<key>: <secret>"`) and the key name in `LIVEKIT_API_KEY`.

## 6. Application resource

1. In Coolify, create a **Docker Compose** resource from this Git repository using
   `docker-compose.prod.yml` at the repository root (Base Directory `/`, compose location
   `/docker-compose.prod.yml`) (**Verify** how Coolify selects a compose file). `migrate` runs once on
   every deployment before the backend; `backup` and `audit-retention` are profile services run on demand.
2. Set the environment variables (Coolify marks them secret): those required by the compose file —
   `TRUST_PROXY`, `DATABASE_URL` (vc_app), `MIGRATE_DATABASE_URL` (vc_migrate), `ADMIN_TOTP_ENC_KEY`,
   `LIVEKIT_PUBLIC_URL=wss://<domain>`, `LIVEKIT_API_KEY`, `LIVEKIT_API_SECRET`, `LIVEKIT_KEYS`,
   `BACKUP_PG*` and `MAINT_DATABASE_URL` (both vc_maint), `AGE_RECIPIENT`; optionally `FCM_CREDENTIALS_FILE=/run/secrets/vaultchat/fcm-service-account.json` once that file is in place.
   The phone APK workflow reads the Firebase client identifiers from the repository's Actions variables
   `VAULTCHAT_FCM_PROJECT_ID`, `VAULTCHAT_FCM_APP_ID`, `VAULTCHAT_FCM_API_KEY`, `VAULTCHAT_FCM_SENDER_ID`.
   Compose checks every variable in the file, including those of the on-demand services, so set them all.
   Variable reference: docs/16 §4 and `backend/.env.example`.
3. Domains / routing (D-18, one domain): service `backend` → `https://<domain>:3000` in Coolify;
   service `livekit` → **leave its domain empty**. Its route (`<domain>/rtc` → port 7880, path kept: the
   LiveKit SDK connects to `<url>/rtc`) is written as Traefik labels in the compose file, because
   Coolify's domain field read `/rtc:7880` as a path, stripped it and picked port 7881. The domain is
   written out in that label (Coolify does not substitute `${...}` in labels): change it there if the
   domain changes. Check `https://<domain>/rtc/validate`
   answers from LiveKit (plain text), not the backend's JSON `NOT_FOUND`. With one domain,
   `/internal/livekit/webhook` is reachable from outside too; it accepts only requests signed with the
   LiveKit secret (tested: forged and tampered requests get 401). The Admin Console port stays on
   `127.0.0.1:3001` (compose file) and gets no domain.

## 7. First start

Deploy the resource in Coolify (or `docker compose -f docker-compose.prod.yml up -d`). Order is
built in: `migrate` applies the migrations and then the role grants (`backend/prisma/grants.sql`, as
`vc_migrate`), and the backend starts only if that succeeded. Every later deployment does the same.
Take a backup before a deployment that brings a migration (§9).

Check: `https://<domain>/healthz` → 200 and `/readyz` → 200 (`/readyz` 503 means the database or a
pending migration — read the `migrate` and backend logs in Coolify).

## 8. Administrator and the two users

```sh
docker compose -f docker-compose.prod.yml exec backend node dist/cli.js admin:create --username <admin-name>
```
It asks for a password (16+ characters) and shows a TOTP secret for your authenticator app once.
Then open the Admin Console through the tunnel — `ssh -L 3001:127.0.0.1:3001 <you>@<vps>` → open
`http://localhost:3001/admin` — and create the two users there (or with `cli user:create`). Hand each
temporary password over in person; each user must change it at first login.

## 9. Scheduled jobs

| Job | Command | When |
|---|---|---|
| Backup (keeps the last 24 h, D-12) | `docker compose -f docker-compose.prod.yml --profile backup run --rm backup` | Daily, quiet hour |
| Audit-log retention (365 d, D-12) | `docker compose -f docker-compose.prod.yml --profile maint run --rm audit-retention` (vc_maint only; needs `MAINT_DATABASE_URL`) | Daily |
| Restore drill | `deploy/backup/restore-test.sh <newest .age file> <age private key>` on a machine that has the private key | Monthly |

Use Coolify's scheduled tasks or cron (**Verify**). The Admin Console health page shows the last backup
time once `BACKUP_STATUS_FILE` is mounted (the compose file does this).

## 10. Android release build

On your own trusted machine (never in CI, never committed):
1. Create the signing key once: `keytool -genkeypair -v -keystore vaultchat-release.jks -keyalg RSA -keysize 4096 -validity 10000 -alias vaultchat`. Keep two encrypted offline copies.
2. In `~/.gradle/gradle.properties`: `vaultchat.apiBaseUrl.release=https://<domain>/`, the
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
