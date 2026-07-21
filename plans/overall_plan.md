# Personal Health Monitoring Pipeline — V3
## Design Document & Implementation Plan — March 2026

> **Scope note**: This document describes the overall system architecture spanning
> hardware, server-side pipeline, and client apps. Files in this project
> (`pulse-bridge`) are focused exclusively on the **native Android app** development.
> This plan is included here for reference only — it is not the spec for this project.

> **Note on math rendering**: Formulas use LaTeX syntax (`$$...$$`).
> They render correctly in VS Code (with Markdown Preview Enhanced),
> GitHub, Obsidian, and JupyterLab. In plain text editors they are
> still readable as structured notation.

---

## Overview

A self-hosted, raw-data health monitoring pipeline that computes transparent recovery
and training readiness metrics — replacing "black box" consumer scores (Garmin Body
Battery, Whoop Recovery, Oura Readiness) with auditable, high-fidelity algorithms
running on a private Python/Android infrastructure.

**The two core problems with existing solutions:**

- **Data access**: Garmin's API is delayed not just in real-time — metrics like Body
  Battery and Training Readiness may not appear accurately in the Connect API for
  hours after they are shown on the device or app. Whoop similarly lacks granular
  time-series access.
- **Black box algorithms**: Scores are computed server-side with no visibility into
  inputs or weights, making them impossible to audit, debug, or personalise.

---

## Hardware Strategy

### Device 1 — The 24/7 Recovery Monitor (Phase 2)

**2× Polar Verity Sense (PVS)** (~$90 each, ~$180 total)

| Property | Detail |
|---|---|
| Sensor type | Optical PPG armband |
| Raw data | PPI (Pulse-to-Pulse Intervals), PPG, accelerometer (52Hz), gyroscope, magnetometer |
| SDK | `polar-python` / official Polar BLE SDK — fully open |
| Offline recording | Stores to internal memory autonomously; no phone required during wear |
| Battery (spec) | 30 hours continuous (after firmware 1.1.5 update) |
| Battery (real-world) | ~24 hours continuous active recording |

**Two-Device Rotation** solves the ~24h real-world battery limit.

The two devices are **interchangeable** — the pipeline does not assign fixed roles
(e.g. "Device A = night") to either. Instead, it **automatically detects the sleep
window from the data itself** using accelerometer and time-of-day signals (see Sleep
Window Detection below). Whichever device happens to be on your arm overnight is
automatically identified as the overnight device for that night.

**The only operational constraint is battery management:**
- Each device needs at least one charge cycle per ~24 hours of continuous recording
- Swap at least twice per day (e.g. morning and evening) — timing is flexible
- The charger is the natural sync trigger: whichever device you place on the charger
  after waking is synced automatically when it comes within BLE range of your phone

### Device 2 — The Exercise Specialist (Phase 1, existing)

**Garmin HRM-Pro** (or similar dual-band chest strap)

| Property | Detail |
|---|---|
| Protocol | Broadcasts **ANT+** to Garmin watch AND **BLE** to Android bridge simultaneously |
| ANT+ | Pure broadcast — unlimited receivers, no pairing conflict |
| BLE | Point-to-point — free during workouts since watch uses ANT+ |
| Data | ECG-grade RR intervals — authoritative source during any exercise window |

### Devices Evaluated and Rejected

| Device | Reason |
|---|---|
| Oura Ring | No raw data API; consumer summaries only |
| Empatica EmbracePlus | Medical/enterprise tier; cloud-dependent; data in Avro chunks on their S3 |
| Withings ScanWatch | Raw API gated behind research approval; raw mode drops battery to 3–4 days |
| Whoop | API lacks granular time-series (same fundamental problem as Garmin) |

---

## Capture Architecture: The Android Bridge

To bypass cloud latency entirely, the system uses an **Android Foreground Service**
as a local data-mule. All interval data is stored locally first, then batch-synced
to the server over WiFi.

```
┌───────────────────────────────────────────────────────────┐
│                      Android App                           │
│                                                            │
│  ┌──────────────────────┐   ┌────────────────────────┐    │
│  │   BLE HRM Service    │   │   Polar Sync Service   │    │
│  │   (foreground)       │   │                        │    │
│  │                      │   │ - Detect any PVS       │    │
│  │ - Connect Garmin BLE │   │   device in range      │    │
│  │ - Parse RR intervals │   │ - Fetch offline        │    │
│  │ - Handle reconnect   │   │   recordings (BLE)     │    │
│  │                      │   │ - PARTIAL_WAKE_LOCK    │    │
│  └──────────────────────┘   │   during memory dump   │    │
│                             └────────────────────────┘    │
│                                                            │
│  ┌────────────────────────────────────────────────────┐   │
│  │                 Local SQLite Store                  │   │
│  │  - Keyed by (device_id, timestamp_device)           │   │
│  │  - Gaps flagged explicitly — never interpolated     │   │
│  │  - Window labels written by server-side detector    │   │
│  │  - 60s write buffer before commit (reduces I/O)     │   │
│  └────────────────────────────────────────────────────┘   │
│                          │                                 │
│                    Batch sync on WiFi                      │
└──────────────────────────┼────────────────────────────────┘
                           ▼
              ┌─────────────────────────┐
              │   Python Server /       │
              │   Cloudflare Worker     │
              │                         │
              │  Sleep Window Detector  │
              │  (labels overnight vs   │
              │   daytime post-sync)    │
              └─────────────────────────┘
                           │
                           ▼
              ┌─────────────────────────┐
              │   Wellness PWA          │
              │   (WebSocket real-time  │
              │    dashboarding)        │
              └─────────────────────────┘
```

### Android Implementation Details

**Service declaration** (Android 14+ requirement):
```xml
<service
    android:foregroundServiceType="connectedDevice|dataSync"
    ... />
```
This is required to survive Android 14+ background restrictions — without it the
service will be killed.

**Wakelock strategy**: Use `PARTIAL_WAKE_LOCK` specifically during the Polar
offline-memory dump to ensure the CPU doesn't sleep mid-sync. Release it
immediately after.

**Local write buffer**: Buffer 60 seconds of intervals in memory before committing
a single transaction to SQLite. This minimises storage I/O and radio wakeups
significantly during continuous recording.

**BLE reconnection**: Android aggressively kills background BLE on many
manufacturers. See [dontkillmyapp.com](https://dontkillmyapp.com) for
per-manufacturer workarounds beyond the foreground service.

**ANT+ alternative (home workouts)**: ~~Removed from scope.~~ The phone is always
present during workouts, so BLE capture via the Android app covers both home and
away workouts with a single path. Maintaining a second ANT+ dongle → Linux server
ingestion pipeline adds complexity with no user-facing benefit.

**Polar offline recording sync**: `polar-python` offline recording fetch may not
be fully implemented yet (GitHub issue #556 open at time of writing). Two paths:
1. Android companion app using official Polar BLE SDK — works today
2. Contribute offline recording fetch to `polar-python` — protocol is documented

---

## Data Handling

### Sleep Window Auto-Detection

Rather than tracking which physical device was worn overnight, the pipeline
**infers the sleep window from the data** after each sync. Neither device has a
fixed role — the labelling is fully automatic.

**Algorithm (server-side, runs after each sync):**

The detector uses the **sync timestamp as a loose upper bound** — it tells us the
sleep definitely ended before this point, but makes no assumption about how soon
after waking the device was placed on the charger. The charger just needs to happen
sometime during the same day; no immediacy is required.

The algorithm looks back a full **24 hours** from sync time and selects the longest
sustained low-movement block. That block will naturally be the sleep window — naps
and rest periods are shorter and will lose to it.

```python
def detect_sleep_window(ppi_stream, acc_stream, sync_timestamp):
    """
    Find the overnight sleep window in a merged PPI+accelerometer stream.
    sync_timestamp is a loose upper bound (charger placement, any time after waking).
    Returns (sleep_start, sleep_end) timestamps.
    """
    # Step 1: Compute movement magnitude at 1-minute resolution
    acc_mag = rolling_magnitude(acc_stream, window='1min')

    # Step 2: Define search window — look back 24 hours from sync.
    # No assumption about how soon after waking the sync occurred.
    search_start = sync_timestamp - timedelta(hours=24)
    search_window = (search_start, sync_timestamp)

    # Step 3: Find candidate low-movement blocks within the search window
    still_blocks = find_contiguous_blocks(
        acc_mag < STILLNESS_THRESHOLD,
        min_duration=timedelta(hours=3),
        within=search_window
    )

    # Step 4: Select the longest block — that is the sleep window.
    # Naps and rest periods are shorter and will not win.
    # Falls back to (None, None) if no candidate found (missed wear, no data).
    if not still_blocks:
        return None, None

    sleep_window = max(still_blocks, key=lambda b: b.duration)

    return sleep_window.start, sleep_window.end
```

**Robustness notes:**
- Works correctly regardless of which physical device was worn overnight
- Works for any bedtime including post-midnight — no calendar date reference
- No immediacy requirement on charger placement — sync can happen hours after waking
- No date-boundary ambiguity: a 1am–9am sleep block is attributed correctly
  regardless of when the sync fires
- Gracefully handles nights where no device was worn — returns `(None, None)`
  for that sync rather than producing corrupted data
- Naps are excluded by the "longest block wins" logic and the 3-hour minimum
  floor; they are preserved as daytime rest segments contributing to the
  Capacitor charge model at a lower Gain rate

**Window labels** are written back to the SQLite store server-side after detection,
so all downstream metric calculations (overnight RMSSD, sleep staging, Capacitor
Gain) query by window label rather than device ID.

### The Priority Multiplexer

When multiple sensors overlap (during a workout), the ingestion script applies
the following source priority hierarchy:

```python
def get_authoritative_rr(garmin_rr, pvs_ppi):
    if garmin_rr.is_active():
        return garmin_rr.value      # Priority 1: ECG-grade accuracy
    elif pvs_ppi.is_valid():
        return pvs_ppi.value        # Priority 2: PPG intervals
    else:
        return None                 # Log as Gap (no interpolation)
```

**Workout window handling:**

```
[PVS PPI — authoritative]
        │
        ▼  workout starts (Garmin strap connects)
[Garmin RR — authoritative for exercise window    ]
[PVS PPI   — collected, flagged, excluded from HRV]
        │
        ▼  workout ends
[PVS PPI — authoritative resumes]
```

Optical PPI degrades during high-intensity exercise due to motion artifacts.
Post-workout, the Verity Sense PPI recovers quickly and the cardiac recovery
rate during that window is itself a meaningful fitness signal.

### Gap Policy

All gaps — device swap, shower, charging, poor skin contact, mid-sync disconnects —
are **flagged as missing data** and **excluded from calculations**.
Never interpolate across a gap. This prevents silent contamination of rolling baselines.

**Swap timing**: No schedule required. Swap at least twice per day for battery
management. The sleep window detector handles any resulting gaps automatically.

---

## Algorithms & Metrics

### Foundation: Raw HRV Computation

**Sources:**
- Garmin chest strap → ECG-grade RR intervals (exercise windows, DFA α1)
- Polar Verity Sense → optical PPI intervals (overnight, daytime rest)

**Library:** `pyHRV` — computes 78 HRV parameters per published standards

**Primary metric:** RMSSD — Root Mean Square of Successive Differences

$$RMSSD = \sqrt{\frac{1}{N-1} \sum_{i=1}^{N-1} (RR_{i+1} - RR_i)^2}$$

RMSSD captures beat-to-beat parasympathetic activity via the vagus nerve.
It is the standard metric used by Oura, Whoop, HRV4Training, and AI Endurance.
Raw RMSSD is highly volatile day-to-day and meaningless without personal context.

**Additional HRV outputs:**
- **SDNN**: Standard deviation of NN intervals (longer-term autonomic tone)
- **LF/HF ratio**: Frequency domain autonomic balance
- **DFA α1**: Nonlinear scaling exponent (exercise readiness — see below)

---

### Metric 1: HRV Baseline & Daily Z-Score

**Purpose:** Establish your personal normal range and express daily HRV as a
statistically meaningful deviation score.

**Step 1 — Build the rolling baseline:**

$$\mu_{60d} = \text{60-day exponentially weighted moving average of overnight RMSSD}$$

$$\sigma_{60d} = \text{60-day rolling standard deviation of overnight RMSSD}$$

The overnight RMSSD is computed exclusively from the **sleep window** identified
by the auto-detector above — device identity is irrelevant.

**Step 2 — Compute the Daily Z-Score:**

$$Z = \frac{RMSSD_{today} - \mu_{60d}}{\sigma_{60d}}$$

**Interpretation:**

| Z-Score | State | Action |
|---|---|---|
| Z > 0.5 | Super-compensated | Green Light — push hard |
| −1.0 < Z < 0.5 | Normal range | Train as planned |
| Z < −1.5 | Under-recovered / systemic stress | Red Light — reduce load |

> **Note on alternative approach:** A simpler deviation-band model (above/within/below
> ±1 SD) is also valid. The Z-score is preferred because it normalises for individual
> variance, making the score comparable across different fitness phases.

**Resting HR** follows the same model with a 30-day rolling window.

---

### Metric 2: Training Load — ATL / CTL / TSB

**Purpose:** Track fitness (chronic load), fatigue (acute load), and form (balance).

**Step 1 — TRIMP per session** (Banister method, from chest strap RR):

$$TRIMP = t \times \Delta HR_r \times 0.64 \times e^{1.92 \times \Delta HR_r}$$

where:

$$\Delta HR_r = \frac{HR_{exercise} - HR_{rest}}{HR_{max} - HR_{rest}}$$

*(Use 1.67 coefficient for females instead of 1.92)*

**Step 2 — Exponential Moving Average model:**

$$\lambda_{ATL} = 1 - e^{-1/7} \quad \text{(7-day fatigue decay)}$$

$$\lambda_{CTL} = 1 - e^{-1/42} \quad \text{(42-day fitness decay)}$$

$$ATL_{today} = TRIMP \times \lambda_{ATL} + (1 - \lambda_{ATL}) \times ATL_{yesterday}$$

$$CTL_{today} = TRIMP \times \lambda_{CTL} + (1 - \lambda_{CTL}) \times CTL_{yesterday}$$

$$TSB_{today} = CTL_{today} - ATL_{today}$$

**Interpretation:**
- **CTL** = chronic fitness proxy — increases with consistent training
- **ATL** = acute fatigue proxy — responds to recent load
- **TSB** = form (positive = fresh, negative = fatigued)

> Requires ~42 days of data before CTL is meaningful. Start collecting immediately.

---

### Metric 3: ACWR — Acute:Chronic Workload Ratio

**Purpose:** A complementary ratio metric that helps prevent over-reaching.
Where TSB is a *difference*, ACWR is a *ratio* — useful for spotting sudden
load spikes even when CTL is low.

$$ACWR = \frac{ATL \; (7\text{-day average TRIMP})}{CTL \; (42\text{-day average TRIMP})}$$

**Target zone:** Keep between **0.8 and 1.3** to build fitness without elevated
injury risk.

| ACWR | State |
|---|---|
| < 0.8 | Undertraining / detraining |
| 0.8 – 1.3 | Sweet spot: building fitness safely |
| > 1.5 | Danger zone: high injury / overreach risk |

> **Relationship to TSB:** Use both. TSB gives the absolute fitness/fatigue picture;
> ACWR flags dangerous *relative* load spikes. They are complementary, not redundant.

---

### Metric 4: DFA Alpha-1 (Exercise Readiness)

**Purpose:** Real-time aerobic/anaerobic threshold detection during workouts,
and a day-to-day readiness index derived from exercise performance.

**Input:** High-fidelity RR intervals from Garmin chest strap during exercise.

DFA α1 measures the fractal scaling exponent of the RR interval time series:

| α1 value | Physiological meaning |
|---|---|
| > 0.75 | Below aerobic threshold (Zone 1–2) |
| ≈ 0.75 | At aerobic threshold |
| < 0.5 | At / above anaerobic threshold (Zone 4–5) |

**Readiness Index (Ra):**

$$Ra = \frac{P_{today}\text{ at fixed } \alpha_1}{P_{60d\text{ baseline}}\text{ at same } \alpha_1} - 1$$

Typical range: −20% to +20%. Negative Ra = poor day, positive Ra = good day.

---

### Metric 5: Energy Reserve Model — "The Capacitor"

**Purpose:** A 0–100 score that tracks available energy across the day, replicating
Body Battery behaviour using a non-linear charge/drain model.

#### The Charge (Sleep)

$$Charge = \Delta t \times (RMSSD_{norm} \times Gain)$$

where $RMSSD_{norm}$ is RMSSD normalised to your personal baseline (0–1 scale),
and **Gain is elevated during the first 4 hours of the detected sleep window**
to reflect the disproportionate restorative value of early deep sleep stages.

The sleep window boundaries come directly from the auto-detector — the Capacitor
model applies elevated Gain from `sleep_start` to `sleep_start + 4h`, and normal
Gain thereafter.

#### The Drain (Stress & Activity)

| Source | Drain rate |
|---|---|
| **Basal metabolic trickle** | 1 point/hour (always active) |
| **Exercise** | Proportional to session TRIMP |
| **Mental stress** | Drain × 2 — triggered when Z-score drops significantly while accelerometer shows near-zero movement |

Mental stress detection condition:

$$\text{Mental Stress} = \begin{cases} \text{True} & \text{if } Z < -1.0 \text{ AND } \|acc\| < \epsilon \\ \text{False} & \text{otherwise} \end{cases}$$

#### Score bounds and fitness scaling

- Score clamped to [5, 100]
- CTL modulates exercise drain rate: fitter athletes drain more slowly
  at equivalent absolute intensities
- A full high-quality sleep night can add 40–60 points

---

### Metric 6: Training Readiness — Morning Score

**Purpose:** Single morning score summarising readiness for today's training,
with full component transparency.

**Inputs:**
- HRV Z-Score (from auto-detected sleep window RMSSD)
- Resting HR deviation from 30-day baseline
- TSB from training load model
- ACWR
- Sleep duration and estimated quality from sleep window detector
- Recent 7-day training load trend

**Output:** 0–100 score + per-component breakdown.

---

## Python Stack

| Layer | Library | Purpose |
|---|---|---|
| BLE capture (Polar) | `polar-python` | Stream / sync PPI, PPG, ACC from Verity Sense |
| BLE capture (generic HRM) | `bleak` | RR intervals from Garmin strap |
| ~~ANT+ capture (alternative)~~ | ~~`openant`~~ | ~~Removed — phone BLE covers all workouts~~ |
| HRV computation | `pyHRV` | 78 HRV parameters from RR/PPI series |
| Biosignal processing | `neurokit2` | Sleep stage estimation, PPG signal cleaning |
| Numerical / rolling stats | `numpy`, `pandas` | EWMA, Z-score, normalization, ACWR |
| DFA alpha-1 | Custom / `alphaHRV` | Nonlinear HRV scaling exponent |
| Storage | SQLite (local) → server | 60s write buffer, gap-flagged + window-labelled schema |
| Frontend | Wellness PWA (WebSockets) | Real-time dashboarding |

---

## Implementation Phases

### Phase 1 — Exercise Capture (Start Now)

**Goal:** Build training load model and validate the end-to-end pipeline before
adding hardware complexity.

1. **Build Android BLE capture app**
   - Connect to Garmin strap BLE HRM characteristic
   - Parse RR intervals with device-level timestamps
   - Foreground service with `foregroundServiceType="connectedDevice|dataSync"`
   - 60-second write buffer before SQLite commit
   - Batch sync to server on WiFi

2. **Build server ingestion endpoint**
   - Accept batched RR data from phone
   - Write to time-series store with gap flags
   - Tag workout session windows (start/end timestamps)

3. **Implement TRIMP per session**
   - Requires HR_rest and HR_max (measure once, store as config)
   - Compute from RR stream using Banister formula

4. **Implement ATL / CTL / TSB**
   - EWMA on daily TRIMP
   - Start collecting immediately — meaningful CTL after ~42 days

5. **Implement ACWR**
   - Derived directly from ATL and CTL — no extra data needed
   - Monitor for values outside 0.8–1.3 sweet spot

6. **Implement DFA α1 during workouts**
   - Compute from exercise RR stream
   - Track Ra readiness index vs 60-day exercise baseline

7. **Validate directionally against Garmin**
   - Compare computed TSB / ACWR against Garmin Training Status trend
   - Note: Garmin Connect API delays make this directional, not exact

**Deliverable:** Full training load picture (CTL, ATL, TSB, ACWR, DFA α1).
No recovery layer yet — morning readiness scoring is incomplete without it.
Garmin watch continues as normal training companion.

---

### Phase 2 — 24/7 Recovery Stream

**Goal:** Add overnight HRV and daytime stress to complete all metrics.

1. **Purchase 2× Polar Verity Sense**

2. **Configure offline recording on both devices**
   - Set `TRIGGER_SYSTEM_START` — recording begins automatically on power-on
   - Records PPI + accelerometer to internal memory
   - No phone required during wear

3. **Extend Android app with Polar sync service**
   - Scan for any paired PVS device when in BLE range
   - `PARTIAL_WAKE_LOCK` during memory dump — release immediately after
   - Fetch offline recordings via Polar BLE SDK
   - Tag records by device MAC address and device-level timestamp only
   - No role assignment at sync time — the server-side detector handles that

4. **Implement server-side sleep window detector**
   - Runs automatically after each device sync
   - Uses accelerometer magnitude + time-of-day prior
   - Writes `window_label` (sleep / daytime / nap / workout) back to SQLite
   - All downstream calculations query by `window_label`, not device ID

5. **Build 60-day RMSSD baseline**
   - Collect overnight RMSSD from sleep-labelled windows nightly
   - Rolling μ and σ computed automatically
   - Directional scoring after ~2 weeks; fully personalised after ~6 weeks

6. **Implement HRV Z-Score**
   - Z = (RMSSD_today − μ_60d) / σ_60d
   - Wire into morning readiness score and dashboard

7. **Implement sleep stage estimation**
   - Input: accelerometer + HRV within detected sleep window
   - Options: `neurokit2` custom pipeline, or Sleep as Android (already supports
     Verity Sense via BLE for off-the-shelf sleep staging)
   - Outputs: deep / REM / light / awake durations, Gain modulator for Capacitor

8. **Implement mental stress detection**
   - Monitor daytime Z-Score drops while accelerometer is near-zero
   - Trigger Drain × 2 in the Capacitor model

9. **Deploy Capacitor energy reserve model**
   - Continuous charge/drain running against live PPI stream
   - Gain boundaries sourced from sleep window detector timestamps
   - Tune Gain parameters and drain rates against subjective feel over 4–6 weeks

10. **Build Training Readiness morning score**
    - Combine Z-Score, RHR deviation, TSB, ACWR, sleep quality
    - Output per-component breakdown alongside composite

11. **Connect Wellness PWA via WebSockets**
    - Real-time dashboarding of all metrics
    - Per-component drill-down on any score

**Deliverable:** Complete, fully transparent Body Battery and Training Readiness
equivalents. No Garmin cloud dependency for any computed metric.

---

## Daily Operational Flow (Phase 2 Steady State)

```
Morning
  └── Wake up
  └── Remove whichever PVS you slept in → place on charger
  └── Put on the other PVS
  └── App detects the charging device in BLE range → syncs offline recording
  └── Server runs sleep window detector on synced data
  └── Pipeline computes: overnight RMSSD, Z-Score, sleep score,
      morning readiness, ACWR, energy reserve starting value
  └── Scores available on PWA within minutes of charger placement

Day
  └── Active PVS records autonomously (offline, no phone needed)
  └── Garmin watch worn as normal

Workout
  └── Garmin strap → phone BLE → RR intervals captured
  └── Garmin watch → ANT+ → unchanged
  └── Active PVS continues recording (flagged, excluded from HRV baseline)
  └── TRIMP computed post-session → ATL/CTL/TSB/ACWR updated
  └── Post-workout: active PVS resumes as authoritative source

Evening (any convenient time — dinner, desk time)
  └── Swap PVS devices: place active one on charger, put on the other
  └── App detects and syncs the charging device automatically
  └── Server labels daytime windows from the synced data

Night
  └── Current PVS records overnight
  └── Sleep window detected automatically from tomorrow morning's sync
```

---

## Key Design Principles

1. **No interpolation across gaps** — missing data is absent, never filled
2. **Device-level timestamps** — integrity independent of phone clock
3. **Priority multiplexer** — ECG-grade strap always wins during exercise
4. **Workout windows explicitly tagged** — source attribution is auditable
5. **All baselines are personal** — Z-scores relative to your own history
6. **Component transparency** — every composite score exposes its inputs
7. **No required cloud dependency** — pipeline does not depend on Garmin Connect
8. **Incremental value** — Phase 1 is independently useful; Phase 2 adds to it
9. **Tunable weights** — Capacitor Gain/drain parameters are exposed config,
   not buried constants
10. **Device-agnostic labelling** — window roles are inferred from data, not
    assigned to hardware; the pipeline is resilient to any swap schedule

---

## Open Questions / Future Exploration

- **polar-python offline recording**: Check GitHub issue #556; may require
  contributing the fetch path or using the official Android Polar BLE SDK
- **Sleep window detector edge cases**: Validate against fragmented sleep and missed
  swaps; add fallback to user-confirmed window if no candidate is found or if the
  longest block is implausibly short (e.g. < 3 hours suggesting a missed-wear night)
- **Nap detection**: Long daytime still-blocks (>45 min) are worth labelling as
  naps separately from sleep — they contribute Capacitor charge at a lower Gain rate
- **Sleep staging accuracy**: Validate Verity Sense accelerometer-based staging
  against subjective sleep quality ratings over first 4–6 weeks
- **Capacitor parameter tuning**: Initial Gain and drain values are estimates;
  tune against subjective energy ratings after ~4 weeks of Phase 2 data
- **Barometric pressure correlation**: Phone barometer data could be correlated
  with HRV suppression and migraine triggers — viable future data layer
- **Garmy MCP integration**: Once Phase 2 is running, Garmy could expose computed
  metrics to Claude for natural language querying ("how has my recovery trended
  this week?") without depending on Garmin's delayed cloud data
- **SQL schema**: Gemini offered to generate a SQLite schema optimised for
  high-frequency interval storage with window labelling — worth pursuing as a
  Phase 1 task

---

## Source Attribution

This document consolidates research and design from two parallel conversations:

- **Claude**: Hardware research, API/SDK landscape, battery validation, data
  architecture, TRIMP/ATL/CTL/TSB formulation, DFA α1, gap policy, two-device
  rotation rationale, sleep window auto-detection design, device-agnostic
  labelling approach
- **Gemini**: Android service implementation specifics (`foregroundServiceType`,
  wakelock, 60s write buffer), HRV Z-Score formulation, ACWR metric, non-linear
  Capacitor charge/drain model with time-varying Gain, mental stress detection
  logic, Priority Multiplexer code pattern, Wellness PWA / WebSocket architecture

Where the two approaches differed, both are documented with a recommendation.

---

*Document compiled March 2026 — V3*
