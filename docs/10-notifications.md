# 10 — Notifications (Firebase Cloud Messaging)

> **D-24 (2026-10-29): notifications are switched off.** The app posts none and registers no push token
> (`Push.NOTIFICATIONS_ENABLED = false`), so calls ring only while the app is open. The design below is
> kept for a later change of mind.

Status: **Implemented for calls and new messages** (see docs/IMPLEMENTATION_PLAN.md, calling slice 2 and chat slice 1); not yet exercised against a real Firebase project. Firebase facts remain **to be verified** against current Firebase documentation (`firebase.google.com` was not reachable from the build environment).

## 1. Why FCM

A sideloaded Android app has no reliable way to wake up for an incoming call except a push service.
Keeping a permanent socket open in a foreground service drains battery and is still killed by many
manufacturers' battery optimizers. FCM is used **only** to wake the app; all real data is fetched from
the backend over the authenticated API.

Trade-offs to accept consciously:
- FCM requires Google Play services on the phone.
- Google delivers the push, so Google sees that a push was sent to the device and the (minimal) payload.
- FCM delivery is fast in most cases but **not guaranteed**.

If either phone lacks Google Play services, incoming calls only ring while the app is open (open decision D-08).

## 2. What is sent

All pushes are **data messages** (no `notification` block), so the app decides what to display and
nothing sensitive is rendered by the system.

| Event | Priority | Payload (complete) | Device shows |
|---|---|---|---|
| Incoming call | high | `{"t":"call","id":"<callId>"}` | Full-screen / heads-up call UI after fetching call state from the backend |
| Call cancelled / missed | high | `{"t":"call_end","id":"<callId>"}` | Stops ringing; "Missed call" |
| New message | normal; only when the recipient is not in the chat; at most one per minute | `{"t":"msg"}` | "New message" — no sender text, no preview |
| Connection request | normal | `{"t":"conn_req"}` | "New connection request" |
| Session revoked | normal | `{"t":"logout"}` | App clears tokens on next start |

Never in a payload: passwords, access or refresh tokens, LiveKit tokens or secrets, message text,
usernames, locations.

## 3. Device token lifecycle

| Step | Action |
|---|---|
| Register | After login (and whenever `FirebaseMessagingService.onNewToken` fires) the app calls `PUT /api/v1/devices/push-token` with the token. Stored on the **current session row** (`sessions.fcm_token`), so it is tied to one authenticated session |
| Rotate | New token replaces the old one on the same session |
| Remove | Logout, session revocation, password reset and account disable all clear the session's `fcm_token` |
| Invalid | If FCM reports the token as unregistered/invalid when sending, the backend clears it |
| Uniqueness | A given FCM token can belong to only one session (unique index); re-registering it moves it |

Because there is one active session per user (D-04), each user has at most one push target.

## 4. Sending

- Backend uses the Firebase Admin SDK (HTTP v1 API) with a service-account credential stored as a
  server secret (file mounted read-only; path given by `GOOGLE_APPLICATION_CREDENTIALS`).
  **Verify** the current SDK and IAM role requirements.
- For an incoming call, the backend sends over the WebSocket **and** FCM if the callee has no live socket.
- Sending is skipped if `push.enabled = false` in Admin settings.
- Push send results are counted for the Health page; failures are logged without payloads or tokens.

## 5. Android-side behaviour

- `FirebaseMessagingService.onMessageReceived` must finish quickly. For calls it: (1) registers the call
  with core-telecom, (2) posts a `CallStyle` notification with a full-screen intent, (3) fetches call
  details from the backend.
- High-priority messages are meant for user-visible, time-sensitive events. If the app receives
  high-priority pushes but does not show a notification, Android/FCM may deprioritise later ones — always
  show the call UI or a missed-call notification.
- On Android 14+, full-screen intents need the `USE_FULL_SCREEN_INTENT` permission to be granted; the app
  checks `canUseFullScreenIntent()` and guides the user to enable it.
- Notification channels: `calls` (high importance, ringtone), `messages` (default), `system` (low).

## 6. Limitations (stated honestly)

A push notification does **not** guarantee that a call rings. Known reasons it may not:

| Cause | Mitigation |
|---|---|
| Phone offline or in poor coverage | None; caller sees "Ringing…" then "No answer" |
| Doze / App Standby buckets delaying pushes | High priority for calls; ask the user to exempt the app from battery optimisation (allowed for a sideloaded app). **Implemented:** Android's own dialog (`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`) is shown once, the first time Home opens while the app is optimised; no in-app banner (owner choice) |
| Manufacturer battery managers (aggressive OEM "auto-start" or "app freezing" features) | Per-device setup guide shown in Settings ("Allow background activity", "Auto-start") |
| User force-stopped the app | Android does not deliver pushes to force-stopped apps until opened again |
| Notifications permission denied / channel muted | Warning in Settings screen |
| FCM or Google outage | None |

The ring timeout (45 s) and "Missed call" notification make failures visible rather than silent.

## 7. Privacy summary

- Data messages only; minimal opaque payloads; no content.
- FCM tokens are sensitive identifiers: never logged, never shown in the Admin Console.
- No Firebase Analytics, Crashlytics or other Firebase products are included (they are separate
  dependencies — do not add them).
- The FCM Android library brings Firebase Installations as a dependency. Disable FCM auto-init until the
  user has logged in (manifest meta-data `firebase_messaging_auto_init_enabled = false`, then enable after
  login) and delete the FCM token on logout. **Verify** what the pinned Firebase versions collect.
- `google-services.json` contains client identifiers, not server secrets, but it is still kept out of the
  public repository and injected at build time.
