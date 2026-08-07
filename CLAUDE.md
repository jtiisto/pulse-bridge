# Pulse Bridge (pulse-bridge)

## Overview
Native Android app acting as a BLE data bridge — captures heart rate and RR interval data from BLE sensors (Garmin HRM chest strap in Phase 1, Polar Verity Sense in Phase 2), stores locally in Room, and batch-syncs to a server.

Published at https://github.com/jtiisto/pulse-bridge. The app label, Gradle
project, base package, server files, and MCP package use the Pulse Bridge name.

## Tech Stack
- Kotlin + Jetpack Compose
- Material 3 (custom dark theme from Wellness PWA)
- Ktor (HTTP client)
- Koin (dependency injection)
- kotlinx.serialization
- Room (local database)
- MVI architecture with StateFlow
- Android BLE APIs (BluetoothLeScanner, BluetoothGatt)

## Module Structure
```
pulse-bridge/
├── app/                    # Application shell (DI wiring, navigation, settings)
├── core/
│   ├── common/            # Date/time utils, ID generation, EnvironmentStore
│   ├── database/          # Room DB, entities, DAOs, DatabaseCleaner
│   ├── network/           # Ktor HTTP client, IntervalApi, DTOs
│   ├── sync/              # SyncManager, SyncWorker (WorkManager)
│   ├── ble/               # BLE abstraction layer, foreground service
│   └── ui/                # Shared theme and components
├── feature/
│   └── capture/           # BLE capture feature (data/domain/ui)
├── server/                # FastAPI ingestion server (Python)
├── specs/                 # Feature specs (approved before implementation)
└── plans/                 # Reference docs (not code)
```

Base package: `dev.jtiisto.pulsebridge`

## Workflow
- **Spec-driven development**: specs/ directory with one spec per feature, approved before implementation
- Follow parent `dev/CLAUDE.md` and `dev/native/CLAUDE.md` for general principles
- JUnit 5 + MockK + Turbine for testing
- minSdk 35, targetSdk 35, JDK 21

## Git Hooks & Coverage
- Hooks live in `githooks/` (tracked), activated per-clone with
  `git config core.hooksPath githooks`. Commits/pushes route through
  `bin/git-commit-push.sh` (detached; see `dev/CLAUDE.md` workflow).
- **pre-commit** — tests scoped to staged paths: Android files →
  `testDebugUnitTest`; `server/` → server pytest; `testdata/golden/` → both;
  docs-only → skip.
- **pre-push** — full suite with coverage gates on code pushes (docs-only and
  no-op pushes skip): `testDebugUnitTest` + `koverVerifyAggregated` (merged
  Kover line coverage, minBound 82 in root `build.gradle.kts`) and server
  pytest with `--cov-fail-under=86` (`SERVER_COV_FAIL_UNDER` in
  `githooks/pre-push`; `server/.coveragerc` omits test files). Baselines
  measured 2026-08-01 after the coverage backfill: Android 83.5%, server 88%.
  Raise gates as coverage improves; never lower without a deliberate
  decision. Overrides: `PULSE_BRIDGE_FULL_PUSH=1`, `PREPUSH_DRYRUN=1`.
- The Kover metric excludes device-only framework glue (services, workers,
  receivers, notification builders, OS-API wrappers, DI modules, theme,
  Composables, generated code — the explicit list with rationale is in root
  `build.gradle.kts`) so the gate tracks unit-testable logic.
  Testable-in-principle classes (stores, `ServerHealthMonitor`, models) stay
  in the metric even while untested.
- Instrumented (androidTest) tests are NOT in the hooks — they need the
  Windows emulator over ADB. Remaining known server gaps: `analysis/__main__.py`
  (CLI glue, 0%) and `analysis/db.py` (38%).

## Key Design Decisions
- One row per RR interval (not per BLE notification)
- Device-level timestamps as PK
- PriorityMultiplexer exists in Phase 1 as passthrough (ready for Phase 2)
- core/ble as separate module (BLE is the distinguishing capability)
- No hardcoded operational parameters — all configurable with sensible defaults
- Indefinite local storage — no TTL on unsynced data
- Sync on any network (WiFi or cellular), not WiFi-only
- Singleton StateFlow for foreground service ↔ UI communication (not bound service)
- START_STICKY service restart — resumes from open session in Room after process kill
- Smart device selection — known devices auto-connect, unknown shown in list, known removable
- Runtime test/production environment toggle — X-Environment header routes to separate server SQLite DBs
- Idempotent sync — INSERT OR IGNORE on composite PK, safe retries on failure

## Server
- FastAPI + SQLite under `server/` directory
- `GET /api/v1/health` — status + interval count + accelerometer summary count per environment
- `POST /api/v1/intervals/batch` — idempotent batch ingestion
- `POST /api/v1/accelerometer/batch` — idempotent accelerometer summary batch ingestion
- Per-environment database files (`pulse_bridge_prod.db`, `pulse_bridge_test.db`)
- Run: `cd server && .venv/bin/uvicorn main:app --reload`
- Deploy server/MCP runtime: `./bin/deploy-prod.sh /path/to/pulse-bridge-prod`
  - If Android files changed, deploy builds `:app:assembleDebug` and copies a
    fresh APK to the rclone target
    `${PULSE_BRIDGE_APK_REMOTE_DIR:-gdrive:Pulse Bridge/APKs}`. Use
    `PULSE_BRIDGE_COPY_APK=always` or `never` to override the default
    changed-files behavior.
- Production server helper: `./bin/server.sh start` from the deployed root
- Tests: `cd server && .venv/bin/pytest -v` (collection roots in `server/pytest.ini`)

## Analysis Module (`server/analysis/`, spec: `specs/workout_analysis.md`)
- Run: `cd server && .venv/bin/python -m analysis --latest` (or `--session ID`,
  `--list`, `--environment test`, `--hrmax N`, `--out DIR`, `--no-files`).
- Pipeline per session: RR-quality gating → rolling 2-min DFA α1 trace (trusted
  only when window artifacts ≤5% and no gap) → RMSSD → HR summary/zones →
  signal-based work/rest bout detection (no laps). Emits terminal summary +
  `report_<id>.json` + `report_<id>.png`.
- numpy required; matplotlib optional (JSON always written, PNG only if present).
- The α1 verdict is deliberately conservative — reports trusted-window mean but
  states when data quality can't support a call (artifacts inflate α1). A
  window is trusted only at RR coverage ≥95% AND artifacts ≤5% AND no gap;
  only full 2-min windows within the data are analyzed. RMSSD pools diffs
  within beat-adjacent runs (never across gaps); avg HR is duration-weighted.

## Data & Analysis Notes (field-validated 2026-07-19)

### Timestamps and Garmin workout alignment
- `timestamp_device` is epoch ms on the phone's NTP-synced wall clock. The
  LAST beat of each BLE notification is stamped at receipt; earlier beats are
  placed backward by their RR durations. Colliding candidates spread backward
  (never past receipt time); cross-notification same-ms collisions may bump
  ≤1 ms forward (accepted limitation, needs sequence-in-PK to remove).
- Garmin workouts align by pure timestamp cut: `activities.start_time +
  duration_seconds` from the garmy-localdb MCP (`start_time` is LOCAL time;
  our rows are UTC epoch). Validated on a real ride: in-window HR avg 141.1 /
  max 147 vs Garmin's own 141 / 147 from the same strap via ANT+ — combined
  phone/watch clock skew is well under a second.
- NOTE: user does NOT press Garmin laps, so `activity_splits` is not a
  reliable segmentation hook, and no per-second power/cadence is synced into
  garmy-localdb (only daily HR/HRV/stress-type series). Segment the app's own
  RR/HR signal directly instead — do not build analysis around lap markers.

### Strap behavior (Garmin HRM 200)
- The HRM 200 has a power button and SLEEPS aggressively: asleep = no BLE
  advertising = nothing can connect and scans see nothing. Ritual: press the
  strap button, then connect (or scan while pressing). Not fixable app-side.
- Mid-workout BLE drops (supervision timeout, GATT status=8) cost ~8 s and
  auto-recover via the reconnect path; the first post-gap row is gap-flagged.
- During hard efforts (e.g. cycling) the strap omits RR values for beats it
  can't measure confidently — observed ~15% RR dropout at threshold effort
  with a CONTINUOUS timestamp/HR stream (no holes). Rest and low-intensity
  segments have near-complete RR coverage.

### Analysis guidance
- Window by TIMESTAMPS, never by RR-sums (RR-sum deficit = scattered omitted
  beats, not missing time).
- Exclude analysis windows containing `is_gap = 1` rows.
- Quality gate for HRV metrics: per-window RR coverage = SUM(rr_interval_ms)
  / window wall time; require ≥ ~95% before trusting DFA α1 (artifact
  tolerance ≈ 5% per literature). Filter `rr_interval_ms = 0` artifact rows.
- Division of labor: HR time series for zones/pacing/decoupling; RR for
  DFA α1 threshold detection (steady sub-threshold work), rest-interval RMSSD
  (parasympathetic reactivation between reps), and HR on/off kinetics.
- Segmentation without laps: DFA α1 is designed to be read as a CONTINUOUS
  rolling trace (window crossing 0.75 = LT1, 0.5 = LT2) — no per-rep
  segmentation required. For per-rep stats, auto-detect work/rest bouts from
  the HR/RR signal (HR rise/decay, or α1 dip/rebound), not from lap markers.
- DFA α1 reliability: artifacts INFLATE α1 (bias toward false "below
  threshold"). Needs <5% artifacts after Kubios-style correction; hard/
  variable efforts (~15% RR dropout observed) do NOT meet this bar. A
  from-scratch DFA without artifact correction is a sanity read only, not a
  verdict. Steady sub-threshold efforts with good strap contact are the
  reliable regime.

## Current Status
Phase 1 complete (all 8 steps). See `plans/phase1_implementation.md` for details.

Phase 2 Steps 1-7 complete (Polar Verity Sense integration). 196 Android unit tests + 154 server tests + 31 instrumented tests = 381 tests, 0 failures (instrumented run pending emulator). Shared golden payload contract fixtures live in `testdata/golden/` — the Kotlin serializer (`GoldenPayloadTest`, using the production `ApiJson`) and the server pytest suite both assert against the same files. Step 8 (integration testing with physical PVS device) pending. Spec: `specs/polar_offline_sync.md`. Plan: `plans/phase2_implementation.md`.

Live tachogram chart implemented (scrolling 10 s instantaneous-HR chart with grid on the capture screen, shown while capturing). Spec: `specs/live_tachogram_chart.md`. On-device visual verification pending.

Screen stays awake while a capture session is active (`KeepScreenOnWhileCapturing` at the app root mirrors the capture service's `isRunning` onto `View.keepScreenOn`; normal dimming resumes when capture stops or the app leaves the foreground). Spec: `specs/keep_screen_on.md`.

Live DFA signal-quality indicator in the tachogram card header (`SignalQualityTracker` in feature/capture/domain): rolling RR coverage + artifact + gap read over a 2-min window, mapped to Good/Fair/Poor/Measuring with live RR-coverage %. Thresholds mirror the offline analysis module (≥95% coverage, ≤5% artifacts, no gap = Good), so green means the offline DFA will trust the data. Spec: `specs/signal_quality_indicator.md`.

⚠ CROSS-LANGUAGE PARITY: the Kotlin `SignalQualityTracker` and the Python offline analysis (`server/analysis/quality.py` thresholds + `pipeline.py` `_window_alpha1` trust rule) intentionally implement the SAME DFA gates. No shared test enforces this — both files carry a `⚠ PARITY` comment. When editing a threshold or an ectopic/omission/coverage rule in one, change the other too, or the "DFA signal: Good" indicator stops meaning "the server will trust this".

2026-07-07 bug-fix pass (verified on device 2026-07-08 — strap connects, captures, syncs): fixed Koin type-erasure collision on the two `MutableStateFlow` singles in `bleModule` (named qualifiers — this was the Garmin connection regression), Polar PendingIntent scan registration moved to app startup and made idempotent, capture start now stops all active scans, `DatabaseCleaner` deletes only synced rows, per-device monotonic timestamps in `IntervalBuffer`/`PolarRecordingParser` (PK-collision data loss), Ktor `expectSuccess` + no-retry on 4xx in `SyncWorker`, server rejects unknown `X-Environment` with 400.

2026-07-09 connection hardening: `GarminHrmConnection` gained a 15 s connect watchdog, null/exception handling around `connectGatt`, disconnect-instead-of-stall on failed service discovery, modern `writeDescriptor` API, and a `connectionDetail` flow surfaced as `error` in the capture UI; default reconnect attempts bounded at 15 (`ReconnectionConfig`). No silent CONNECTING/CONNECTED-without-data dead ends remain.

2026-08-07 advertising probe (spec: `specs/advertising_probe.md`): field diagnostics confirmed the gym failure — 7 connect attempts, zero GATT callbacks, strap LED showing "active, open connection" = HRM 200 held via ANT+ (watch) stops BLE advertising. An address-filtered scan now runs in parallel with each Garmin connect attempt; watchdog aborts report "strap not advertising (held by watch/ANT+ or asleep)" vs "strap is advertising (rssi=N) — connect failed/rejected" in the diagnostic log and capture UI. Probe failure stays agnostic (never claims silence unheard). On-device verification pending.

2026-07-18 diagnostic log buffer (spec: `specs/diagnostic_log.md`): `DiagnosticLog` ring buffer in `core/common` instrumented across BLE connect (raw GATT status codes), scanner, capture service, Polar sync, and SyncWorker; uploaded via Settings → `POST /api/v1/diagnostics/upload` → JSONL files under `server/data/diagnostics/`. Built for remote debugging of the gym-time "strap won't connect mid-session" issue (suspected: strap stops BLE advertising while ANT+ keeps working; field test pending).

2026-07-18 Codex full-repo review remediation (all findings except the HTTP/TLS/auth hardening, which is accepted for the Tailscale LAN deployment): durable IntervalBuffer flush (batch kept on DB failure); RR timestamps anchored so the last beat lands at receipt time (no future clocks); true START_STICKY session resume with deterministic newest-open-session selection; GATT readiness gated on first sample (retry budget resets on data, not on bare CONNECTED; onDescriptorWrite/setCharacteristicNotification checked; first-sample watchdog); centralized idempotent service teardown incl. onDestroy; synchronous double-start guard; DB v3 adds isQuarantined (4xx poison batches quarantined, sync continues; exportSchema now on with schemas/ committed; hand-DDL instrumented MigrationTest 1→3); BLE actions gated on permissions with Polar re-registration on grant; unsynced count combines intervals + accelerometer; interval counts count rows and averageHr is populated; Polar lastSync persisted on COMPLETE; diagnostics filenames uniquified; server test fixture aligned to polar_pvs. Codex re-review (resumed thread) verified all 13 fixes and raised 6 follow-ups, all fixed: quarantine now BISECTS failing batches to isolate individual poison rows (row-level, budget of maxQuarantinePerRun=50 per run shared across streams, exhaustion aborts loudly), quarantined counts are visible in the sync card with a Retry action (clearQuarantine + sync); failed final flush leaves the buffer's own-scoped retry loop running as durable owner and session totals are Room-derived; BLE sample drops mark a gap on the next stored sample + cumulative counter (flow buffer 1024); permission gating is BLE-only (POST_NOTIFICATIONS denial no longer blocks capture; denial snackbar offers re-request); service startup failures roll back foreground/wake-lock/guard state; zero-RR sentinel timestamps spread backward so nothing exceeds receipt time. Final Codex pass hardened the quarantine classifier: ONLY 422 (FastAPI row validation) may bisect/quarantine — any other 4xx (404/409/429/systemic 400) aborts untouched; a circuit breaker limits quarantine to maxQuarantineWithoutSuccess=3 rows when nothing has succeeded in the run; budget default lowered to 10; failed final flush now leaves the session OPEN (finalization only on confirmed persistence, resumable via START_STICKY). Known accepted limitation: two notifications sharing a receipt millisecond (or clock rollback) can bump a row ≤1 ms past receipt time — eliminating it needs a sequence component in the PK (DB v4 + server PK change), deferred.
