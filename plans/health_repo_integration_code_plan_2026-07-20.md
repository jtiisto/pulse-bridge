# Health Repo Integration Code Plan - 2026-07-20

## Purpose

Implement the native-repo pieces needed for `~/proj/health` to use Wellness
Sync as an occasional supplementary workout signal, especially for VO2 max /
HIIT sessions.

The health repo will own coaching interpretation, source-authority rules, and
instruction updates. This repo should own capture storage, signal analysis,
time-window cropping, interval matching, report JSON shape, and any local API
surface needed by a future MCP.

## Core Constraint

Pulse Bridge captures are not exact workout windows. The user may start
capture before the Garmin activity and stop it after cooldown/setup. Therefore
analysis code must model:

1. `raw_capture_window` - first/last stored sample.
2. `analysis_window` - cropped timeframe used for metrics, usually Garmin
   activity start/end or an explicit manual window.
3. `intent` - expected interval structure, usually from Coach or supplied by the
   caller.

Do not assume raw capture start/end equals work start/end.

## Phase 1 - Stabilize Report Schema

Add a stable report object produced by `server/analysis/pipeline.py` and exposed
by JSON/API callers.

### Session Fields

- `session_id`
- `raw_capture_window`
  - `start_ms`
  - `end_ms`
  - `duration_s`
- `analysis_window`
  - `source`: `full_capture` | `garmin_activity` | `manual`
  - `start_ms`
  - `end_ms`
  - `duration_s`
  - `trimmed_before_s`
  - `trimmed_after_s`
  - `confidence`
- `device`
- `quality`
  - `rr_coverage`
  - `gaps`
  - `artifact_frac_overall`
  - `trusted_dfa_windows`
  - `total_dfa_windows`
  - `hr_usable`
  - `rr_hrv_usable`
- `hr`
  - avg, max, min
  - zones
  - time above 169 bpm
  - time above 90% HRmax
  - time above 95% HRmax
- `intent`
  - `source`: `coach` | `user_supplied` | `inferred` | `unknown`
  - `rounds`
  - `work_duration_s`
  - `rest_duration_s`
  - `target_hr_min`
  - `target_hr_max`
  - `modality`
  - `notes`
- `flags`
  - `analysis_window_uncertain`
  - `intent_missing`
  - `rr_quality_insufficient`
  - `detected_intent_mismatch`

### Backward Compatibility

Keep the existing terminal output and `report_<session>.json` usable. Add fields
without removing current keys unless the server tests are updated in the same
change.

## Phase 2 - Window Cropping

Add an analysis option that crops beats before calculating metrics.

### CLI

Extend `python -m analysis` with:

- `--start-ms`
- `--end-ms`
- `--start-time`
- `--end-time`
- `--window full|manual`

Future Garmin matching can call the same internal crop function.

### Internal API

Add a helper in the analysis package:

- `crop_beats(beats, start_ms=None, end_ms=None) -> list[Beat]`
- `build_analysis_window(raw_beats, crop_start_ms, crop_end_ms, source)`

Rules:

- metrics operate only on cropped beats;
- raw quality diagnostics should remain available for debugging;
- if crop excludes all beats, return a clear error;
- if crop leaves too little data for DFA, HR metrics should still be returned.

## Phase 3 - Intended Interval Input

Add a typed interval-intent model independent of Coach. Coach parsing can happen
outside this repo or in a future MCP, but the native server should accept the
intent once supplied.

### Data Shape

```json
{
  "rounds": 3,
  "work_duration_s": 240,
  "rest_duration_s": 180,
  "target_hr_min": 169,
  "target_hr_max": 188,
  "modality": "bike",
  "notes": "VO2 max intervals"
}
```

### CLI

Add one or both:

- `--intent-json '{"rounds":3,"work_duration_s":240,...}'`
- `--intent-file /path/to/intent.json`

### Validation

- rounds >= 1
- work/rest duration > 0
- target HR bounds optional but, if present, min <= max
- unknown intent is valid and should not fail analysis

## Phase 4 - VO2 Bout Matching

Extend `server/analysis/segment.py` or add `server/analysis/vo2.py`.

### Inputs

- cropped beat list;
- optional interval intent;
- detected work/rest bouts from current HR segmentation;
- HRmax.

### Outputs

For each expected work bout:

- expected start/end offset when intent is available;
- detected start/end if matched;
- match confidence;
- timing error versus expected;
- duration error versus expected;
- avg HR;
- peak HR;
- time to target HR min;
- time to 90% HRmax;
- seconds >= target HR min;
- seconds >= 90% HRmax;
- seconds >= 95% HRmax;
- HR at work-end.

For each following rest bout:

- expected/detected duration;
- HR drop at 30, 60, 120, and 180 seconds;
- minimum HR;
- HR at next-work start;
- RMSSD if RR quality permits;
- RR coverage and artifact rate.

### Matching Strategy

Start simple and conservative:

1. Use intent to lay out expected work/rest windows from analysis-window start.
2. Use current detected bouts as candidates.
3. Match by maximum time overlap.
4. Mark low confidence if overlap is poor or if detected bout count differs from
   expected count.
5. Return unmatched leading/trailing time explicitly.

Do not hide uncertainty. The health repo can still use the aligned time series
when bout matching is imperfect.

## Phase 5 - Compact Time Series Export

Expose an LLM-friendly time series for manual or MCP-assisted review.

### CLI/API Output

Add a time-series function that returns rows at a configurable resolution:

- default `resolution_s=5`;
- timestamp/offset;
- mean HR in bucket;
- max HR in bucket;
- RR coverage in bucket;
- artifact fraction in bucket;
- gap marker.

This is the fallback when automated bout matching is uncertain.

## Phase 6 - FastAPI Analysis Endpoints

Add read-only endpoints in `server/main.py` or an analysis router.

Recommended endpoints:

- `GET /api/v1/analysis/sessions`
- `GET /api/v1/analysis/sessions/{session_id}`
- `GET /api/v1/analysis/latest`
- `GET /api/v1/analysis/by-date/{date}`
- `GET /api/v1/analysis/sessions/{session_id}/timeseries?resolution_s=5`
- `POST /api/v1/analysis/sessions/{session_id}/vo2`

The `POST /vo2` body may include:

- explicit crop window;
- interval intent;
- HRmax override.

The endpoint should not write to the database.

## Phase 7 - MCP Handoff Support

The health repo may later add a read-only MCP. This repo should make that easy
by keeping the API/report shape stable.

Expected MCP tools on the health side:

- `list_sessions(start_date, end_date, environment="prod")`
- `get_session_report(session_id, hrmax=188)`
- `get_latest_session_report(hrmax=188)`
- `get_vo2_summary(date, intent=None, crop_to_garmin=True)`
- `get_aligned_timeseries(session_id, window="garmin", resolution_s=5)`
- `get_signal_quality_summary(start_date, end_date)`

Native repo requirement: endpoints or local analysis calls must provide the data
these tools need without requiring code duplication in the health repo.

## Testing

Add server tests for:

- crop window trims leading/trailing capture time;
- full-capture behavior remains unchanged when no crop is supplied;
- HR metrics use cropped beats;
- raw capture window and analysis window are both reported;
- intent validation accepts valid VO2 schema and rejects malformed fields;
- expected intervals are generated correctly from intent;
- detected bouts match expected windows by overlap;
- mismatched detected/expected counts produce uncertainty flags;
- time series export buckets HR and quality fields correctly;
- API endpoints are read-only and return stable JSON.

Use synthetic sessions with:

- clean 3 x 4 min hard / 3 min easy pattern plus extra leading/trailing time;
- missing RR during hard intervals but usable HR;
- poor bout detection where time-series fallback is still returned;
- no intent supplied.

## Deployment Notes

Document native-server startup in this repo once endpoints exist:

- local command for development;
- optional systemd user service or shell script for Tailscale/LAN use;
- database path expectations for prod/test environments;
- health-repo MCP configuration should point at this stable server/API, not
  import native repo internals.

## Acceptance Criteria

Implementation is ready for health-repo integration when:

- a captured VO2 session can be analyzed with extra raw capture time trimmed;
- the caller can supply `3 x 4 min hard / 3 min easy` intent;
- the report returns expected/detected/matched interval details;
- HR metrics remain useful when RR quality is insufficient;
- JSON exposes raw capture window, analysis window, intent, quality, HR summary,
  interval summary, and uncertainty flags;
- API or CLI output is stable enough for a read-only MCP wrapper;
- tests cover cropped, uncropped, intent, no-intent, good-quality, and poor-RR
  cases.

## Non-Goals

- Do not query Garmin, Coach, or Journal directly from native app code.
- Do not write back to Coach, Garmin, or the health repo.
- Do not make Pulse Bridge required for workout adherence.
- Do not claim DFA alpha-1 threshold insights unless quality gates pass.
- Do not make medical readiness decisions from one captured session.
