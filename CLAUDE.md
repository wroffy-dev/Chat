# CLAUDE.md

Guidance for AI assistants and contributors working in this repository.

## Current phase: implementation (Phases 1–2 done; see docs/IMPLEMENTATION_PLAN.md)

The owner has authorised implementation, one phase at a time, in the order listed in
`docs/IMPLEMENTATION_PLAN.md`. That file is the record of what is actually built and verified —
update it with every change and never mark something done without evidence (tests, build output).

Start with `README.md`, `docs/IMPLEMENTATION_PLAN.md`, then `docs/17-open-decisions.md` — several
product decisions are intentionally unresolved and must not be decided silently.

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
  attachments, no voice notes. Text chat only.
- Exactly two app users. The administrator is a separate operational identity, not a third app user.
- Do not decide the "messages sent while neither user is in the chat" policy — it is open decision D-01.
- Location sharing is a **proposal requiring approval**; do not implement it without sign-off.
- Do not claim end-to-end encryption, screenshot prevention, or guaranteed deletion.
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
