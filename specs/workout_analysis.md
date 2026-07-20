# Workout Analysis Module

## Status: IMPLEMENTED (v1, six Codex review passes; last found no P1s)

## Review hardening (2026-07-20)
Round 1 fixes: DFA windows require RR coverage ≥95% (not just low artifact
flags) AND only full 2-min windows within the data are analyzed; RMSSD pools
successive diffs WITHIN beat-adjacent runs, never across gaps; avg HR is
duration-weighted; bout smoothing is edge-padded; short bouts merge into
neighbours with same-kind coalescing; each bout reports RMSSD/coverage/α1. On
the validated cycling session the coverage gate correctly flipped the verdict
from a false "below LT1" to "cannot assess."

Round 2 fixes: ectopic detection uses LOCAL-MEDIAN deviation (successive-diff
flagged the valid rebound beat too, so correcting it reused the artifact value
and inflated RMSSD ~289 ms on a flat series → now 0); ALL HR durations derive
from timestamp deltas (not summed RR) so omitted beats still contribute
wall-clock time to averages/zones, and window/bout HR are time-weighted;
per-bout α1 also requires no-gap; `--out` creates missing directories; short
sessions still render an HR-only PNG (α1 panel annotated) instead of skipping;
the PNG overlays detected work bouts as shaded spans.

Round 3 fixes: window/bout DFA trust now requires the longest CONTIGUOUS clean
run to span ≥95% of the full window/bout duration — not coverage of the
observed span, which a hole straddling the window edge (seg starts late) or
middle (run splits) could fool into ~100%. Ectopic interpolation now draws
only from valid neighbours (skips zero-RR/ectopic/boundary), so an artifact
beside a sentinel zero no longer produces a spurious ~400 ms value.
`quality.contiguous_runs` returns each run's wall-clock span for these gates.

Round 4 fix: window/bout DFA trust now gates on COVERED TIME (summed RR
intervals of the longest clean run) ≥95% of the window/bout duration — a
single measure that subsumes the round-3 span check AND catches a full-span
run whose beats under-cover the window via steady sub-threshold timestamp
drift that never trips the 1.5× omission flag.

Round 5 fixes: coverage is bounded to [0.95, 1.05] (RR summing to >105% of
wall-clock = compressed/inconsistent timeline → untrusted); Polar PPI
timestamps are normalized to Garmin's end-anchored convention at load (shift
forward by RR); bout α1 requires a full 2-min covered run (comparable to the
rolling thresholds — short bouts get RMSSD only); HR duration weights cap
disconnect-gap/omission dead time against each beat's own RR (genuine slow-HR
stretches, where delta ≈ RR, are preserved).

Round 6 fixes (no P1s): bout plateaus for segmentation are taken from a
uniform-time-resampled HR signal, so asymmetric intervals (long work / short
rest) are still detected instead of collapsing the percentiles; bout α1 is now
the mean of the bout's own TRUSTED 2-min rolling windows, which is comparable
to the session thresholds AND works for bouts of any length (the round-5 upper
bound had wrongly nulled α1 for bouts longer than ~126 s).

## Problem

Captured RR/HR data lands in the server SQLite DB but nothing analyzes it. The
value of ECG-grade RR (vs. the watch's HR series) is threshold detection
(DFA α1), rest-interval parasympathetic recovery (RMSSD), and HR kinetics —
none available from the raw numbers. The user does NOT press Garmin laps and
no power/cadence is synced, so segmentation must come from the signal itself.

## Solution

A Python package `server/analysis/`, run as `python -m analysis`, that reads a
capture session from the per-environment SQLite DB and produces a terminal
summary plus a JSON + PNG report. Pure-Python + numpy for compute; matplotlib
is an OPTIONAL import (JSON always written, PNG only if matplotlib present) so
the core stays testable and light.

### Pipeline (per session)

1. **Load** — intervals for a `session_id` (or `--latest`, or a time range),
   ordered by `timestamp_device`.
2. **Quality** — per beat: flag `rr_interval_ms == 0` artifacts, ectopics
   (|Δrr| > 20% of previous), and omissions (timestamp gap > 1.5× rr, or
   `is_gap = 1`). Report overall RR coverage = Σrr / wall-clock span.
3. **DFA α1** — rolling 2-min windows, 30 s step. Per window: correct isolated
   ectopics by neighbour interpolation, compute α1 over box sizes n=4..16,
   record artifact rate. A window is TRUSTED only if artifact rate ≤ 5% and it
   contains no gap. Threshold lines: α1 0.75 = LT1, 0.5 = LT2. Report the
   trusted-trace mean/min and time/fraction spent above each threshold.
   NOTE: artifacts inflate α1 (bias toward false "below threshold"), so
   untrusted windows are reported but excluded from verdicts.
4. **RMSSD** — overall and per auto-detected rest bout, on artifact-filtered RR.
5. **HR summary** — avg/max/min, distribution; zones only if `--hrmax` given
   (no HRmax is assumed).
6. **Segmentation** — auto work/rest bouts from smoothed instantaneous HR via a
   hysteresis threshold + minimum bout duration (heuristic, params
   configurable). Per bout: duration, HR stats, and where quality allows,
   α1 and post-bout RMSSD recovery.

### Output

- Terminal: compact human summary (see CLI preview in the approved plan).
- `report_<session>.json`: all metrics + per-window α1 trace + bouts.
- `report_<session>.png`: HR trace, α1 trace with 0.75/0.5 lines and
  trusted/untrusted shading, detected bouts (skipped if matplotlib absent).

## Out of scope (v1)

- Garmin/garmy cross-referencing (lives in a different DB; alignment stays a
  manual/notebook step).
- Kubios-grade artifact correction (v1 gates rather than fully corrects).
- Multi-session trends, web/PWA surface.

## Testing

`test_analysis.py` (pytest, in the server suite): DFA α1 on synthetic series
with known scaling (white noise → ~0.5, strongly correlated → >1); RMSSD
against a hand-computed vector; quality flagging (zero-RR, ectopic, omission,
gap); segmentation on a synthetic work/rest square wave; pipeline smoke test on
an in-memory session. Charts are not asserted (optional dep).
