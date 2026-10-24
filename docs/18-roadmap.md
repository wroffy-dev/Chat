# 18 — Implementation Roadmap (for later)

Status: **Proposed plan.** Implementation has **not** started and should begin only when the owner asks.

## 1. De-risking spikes first

Calling quality is the highest priority, so the riskiest technical questions are tested before building
the full app.

| Spike | Question | Done when |
|---|---|---|
| S-1 LiveKit on Coolify | Does LiveKit in a Coolify Compose resource (bridge network, UDP mux 7882, TCP 7881, TURN 3478) work from real mobile networks? Does embedded TURN start without a cert? | Two phones (sample app) connect over UDP; UDP-blocked network falls back to TCP/TURN |
| S-2 Telecom + LiveKit audio | How do androidx core-telecom audio endpoints and the LiveKit SDK's audio handling work together (speaker, earpiece, Bluetooth)? | Routing works on both users' phones with no double handling |
| S-3 Wake-up reliability | Does a high-priority FCM data message ring the users' actual phones from killed/Doze states? | Ring delivery times recorded (T-SETUP-2/3) |
| S-4 Region latency | RTT from both users' usual locations to the VPS | T-RTT recorded; D-07 decided |
| S-5 1080p capability | Do the users' phones sustain 1080p30 capture + encode within thermal limits? Which codec? | T-Q-1080, T-Q-CODEC, T-THERMAL recorded |

## 2. Phases

| Phase | Scope | Exit criteria |
|---|---|---|
| 0 | Decide D-01, D-02, D-03, D-04, D-06, D-09, D-14; run spikes S-1…S-5 | Decisions recorded in [17](17-open-decisions.md) |
| 1 Foundation | Repo skeleton, CI (lint, tests, `npm audit`, secret scan), backend skeleton with auth, admin CLI, Admin Console login, DB migrations, Android login | Auth tests incl. throttling and revocation pass |
| 2 Pairing | QR generate/scan/accept, WebSocket hub | Race tests pass; edge cases in [06 §6](06-qr-connection.md#6-edge-cases) covered |
| 3 Calling | Call state machine, LiveKit tokens/webhooks, Android call UI, telecom, FCM | [09](09-call-quality-test-plan.md) exit criteria met |
| 4 Chat | Messages, epoch, leave/clear, leases (per D-01/D-02) | Deletion consistency tests pass, including offline device and crash cases |
| 5 Hardening | Threat-model verification ([12 §4](12-security-and-threat-model.md#4-verification-activities)), backups + restore test, monitoring, go-live checklist | Checklist in [16 §14](16-deployment-and-operations.md#14-go-live-checklist-for-later--do-not-execute-as-part-of-documentation) complete |
| 6 Optional | Location sharing (only if D-06 approved); E2EE evaluation (D-16) | Separate approval |

Calling comes before chat because it is the top priority and carries the most technical risk.
