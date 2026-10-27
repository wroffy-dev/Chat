# 02 — System Architecture

Status: **Proposed**. Nothing described here is implemented.

## 1. Overview

There are three distinct server-side pieces. Deploying the backend alone does **not** make
calling work — LiveKit and its network ports must be deployed and reachable too.

```
                         ┌──────────────────────────── Contabo VPS (Coolify, Docker) ─────────────────────────────┐
                         │                                                                                          │
 Android app A ──HTTPS/WSS──► Traefik (Coolify proxy, :443) ──► Backend API (Fastify)  ──► PostgreSQL (internal only)
 Android app B ──HTTPS/WSS──►        │                              │   ▲                                          │
                         │           │                              │   │ webhooks (signed)                        │
                         │           └──WSS signalling──► LiveKit SFU ◄─┘                                          │
                         │                                   ▲  ▲                                                  │
 Android app A ══ media: UDP 7882 / TCP 7881 / TURN 3478 ════╝  ║                                                  │
 Android app B ══ media: UDP 7882 / TCP 7881 / TURN 3478 ═══════╝                                                  │
                         │                                                                                          │
                         │  Admin Console (backend admin listener, bound to 127.0.0.1) ◄── SSH tunnel ── admin     │
                         └──────────────────────────────────────────────────────────────────────────────────────────┘
 Backend ──HTTPS──► Firebase Cloud Messaging ──► Android devices (wake-up for calls / new-message alert)
 PostgreSQL backups ──► offsite S3-compatible storage (encrypted)
```

| Component | Responsibility | Not responsible for |
|---|---|---|
| **Android app** | UI, login, QR scan/generate, chat UI, call UI, camera/mic capture, LiveKit client | Holding any server secret |
| **Backend API** (Node.js, TypeScript, Fastify, Prisma) | Accounts, sessions, authorization, QR pairing, chat storage & deletion, call state machine, LiveKit token issuing, FCM sending, Admin Console, audit | Carrying audio/video |
| **PostgreSQL** | Durable state (users, sessions, connection, messages until deleted, call metadata, audit) | Anything public — never exposed to the internet |
| **LiveKit SFU** (self-hosted, single node) | WebRTC media routing, congestion control, simulcast layer selection, embedded TURN | Deciding who may call whom (the backend decides by issuing tokens) |
| **Traefik** (Coolify's proxy) | TLS termination for HTTPS API and LiveKit WebSocket signalling | WebRTC media (UDP/TCP media ports bypass the HTTP proxy) |
| **FCM** (Google) | Delivering wake-up pushes | Guaranteeing that a call rings (see [10](10-notifications.md)) |

## 2. Application backend vs media infrastructure

- The **backend** is the authority on identity and permission. It decides that user A may call user B
  and then issues a short-lived LiveKit access token for one specific room.
- **LiveKit** trusts any token signed with its API secret. It does not know about users, passwords or
  the connection. That is why the API secret lives only on the server and why `room.auto_create`
  is disabled (only the backend creates rooms).
- Media never passes through the backend or Traefik. It flows between each phone and LiveKit on
  dedicated UDP/TCP ports ([08 §5](08-calling.md#5-connectivity-stun-turn-udp-tcp-and-nat)).

## 3. Real-time channels

| Channel | Transport | Used for |
|---|---|---|
| REST API | HTTPS (`/api/v1/...`) | All state-changing actions, history fetches, token issuing |
| App event socket | WSS (`/api/v1/ws`) via Traefik | Live chat delivery, chat-cleared events, call ringing/accept/decline/end, connection request events, presence lease heartbeats |
| LiveKit signalling | WSS to `rtc.<domain>` via Traefik | WebRTC negotiation, managed entirely by the LiveKit SDK |
| LiveKit media | UDP/TCP direct to VPS ports | Audio and video |
| Push | FCM | Waking the app when the socket is not connected |

REST is the source of truth; the WebSocket only delivers hints and live events. A client that missed
events recovers by calling REST endpoints (for example, `GET /messages?afterSeq=`).

## 4. Why these technologies

| Decision | Reason | Alternatives considered |
|---|---|---|
| LiveKit SFU, self-hosted | Mature Android SDK, built-in congestion control, simulcast, dynacast, adaptive stream, reconnection, embedded TURN; open source (Apache-2.0); ₹0 licence cost | Managed LiveKit Cloud (simpler ops, usage-based cost); raw WebRTC P2P (no server media cost but harder NAT traversal, no SFU-side adaptation; benchmark later only) |
| Fastify + TypeScript | Fast, schema-first validation (JSON Schema), good plugin ecosystem (`@fastify/rate-limit`, `@fastify/websocket`, `@fastify/helmet`) | Express (less built-in validation), NestJS (heavier) |
| PostgreSQL + Prisma | Transactions and row locks for message deletion and QR single-use; Prisma migrations | SQLite (simpler, but weaker concurrency and backup tooling in Coolify) |
| Opaque session tokens (not JWT) | Instant revocation, nothing to forge, only 2 users so a DB lookup per request is cheap | JWT access tokens (revocation needs a deny-list anyway) |
| No Redis | Single LiveKit node and single backend instance; LiveKit only needs Redis for multi-node; rate-limit counters fit in process memory + PostgreSQL | Redis (add only when scaling to more than one LiveKit node or backend replica) |
| FCM for wake-ups | Only reliable way to wake a sideloaded app on most Android devices without a battery-hungry persistent connection | Persistent foreground-service socket (drains battery, may be killed by OEM optimizers) |
| core-telecom (androidx) | System-integrated call handling, audio routing, Bluetooth, call-style notifications, `phoneCall` foreground service | Hand-rolled audio routing (error-prone) |

## 5. Trust boundaries

1. **Internet ↔ Traefik:** TLS 1.2+ only. Everything behind it is internal to the Docker network.
2. **Android app ↔ backend:** every request authenticated with an access token; every authorization
   decision made server-side from the token's user, never from IDs in the request body.
3. **Backend ↔ LiveKit:** shared API key/secret (server-side only). LiveKit → backend webhooks are
   verified with the same key (signed JWT in the `Authorization` header).
4. **Admin ↔ Admin Console:** separate listener bound to `127.0.0.1`, reachable only through an SSH
   tunnel, separate credentials + TOTP.
5. **Backend ↔ FCM:** Google service-account credentials stored as a server secret. Payloads carry no
   sensitive content.

## 6. Key flows (summary)

### 6.1 Login
App → `POST /auth/login` → backend verifies Argon2id hash → returns access + refresh tokens → app
stores them encrypted with an Android Keystore key. Details: [04](04-accounts-and-authentication.md).

### 6.2 Pairing
A → `POST /connection-qr` → displays QR → B scans → `POST /connection-requests` → A gets
`connection_request.created` over WS (or FCM) → A → `accept` → `connections` row created. Details: [06](06-qr-connection.md).

### 6.3 Chat
`POST /conversations/{id}/enter` → messages over REST + WS → Back → `POST /conversations/{id}/leave`
→ server deletes messages in one transaction → `conversation.cleared` sent to both. Details: [07](07-chat-and-disappearing-messages.md).

### 6.4 Call
A → `POST /calls` → backend creates LiveKit room (max 2 participants), sets state `ringing`, notifies B
via WS + FCM → B → `POST /calls/{id}/accept` → both fetch LiveKit tokens → both connect to LiveKit →
media flows → either side → `POST /calls/{id}/end` → backend deletes the LiveKit room. Details: [08](08-calling.md).

## 7. Environments

| Environment | Where | Purpose |
|---|---|---|
| Development | Developer machine, Docker Compose (PostgreSQL, LiveKit in `--dev` mode, backend) | Local work; Android emulator for UI, real phones on LAN for calls |
| Staging (optional, open decision D-10) | Same VPS, separate Coolify project, separate DB, separate LiveKit keys and subdomains | Pre-release checks; only run when it will not compete with real calls |
| Production | Contabo VPS via Coolify | The two real users |

Configuration, secrets and databases are never shared between environments.
See [16 §4](16-deployment-and-operations.md#4-environments-and-configuration).

## 8. Architecture decision log

| ID | Decision | Status |
|---|---|---|
| ADR-01 | Single self-hosted LiveKit node, embedded TURN, no Redis | Proposed |
| ADR-02 | Opaque, hashed, revocable session tokens with refresh rotation | Proposed |
| ADR-03 | Administrator is a separate identity using a localhost-only Admin Console | Proposed |
| ADR-04 | Chat message content is never written to device storage | Proposed |
| ADR-05 | Message table data excluded from database backups | Proposed |
| ADR-06 | Backend is the only creator of LiveKit rooms (`room.auto_create: false`) | Proposed |
| ADR-07 | One backend instance; in-process WebSocket hub and rate limiting | Proposed |
| ADR-08 | No E2EE claim in MVP; transport encryption only | Proposed |

## 9. Proposed repository structure

Documentation only — none of these directories except `docs/` exist yet.

```
/
├── README.md
├── CLAUDE.md
├── docs/                         # this documentation set
├── android/                      # Gradle project (single :app module for MVP)
│   └── app/src/main/java/.../
│       ├── ui/                   # Compose screens, theme, navigation
│       ├── data/                 # API client, WebSocket client, token store
│       ├── call/                 # LiveKit + core-telecom integration, call service
│       └── push/                 # FirebaseMessagingService
├── backend/
│   ├── src/
│   │   ├── app/                  # public API (routes, schemas, plugins)
│   │   ├── admin/                # admin listener: routes + server-rendered pages
│   │   ├── modules/              # auth, connections, chat, calls, push, audit, settings
│   │   ├── realtime/             # WebSocket hub
│   │   └── cli/                  # admin:create, admin:reset-password, user:create
│   ├── prisma/                   # schema.prisma, migrations/
│   └── Dockerfile
└── deploy/
    ├── docker-compose.dev.yml    # local dev stack
    ├── docker-compose.prod.yml   # incl. LiveKit's config (no secrets)
    └── coolify/README.md         # resource-by-resource setup notes
```
