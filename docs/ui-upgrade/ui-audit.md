# Vault UI/UX audit (Phase 1)

Date: 2026-11-20 (UTC). Scope: the Android app's UI layer only (`android/app/src/main/java/app/vaultchat/ui/`).
Written before any code change for the owner's "UI/UX upgrade only" brief. Backend, API, database,
authentication, LiveKit/WebRTC and delivery logic are out of scope and are not touched.

## 1. Current screens and navigation

Single activity, Compose Navigation (`ui/navigation/VaultChatNavHost.kt`), no screen transitions defined
(Navigation's default cross-fade).

| Route | Screen (file) | Notes |
|---|---|---|
| `LoginRoute` | `LoginScreen` (`ui/login`) | Username + password; no signup (by design) |
| `ChangePasswordRoute(forced)` | `ChangePasswordScreen` (`ui/password`) | Forced after an admin reset; `forced = false` exists but nothing opens it |
| `HomeRoute` | `HomeScreen` (`ui/home`) | Chat list; header with title and "+"; search field; All/Unread pills; rows; bottom bar (Chat, Camera, Map); swipe right → camera; one-time permission dialogs |
| `ChatRoute` | `ChatScreen` (`ui/chat`) | Header (back, avatar, name, typing line, walkie-talkie, video, voice); deletion-rule line; waiting-snaps banner; message list; reply strip; composer (camera, privacy EditText, send) |
| `CallRoute` | `CallScreen` (`ui/call`) | Voice and video in one screen; ringing/connecting/active/reconnecting/ended; controls; draggable self-view; zoom and switch menus; minimise |
| — | `MiniCall`, `TalkDialog` (`ui/call`) | Floating minimised call; walkie-talkie dialog |
| `MapRoute` | `MapScreen` (`ui/map`) | OSM tiles, pins, chips; bottom bar |
| `SnapCameraRoute`, `SnapViewerRoute` | `ui/snap` | Camera, recipient picker, viewer |
| `InboxRoute`, `ShowQrRoute`, `ScanQrRoute` | `ui/connect` | Pairing by QR |
| — | `LocationRequiredScreen` (`ui/location`), `UpgradeRequiredScreen`, `UpdateBanner`, `LastCrashCard` (`ui/about`) | Gates and banners |

There is **no Settings screen**: the owner removed the ⋮ menu (with sign-out and the location "Turn off") on
2026-11-02 (CLAUDE.md, D-06). There is no profile screen by design (docs/01, docs/15).

## 2. Existing design system

- **Colours** (`ui/theme/Theme.kt`): the owner's six greens (`VaultGreen.Deepest … Mint`, 2026-11-19), light and
  dark Material 3 schemes following the system setting. Light: mint background, near-white surface. Dark:
  `#051F20` background, `#0B2B26` surface. Call screen always dark green.
- **Typography**: Material 3 defaults with three tweaks (headlineMedium SemiBold, titleMedium Medium, labelLarge 16 sp).
- **Shapes**: Material defaults; screens hard-code `RoundedCornerShape(10/12/14/16/24/28/50)`.
- **Spacing**: hard-coded per screen (4, 6, 8, 10, 12, 16, 20, 24 dp); no tokens.
- **Components** (`ui/components`): `InitialsAvatar`, `TopBar`, `PrimaryButton`, `StatusBanner`, `EmptyState`,
  `FullScreenLoading`, `PasswordField`, `AppIcons` (vector paths). `RoundIconButton` lives in `HomeScreen.kt`.
- **Motion**: almost none. `AnimatedVisibility` fades the call controls; `animateScrollToItem` for new messages.

## 3. State management

ViewModels expose `StateFlow`s (`HomeViewModel`, `ChatViewModel` → `ChatController`, `CallSession` →
`CallController`, `LoginViewModel`, connect view models). Screens collect with `collectAsStateWithLifecycle`.
The upgrade reuses all of them unchanged.

## 4. States today

| Screen | Loading | Empty | Error / offline |
|---|---|---|---|
| Home | none visible (blank list while `loading`) | "Not connected yet" + Connect button; "No matches" for search | "Server unreachable" banner with Retry |
| Chat | full-screen spinner (`ENTERING`) | one line "No messages" | banner per `ChatNotice`; `UNAVAILABLE` error banner; outgoing "Sending…" / "Not sent · Retry · Discard" |
| Call | phase text | — | ended reasons as text; reconnecting text |

## 5. Problems and inconsistencies

1. **No shared tokens**: a dozen corner radii and spacings, so screens drift apart.
2. **Home header** is plain text + "+"; the search field is always open and takes a full row above the
   filter pills, pushing the first chat down.
3. **Chat rows**: the time is part of the status line, not aligned to the right; unread count is only in text;
   no loading placeholder, so the list "pops" in.
4. **Chat messages**: the Snapchat-style bars (owner, 2026-11-19) put every message on the left, so "mine" and
   "theirs" are told apart only by a 2 dp bar colour and a label; the timestamp sits under every single message.
   The owner's new brief asks for balanced sent/received bubbles with grouping.
5. **Composer**: the input is a bare EditText on the background colour (needed for the no-learning keyboard
   flag, docs/07 §4), with no focus feedback; the send button snaps between states.
6. **Outgoing states** use a faded bubble plus a separate text row; the failed row's buttons crowd the line.
7. **Deletion rule** (D-01/D-02) is a tap-to-expand line; it reads like a link, not information.
8. **Call screen**: functional but dense; the status line and avatar have no ringing/connecting feedback; the
   live statistics overlay is debug-only, so release users have no connection-quality hint even though
   `CallController.liveStats` has real RTT and packet counts.
9. **No settings**: password change (`forced = false`), the app version and notification settings are
   unreachable from the app.
10. **Transitions**: the default cross-fade only; opening a chat does not feel directional.
11. **Touch feedback**: plain ripples; no press feedback on the round bottom-bar camera.

## 6. What the brief asks for vs. what exists (no backend changes allowed)

| Brief item | Exists? | Plan |
|---|---|---|
| Last-message preview in rows | **No** (chat list API returns counts/time only; message text is not kept on the list) | Not added; status line keeps "New message / Tap to chat · time" |
| Unread count badge | Yes (`unreadCount`) | Badge on the right |
| Pinned conversations | No | Not added (documented) |
| Online / last seen | No presence data in the app (chat presence lease is server-side only) | Not shown; reusable `PresenceDot` not added to avoid fake states |
| Typing indicator | Yes, inside an open chat (`peerTyping`) | Animated dots in the chat header; not in the list (no data there) |
| In-call indicator | Yes (`AppContainer.activeCall`) | Not added to the list in this pass (would need wiring the active call into Home; kept for later) |
| Read/delivery ticks | No (only sending/failed locally) | Sending/failed shown on the bubble; no ticks invented |
| Disappearing-message options (1 min … 7 days) | **No**: deletion is fixed by D-01/D-02 (on exit, 60 s lease, 24 h backstop) | A bottom sheet that explains the fixed rule; no selectable options (would pretend) |
| Notification settings toggles | **No** in-app settings; push behaviour is fixed in code (`Push.MESSAGE_NOTIFICATIONS`) | Settings screen links to Android's own notification settings for Vault (real), no fake switches |
| Settings: account, privacy, appearance, devices, about | Partly: username, change password, version exist; theme follows the system; no device list | New Settings screen with only real rows; **no Log out** and no location "Turn off" (owner removed both, 2026-11-02) |
| Call quality indicator | Yes (`liveStats`: RTT, packets lost/received) | Small bars indicator from real RTT/loss; hidden when no sample |
| Message preview on lock screen | No setting | Not added |

## 7. Proposed improvements (Phases 2–7)

- **Design system**: `ui/theme/Tokens.kt` (spacing, radii, sizes, motion durations); Material `Shapes` set
  from the radii; typography refined (title weights, tighter body line height); shared components:
  `VaultIconButton`, `UnreadBadge`, `SectionHeader`, `SettingsRow`, `TypingDots`, `pressScale` modifier,
  `SkeletonRow`.
- **Inbox**: header with "Vault" title, search icon that expands/collapses the search field, Settings and "+";
  rows with avatar, name, right-aligned time, status line, unread badge, streak and camera; skeleton rows while
  loading; animated item placement.
- **Chat**: balanced bubbles (mine right in primary green, theirs left on surface), grouping with tighter
  corners inside a run, time under the last bubble of a run, inline sending/failed states, header with animated
  typing dots, rounded composer with focus border and an animated send button, deletion rule as a sheet.
- **Settings**: new `SettingsRoute` reached from the Home header.
- **Calls**: larger avatar with a soft pulse while ringing/connecting, cleaner status, network-quality bars from
  real stats, refined control buttons. No change to call logic.
- **Motion**: directional slide + fade between screens; message appearance via `animateItem`; press scale on
  round buttons. Short durations (150–250 ms).

## 8. Files that will be modified or added

- Modified: `ui/theme/Theme.kt`, `ui/components/Avatar.kt`, `ui/home/HomeScreen.kt`, `ui/home/BottomMenu.kt`,
  `ui/chat/ChatScreen.kt`, `ui/call/CallScreen.kt`, `ui/navigation/VaultChatNavHost.kt`,
  `res/values/strings.xml`.
- Added: `ui/theme/Tokens.kt`, `ui/components/Polish.kt`, `ui/settings/SettingsScreen.kt`.
- Docs: this audit, `docs/ui-upgrade/implementation-report.md`, `docs/IMPLEMENTATION_PLAN.md`, `docs/17` (the
  Settings screen comes back without sign-out), `docs/15` (layout note).
