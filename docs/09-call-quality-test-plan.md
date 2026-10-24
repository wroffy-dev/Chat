# 09 — Call Quality Test Plan (Real Devices)

Status: **Proposed**. Thresholds marked "proposed" are acceptance criteria to agree on, not measured facts.

## 1. Principles

- Test on **real Android phones**, on **real Wi-Fi and real mobile data** — emulators are only for UI.
- Use the two users' actual phone models as the primary devices, plus one low/mid-range phone.
- Measure with the in-app diagnostics (see [08 §11](08-calling.md#11-call-statistics-and-diagnostics))
  plus the LiveKit server logs. Record every run in a results sheet (template in §7).
- Report results as distributions (p50/p90), not single best runs.

## 2. Test setup

| Item | Setup |
|---|---|
| Devices | D1, D2 = the two users' phones; D3 = a mid-range phone (Android 10+); note model, Android version, chipset |
| Networks | Home Wi-Fi (5 GHz and 2.4 GHz), each mobile carrier the users use (4G/5G), a public/hotel/office Wi-Fi if available, a phone hotspot |
| Impaired network | Laptop hotspot or Linux router with `tc netem` (delay, jitter, loss, rate limits). Android's own developer options are not reliable for this |
| Server | Production-like LiveKit config on the VPS (staging project or production outside real use) |
| Build | Release-mode APK (R8 on) with the hidden diagnostics overlay enabled |

## 3. Metrics and how to measure

| Metric | How | Proposed target |
|---|---|---|
| Call setup time | Callee taps Accept → first remote video frame rendered (app timestamp events) | Good network: p50 ≤ 2 s, p90 ≤ 4 s |
| Ring delivery time | Caller taps Call → callee device rings (app open / app killed) | App open: ≤ 2 s; app killed: p90 ≤ 5 s (depends on FCM, not guaranteed) |
| RTT | Candidate-pair `currentRoundTripTime` | Report only; decides VPS-region suitability (≤ 150 ms good, > 300 ms poor — proposed) |
| Jitter | `inbound-rtp.jitter` | Report; audio ≤ 30 ms p90 on good networks (proposed) |
| Packet loss | `packetsLost` deltas | Report |
| Audio interruptions | `concealmentEvents`, subjective notes | No gap > 1 s at ≤ 5 % loss (proposed) |
| Video freezes | `freezeCount`, `totalFreezesDuration` | ≤ 1 freeze/min on good Wi-Fi (proposed) |
| Actual bitrate | `bytesSent` / `bytesReceived` deltas | Report vs. preset |
| Negotiated resolution / FPS | `frameWidth`/`frameHeight`, `framesPerSecond` | Good Wi-Fi + capable devices: ≥ 80 % of call time at 1080p and ≥ 25 fps (proposed) |
| Quality limitation | `qualityLimitationReason` time share | Report (bandwidth vs CPU) |
| Reconnection success | Count of `Reconnecting` → `Reconnected` within 10 s | ≥ 90 % (proposed) |
| Call failure rate | Attempts that never reach `active` / total | ≤ 2 % on good networks (proposed) |
| CPU | `adb shell top` / Android Studio profiler on a sample of runs | Report |
| Memory | `adb shell dumpsys meminfo <package>` | No growth across 10 consecutive calls (leak check) |
| Battery | % drop over a 30-minute call, screen on, same brightness | Report per device and quality |
| Thermal | `PowerManager.getCurrentThermalStatus()` over time; surface temperature if available | Report time to `MODERATE` |
| Server | LiveKit CPU (`docker stats`), VPS bandwidth (`vnstat`) | Report GB/h per quality to update [03](03-capacity-bandwidth-and-cost.md) |

## 4. Test cases

### Connectivity and setup
| ID | Scenario | Pass criteria |
|---|---|---|
| T-SETUP-1 | Both on good Wi-Fi, app in foreground, 20 calls | Setup time targets met; 0 failures |
| T-SETUP-2 | Callee app killed (swiped away), screen off | Rings via FCM within target; answer from lock screen works |
| T-SETUP-3 | Callee in Doze (idle ≥ 1 h, unplugged) | Record ring delay / failure; documents real-world limitation |
| T-NET-1 … n | One test per real carrier and Wi-Fi network | Record candidate type (UDP/TCP/TURN); call connects |
| T-NET-UDP-BLOCK | Block UDP on the test router | Call connects via TCP 7881 or TURN; quality recorded |
| T-NET-ONLY-443 | Allow only TCP 443 outbound | Expected to **fail** unless TURN/TLS on 443 is deployed (phase 2). Record result |
| T-RTT | Measure RTT from both users' locations to the VPS | Decision input for VPS region |

### Quality and adaptation
| ID | Scenario | Pass criteria |
|---|---|---|
| T-Q-1080 | Capable devices, good Wi-Fi, 10 min | Resolution/FPS targets met |
| T-Q-LIMIT-{3,1.5,0.7,0.3} | Downlink limited to 3 / 1.5 / 0.7 / 0.3 Mbps | Video degrades step-wise; audio stays clear; at 0.3 Mbps video may pause, audio must continue |
| T-Q-LOSS-{2,5,10} | 2 %, 5 %, 10 % random loss | Audio interruption targets; record freezes |
| T-Q-JITTER | 100 ms ± 50 ms jitter | Audio intelligible; record concealment |
| T-Q-LATENCY | +200 ms added delay | Call usable; record subjective conversational delay |
| T-Q-CODEC | Same scenarios for VP8 vs H.264 (vs VP9 SVC) | Pick MVP codec using quality + battery + thermal |
| T-Q-DEGRADE | `MAINTAIN_FRAMERATE` vs `BALANCED` | Pick by subjective quality under limits |

### Recovery
| ID | Scenario | Pass criteria |
|---|---|---|
| T-REC-HANDOVER | Walk out of Wi-Fi range (Wi-Fi → mobile), and back | Reconnects within 10 s, no user action |
| T-REC-AIRPLANE-5 | Airplane mode on for 5 s, then off | Reconnects |
| T-REC-AIRPLANE-40 | Airplane mode for 40 s | Call ends cleanly with "network lost"; both sides cleaned up |
| T-REC-SERVER | Restart LiveKit container during a call | Call ends cleanly on both phones; backend marks call ended |

### Device behaviour
| ID | Scenario | Pass criteria |
|---|---|---|
| T-PERM-1 | Deny camera | Voice call offered; no crash |
| T-PERM-2 | Deny microphone permanently | Clear message + settings link; no call starts |
| T-BG-VOICE | Voice call, go home, lock screen 5 min | Audio continues; notification shows ongoing call |
| T-BG-VIDEO | Video call, go home | Outgoing video paused / PiP; audio continues; resumes on return |
| T-GSM | Receive a normal phone call during our call | Our call held (mic muted); resumes after |
| T-BT | Connect/disconnect Bluetooth headset mid-call | Audio routes correctly |
| T-SWITCH | Switch camera 10 times | No freeze > 2 s, no crash |
| T-THERMAL | 30-min 1080p call, phone in a warm place | Thermal caps applied; call continues |
| T-BATT | 30-min call at 1080p, 720p, voice | Record % drop |
| T-CLEAN | End call by each end path ([08 §7](08-calling.md#7-call-lifecycle-and-cleanup)) | Camera/mic privacy indicators off within 2 s; no foreground notification left; LiveKit room deleted |
| T-LEAK | 10 consecutive calls | No memory growth trend, no lingering threads |

## 5. Bandwidth measurement (T-BW)

For each quality (1080p, 720p, 540p, 360p, voice): run a 10-minute call, read VPS interface counters
(`vnstat` or `/proc/net/dev`) before and after, and the phones' data-usage screens. Compute GB/h and
update [03 §4](03-capacity-bandwidth-and-cost.md#4-server-traffic-per-11-call).

## 6. Exit criteria for MVP calling

Proposed: all T-SETUP, T-REC, T-PERM, T-CLEAN tests pass; T-Q-LIMIT shows audio priority working;
results recorded for every real network the users rely on; any network that cannot connect is documented
with the decision taken (accept, or add TURN/TLS on 443).

## 7. Results template

```
Date (UTC) | Build | Test ID | Device A / Android | Device B / Android | Network A | Network B |
Path (udp/tcp/relay) | Setup s | RTT ms (avg/p90) | Jitter ms | Loss % | Res/FPS (time share) |
Bitrate Mbps | Freezes | Audio concealment events | Reconnects | Battery % | Thermal max | Notes
```
