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
| D-12 | Retention periods | Calls/diagnostics 30 d; audit 365 d; errors 14 d; backups 7 daily + 4 weekly (proposed) | As proposed | DB jobs |
| D-13 | Video codec and degradation preference for MVP | VP8 · H.264 · VP9 SVC; MAINTAIN_FRAMERATE · BALANCED | Decide from T-Q-CODEC / T-Q-DEGRADE results | Calling |
| D-14 | App name, package name (`applicationId`) and deep-link scheme | "Vault Chat" / `vaultchat://` are working names | — | Android project setup, QR payload |
| D-15 | TURN/TLS on port 443 | Not in MVP · add if a real network fails | Add only if T-NET-ONLY-443 matters for the users' networks | Deployment |
| D-16 | End-to-end encryption | Not in MVP · evaluate later with key exchange during QR pairing | Evaluate after MVP; never claim before verified | Security roadmap |
| D-17 | Offsite backup provider | Any S3-compatible provider with low/zero cost at this size | Choose by current pricing and region | Backups |
| D-18 | Domain names | Reuse an existing domain's subdomains · new domain | Reuse existing domain (₹0) | DNS/TLS |
| D-19 | Data saver default | Off · on for mobile data | Off, user-controlled | Calling |

## Decided

| ID | Decision | Date | By |
|---|---|---|---|
| Q-1 | Build QR connection before calling | 2026-10-24 | Owner ("Go" to the recommendation) |
| D-01 | **Option D.** On any exit, delete messages the recipient has already seen. Unseen messages wait for the recipient, and are deleted on the next exit after they are seen. | 2026-10-24 | Owner |
| D-02 | **Exit after about a minute.** The chat holds a 60 s presence lease renewed every 20 s; when it lapses (background, crash, power or network loss) the server applies the exit rule. **Backstop: every message is deleted at most 24 h after it was sent**, seen or not. | 2026-10-24 | Owner |

## How to record a decision

Edit the row: replace the options with the chosen option, add the date (UTC) and who decided, and update
the referenced document(s) in the same change.
