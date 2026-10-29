# 17 — Open Decisions

Items the product owner must decide. The documentation does **not** silently decide them. Where a
recommendation is given, it is labelled as such and still needs sign-off.

| ID | Decision | Options | Recommendation (not decided) | Blocks |
|---|---|---|---|---|
| D-03 | Administrator identity | Separate operational identity (proposed) · one of the two users is also admin | Separate identity | Auth implementation |
| D-04 | Sessions per user | One active session (new login revokes old) · allow 2 devices | One session — **implemented that way; owner has not explicitly confirmed** | Auth implementation |
| D-05 | Admin Console access | Localhost + SSH tunnel (proposed) · public subdomain + IP allow-list + TOTP | Localhost + SSH tunnel | Deployment |
| **D-06** | Approve the consent-based location sharing feature? | Approve as specified · approve with changes · reject | Owner decision; should also be agreed with both users | Any location work ([11](11-location-sharing-proposal.md)) |
| D-07 | Is the VPS region suitable for media latency? | Keep · move LiveKit to a VPS nearer the users | Decide after test T-RTT | Go-live |
| D-08 | Do both phones have Google Play services (needed for FCM)? | Yes → FCM · No → calls ring only while app open, or evaluate an alternative push | Confirm device models | Notifications |
| D-09 | Minimum Android version and target devices | e.g. Android 10 (API 29) floor | Set from the two users' actual phones | Android project setup |
| D-10 | Staging environment | None · same VPS, backend-only staging · full staging incl. LiveKit | Backend-only staging on the same VPS | Deployment |
| D-11 | Show "last seen" / online presence to the other user? | Show both · in-chat only · nothing | Show "in chat" only (privacy-minimal) | UI |
| D-13 | Video codec and degradation preference for MVP | VP8 · H.264 · VP9 SVC; MAINTAIN_FRAMERATE · BALANCED | Decide from T-Q-CODEC / T-Q-DEGRADE results | Calling |
| D-15 | TURN/TLS on port 443 | Not in MVP · add if a real network fails | Add only if T-NET-ONLY-443 matters for the users' networks | Deployment |
| D-16 | End-to-end encryption | Not in MVP · evaluate later with key exchange during QR pairing | Evaluate after MVP; never claim before verified | Security roadmap |
| D-17 | Offsite backup provider | Any S3-compatible provider with low/zero cost at this size | Choose by current pricing and region | Backups |
| D-19 | Data saver default | Off · on for mobile data | Off, user-controlled | Calling |

## Decided

| ID | Decision | Date | By |
|---|---|---|---|
| Q-1 | Build QR connection before calling | 2026-10-24 | Owner ("Go" to the recommendation) |
| D-01 | **Option D.** On any exit, delete messages the recipient has already seen. Unseen messages wait for the recipient, and are deleted on the next exit after they are seen. | 2026-10-24 | Owner |
| D-12 | **Retention.** Call records and call diagnostics: 24 h. Audit log: 365 days. Backups: only the last 24 h are kept. (Error records: no table exists yet.) | 2026-10-25 | Owner ("Just 24", then chose the scope) |
| D-18 | **Domain:** everything on the owner's main domain `fluteconsulatancy.com`: backend at `https://fluteconsulatancy.com`, LiveKit under `https://fluteconsulatancy.com/rtc` (path routing; `LIVEKIT_PUBLIC_URL=wss://fluteconsulatancy.com`). Replaced the 2026-10-27 choice of `api.`/`rtc.` subdomains. Optional `coolify.fluteconsulatancy.com` for the dashboard. | 2026-10-28 | Owner ("main domain se sab kuchh") |
| D-20 | **In-app updates for test builds.** After sign-in the app offers an Update button; the build is uploaded by the GitHub Actions workflow to the server and downloaded only by signed-in users. One fixed test signing key, kept as GitHub Actions secrets. | 2026-10-28 | Owner ("update pr click kre or update ho jaye … login k baad hi dikhe"; signing key in GitHub Secrets) |
| D-14 | **App name "Vault"** with the owner's ship's-wheel icon (2026-10-29). The package name stays `app.vaultchat` (`.dev` for test builds): changing it would make Android treat it as a different app, so in-app updates would stop and both phones would need a reinstall. Deep-link scheme stays `vaultchat://`. | 2026-10-29 | Owner ("app new name is vault", icon image supplied) |
| D-02 | **Exit after about a minute.** The chat holds a 60 s presence lease renewed every 20 s; when it lapses (background, crash, power or network loss) the server applies the exit rule. **Backstop: every message is deleted at most 24 h after it was sent**, seen or not. | 2026-10-24 | Owner |

## How to record a decision

Edit the row: replace the options with the chosen option, add the date (UTC) and who decided, and update
the referenced document(s) in the same change.
