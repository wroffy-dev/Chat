# Vault Chat (working name)

A private, Android-only app for **exactly two pre-approved people**: high-quality 1:1 video and voice
calls, plus text-only chat whose messages disappear for both users when either leaves the chat.
Distributed as a privately signed APK, not on Google Play.

> **Project status: documentation only.** No application code exists yet. Everything in `docs/` is a
> proposed design, not a description of working software. See
> [docs/00-repository-assessment.md](docs/00-repository-assessment.md).

## Planned stack

| Layer | Technology |
|---|---|
| Android app | Kotlin, Jetpack Compose, LiveKit Android SDK, androidx core-telecom, Firebase Cloud Messaging |
| Backend | Node.js, TypeScript, Fastify, PostgreSQL, Prisma |
| Real-time media | Self-hosted LiveKit SFU (WebRTC), embedded TURN, single node, no Redis |
| Hosting | Existing Contabo VPS (4 vCPU / 8 GB), Coolify, Docker, Let's Encrypt TLS |

## Documentation

| # | Document | What it covers |
|---|---|---|
| 00 | [Repository assessment](docs/00-repository-assessment.md) | What existed before this documentation (nothing) and the sources checked |
| 01 | [Product requirements](docs/01-product-requirements.md) | Vision, scope, out-of-scope list, targets |
| 02 | [Architecture](docs/02-architecture.md) | Components, backend vs media infrastructure, trust boundaries, decisions, repo layout |
| 03 | [Capacity, bandwidth and cost](docs/03-capacity-bandwidth-and-cost.md) | GB/hour per quality, monthly budget, extra costs |
| 04 | [Accounts and authentication](docs/04-accounts-and-authentication.md) | Two users, Argon2id, sessions, admin setup and recovery |
| 05 | [Admin Console](docs/05-admin-console.md) | Allowed and prohibited admin actions, access model |
| 06 | [QR connection](docs/06-qr-connection.md) | Rotating single-use QR pairing, transactions, edge cases |
| 07 | [Chat and disappearing messages](docs/07-chat-and-disappearing-messages.md) | Server-side deletion lifecycle, races, crash handling, **open decision D-01** |
| 08 | [Calling](docs/08-calling.md) | LiveKit SFU design, signalling, tokens, TURN, adaptation, recovery, Android integration |
| 09 | [Call quality test plan](docs/09-call-quality-test-plan.md) | Real-device measurements and proposed acceptance criteria |
| 10 | [Notifications](docs/10-notifications.md) | FCM wake-ups, token lifecycle, privacy, limitations |
| 11 | [Location sharing proposal](docs/11-location-sharing-proposal.md) | **Requires approval** — consent-based, foreground-only |
| 12 | [Security and threat model](docs/12-security-and-threat-model.md) | Threats, controls, OWASP ASVS/MASVS mapping, E2EE vs transport encryption |
| 13 | [Database design](docs/13-database-design.md) | Tables, constraints, retention, transactions, migrations |
| 14 | [API contracts](docs/14-api-contracts.md) | REST endpoints, errors, rate limits, WebSocket events |
| 15 | [Android UI and UX](docs/15-android-ui-ux.md) | Screens, states, accessibility |
| 16 | [Deployment and operations](docs/16-deployment-and-operations.md) | Coolify services, ports/firewall, LiveKit, backups, releases, monitoring |
| 17 | [Open decisions](docs/17-open-decisions.md) | Choices the product owner still has to make |
| 18 | [Roadmap](docs/18-roadmap.md) | De-risking spikes and implementation phases (for later) |

## Things this project will not claim

- That calls always run at 1080p — quality adapts to devices and networks.
- That the VPS supports a particular number of simultaneous calls — not measured yet.
- End-to-end encryption — media and messages use transport encryption only (TLS, DTLS-SRTP).
- That screenshots, screen recording or deleted-message recovery can be fully prevented.
- That push notifications always make a call ring.
