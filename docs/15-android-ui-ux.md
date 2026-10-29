# 15 — Android UI and UX

Status: **Proposed design**, partly **Implemented**. The screens built so far and their current look
(matched to the owner's interactive prototype on 2026-10-29) are listed in `docs/IMPLEMENTATION_PLAN.md`;
where this document and the built screens differ, the plan records what exists.

## 1. Design principles

- Minimal, calm and professional. Material 3 (Jetpack Compose), flat surfaces, one accent colour,
  generous spacing. **No glassmorphism**, no heavy blur, gradients or decorative animation.
- Light and dark themes following the system setting.
- Every screen has explicit loading, empty, error and offline states.
- Plain language. Errors say what happened and what to do next.
- **No** My Profile screen, profile photos, avatars, bios, user search, discovery, stories, reactions, or
  media pickers.

## 2. App structure

- Single activity, Compose Navigation. Calls run in a separate activity (`CallActivity`) so they can show
  over the lock screen, use picture-in-picture and survive navigation changes.
- Architecture: MVVM — Compose screens observe `StateFlow` from ViewModels; repositories wrap the REST
  client, WebSocket client and LiveKit.
- Proposed minimum Android version: open decision D-09 (depends on the two users' phones; Android 10 / API 29
  is a reasonable floor). Target SDK: latest stable.

### Navigation map

```
Splash ─► Login ─► (Change password, if required) ─► Home
Home ─► Inbox ─► Show my QR
             ├─► Scan QR ─► Request sent
             └─► Incoming request ─► Accept / Reject
Home ─► Chat (only when connected)
Home ─► Outgoing call ─► Active call
(any) ◄── Incoming call (full-screen / notification) ─► Active call
Home ─► Settings ─► Change password · Call quality · Notifications & battery · About · (Location sharing, if approved) · Log out
```

## 3. Screens

### 3.1 Login
- Fields: Username, Password (show/hide toggle). Button: Log in. No "Sign up", no "Forgot password" link —
  instead a small line: "Forgot your password? Ask the administrator to reset it."
- States: loading spinner on the button; `INVALID_CREDENTIALS` → "Username or password is incorrect";
  `429` → "Too many attempts. Try again in N minutes."; offline → "No internet connection".
- Autofill hints set for password managers.

### 3.2 Change password (forced or from Settings)
- Current password (after an admin reset, the user types the temporary password here), new password,
  confirm. Live length check (min 12). Explanation when forced: "Your password was
  reset. Choose a new one to continue."

### 3.3 Home (main screen)
- **Not connected:** empty state illustration-free card: "You're not connected yet." Button → Inbox.
- **Connected:** a single contact card: peer's username, presence ("In chat" / "Online" / "Last seen …" —
  open decision whether to show last seen, D-11), and three large buttons: **Message**, **Video call**,
  **Voice call**.
- Top bar: app name, Settings icon. Warning banner if notifications/full-screen permission is missing.

### 3.4 Inbox
- Sections: "Connection" (status), "Requests" (incoming/outgoing pending with expiry countdown), actions
  **Show my QR** and **Scan a QR**. When connected, actions are hidden and the screen says "You're connected with <username>".
- Empty: "No pending requests."

### 3.5 Show my QR (QR generator)
- Large QR centred on a white quiet zone (even in dark theme, for scan reliability), brightness raised
  while visible, countdown ring showing time until refresh, text: "Ask the other person to scan this code.
  It changes every minute and works once."
- Auto-refresh at ~45 s; on failure show "Couldn't refresh code — Retry".
- When a request arrives, a bottom sheet appears: "<username> wants to connect" — **Accept** / **Reject**.

### 3.6 Scan QR
- CameraX preview with ML Kit barcode scanning (bundled model, on-device) — **verify** licence/size.
- Camera permission rationale first; denied → explanation + "Open settings".
- After scan: "Request sent. Waiting for <owner> to accept…" with Cancel.
- Errors: expired/used → "This code has expired. Ask them to show a new one."; already connected → info message.
- Only `vaultchat://connect` payloads are accepted; any other QR content → "This isn't a Vault Chat code." (never opened as a link).

### 3.7 Chat
- Top bar: back arrow, peer username, presence, call buttons.
- Persistent notice (small, top): "Messages disappear for both of you when either leaves this chat."
  (Text will depend on D-01.)
- Message list (newest at bottom), bubbles with time; sending/sent/failed state; failed → tap to retry.
- Input: multiline text field (max 4,000), character counter after 3,500, Send button. No attachment,
  camera, mic, emoji-sticker or GIF buttons.
- Back → confirm? **No confirmation dialog** by default (it would slow the intended disappearing behaviour);
  D-02 may revisit.
- On `conversation.cleared` while open: list empties with a short notice "Chat cleared".
- Offline: banner "Offline — messages will send when you're back online" (sends still subject to epoch rules).

### 3.8 Outgoing call
- Peer username, "Calling…", then "Ringing…" ; local camera preview (video calls); buttons: mute, camera
  on/off, speaker, **Cancel**. Timeout → "No answer" with "Call again".

### 3.9 Incoming call
- Over lock screen via full-screen intent (if permitted) or heads-up `CallStyle` notification with
  **Decline** / **Answer**. Shows username and call type. Ringtone and vibration from the `calls` channel.

### 3.10 Active call
- Video: remote video full screen; local preview in a draggable corner tile; tap to hide/show controls.
- Voice: peer username, call timer, large controls; proximity sensor turns screen off at the ear.
- Controls bar: **Mic**, **Camera**, **Switch camera** (own front/back), **Flip their camera** (the other person’s front/back, with a notice on their phone), **Audio output** (earpiece/speaker/Bluetooth picker),
  **End** (red). All 48 dp+ touch targets with content descriptions.
- Connection state chip: "Connecting…", "Connected", "Reconnecting…", "Poor connection", "Video paused —
  poor connection". Remote mute/camera-off indicators.
- Picture-in-picture when leaving the app during a video call.
- Hidden diagnostics overlay (long-press timer in debug/test builds): path, RTT, loss, resolution, FPS, bitrate.

### 3.11 Call permission and network error states
| State | UI |
|---|---|
| Mic denied | Blocking dialog: "Vault Chat needs the microphone for calls." → Allow / Open settings |
| Camera denied | Video call offers "Start as voice call"; camera button shows disabled with reason |
| Notifications denied | Banner on Home + Settings item with "Fix" |
| Full-screen intent not allowed (Android 14+) | Settings item "Show incoming calls on lock screen" → system page |
| Battery optimisation on | Settings item with per-manufacturer guidance |
| Backend unreachable | "Can't reach the server. Check your connection." + Retry |
| LiveKit unreachable (`503 MEDIA_UNAVAILABLE`) | "Calling is temporarily unavailable." |
| Network lost mid-call (> 30 s) | Call ends: "Call ended — connection lost." + "Call again" |

### 3.12 Settings
- Change password · Call quality (**Auto** / **Data saver on mobile data**) · Notifications & battery
  (permission status, battery optimisation, full-screen intents) · About (version, build, APK SHA-256 to
  compare with the admin's published value) · Log out.
- **No profile section.**

### 3.13 Location sharing (only if D-06 approved)
- Explanation screen, toggle, current status ("Sharing · last sent 2 min ago · precise"), "Last viewed by
  admin", Stop sharing. Persistent indicator on Home while on. See [11](11-location-sharing-proposal.md).

## 4. Accessibility

- TalkBack labels on all icons and controls; call state changes announced (live regions).
- Supports font scaling up to 200 % without clipping (test at largest size).
- Contrast ≥ 4.5:1 for text; do not rely on colour alone (icons + labels for mute/camera state).
- Touch targets ≥ 48 × 48 dp; End-call separated from other controls to avoid accidental taps.
- Respect "Remove animations" system setting.

## 5. Responsive layouts

- Phones in portrait are primary; landscape supported on call screen (video rotates, controls stay reachable).
- Window size classes for large phones/foldables/tablets: chat content max width ~640 dp; call controls centred.
- Handles display cutouts and gesture navigation insets (edge-to-edge with proper insets).

## 6. Copy guidelines

- Short sentences, no jargon ("connection lost", not "ICE failed").
- Never promise what the system cannot guarantee: "Messages are deleted from the server and both apps"
  — not "messages can never be recovered".
