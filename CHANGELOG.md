# Changelog

Versions of the Android app (`versionName`) and the backend (`package.json`). The in-app update number
(`versionCode`, "v170" and so on) still goes up with every build sent to the phones.

## Unreleased

### Just Notes
- **The app is now called Just Notes** and opens on your notes: write notes with a title and text; they are
  saved on their own and kept on the server. Tap the "Just Notes" title to open the chats, Map and camera; Back returns to the
  notes. The backend needs a redeploy for the new `notes` table.

### Look (latest)
- **Chat doodles:** the light doodle background is back in chats, like WhatsApp.
- **Green again:** the light Material look now uses the owner's green palette instead of wallpaper colours.
- **Light Material look:** the glass is gone. The app is always light and looks like Google's own messaging app:
  wallpaper colours on Android 12+, plain chat rows, colourful avatars, a "Connect" button, a Material navigation
  bar, grey and colour message bubbles and a Material chat top bar with a ⋮ menu. The camera, snaps and calls stay
  dark. The entries below describe the earlier glass looks of the same day.

### Look
- **Dark glass:** the app is always dark, as in the mockup the owner approved:
  - near-black screens with a faint teal and warm light;
  - glass cards, buttons, chips, the All/Unread switch and the tab bar, with a silver camera button;
  - mint bubbles, badges and send button; dark grey-teal avatars.
- **Chat:** the person's name sits in a glass capsule that opens the chat options; the timer pill is centred
  under it; "Read" is shown as text. The doodle background is gone.
- **Motion:** iOS-like spring animations:
  - the All/Unread glass slides like liquid (tap or drag), and the tab bar glass slides between tabs;
  - buttons and cards bounce when pressed, chats cascade in, and the large title shrinks on scroll;
  - badges pop, the send button grows in, and the reaction picker rises in.
- **Premium finish:**
  - glass with a cut-edge shine, a fine frosted grain and deeper edges;
  - brushed-silver titles, mint with depth and a soft glow;
  - dialogs and menus in neutral glass greys, refined typography.
- **Camera:** a shutter ring with a recording arc, a capture blink, smooth start and flip, a zoom pill, glass
  controls and review actions, and a permission card that opens settings when access is blocked.
- **Map:** a rounded map card on the dark screen (status bar and tab bar readable again), no empty squares while
  zooming, markers that drop in, and a beacon on my marker.
- **Calls:** a glass control dock, spreading rings while waiting, and an Accept button that breathes.
- **Screens** push and pop with a soft spring.

## 0.2.0 — 2026-11-22

The first named release since 0.1.0, collecting the work shipped as builds v154–v173.

**Owner actions for this release:**
- Redeploy the backend if this has not been done since v169. The view-once migration and the busy-call fix both need it.

Nothing listed here has been tried on a real phone unless `docs/IMPLEMENTATION_PLAN.md` says so.

### Chat
- **Reference designs:** chat, login, video call and disappearing-messages screens match the owner's reference designs, in the app's green theme.
- **Disappearing messages (D-30):** Off, 1 min, 5 min, 1 h or 24 h after seen. Either person sets it for both.
- **Emoji effects:** a full-screen effect plays for single emoji (hearts, confetti, fire, and more).
- **Typing:**
  - The typing bubble shows inside the conversation.
  - New messages scroll into view only when you are at the bottom; otherwise a ↓ button shows how many are waiting.
  - Autocorrect is on, and a counter shows how many characters are left near the limit.
  - Typing no longer lags.
- **Message actions:** swipe right to reply, double-tap for ❤️, and tap a quote to jump to the original message.
- **Layout:**
  - Messages sent close together are grouped.
  - Sending and receiving use soft animations.
  - Screens slide when changing.
  - The background is a light doodle pattern with mini doodles.

### Snaps (D-27)
- **Camera:**
  - Pinch to zoom, with sharper photos.
  - Tap to focus; double-tap to flip the camera.
  - Flash on both cameras; the front camera uses a white screen.
  - Night mode.
  - Mute the sound of a video.
- **Before sending:**
  - Save your own snap to the gallery.
  - Add a caption and draw with a pen.
- **Gallery:** pick a photo, or a video of up to 30 s (re-encoded to 720p), and send it as a snap.
- **View once:** the snap is shown a single time, with no replay.

### Calls
- **Light:** the torch on the back camera, a screen glow on the front camera.
- **Night mode for the sent video.**
- **Busy handling:**
  - Only one call or walkie-talkie per person, even when several people call at the same moment.
  - A "Missed call" line appears when you were busy.
  - A clear "They are on another call" message for the caller.

### Map
- Full-screen map with round markers; names show only on tap.
- Chat and call buttons on each marker.
- Panning is smoother.

### Speed and reliability
- **Faster build:** the phones get a non-debuggable "phone" build. It installs over the previous app as an in-app update.
- **Crash card:** it now also reports freezes (ANR) and system kills, with the app's recent errors.
- **Security and code-quality audit fixes:**
  - Oversized snap uploads are cut off.
  - npm advisories are cleared.
  - Heavy snap-editor work runs off the main thread.
