# Vault UI/UX upgrade: implementation report

Date: 2026-11-20 (UTC). Brief: the owner's "Vault — UI/UX upgrade only". Audit first: [ui-audit.md](ui-audit.md).
Only Android UI code, strings and docs changed. Backend, API, database, authentication, LiveKit/WebRTC,
message delivery and notification infrastructure are untouched.

## Screens audited

Login, change password, Home (chat list), Chat, Call (voice and video, minimised call, walkie-talkie dialog),
Map, Snap camera and viewer, Inbox / Show QR / Scan QR, location gate, update and crash banners (audit §1).

## Screens redesigned

| Screen | What changed |
|---|---|
| **Home (inbox)** | Header: "Vault" title, search icon that expands/collapses the search field (with a clear button), Settings and a filled "+"; animated All/Unread pills; rows with a 52 dp avatar (ring when a snap waits), name + 🔥 streak, right-aligned time (accent when unread), status line with the Snapchat square, **unread count badge**, camera button; inset hairline dividers; rows animate to new positions; four pulsing placeholder rows while the first list loads |
| **Chat** | Header: back, avatar, semibold name, "typing" with animated dots that slide in/out, three call actions as consistent round buttons; deletion rule as a small pill opening a **bottom sheet** with the full rule; **bubbles** (mine right in the accent green, theirs left on the surface, 18 dp corners tightening inside a run, max 78 % width), time under the last message of a run, message appearance animated; sending bubble faded, failed bubble in the error colour with a warning icon, Retry and Discard; empty chat with avatar and name; composer: 48 dp touch targets, rounded input with an accent outline on focus, send button that scales in when enabled |
| **Settings (new)** | Account (initial, username), Privacy and security (Change password, screen protection info, message deletion info, Location → Android's permission page), Notifications (Android's notification page for Vault, calls over other apps), Appearance (theme follows the phone), About (version) |
| **Call** | Larger avatar (136 dp) with a soft breathing ring while starting/ringing/connecting/reconnecting; **connection-quality bars** with "Good / Fair / Poor connection" from the call's real statistics (RTT and packet loss); everything else (controls, zoom, switch, minimise, end) unchanged |
| **Bottom bar** | Round camera button with a shadow and press-scale feedback |
| **Navigation** | Short directional transitions (slide from the right + fade, 220 ms); back reverses them |

## Components created (`ui/components/Polish.kt`)

`pressScale` (modifier), `VaultIconButton`, `UnreadBadge`, `TypingDots`, `SectionHeader`, `SettingsGroup`,
`SettingsRow`, `SettingsDivider`, `SkeletonRow`, `SignalBars`. `InitialsAvatar` gained an optional
highlight ring.

## Design system improvements

- `ui/theme/Tokens.kt`: `Spacing`, `Radii`, `Sizes` (touch 48 dp, avatar sizes, content max width) and `Motion`
  (150/220/320 ms).
- `Theme.kt`: one `Shapes` set (8/12/16/24 dp) and a refined type scale (semibold titles, slightly tighter
  headline tracking, 22 sp body line height). Colours unchanged (owner's six greens, light and dark).

## Files modified

- Added: `ui/theme/Tokens.kt`, `ui/components/Polish.kt`, `ui/settings/SettingsScreen.kt`,
  `docs/ui-upgrade/ui-audit.md`, this report.
- Modified: `ui/theme/Theme.kt`, `ui/components/Avatar.kt`, `ui/home/HomeScreen.kt`, `ui/home/BottomMenu.kt`,
  `ui/chat/ChatScreen.kt`, `ui/call/CallScreen.kt`, `ui/navigation/VaultChatNavHost.kt`, `res/values/strings.xml`,
  `docs/IMPLEMENTATION_PLAN.md`, `docs/15-android-ui-ux.md`, `docs/17-open-decisions.md`.

## Existing functionality preserved

ViewModels, controllers and repositories are unchanged and reused. Kept as they were: swipe right → camera,
streaks and snap buttons, filters, reactions (long press), replies, missed-call lines with call back,
walkie-talkie, waiting-snaps banner, privacy text field (no keyboard learning, nothing saved), FLAG_SECURE,
draft restore, retry/discard of unsent messages, call controls, zoom, camera switch, minimise, auto-hide of
video controls, permissions prompts, the location gate and update banner.

One behaviour fix in navigation: calls now also ring, and live updates keep running, while the **Map or
Settings** screen is open (they were missing from the list of signed-in screens, so an incoming call was not
noticed on the Map).

## Build and tests

- Android cannot be built in this container (the Android Gradle plugin repository is not reachable); the
  build, unit tests and lint run in CI (`.github/workflows/ci.yml`: `testDebugUnitTest assembleDebug
  lintDebug`, plus a release build). The result is reported on the pull request; nothing here claims it
  passed before CI says so.
- Not executed here: on-device checks of navigation, sending/receiving, voice/video calls, keyboard,
  light/dark, screen sizes and font scaling. They need the owner's phones (the app is installed from the
  in-app update after merge).

## Known UI issues / limits

- The chat list shows no last-message preview: the chat list API returns counts and times only.
- Bubbles replace the Snapchat-style bars the owner chose on 2026-11-19 (the new brief asks for balanced
  sent/received bubbles); easy to revert if the owner prefers the bars.
- The quality bars use the loss ratio over the whole call (the sample's counters are cumulative), so they
  recover slowly after a bad patch.

## Unsupported UI ideas not implemented (need backend or a decision)

- Online / last seen indicators (no presence data reaches the app).
- Typing indicator in the chat list (typing is only sent to an open chat).
- In-call indicator on chat rows (needs the active call passed to Home; possible later, UI only).
- Pinned conversations, read/delivery ticks, message previews.
- Disappearing-message choices (1 min … 7 days): deletion is fixed by D-01/D-02; the sheet explains it instead.
- In-app notification switches (message preview, sound, vibration, per-chat): the app has no such settings;
  Settings opens Android's own notification page for Vault instead.
- Sign-out and a location switch in Settings: removed by the owner on 2026-11-02; not brought back.
- Devices list: no device management exists.
