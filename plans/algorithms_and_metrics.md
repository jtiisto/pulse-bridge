# Health Pipeline V3 — Algorithms & Metrics

> Extracted from `overall_plan.md` (Personal Health Monitoring Pipeline — V3, March 2026) for standalone review.
> Formulas use LaTeX (`$$...$$`) — renders in a Markdown viewer; readable as notation in plain text.

---

## Foundation: Raw HRV Computation

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

## Metric 1: HRV Baseline & Daily Z-Score

**Purpose:** Establish your personal normal range and express daily HRV as a
statistically meaningful deviation score.

**Step 1 — Build the rolling baseline:**

$$\mu_{60d} = \text{60-day exponentially weighted moving average of overnight RMSSD}$$

$$\sigma_{60d} = \text{60-day rolling standard deviation of overnight RMSSD}$$

The overnight RMSSD is computed exclusively from the **sleep window** identified
by the auto-detector — device identity is irrelevant.

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

## Metric 2: Training Load — ATL / CTL / TSB

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

## Metric 3: ACWR — Acute:Chronic Workload Ratio

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

## Metric 4: DFA Alpha-1 (Exercise Readiness)

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

## Metric 5: Energy Reserve Model — "The Capacitor"

**Purpose:** A 0–100 score that tracks available energy across the day, replicating
Body Battery behaviour using a non-linear charge/drain model.

### The Charge (Sleep)

$$Charge = \Delta t \times (RMSSD_{norm} \times Gain)$$

where $RMSSD_{norm}$ is RMSSD normalised to your personal baseline (0–1 scale),
and **Gain is elevated during the first 4 hours of the detected sleep window**
to reflect the disproportionate restorative value of early deep sleep stages.

The sleep window boundaries come directly from the auto-detector — the Capacitor
model applies elevated Gain from `sleep_start` to `sleep_start + 4h`, and normal
Gain thereafter.

### The Drain (Stress & Activity)

| Source | Drain rate |
|---|---|
| **Basal metabolic trickle** | 1 point/hour (always active) |
| **Exercise** | Proportional to session TRIMP |
| **Mental stress** | Drain × 2 — triggered when Z-score drops significantly while accelerometer shows near-zero movement |

Mental stress detection condition:

$$\text{Mental Stress} = \begin{cases} \text{True} & \text{if } Z < -1.0 \text{ AND } \|acc\| < \epsilon \\ \text{False} & \text{otherwise} \end{cases}$$

### Score bounds and fitness scaling

- Score clamped to [5, 100]
- CTL modulates exercise drain rate: fitter athletes drain more slowly
  at equivalent absolute intensities
- A full high-quality sleep night can add 40–60 points

---

## Metric 6: Training Readiness — Morning Score

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
