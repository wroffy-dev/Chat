# CLAUDE.md

Guidance for AI assistants and contributors working in this repository.

## Current phase: implementation (foundation, accounts/admin, QR pairing done; see docs/IMPLEMENTATION_PLAN.md)

The owner has authorised implementation, one phase at a time, in the order listed in
`docs/IMPLEMENTATION_PLAN.md`. That file is the record of what is actually built and verified —
update it with every change and never mark something done without evidence (tests, build output).

Start with `README.md`, `docs/IMPLEMENTATION_PLAN.md`, then `docs/17-open-decisions.md` — product
decisions there are either recorded as decided or still open; never decide an open one silently.

## Working in the code

- Backend (`backend/`): `npm run typecheck`, `npm run lint`, `npm test` must pass. DB integration tests
  need `TEST_DATABASE_URL` pointing at a disposable database — never a production URL.
- Android (`android/`): `./gradlew :app:testDebugUnitTest :app:assembleDebug`. Keep `domain/` and `data/`
  free of Android imports so they stay testable on a plain JVM.
- Only add database tables in the phase that needs them, through Prisma migrations.

## Non-negotiable rules

- Never commit secrets: passwords, LiveKit API keys/secrets, signing keystores, Firebase service
  account JSON, database URLs with credentials, TOTP seeds. Use placeholders like `<livekit-secret>`.
- Never put backend secrets or LiveKit API secrets in the Android app.
- No public signup, no user search, no profiles, no profile photos, no media uploads, no file
  attachments, no voice notes. Text chat only. The walkie-talkie (D-26, 2026-11-12) is live one-way audio of
  at most 10 s that is never recorded or stored; do not turn it into stored voice messages, lengthen it or
  let the listening phone send audio without the owner.
- At most ten app users (owner decision D-21, 2026-10-29; previously exactly two), each chatting and
  calling 1-to-1 with the people they paired with by QR. No group chats or group calls. The administrator
  is a separate operational identity, not an app user.
- Chat deletion follows decisions D-01 (option D) and D-02 (60 s lease, 24 h backstop) in docs/17 — do not change them without the owner.
- Location sharing is **approved with changes** (D-06, 2026-11-02): with the administrator only, sent when the
  app is opened (never in the background), required to use the app; the last 10 points are kept and shown to the
  administrator on a map (owner, 2026-11-03). The Home line and the ⋮ menu (with "Turn off") were removed by the owner on 2026-11-02, so the
  app has no in-app switch; sharing stops only by revoking Android's permission or switching the phone's location off
  (which blocks the app). Do not
  widen it (background tracking, a longer history, sharing with other users) without the owner.
- Do not claim end-to-end encryption, guaranteed screenshot prevention, or guaranteed deletion. (Chats set
  `FLAG_SECURE` per D-23, which stops ordinary screenshots and recordings, not another camera or a rooted phone.)
- Do not claim the VPS supports N simultaneous calls or 1080p without real-device test results.
- Never deploy to or modify the live server as part of documentation or code work unless asked.

## Planned stack (proposed, see docs/02-architecture.md)

- Android: Kotlin, Jetpack Compose, LiveKit Android SDK, androidx core-telecom, FCM.
- Backend: Node.js, TypeScript, Fastify, PostgreSQL, Prisma.
- Media: self-hosted LiveKit SFU (single node, no Redis) with embedded TURN.
- Hosting: existing Contabo VPS managed by Coolify, Docker.

## Documentation conventions

- Mark status explicitly: **Proposed**, **Planned**, **Requires verification**, **Open decision**,
  **Implemented** (check docs/IMPLEMENTATION_PLAN.md for what is).
- Timestamps are UTC. Units: Mbps = megabits per second, GB = 10^9 bytes.
- When a third-party fact (port, config key, env var) is added, cite the official source and date.
- Keep the docs consistent: table names in `docs/13-database-design.md` and routes in
  `docs/14-api-contracts.md` are the source of truth; other docs reference them.
