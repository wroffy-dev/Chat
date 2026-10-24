# 11 — Optional Location Sharing (Proposal)

> **Status: PROPOSED FEATURE — REQUIRES EXPLICIT APPROVAL BEFORE ANY IMPLEMENTATION (open decision D-06).**
> It must not be built, partially built, or stubbed in the MVP without written sign-off from the product owner,
> and it should also be agreed with **both users**.

## 1. What it is (and is not)

**Is:** a user may choose to share their **current** location with the administrator, only while the app
is open and only while sharing is switched on. The Admin Console shows the latest position, its accuracy and
when it was taken.

**Is not:** tracking. No background location, no history, no geofencing, no alerts, no sharing with the
other app user, no collection while sharing is off.

## 2. Consent rules

| Rule | Design |
|---|---|
| Default | **Off** for every user |
| Turning on | User opens Settings → Location sharing → reads a plain-language explanation → taps "Share my location with the administrator" → Android permission prompt |
| Visible state | While on, a persistent indicator is shown on the main screen ("Sharing location · last sent 2 min ago") |
| Turning off | One tap in Settings or from the indicator. Server deletes the stored location immediately |
| Revoking OS permission | Treated as turning off: app reports `disabled`, server deletes the stored location |
| Approximate vs precise | User's choice in the Android dialog is respected. If only approximate is granted, only approximate is sent |
| Transparency | The user can see when the administrator last viewed their location ("Last viewed by admin: …"), from audit events |
| Re-consent | If the explanation text changes (`consent_version`), sharing turns off until the user agrees again |

## 3. Android permission flow

1. Check feature flag and consent state.
2. Show the in-app explanation screen first.
3. Request `ACCESS_COARSE_LOCATION` and `ACCESS_FINE_LOCATION` together (Android 12+ lets the user pick
   approximate). **Never** request `ACCESS_BACKGROUND_LOCATION`.
4. If denied → stay off, show "Location permission denied" with a link to app settings. No repeated nagging.
5. If granted → collect while the app is in the foreground only:
   - on app open, and every 5 minutes while the app stays in the foreground (proposed interval);
   - using a single current-location request (Fused Location Provider if available, otherwise `LocationManager`).
6. No `location` foreground service in this version (it would allow collection outside active app use).
7. If location services are off or no fix within 30 s → send nothing; show "Location unavailable".

## 4. Data model

One row per user; **overwritten**, never appended (no history).

`location_shares` (see [13](13-database-design.md#314-location_shares-proposed--only-if-d-06-approved)):
`user_id` (PK), `enabled`, `consent_version`, `consented_at`, `precision` (`approximate`/`precise`),
`latitude`, `longitude`, `accuracy_m`, `captured_at` (device time of fix, UTC), `received_at`.

- When sharing is disabled or permission is revoked: coordinate columns are set to `NULL` in the same
  transaction as `enabled = false`.
- Stale data: a location older than 24 hours is nulled by a scheduled job (proposed).
- Coordinates are rounded to 5 decimal places (~1 m) for precise, 2 decimal places (~1 km) for approximate.
- Database backups **exclude** this table's data (`--exclude-table-data=location_shares`).

## 5. API (proposed)

| Method & route | Auth | Purpose |
|---|---|---|
| `GET /api/v1/location-sharing` | User | Current consent state, last sent time, last admin view time |
| `PUT /api/v1/location-sharing` | User | `{ "enabled": true, "consentVersion": 1, "precision": "precise" }` or `{ "enabled": false }`. Disabling deletes stored coordinates |
| `PUT /api/v1/location-sharing/current` | User; only if `enabled` | `{ "lat", "lng", "accuracyM", "capturedAt" }`. `409 SHARING_DISABLED` if off. Rate limit 1 / 60 s |
| `GET /admin/users/{id}/location` | Admin | Latest location or "not sharing". Writes audit `admin.location_viewed` |

Validation: `lat` ∈ [-90, 90], `lng` ∈ [-180, 180], `accuracyM` ∈ (0, 100000], `capturedAt` not more than
5 minutes in the future or older than 1 hour.

## 6. Access control

- Only the administrator role can read locations; the other app user cannot.
- Every admin view is audited and visible to the user (§2).
- The Admin Console shows a map link generated locally (no third-party map tiles loaded automatically —
  the admin chooses to open an external map). **Verify** this meets the admin's needs.

## 7. Logging rules

- Coordinates never appear in application logs, error events, audit metadata or analytics.
- Audit events record only "location viewed/updated/disabled", not the values.

## 8. Threat model (feature-specific)

| Threat | Control |
|---|---|
| Covert tracking by the admin | Off by default, explicit consent, visible indicator, view audit visible to user, no background permission |
| Coercion of a user to enable sharing | Out of scope technically; the transparency features make sharing obvious. Product/ethical approval required (D-06) |
| Location history reconstruction | Single overwritten row; excluded from backups; stale data nulled |
| Database or backup leak | Only the latest point at most; backups exclude it |
| Spoofed locations | Not prevented (mock-location apps exist). The feature is informational only |
| Admin account compromise | Admin TOTP, localhost-only console, audit |

## 9. Tests (if approved)

- Permission denied / approximate only / precise; revoke permission while sharing; location services off.
- Disabling deletes coordinates server-side (DB check).
- No location requests while disabled or while the app is in the background (verify with
  `adb shell dumpsys location`).
- Admin views are audited and shown to the user.
- No coordinates in logs (grep server logs after test run).
