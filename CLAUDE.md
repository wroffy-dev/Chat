# CLAUDE.md

Guidance for AI assistants and contributors working in this repository.

## Current phase: documentation only

The repository contains design documentation for **Vault Chat** (working name), a private
Android video-calling and text-chat app for exactly two pre-approved users. **No application code
exists yet.** Do not start implementing unless the owner explicitly asks for implementation.

Start with `README.md`, then `docs/17-open-decisions.md` — several product decisions are
intentionally unresolved and must not be decided silently.

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
  **Implemented** (nothing is implemented yet).
- Timestamps are UTC. Units: Mbps = megabits per second, GB = 10^9 bytes.
- When a third-party fact (port, config key, env var) is added, cite the official source and date.
- Keep the docs consistent: table names in `docs/13-database-design.md` and routes in
  `docs/14-api-contracts.md` are the source of truth; other docs reference them.
