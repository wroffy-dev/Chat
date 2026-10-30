# 08 — Video and Audio Calling (Highest Priority)

Status: **Proposed**. Not implemented. Facts about LiveKit come from its source repositories,
checked 2026-10-24 (see [16 §15](16-deployment-and-operations.md#15-sources-checked)).

## 1. Goals

| Goal | Meaning |
|---|---|
| Up to 1080p @ 30 fps | Used **only when both devices and the network can sustain it**. Never forced |
| Audio first | When bandwidth drops, video resolution/frame rate falls (or video pauses) before audio suffers |
| Low latency, fast setup | Caller pre-connects while ringing; target setup times in [09](09-call-quality-test-plan.md) |
| Recovery | Survives Wi-Fi ↔ mobile switches and short outages without user action |
| Clean exit | Camera, mic, audio mode, foreground service, LiveKit room all released on every end path |

Voice calls are the same flow with the camera off (no video track published).

## 2. Why an SFU (LiveKit) for a 1:1 app

| Factor | LiveKit SFU on the VPS | Direct P2P WebRTC |
|---|---|---|
| Connectivity | Each phone only needs to reach **one** server with a public IP. Works behind carrier-grade NAT, which is common on mobile data | Fails when both sides are behind restrictive/symmetric NAT; then it needs TURN relay anyway |
| Adaptation | SFU congestion control, simulcast layer selection per receiver, dynacast, pause/resume video under congestion | Only end-to-end bandwidth estimation |
| Reconnection | SDK + server handle ICE restart and session resume | Must be built by hand |
| Latency | Extra hop via the VPS. **If the VPS is far from both users (e.g. in Europe while both users are in India), every packet travels there and back** — often the largest latency factor | Shortest path when a direct route exists |
| Server cost | Uses VPS bandwidth ([03](03-capacity-bandwidth-and-cost.md)) | ~₹0 when direct; relay traffic when not |
| Privacy | Media is decrypted at the SFU (hop-by-hop encryption) | DTLS-SRTP end to end between the phones (unless relayed through TURN, which still cannot decrypt) |

Decision: **LiveKit SFU only in the MVP.** One calling architecture, not two. Direct P2P may be
benchmarked later as a separate experiment; it is not assumed to be better.

Action: confirm the VPS data-centre region. If it is far from both users, measure RTT (test T-RTT) early —
it decides whether the existing VPS is suitable for media.

## 3. Media flow

```
 Phone A                         VPS: LiveKit SFU                         Phone B
 camera+mic ─► encoder ─► SRTP ═══ UDP 7882 (or TCP 7881 / TURN) ══► SFU ══► SRTP ─► decoder ─► screen+speaker
 screen+speaker ◄─ decoder ◄─ SRTP ◄══════════════════════════════════ SFU ◄══ SRTP ◄─ encoder ◄─ camera+mic
```

- Each phone publishes one audio track and (for video calls) one video track with simulcast layers.
- The SFU forwards packets; it does not transcode. It chooses which simulcast layer to forward to the
  receiver based on the receiver's bandwidth and rendered size (adaptive stream).
- Signalling (SDP/ICE/track events) runs over a WebSocket to `wss://rtc.<domain>` through Traefik.
  Media never uses the HTTP proxy.

## 4. Signalling and call state

The app backend owns "who is calling whom". LiveKit owns the media session.

### 4.1 Call state machine (`calls.status`)

```
           ┌──────────── cancel (caller) ───────────► cancelled
           │──────────── decline (callee) ──────────► declined
 ringing ──┼──────────── ring timeout (45 s) ───────► missed
           │──────────── setup error ───────────────► failed
           └── accept ──► accepted ── both joined (webhook) ──► active ── end / room_finished ──► ended
                              └── no join within 30 s ──► failed
```

Only one non-final call may exist per connection (partial unique index). If A and B call each other at
the same moment, the second `POST /calls` returns `409 CALL_IN_PROGRESS` with the existing call ID;
the second caller's app switches to showing that call as incoming.

### 4.2 Sequence

```
A app                 Backend                         LiveKit                 FCM / WS          B app
  │ POST /calls ──────►│ authz: A connected to B          │                       │                 │
  │                    │ createRoom(call_<uuid>,           │                       │                 │
  │                    │   maxParticipants 2) ────────────►│                       │                 │
  │                    │ status=ringing                    │                       │                 │
  │◄── 201 {callId, livekitUrl, token(A)} ─────────────────│                       │                 │
  │ connect + publish ─────────────────────────────────────►│ (A waits alone)       │                 │
  │                    │── call.incoming ─────────────────────────────────────────►│ WS, else FCM ──►│
  │                    │◄──────────────────────────────────────────────────── POST /calls/{id}/accept│
  │                    │ status=accepted ──► 200 {token(B)} ──────────────────────────────────────────►│
  │                    │                                   │◄──────────────────────── connect+publish│
  │                    │◄── webhook participant_joined ────│                       │                 │
  │                    │ status=active; call.active to both│                       │                 │
  │ ════════════════ media ═══════════════════════════════ │ ═════════════════════════════ media ═══ │
  │ POST /calls/{id}/end ►│ deleteRoom ───────────────────►│ disconnects both      │                 │
  │                    │ status=ended; call.ended to both   │                       │                 │
```

Caller pre-connection (joining the room and publishing while ringing) removes connection time from the
callee's perceived setup time. The caller's outgoing video is not sent to anyone until B subscribes.

### 4.3 Auto-answer

**Implemented (2026-10-29, owner request; this is a private app of at most ten QR-paired people, D-21).**
An incoming call that the person does not pick up is answered by the phone itself after
`AUTO_ANSWER_MS` (10 s), with camera and microphone on. It is not silent: the phone rings (sound and the
incoming-call screen) for those 10 s and the person can decline within them, and once answered the normal
in-call screen is shown (mute, camera, hang-up). The ring timeout that marks a call missed is longer
(`ringSeconds`, 45 s), so auto-answer takes effect first.

## 5. Connectivity: STUN, TURN, UDP, TCP and NAT

The VPS has a public IP, so phones normally connect straight to the SFU. Candidate order, best first:

| Path | Port (proposed) | Notes |
|---|---|---|
| UDP to SFU (UDP mux) | `7882/udp` (`rtc.udp_port`) | Best quality. One UDP port instead of the default `50000–60000` range — easier firewall and Docker setup |
| ICE over TCP to SFU | `7881/tcp` (`rtc.tcp_port`) | Used when UDP is blocked. Works, but packet loss causes head-of-line delay, so quality is worse |
| TURN over UDP (embedded) | `3478/udp` (`turn.udp_port`) | For networks that block the SFU's UDP port but allow 3478 |
| TURN over TLS (embedded) — **phase 2, only if tests require it** | `5349/tcp` (`turn.tls_port`), or `443` via Traefik TCP SNI routing | For networks that allow only HTTPS-like traffic. Needs a TLS certificate for `turn.<domain>` ([16 §8.3](16-deployment-and-operations.md#83-turn)) |

- `rtc.use_external_ip: true` lets LiveKit discover the public IP via STUN; if discovery is wrong, set
  `NODE_IP` / `rtc.node_ip` explicitly.
- **TURN credentials** are generated per participant by LiveKit and delivered through the authenticated
  signalling connection. No static TURN username/password exists in the APK.
- TURN abuse controls (from LiveKit config): `turn.per_user_relay_allocation_limit` (default 12),
  restricted/private peer CIDRs denied by default (`allow_restricted_peer_cidrs` empty), optional `deny_peer_cidrs`.
- `rtc.allow_tcp_fallback` (default true) lets clients fall back to TCP/TURN when UDP is unstable.
- Restrictive mobile networks: test every carrier the users actually use (T-NET tests). Only add the
  TURN/TLS-on-443 work if a real network fails without it.

Full port/firewall table: [16 §7](16-deployment-and-operations.md#7-firewall-and-ports).

## 6. LiveKit tokens and room authorization

Issued by the backend with `livekit-server-sdk` (`AccessToken` + `VideoGrant`).

| Field | Value | Why |
|---|---|---|
| `identity` | The user's UUID (`users.id`) | Stable, not the username; a second join with the same identity replaces the first (reconnect from a new process) |
| `ttl` | 10 minutes | Short window to connect; the SDK default is 6 hours, which is too long |
| `room` | `calls.livekit_room` (e.g. `call_<random uuid>`) | Unguessable, one room per call |
| `roomJoin` | true | |
| `canPublish` / `canSubscribe` | true / true | |
| `canPublishSources` | `[camera, microphone]` | No screen share |
| `canPublishData` | true (since 2026-10-30) | Only for in-call controls ("switch their camera", §10.4). Chat still uses the app backend, never LiveKit data. The room holds two people, and the app ignores every topic but its own |
| `canUpdateOwnMetadata`, `roomCreate`, `roomAdmin`, `hidden` | false | Least privilege |

Rules:
- Tokens are issued only to the caller or callee of that call, and only while the call is `ringing`
  (caller), `accepted` or `active`.
- `room.auto_create: false` in LiveKit config — rooms exist only if the backend created them, so a
  leaked token cannot create new rooms.
- Rooms are created with `maxParticipants: 2`, short `emptyTimeout` and `departureTimeout`.
- `POST /calls/{id}/token` returns a fresh token for re-joining an active call (e.g. after the app process restarted).
- Token expiry vs. long calls: LiveKit is designed to keep connected participants working after the join
  token's expiry by refreshing credentials over the signalling connection — **verify with the pinned
  server/SDK versions**. The app also handles a rejected reconnect by fetching a new token from the backend.

### LiveKit → backend webhooks

LiveKit sends `participant_joined`, `participant_left`, `room_finished` to
`http://backend:3000/internal/livekit/webhook` over the internal Docker network. The backend verifies the
signed `Authorization` header (`WebhookReceiver`) before trusting it. Webhooks drive `active`/`ended`
status and act as a backstop when clients fail to report the end of a call.

## 7. Call lifecycle and cleanup

Every end path runs the same cleanup on the device:

1. `room.disconnect()`; release local tracks (camera, microphone).
2. Telecom call `disconnect()`; stop the foreground service; cancel call notifications.
3. Restore audio mode/routing; release proximity wake lock; clear keep-screen-on.
4. Post the diagnostics summary (best effort, §11).

| End path | Server action |
|---|---|
| User taps End | `POST /calls/{id}/end` → `deleteRoom` → `ended (hangup)` |
| Remote ended | Client receives `call.ended` (WS) and/or LiveKit disconnect |
| Ring timeout | Job sets `missed`, deletes room, notifies both, posts "Missed call" notification to callee |
| Both dropped (crash/network) | LiveKit `departureTimeout` → `room_finished` webhook → `ended (room_finished)` |
| Network not recovered in 30 s | Client gives up, calls `/end` with reason `network_lost` (if reachable) |
| Backend restart | On startup, calls in `ringing/accepted` older than their timeouts are closed; `active` calls are checked against LiveKit `ListRooms` |

## 8. Adaptive quality policy

**Removed from the screen (2026-10-29, owner request): the sent video is always Auto.** The picker
described next is no longer shown; its code (`setVideoQuality`, `QualityAdvisor`) remains unused.

**User choice during a call (was Implemented, 2026-10-29).** In a video call the "Quality"
button lets the user pick the size they send: Auto (the phone's own pick above), 1080p, 720p, 540p or
360p (data saver). The app recommends one from this call's measured connection (`QualityAdvisor`): the
median WebRTC `availableOutgoingBitrate` of the last five stats samples (≥ 2.5 Mbps → 1080p,
≥ 1.2 → 720p, ≥ 0.6 → 540p, else 360p; by round-trip time alone when no estimate exists), one step lower
with > 5 % loss or > 350 ms RTT. The thresholds are starting values to be tuned from real calls
(docs/09). A change republishes the camera on a fresh track (video pauses briefly; with the camera off it waits
until the camera is turned on). **A fixed size is forced (owner request):** one layer (no simulcast,
so neither the SFU nor the receiver can pick a smaller one), 30 fps, and WebRTC degradation DISABLED,
so the size and frame rate stay and a weak network makes the picture blockier instead. **Auto** keeps
simulcast with LiveKit's camera default (keep 30 fps, lower the size), with all lower layers at 30 fps
(360p/540p, or 180p for a 360p top; LiveKit's own 180p/216p presets are 15 fps). An encoder that
cannot keep up (a slow phone at 1080p) can still drop frames.
Congestion control still lowers the picture on its own.

LiveKit Android SDK defaults (checked in source): `adaptiveStream = false`, `dynacast = false`,
simulcast = true, video codec VP8, capture 720p, audio 48 kbps with DTX and RED.

Proposed configuration:

| Setting | Proposed value | Reason |
|---|---|---|
| `RoomOptions.adaptiveStream` | **true** | Receive only the resolution actually rendered (e.g. smaller in picture-in-picture) |
| `RoomOptions.dynacast` | **true** | Sender stops encoding layers nobody watches — saves uplink, CPU and battery |
| Capture | 1080p30 on devices that pass a capability check; otherwise 720p30 | Do not hard-code 1080p |
| Simulcast layers | Default (`H180`, `H360`) + top layer; benchmark `H360`+`H720` as an alternative for 1:1 | Lets the SFU drop to a lower layer instantly when the receiver's network weakens |
| Degradation preference | Benchmark `MAINTAIN_FRAMERATE` vs `BALANCED` | For faces, smoother motion at lower resolution usually looks better than sharp, stuttering video |
| Codec | Benchmark VP8 (default) vs H.264 (hardware encoders common on Android) vs VP9 SVC. Pick one for MVP | Battery/thermal and quality differ by device |
| Server cap | `call.max_video_quality` from Admin settings, returned with the token | Emergency bandwidth control |
| Data saver (user setting) | On mobile data: cap at 540p (or 360p) | Mobile data cost ([03](03-capacity-bandwidth-and-cost.md#per-phone-mobile-data)) |
| Audio | Opus, DTX + RED on (SDK defaults), echo cancellation / noise suppression / AGC on | Clear speech under loss |
| Congestion | LiveKit `congestion_control.enabled: true`, `allow_pause: true` (defaults) | Under heavy congestion the SFU pauses video before audio fails |

When bandwidth is insufficient the order of degradation is: lower video layer → lower frame rate →
video paused (UI shows "Video paused — poor connection") → audio continues. Audio is never deliberately
dropped to keep video.

### Noise filter (RNNoise)

**Implemented (2026-10-29, owner request); not yet heard on a real call.** On top of WebRTC's own
echo cancellation and noise suppression, the sent voice goes through RNNoise
([xiph/rnnoise](https://github.com/xiph/rnnoise) v0.1, BSD-3-Clause, vendored in
`android/app/src/main/cpp/rnnoise/`), a small neural network trained to remove steady background noise
(fan, traffic, TV). It runs on the phone as LiveKit's capture post-processor
(`AudioProcessorOptions.capturePostProcessor`, livekit-android 2.29.1 sources, checked 2026-10-29), on each
10 ms frame of 48 kHz audio; nothing extra leaves the device. It is on by default; the call screen has a
"Noise filter" button to turn it off for that call (e.g. if a voice sounds robotic). If the native library
cannot load, or the audio is not 48 kHz / 480 samples per frame, audio passes through unchanged. Measured
locally on a synthetic white-noise signal: about 8–10 dB less noise; real-call effect **requires
verification** on the phones.

### Device capability check (1080p eligibility)

1080p capture is allowed only if: the camera reports a 1920×1080 output at ≥ 30 fps; a hardware encoder
for the chosen codec supports 1080p30; the thermal status is below `MODERATE`; the device is not in power
saver mode. The result is cached per app version and re-evaluated when thermal status changes.

## 9. Network recovery

- The LiveKit SDK handles transient failures: it first attempts a quick resume (ICE restart), then a full
  reconnect. The app listens for `RoomEvent.Reconnecting` / `Reconnected` / `Disconnected`.
- UI: a "Reconnecting…" banner on the call screen; video freezes on the last frame; audio resumes automatically.
- Wi-Fi ↔ mobile handover: the app also registers a `ConnectivityManager.NetworkCallback`; on a default-network
  change during a call it logs the event for diagnostics (the SDK performs the ICE restart).
- Give-up rule: if not reconnected within 30 s, end the call with reason `network_lost` and offer "Call again".
- The app WebSocket and LiveKit connection are independent; losing the app socket does not end a call.

## 10. Android integration

### 10.1 Permissions

| Permission | When | If denied |
|---|---|---|
| `RECORD_AUDIO` (runtime) | First call | Calls cannot start; explain why; link to app settings if permanently denied |
| `CAMERA` (runtime) | First video call | Offer voice call instead; camera button disabled with explanation |
| `POST_NOTIFICATIONS` (runtime, Android 13+) | After login | Incoming calls will not ring when app is closed; persistent warning in Settings |
| `BLUETOOTH_CONNECT` (runtime, Android 12+) | When a Bluetooth headset is present | Bluetooth audio routing unavailable |
| `MANAGE_OWN_CALLS`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_PHONE_CALL`, `FOREGROUND_SERVICE_CAMERA`, `FOREGROUND_SERVICE_MICROPHONE` | Manifest only | — |
| `USE_FULL_SCREEN_INTENT` | Manifest; on Android 14+ check `NotificationManager.canUseFullScreenIntent()` | Ask the user to enable it via `ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`; otherwise incoming calls show as a heads-up notification only |
| `WAKE_LOCK` | Manifest | Proximity screen-off in voice calls |

Permissions are requested at the moment they are needed, with a one-line explanation first.

### 10.2 System call integration (androidx `core-telecom`)

- `CallsManager.registerAppWithTelecom(CAPABILITY_BASELINE or CAPABILITY_SUPPORTS_VIDEO_CALLING)`.
- Each call is added with `addCall(...)`; callbacks handle answer/disconnect/hold from the notification,
  Bluetooth headsets, and watches.
- A `NotificationCompat.CallStyle` notification must be posted within 5 s of adding the call.
- The call runs in a foreground service with `foregroundServiceType="phoneCall|camera|microphone"`.
- Android 14+: camera and microphone foreground services **cannot be started from the background**. The
  "Answer" action must bring an activity to the foreground (full-screen intent / activity launch) before
  starting camera/mic capture.
- Audio routing (earpiece, speaker, Bluetooth, wired) uses the Telecom call endpoints. Google's guidance is
  not to mix Telecom with `AudioManager.setCommunicationDevice`/`startBluetoothSco`.
  **Integration risk:** the LiveKit SDK has its own audio-switching logic; how it should defer to
  core-telecom must be proven in spike S-2 ([18](18-roadmap.md)).
- Incoming GSM/VoIP call during our call: Telecom `onSetCallInactive` → mute mic and pause video; on
  resume → unmute (if it was unmuted) and resume video.

**Implemented, off by default, not yet tested on a phone (2026-10-26).** Build with
`vaultchat.systemCalls=true` to switch it on. What exists:

- `SystemCallSync` (domain, JVM-tested) follows the call state: it adds the call to the system when it
  starts ringing, answers it (incoming) or sets it active (outgoing) when it connects, passes speaker
  changes on, and disconnects it with a matching cause when the call ends. Answer and end from the
  system (headset, car, watch) become the app's own accept/decline/hang-up. If the system refuses the
  call, the call goes on in the app as before.
- Hold from the system mutes the microphone; resuming unmutes only if the hold muted it. Video is not
  paused yet.
- Audio: LiveKit's own handler (`AudioSwitchHandler`) routes audio until the system call is registered,
  then stops, and the speaker button uses Telecom endpoints (a connected headset is left alone). This
  is the S-2 question: it compiles, but whether it routes correctly on both phones is unknown.
- Android draws no call screen for self-managed calls; the app's call screen and notifications stay.
- Not done: the call is added only while the app's call screen is open, so with the app closed a
  headset cannot answer before the screen opens; the 5-second CallStyle notification rule is not
  checked for calls added while the app is in the foreground; the foreground service keeps the
  `microphone|camera` types (no `phoneCall`).

### 10.3 Backgrounding

- Voice call: continues in the background via the foreground service.
- Video call: when the user leaves the app, outgoing video is paused (privacy and battery) and the call
  enters picture-in-picture if supported; audio continues. Video resumes when the app returns.
- The app never captures camera or microphone outside an active call. Capture starts only after the
  user starts or accepts a call and stops in cleanup. There is no recording of calls.

### 10.4 Camera switching and controls

Controls: mute/unmute mic, camera on/off, switch front/back, speaker/earpiece/Bluetooth picker, end call.
Camera switch uses the SDK's camera switching on the local video track (CameraX module optional).

The **Switch** button opens a menu with two choices: switch this phone's own camera, or flip **the other** person's camera during a video call ("Switch their camera";
owner request 2026-10-29, **Implemented**). It only flips an already-shared camera front/back — it
cannot turn a camera on — and it is not covert: the other phone shows "<name> switched your camera".
It is sent over a LiveKit reliable data message on the `vaultchat.switch_camera` topic; the receiving
phone flips its own camera and the sender sees "Switched <name>'s camera".

When the other person is muted the screen says "<name> is muted" (**Implemented** 2026-10-29). It follows
the LiveKit server's view of their microphone (published and not muted), so it also confirms a mute
reached the server.

## 11. Call statistics and diagnostics

Collected in memory during the call (every 2 s from WebRTC stats via the SDK) and summarised at the end.

| Metric | Source (WebRTC stats) |
|---|---|
| Connection path | Selected candidate pair: `host`/`srflx`/`relay`, `udp`/`tcp` |
| RTT | `currentRoundTripTime` on the candidate pair / `remote-inbound-rtp` |
| Jitter | `inbound-rtp.jitter` (audio and video) |
| Packet loss | `packetsLost` / `packetsReceived` deltas |
| Bitrate | `bytesSent` / `bytesReceived` deltas |
| Resolution / FPS | `frameWidth`, `frameHeight`, `framesPerSecond` (outbound and inbound) |
| Why quality was limited | `outbound-rtp.qualityLimitationReason` (`bandwidth`, `cpu`, `none`) |
| Video freezes | `freezeCount`, `totalFreezesDuration` |
| Audio interruptions | `concealedSamples`, `concealmentEvents`, `insertedSamplesForDeceleration` |
| Connection quality | LiveKit `ConnectionQuality` events |
| Reconnects | Count and duration of `Reconnecting` → `Reconnected` |
| Device | Thermal status changes, battery % start/end, network type changes |

At call end the app posts a summary (min/avg/max, not raw time series) to `POST /calls/{id}/diagnostics`.
No media, no IP addresses, no location. A hidden "debug overlay" shows live values during testing.

**Implemented** (except the debug overlay, connection-quality events and device/battery fields):
`WebRtcStats` + `CallStatsAggregator` (plain Kotlin, JVM-tested) and `LiveKitMediaSession.stats()`, which
reads `Room.getPublisherRTCStats` / `getSubscriberRTCStats` (API names checked in the livekit-android
2.29.1 sources). Summary keys: `rttMsMin/Avg/Max`, `jitterMsAvg/Max`, `lossPct`, `sendKbpsAvg`,
`recvKbpsAvg`, `sendResMax`, `recvResMax`, `sendFpsAvg`, `recvFpsAvg`, `qualityLimitedPct`,
`qualityLimitation`, `videoFreezes`, `audioConcealmentEvents`, `path`, `statsSamples`.

## 12. Battery and thermal

- Use hardware encoders where available; verify with the codec benchmark.
- Listen to `PowerManager.addThermalStatusListener` (API 29+):
  `MODERATE` → cap at 720p; `SEVERE` → cap at 540p/15 fps; `CRITICAL` → suggest turning video off.
- Keep the screen on only during video calls; use the proximity sensor to turn the screen off in voice calls
  held to the ear.
- Stop all capture immediately at call end; verify no camera/mic indicator remains (test T-CLEAN).

## 13. LiveKit server configuration (proposed values)

Keys verified against `livekit/livekit` `config-sample.yaml` (2026-10-24):

```yaml
port: 7880                      # signalling + RoomService API (behind Traefik TLS)
rtc:
  tcp_port: 7881
  udp_port: 7882                # UDP mux; do not set port_range_start/end with this
  use_external_ip: true
  # allow_tcp_fallback: true    # default
turn:
  enabled: true
  domain: turn.<domain>
  udp_port: 3478
  tls_port: 5349                # only opened in the firewall if TURN/TLS is enabled (see doc 16 §8.3)
  # cert via LIVEKIT_TURN_CERT / LIVEKIT_TURN_KEY env vars, or external_tls behind a TCP TLS terminator.
  # Verify whether the pinned version needs a certificate even when only TURN/UDP is used.
room:
  auto_create: false
  max_participants: 2
  empty_timeout: 60
  departure_timeout: 20
webhook:
  api_key: <livekit-api-key>    # placeholder; must match one of `keys`
  urls:
    - http://backend:3000/internal/livekit/webhook
logging:
  level: info
# keys: supplied via LIVEKIT_KEYS env var ("<key>: <secret>"), never committed
# redis: not configured — single node does not need it
```

## 14. Costs

Bandwidth per call and monthly hours: [03-capacity-bandwidth-and-cost.md](03-capacity-bandwidth-and-cost.md).
