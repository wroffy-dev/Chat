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
  let the listening phone send audio without the owner. Snaps (D-27, 2026-11-19) are the one exception to "no
  photos/media": a camera photo or a video of at most 7 s, deleted on the server when opened (one replay in
  memory) or after 24 h, never in the database or backups; since 2026-11-20 (owner) the sender may save their own photo or video to
  the gallery before sending; since 2026-11-21 (owner) a photo or a video of at most 30 s may also be picked from the
  gallery and sent as a snap under the same rules, and any snap may be marked view once (no replay). Do not add saving
  received snaps, media kept in the chat, videos longer than 30 s or keeping snaps without the owner.
- At most ten app users (owner decision D-21, 2026-10-29; previously exactly two), each chatting and
  calling 1-to-1 with the people they paired with by QR. No group chats or group calls. The administrator
  is a separate operational identity, not an app user.
- Chat deletion follows decisions D-01 (option D) and D-02 (60 s lease, 24 h backstop) in docs/17, with the per-chat
  disappearing timer of D-30 (owner, 2026-11-20: Off, 1 min, 5 min, 1 h, 24 h after seen, set by either person for
  both) — do not change them without the owner.
- Location sharing is **approved with changes** (D-06, 2026-11-02): sent when the
  app is opened, and since 2026-11-19 (owner, Snap Map style) about every 15 minutes in the background when the user
  allows "Allow all the time" (not required); required to use the app; the last 10 points are kept and shown to the
  administrator on a map (owner, 2026-11-03). Since 2026-11-19 (owner) the app's Map tab also shows each connected
  person's latest point (only the latest; consent version 4 covers this and the background updates) to the people they are paired with. The Home line and the ⋮ menu (with "Turn off") were removed by the owner on 2026-11-02, so the
  app has no in-app switch; sharing stops only by revoking Android's permission or switching the phone's location off
  (which blocks the app). Do not
  widen it further (more often than every 15 minutes, a longer history, sharing beyond connected people) without the owner.
- The app is called **Just Notes** and opens on the user's own notes (D-31, owner 2026-11-23); the chats, Map and camera
  are reached only by tapping the "Just Notes" title. Notes are shown to the writer and the people they are connected with (owner
  2026-11-23), who can only add lines; do not widen that (other users, the administrator, editing others' text) without the owner.
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
