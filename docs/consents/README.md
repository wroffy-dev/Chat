# Consents

Every consent text lives in **one file**, `backend/consents/<id>.json`, and nowhere else: the server
reads it at start-up (`backend/src/consents/store.ts`), serves it to the app
(`GET /api/v1/consents/<id>`, docs/14 §7), and accepts agreement only to its current `version`.
The app shows exactly what the server sends; it carries no copy of the words.

| Consent | File | Version | In force since |
|---|---|---|---|
| Location sharing with the administrator (D-06) | `backend/consents/location-sharing.json` | 1 | 2026-11-02 (app v126) |

File format: `{ "id", "version", "title", "points": [..], "agree" }`. A malformed file stops the server
at start-up rather than showing a broken text.

## Changing a consent

1. Edit `backend/consents/<id>.json` and **raise `version`** if the meaning changes (wording fixes that
   change nothing may keep it).
2. Redeploy the backend. With a raised version, every user's sharing stops until they read and agree
   to the new text; the server records who agreed to which version and when
   (`location_shares.consent_version`, `consented_at`, audit `user.location_sharing_on`).
3. Update the table above. Earlier texts stay in the git history of the JSON file.
