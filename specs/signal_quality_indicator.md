# Live Signal-Quality Indicator

## Status: IMPLEMENTED (Codex-reviewed)
## UI form: colored dot + Good/Fair/Poor label + live RR-coverage %

## Review hardening (2026-07-20)
Codex fixes to align the live read with the offline DFA trust gates:
coverage is measured over the LONGEST contiguous (break-free) run — a mid-
window no-RR/gap event splits the run and drops below GOOD, matching the
offline "longest run" rule; any gap anywhere in the 120 s window vetoes to
POOR for the whole window (not a 10 s recency); coverage has an upper bound
(≤1.05) so a compressed/inconsistent timeline isn't GOOD; and the ViewModel
recomputes quality on a timer so a silent sensor degrades the indicator
instead of leaving it stale (tick disabled in unit tests).

Round 2 fixes: runs also break on timestamp-detected omissions (receipt gap
> 1.5×RR, matching the offline classifier) even without an isGapBefore flag;
an ectopic RR is corrected to the recent median BEFORE counting toward covered
time (a spurious long RR can't inflate coverage); and the first beat of each
run is excluded from covered time (its interval ends at the run start),
matching offline `_covered_ms`.

Round 3 fixes: bundled multi-RR notifications share one receipt time, so each
beat is anchored BACKWARD by the RRs that follow it (same as IntervalBuffer)
before omission detection — otherwise every bundled packet reads as an
omission and shows POOR during normal use. Prolonged sensor silence that trims
the window empty now reports POOR (a degraded outage) rather than MEASURING
(initial collection), tracked via a `hadSignal` flag cleared on reset.

## Problem

DFA α1 threshold analysis needs clean RR data (the analysis module trusts a
window only at ≥95% RR coverage, ≤5% artifacts, no gaps). During a capture the
user has no live sense of whether the strap is delivering DFA-usable data —
they only find out later when the offline analysis says "cannot assess." A live
indicator lets them fix contact/position mid-session. It need not be exact —
just a fast, honest read.

## Solution

### New: `SignalQualityTracker` (`feature/capture/domain`)

Pure Kotlin, injected clock, fully unit-testable — mirrors the analysis
module's gates so "green" in the app means "the offline DFA will trust this."

- Consumes each `HeartRateSample` (already flowing to the tachogram). Expands
  RR list into beats; an empty RR list or `rrIntervalMs == 0` is a no-RR event.
- Rolling window (default 120 s, matching the DFA window). Per window computes:
  - **RR coverage** = Σ rr / window wall-clock span
  - **artifact fraction** = (ectopics + no-RR + gap beats) / beats, where an
    ectopic is a >20% deviation from the local median (same rule as analysis)
  - **recent gap** = any `isGapBefore` in the window
- Emits `SignalQuality(level, rrCoveragePercent)`:
  - `MEASURING` — < ~20 s / too few beats collected yet
  - `GOOD` (green) — coverage ≥95%, artifacts ≤5%, no gap → DFA-ready
  - `FAIR` (amber) — coverage ≥85%, artifacts ≤15%
  - `POOR` (red) — below that, or a recent gap
- Thresholds are constructor params with the above defaults (no hard-coded
  operational parameters, per project convention).

### Updated: `CaptureViewModel`

Feed samples to the tracker alongside the tachogram buffer in the existing beat
collector; publish `signalQuality` in `CaptureState`; reset on stop.

### Updated: `CaptureScreen`

Render a `SignalQualityIndicator` in the tachogram card header (only while
capturing) — a colored dot + label, form per the approved design option.

## Out of scope (v1)

- Full artifact correction / real DFA on-device (offline module's job).
- Polar live streaming (Polar path is offline sync).
- Historical quality trend.

## Testing

`SignalQualityTrackerTest`: clean stream → GOOD; ~15% no-RR → FAIR/POOR;
ectopic burst → degraded; gap → POOR; window trimming; MEASURING before enough
data; reset. `CaptureViewModelTest`: quality updates while capturing, clears on
stop.
