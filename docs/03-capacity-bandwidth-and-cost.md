# 03 — Capacity, Bandwidth and Cost

Status: **Estimates**. Every number here is a calculation from stated assumptions, not a measurement.
Real numbers must come from the test plan in [09](09-call-quality-test-plan.md).

## 1. Server baseline

| Item | Value | Source |
|---|---|---|
| VPS | Existing Contabo VPS, 4 vCPU, 8 GB RAM | Owner |
| Monthly traffic allowance | Approximately 1 TB (assumed) | Owner — **verify the exact plan terms**, including whether inbound, outbound or both directions count |
| Orchestration | Coolify + Docker | Owner |

## 2. What limits calls on this VPS

Call capacity depends on resolution, bitrate, frame rate, codec, call duration, how many layers are
sent (simulcast), whether TURN relay is used, network quality on both ends, CPU, and the traffic
allowance. **No specific number of simultaneous calls is promised.** For a two-user app there is at
most one call at a time, so the practical limits are:

1. **Monthly traffic allowance** — the most likely limit (see §4).
2. **Per-user mobile data** — 1080p calls use several GB per hour on the phone's data plan.
3. **Network quality** between each phone and the VPS location — determines the achieved resolution.
4. **CPU** — an SFU forwards packets without transcoding. A single 1:1 call should use a small fraction
   of 4 vCPUs, but this must be measured (test T-CPU in [09](09-call-quality-test-plan.md)).
   Other workloads already on the VPS (other Coolify apps) compete for the same CPU and bandwidth.

## 3. Bitrate assumptions

From the LiveKit Android SDK source (`VideoPreset169`, `EncodingUtils`, `AudioTrackPublishDefaults`,
checked 2026-10-24):

| Preset | Capture | Max video bitrate |
|---|---|---|
| H1080 | 1920×1080 @ 30 fps | 3.0 Mbps |
| H720 (SDK default capture) | 1280×720 @ 30 fps | 1.7 Mbps |
| H540 | 960×540 @ 30 fps | 0.8 Mbps |
| H360 | 640×360 @ 30 fps | 0.45 Mbps |
| H180 | 320×180 @ 15 fps | 0.16 Mbps |

- Simulcast is on by default; default extra layers for 16:9 are **H180 + H360**.
- Default audio publish bitrate is 48 kbps (Opus, `AudioPresets.MUSIC`), with RED (redundant audio)
  and DTX enabled by default.

Model assumptions:

- **A1** Audio ≈ 0.10 Mbps per direction (48 kbps Opus, roughly doubled by RED redundancy).
- **A2** Packet overhead (RTP/SRTP/UDP/IP/RTCP) ≈ +10 %.
- **A3** Encoders run at the preset maximum the whole time (worst case; real usage is usually lower,
  especially for static scenes).
- **A4** Upload from each phone carries all simulcast layers (worst case). With **dynacast** enabled,
  LiveKit pauses layers nobody is watching, so in a 1:1 call upload is usually close to the download figure.
- **A5** No TURN relay. When embedded TURN is used on the same VPS, the phone↔VPS leg is the same size;
  the TURN↔SFU hop stays inside the server, so external traffic does not double.

Formulas:

```
S (stream received by one phone, Mbps) = (top video layer + audio) × 1.10
U (stream sent by one phone, Mbps)     = (all simulcast layers + audio) × 1.10
Server ingress per call = 2 × U        Server egress per call = 2 × S
GB per hour = Mbps × 3600 s ÷ 8 bits/byte ÷ 1000 MB/GB = Mbps × 0.45
```

## 4. Server traffic per 1:1 call

| Quality | S (Mbps) | U (Mbps) | Server ingress GB/h | Server egress GB/h | **Total GB/h** |
|---|---|---|---|---|---|
| 1080p30 | (3.0+0.1)×1.1 = 3.41 | (3.0+0.45+0.16+0.1)×1.1 = 4.08 | 3.67 | 3.07 | **6.74** |
| 720p30 | (1.7+0.1)×1.1 = 1.98 | (1.7+0.45+0.16+0.1)×1.1 = 2.65 | 2.39 | 1.78 | **4.17** |
| 540p30 | (0.8+0.1)×1.1 = 0.99 | (0.8+0.45+0.16+0.1)×1.1 = 1.66 | 1.49 | 0.89 | **2.39** |
| 360p30 | (0.45+0.1)×1.1 = 0.61 | (0.45+0.16+0.1)×1.1 = 0.78 | 0.70 | 0.54 | **1.25** |
| Voice only | 0.11 | 0.11 | 0.10 | 0.10 | **0.20** |

Example — one 30-minute call at 1080p: 6.74 × 0.5 ≈ **3.4 GB** of server traffic (worst case).

### Monthly hours within a 1 TB allowance (1 TB = 1,000 GB)

| Quality | If both directions count | If only egress counts |
|---|---|---|
| 1080p30 | 1000 ÷ 6.74 ≈ **148 h** (~4.9 h/day) | 1000 ÷ 3.07 ≈ 325 h |
| 720p30 | ≈ 240 h (~8 h/day) | ≈ 561 h |
| 540p30 | ≈ 420 h | ≈ 1,120 h |
| 360p30 | ≈ 800 h | ≈ 1,850 h |
| Voice only | ≈ 5,000 h | ≈ 10,000 h |

Example budget: 1 hour/day at 1080p ≈ 30 × 6.74 ≈ **202 GB/month**; 2 hours/day ≈ 404 GB/month.
Other services on the same VPS also consume the allowance — measure the baseline before launch.

### Per-phone mobile data

A phone sends U and receives S. At 1080p that is (4.08 + 3.41) × 0.45 ≈ **3.4 GB per hour** on the
phone's data plan (worst case). At 720p ≈ 2.1 GB/h; at 360p ≈ 0.6 GB/h. A **Data saver** call setting
(cap at 540p or 360p on mobile data) is proposed in [08 §8](08-calling.md#8-adaptive-quality-policy).

## 5. Monthly cost

| Item | Expected monthly cost | Notes |
|---|---|---|
| Contabo VPS | Already paid | Existing server; no change assumed |
| Self-hosted LiveKit + embedded TURN | ₹0 | Open source; uses VPS CPU and traffic |
| PostgreSQL | ₹0 | Runs on the VPS |
| Coolify (self-hosted) | ₹0 | |
| Domain | ~₹70–₹100/month equivalent | Typically ₹800–₹1,200/year for a `.com`/`.in`; can reuse an existing domain with subdomains for ₹0 extra |
| TLS certificates | ₹0 | Let's Encrypt via Coolify/Traefik |
| Firebase Cloud Messaging | ₹0 | FCM is offered at no cost — **verify current Firebase pricing before relying on it**; do not enable other paid Firebase products |
| Offsite backups | ₹0–₹300 | Database excluding message bodies is small (MBs). Several S3-compatible providers have free tiers or very low per-GB prices — **verify current pricing** of the chosen provider |
| Extra VPS traffic | ₹0 if within allowance | Contabo overage terms vary by plan — **verify** |
| Uptime monitoring | ₹0 | Self-hosted or free-tier external check |
| **Total extra** | **≈ ₹0–₹400/month** | Within the ₹0–₹2,000 target |

### Services that could add cost (avoid or decide deliberately)

| Service | When it costs money |
|---|---|
| LiveKit Cloud (managed) | Usage-based beyond its free allowance. Not used in the proposed design. |
| Third-party TURN service | Billed per GB relayed. Not needed: LiveKit's embedded TURN is used. |
| Firebase paid products (e.g. Cloud Functions, Firestore beyond free tier) | Not used. Only FCM is needed. |
| Separate staging VPS | Not proposed; staging, if any, shares the existing VPS. |
| Larger offsite backup storage | Only if retention or DB size grows a lot. |

## 6. Trade-offs of self-hosting

| Benefit | Cost |
|---|---|
| ₹0 media cost, full control, data stays on own server | You operate it: upgrades, certificates, firewall, monitoring |
| No per-minute pricing | Single point of failure: if the VPS is down, calls and chat are down |
| | Media quality depends on the VPS data-centre location relative to both users — choose/confirm the region closest to both users |
| | Restrictive networks may block UDP; TURN/TLS on 443 needs extra proxy work ([16 §8](16-deployment-and-operations.md#8-livekit-deployment)) |

## 7. How to replace estimates with facts

1. Measure VPS baseline traffic for one week before launch (`vnstat` on the host).
2. Run the bandwidth tests (T-BW-*) in [09](09-call-quality-test-plan.md) at each quality level.
3. Update §4 with measured GB/h and record the date, devices and networks used.
