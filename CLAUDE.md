# Wellness Sync (pulse-bridge)

## Overview
Native Android app acting as a BLE data bridge — captures heart rate and RR interval data from BLE sensors (Garmin HRM chest strap in Phase 1, Polar Verity Sense in Phase 2), stores locally in Room, and batch-syncs to a server.

Published at https://github.com/jtiisto/pulse-bridge (repo renamed for clarity; local directory, base package, and app label remain Wellness Sync).

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
wellness-sync/
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

Base package: `dev.jtiisto.wellnesssync`

## Workflow
- **Spec-driven development**: specs/ directory with one spec per feature, approved before implementation
- Follow parent `dev/CLAUDE.md` and `dev/native/CLAUDE.md` for general principles
- JUnit 5 + MockK + Turbine for testing
- minSdk 35, targetSdk 35, JDK 21

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
- Per-environment database files (`wellness_prod.db`, `wellness_test.db`)
- Run: `cd server && .venv/bin/uvicorn main:app --reload`
- Tests: `cd server && .venv/bin/pytest test_server.py -v`

## Current Status
Phase 1 complete (all 8 steps). See `plans/phase1_implementation.md` for details.

Phase 2 Steps 1-7 complete (Polar Verity Sense integration). 161 Android unit tests + 32 server tests + 31 instrumented tests = 224 tests, 0 failures (instrumented run pending emulator). Shared golden payload contract fixtures live in `testdata/golden/` — the Kotlin serializer (`GoldenPayloadTest`, using the production `ApiJson`) and the server pytest suite both assert against the same files. Step 8 (integration testing with physical PVS device) pending. Spec: `specs/polar_offline_sync.md`. Plan: `plans/phase2_implementation.md`.

Live tachogram chart implemented (scrolling 10 s instantaneous-HR chart with grid on the capture screen, shown while capturing). Spec: `specs/live_tachogram_chart.md`. On-device visual verification pending.

2026-07-07 bug-fix pass (verified on device 2026-07-08 — strap connects, captures, syncs): fixed Koin type-erasure collision on the two `MutableStateFlow` singles in `bleModule` (named qualifiers — this was the Garmin connection regression), Polar PendingIntent scan registration moved to app startup and made idempotent, capture start now stops all active scans, `DatabaseCleaner` deletes only synced rows, per-device monotonic timestamps in `IntervalBuffer`/`PolarRecordingParser` (PK-collision data loss), Ktor `expectSuccess` + no-retry on 4xx in `SyncWorker`, server rejects unknown `X-Environment` with 400.

2026-07-09 connection hardening: `GarminHrmConnection` gained a 15 s connect watchdog, null/exception handling around `connectGatt`, disconnect-instead-of-stall on failed service discovery, modern `writeDescriptor` API, and a `connectionDetail` flow surfaced as `error` in the capture UI; default reconnect attempts bounded at 15 (`ReconnectionConfig`). No silent CONNECTING/CONNECTED-without-data dead ends remain.

2026-07-18 diagnostic log buffer (spec: `specs/diagnostic_log.md`): `DiagnosticLog` ring buffer in `core/common` instrumented across BLE connect (raw GATT status codes), scanner, capture service, Polar sync, and SyncWorker; uploaded via Settings → `POST /api/v1/diagnostics/upload` → JSONL files under `server/data/diagnostics/`. Built for remote debugging of the gym-time "strap won't connect mid-session" issue (suspected: strap stops BLE advertising while ANT+ keeps working; field test pending).

2026-07-18 Codex full-repo review remediation (all findings except the HTTP/TLS/auth hardening, which is accepted for the Tailscale LAN deployment): durable IntervalBuffer flush (batch kept on DB failure); RR timestamps anchored so the last beat lands at receipt time (no future clocks); true START_STICKY session resume with deterministic newest-open-session selection; GATT readiness gated on first sample (retry budget resets on data, not on bare CONNECTED; onDescriptorWrite/setCharacteristicNotification checked; first-sample watchdog); centralized idempotent service teardown incl. onDestroy; synchronous double-start guard; DB v3 adds isQuarantined (4xx poison batches quarantined, sync continues; exportSchema now on with schemas/ committed; hand-DDL instrumented MigrationTest 1→3); BLE actions gated on permissions with Polar re-registration on grant; unsynced count combines intervals + accelerometer; interval counts count rows and averageHr is populated; Polar lastSync persisted on COMPLETE; diagnostics filenames uniquified; server test fixture aligned to polar_pvs. Codex re-review (resumed thread) verified all 13 fixes and raised 6 follow-ups, all fixed: quarantine now BISECTS failing batches to isolate individual poison rows (row-level, budget of maxQuarantinePerRun=50 per run shared across streams, exhaustion aborts loudly), quarantined counts are visible in the sync card with a Retry action (clearQuarantine + sync); failed final flush leaves the buffer's own-scoped retry loop running as durable owner and session totals are Room-derived; BLE sample drops mark a gap on the next stored sample + cumulative counter (flow buffer 1024); permission gating is BLE-only (POST_NOTIFICATIONS denial no longer blocks capture; denial snackbar offers re-request); service startup failures roll back foreground/wake-lock/guard state; zero-RR sentinel timestamps spread backward so nothing exceeds receipt time. Final Codex pass hardened the quarantine classifier: ONLY 422 (FastAPI row validation) may bisect/quarantine — any other 4xx (404/409/429/systemic 400) aborts untouched; a circuit breaker limits quarantine to maxQuarantineWithoutSuccess=3 rows when nothing has succeeded in the run; budget default lowered to 10; failed final flush now leaves the session OPEN (finalization only on confirmed persistence, resumable via START_STICKY). Known accepted limitation: two notifications sharing a receipt millisecond (or clock rollback) can bump a row ≤1 ms past receipt time — eliminating it needs a sequence component in the PK (DB v4 + server PK change), deferred.
