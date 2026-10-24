# 01 — Product Requirements

Status: **Proposed**. Nothing described here is implemented.

## 1. Product summary

Vault Chat (working name — see open decision D-14) is a **private, Android-only** app that lets
exactly **two pre-approved people** make high-quality video and voice calls and exchange
**disappearing text messages**. It is distributed as a privately signed APK, not through Google Play.

It is a personal, closed tool. It is not a social network.

## 2. Priorities (in order)

1. Excellent video and audio calling quality on real mobile networks.
2. Strong account security and privacy.
3. Simple, minimal, professional UI.
4. Reliability (calls connect, recover from network changes, clean up properly).
5. Low operating cost (target: ₹0–₹2,000/month on top of the existing VPS).
6. Private APK distribution.

## 3. Users and roles

| Role | Count | How they get access | What they can do |
|---|---|---|---|
| App user | Exactly 2 | Provisioned by the administrator; no signup | Log in, connect via QR, chat, call, change own password |
| Administrator | 1 operational identity | Created by a server-side setup command | Admin Console only: reset passwords, revoke sessions, view health/errors/audit, manage settings |

The administrator is **not** a third app user and cannot log in to the Android app. The same human
may hold the admin credential and one app account, but they are separate credentials. See
[04-accounts-and-authentication.md §2](04-accounts-and-authentication.md#2-identity-model).

## 4. In scope (MVP)

| Area | Requirement | Detail doc |
|---|---|---|
| Accounts | Username + password login, admin-provisioned, exactly two users | [04](04-accounts-and-authentication.md) |
| Admin Console | Minimal, private, audited | [05](05-admin-console.md) |
| Connection | One-time QR-based pairing, accept/reject, permanent once accepted | [06](06-qr-connection.md) |
| Chat | Text only; messages disappear for both users when a user exits the chat | [07](07-chat-and-disappearing-messages.md) |
| Calls | 1:1 video and voice calls via LiveKit SFU, adaptive quality up to 1080p30 | [08](08-calling.md), [09](09-call-quality-test-plan.md) |
| Notifications | Incoming-call and new-message alerts via FCM, no sensitive content | [10](10-notifications.md) |
| Security | Threat model, OWASP ASVS/MASVS-referenced controls | [12](12-security-and-threat-model.md) |
| Deployment | Contabo VPS + Coolify + Docker, TLS, backups | [16](16-deployment-and-operations.md) |

## 5. Proposed, requires approval before implementation

| Feature | Status | Doc |
|---|---|---|
| Consent-based location sharing visible in Admin Console | **Requires explicit approval** | [11](11-location-sharing-proposal.md) |
| End-to-end encryption for media and/or chat | **Future evaluation**, not claimed | [12 §6](12-security-and-threat-model.md#6-transport-encryption-vs-end-to-end-encryption) |
| Direct peer-to-peer calling | **Future benchmark experiment only** | [08 §2](08-calling.md#2-why-an-sfu-livekit-for-a-11-app) |

## 6. Explicitly out of scope

- Public registration, invite links, user search, user directory, public profiles.
- "My Profile" screen, profile photos, avatars, bios, status messages.
- Image, video, file or voice-note messages; message forwarding; media storage; public media links.
  **No media upload endpoints will exist.**
- Group chats or group calls (rooms are limited to 2 participants).
- Deleting or archiving the connection (not available in the initial version).
- Screenshot blocking (screenshots are allowed by requirement).
- Call recording of any kind, including background capture.
- Analytics SDKs, advertising SDKs, crash reporters that upload user content.
- iOS, web, or desktop clients.
- Google Play distribution.

## 7. Key behaviours (summary)

- **Connection:** A shows a rotating QR → B scans → B's request appears for A → A accepts → both are
  permanently connected. Details and edge cases: [06](06-qr-connection.md).
- **Chat exit:** When either user leaves the chat via Back or another supported exit, all messages in
  that conversation up to that moment are deleted on the server and removed from both devices.
  The policy for messages sent while the recipient is not in the chat is **open decision D-01**.
- **Calls:** Quality adapts automatically. When bandwidth is short, audio is protected first and video
  resolution/frame rate drops. 1080p30 is a target when both devices and networks support it, not a
  guarantee.
- **Screenshots:** allowed. The app does not claim to prevent screenshots or external recording.

## 8. Non-functional targets (proposed)

These are **proposed acceptance criteria**, not measured results. They are refined in
[09-call-quality-test-plan.md](09-call-quality-test-plan.md).

| Area | Proposed target |
|---|---|
| Call setup (callee taps Accept → remote video visible), good network | p50 ≤ 2 s, p90 ≤ 4 s |
| Audio continuity at ≤ 5 % packet loss | No audible gap longer than 1 s |
| Reconnect after Wi-Fi ↔ mobile-data switch | ≥ 90 % of attempts recover within 10 s without user action |
| Call failure rate (good networks) | ≤ 2 % of attempts |
| API availability | Best effort, single VPS; no formal SLA |
| Monthly extra cost | ₹0–₹2,000 (see [03](03-capacity-bandwidth-and-cost.md)) |
