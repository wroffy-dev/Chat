# First test on two phones — step by step

Status: **Written, not yet carried out.** This runs everything on your own computer and two Android
phones on the same Wi-Fi. Nothing touches the VPS. Expect the first attempt to find problems: that is
its purpose. Write down what happens (template at the end) and send it back.

Placeholders look like `<this>`. Never paste a real password or key into an issue, chat or screenshot.

## 0. What you need

- A computer (Linux, macOS or Windows) with: **Docker** (Desktop or Engine), **Node.js 22.12+**,
  **Android Studio** (or JDK 17 + Android SDK API 35), and `git`.
- Two Android phones, Android 10 or newer, with **Developer options → USB debugging** on, and a USB cable
  (or copy the APK over instead).
- An authenticator app (for the administrator's TOTP code), on any phone.
- Computer and phones on **the same Wi-Fi**, and a network that lets devices talk to each other
  (some guest/office Wi-Fi blocks this — a home router is usually fine).

Find the computer's LAN address (`<lan-ip>`, e.g. `192.168.1.20`):
- macOS: `ipconfig getifaddr en0` · Linux: `hostname -I` · Windows: `ipconfig` (IPv4 Address).

Allow incoming connections on the computer's firewall from the local network for: TCP 3000 (backend),
TCP 7880 and 7881 (LiveKit), UDP 7882 (call media). Windows asks the first time; on macOS allow Docker
and Node when asked.

## 1. Get the code

```sh
git clone https://github.com/wroffy-dev/Chat.git && cd Chat
git checkout claude/new-session-8x6iea      # until PR #1 is merged into main
```

## 2. Database and LiveKit (Docker)

```sh
cp deploy/.env.dev.example deploy/.env.dev
```
Edit `deploy/.env.dev`:
- `POSTGRES_PASSWORD=` any local password.
- `LIVEKIT_KEYS=devkey: <secret>` — make the secret with `openssl rand -base64 36` (32+ characters).
  Keep the key name `devkey`: the dev LiveKit config signs webhooks with it.
- `DEV_LAN_IP=<lan-ip>` — this publishes LiveKit on your LAN address and tells it to offer that
  address to the phones. Without it, calls connect but no audio/video flows.

```sh
docker compose --env-file deploy/.env.dev -f deploy/docker-compose.dev.yml up -d
docker compose --env-file deploy/.env.dev -f deploy/docker-compose.dev.yml ps    # both "running"/"healthy"
```
PostgreSQL stays on 127.0.0.1 (not reachable from the phones, which is intended).

## 3. Backend

```sh
cd backend
cp .env.example .env
```
Edit `backend/.env`:
- `DATABASE_URL=postgresql://vaultchat:<POSTGRES_PASSWORD>@localhost:5432/vaultchat`
- `ADMIN_TOTP_ENC_KEY=` the output of `node -e "console.log(require('crypto').randomBytes(32).toString('base64'))"`
- Uncomment and set the LiveKit lines (same key and secret as `LIVEKIT_KEYS`):
  ```
  LIVEKIT_PUBLIC_URL=ws://<lan-ip>:7880
  LIVEKIT_API_URL=http://<lan-ip>:7880
  LIVEKIT_API_KEY=devkey
  LIVEKIT_API_SECRET=<secret>
  ```
  (`LIVEKIT_API_URL` uses `<lan-ip>`, not 127.0.0.1, because `DEV_LAN_IP` moves LiveKit's ports there.)
- Leave `HOST=0.0.0.0` so the phones can reach port 3000. Leave FCM unset for this first test.

```sh
npm ci
npx prisma migrate deploy
npm run build
npm start
```
Check from the computer: `curl http://<lan-ip>:3000/readyz` → `{"status":"ready"}`. Then open
`http://<lan-ip>:3000/healthz` in a phone's browser — if it does not load, the firewall or Wi-Fi is
blocking the phones; fix that before going on.

Leave this terminal running. In a second terminal (`cd backend`):

```sh
npm run cli -- admin:create --username <admin-name>   # password (16+ chars), then scan the TOTP secret
npm run cli -- user:create --username <first-user>    # prints a temporary password
npm run cli -- user:create --username <second-user>
```
Note the two temporary passwords; each user changes theirs at first sign-in.

Admin Console (on the computer only): `http://127.0.0.1:3001/admin` — password + TOTP code.

## 4. Build and install the app

```sh
cd android
./gradlew :app:assembleDebug -Pvaultchat.apiBaseUrl=http://<lan-ip>:3000/
```
The debug build allows plain HTTP only to `<lan-ip>` (and the emulator's host); the release build stays
HTTPS-only. Install on each phone (USB, one at a time):

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
(Or copy `app-debug.apk` to the phone and open it, allowing "install unknown apps" just for that.)
The debug app is called Vault Chat and installs as `app.vaultchat.dev`, separate from any later release.

Optional second round: build with `-Pvaultchat.systemCalls=true` as well to try Android's call framework
(headset/car buttons). Do the first round **without** it.

## 5. Tests, in this order

Keep both apps **open and in front** unless a step says otherwise: without Firebase, a closed app does
not ring.

1. **Sign in** on each phone with its temporary password; set a new one. Expected: Home screen.
2. **Pair** (docs/06): phone A → Inbox → Show my QR; phone B → Scan QR → A accepts. Expected: both Home
   screens show the connection. Note how well the camera reads the code (distance, light).
3. **Voice call** A → B, answer, talk 1 minute, A hangs up. Then B → A, B hangs up.
4. **Video call** both ways, 2 minutes each. Switch camera, mute, camera off/on, speaker on/off.
   The debug overlay shows live numbers (RTT, loss, resolution, fps) — note what you see.
5. **Decline / no answer / cancel:** B declines; B ignores it until it stops ringing (45 s);
   A cancels while it rings.
6. **Cleanup (T-CLEAN):** after each way of ending, check within ~2 s that the green camera/microphone
   dot in the status bar is gone on both phones and no call notification is left.
7. **Background (T-BG-VOICE / T-BG-VIDEO):** in a call, press Home, lock the screen for 5 minutes.
   Expected: audio continues, an ongoing-call notification shows. Note what happens to video.
8. **Network:** during a video call turn Wi-Fi off on one phone for ~10 s, then on. Note whether it
   reconnects; after 30 s without network the call should end.
9. **Chat** (D-01, D-02):
   - Send messages both ways; both see them.
   - A presses Back (leaves the chat): the messages B has seen disappear from both phones.
   - A sends a message while B is not in the chat, then B opens the chat and leaves: it disappears.
   - In the chat, put one phone in the background for over a minute: it counts as leaving.
10. **Admin Console** (computer): Calls page lists each call with outcome and both phones' quality
    numbers; Users page shows both users' sessions.

## 6. Stop and clean up

`Ctrl+C` the backend. `docker compose --env-file deploy/.env.dev -f deploy/docker-compose.dev.yml down`
(add `-v` to delete the local database too). Uninstall the debug app when done.

## 7. What to send back

Phone models and Android versions (also settles D-08/D-09), and for each test step:

| Step | What you did | What happened | Expected? |
|---|---|---|---|
| e.g. 4 | Video A→B, 2 min | Picture froze after 30 s, audio fine | No |

Plus, from the Calls page, the quality line for 2–3 of the calls; and anything that confused you in the
app. If something fails early (e.g. step 3 never connects), stop there and send what you have, with the
last ~50 lines of the backend terminal (it never logs passwords, tokens or message text).
