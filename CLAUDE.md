# Wellness Sync

## Overview
Native Android app acting as a BLE data bridge — captures heart rate and RR interval data from BLE sensors (Garmin HRM chest strap in Phase 1, Polar Verity Sense in Phase 2), stores locally in Room, and batch-syncs to a server.

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

Phase 2 Steps 1-7 complete (Polar Verity Sense integration). 106 Android unit tests + 23 server tests + 21 instrumented tests = 150 tests, 0 failures. Step 8 (integration testing with physical PVS device) pending. Spec: `specs/polar_offline_sync.md`. Plan: `plans/phase2_implementation.md`.

2026-07-07 bug-fix pass (uncommitted, pending on-device verification): fixed Koin type-erasure collision on the two `MutableStateFlow` singles in `bleModule` (named qualifiers — the prime suspect for the Garmin strap no longer connecting), Polar PendingIntent scan registration moved to app startup and made idempotent, capture start now stops all active scans, `DatabaseCleaner` deletes only synced rows, per-device monotonic timestamps in `IntervalBuffer`/`PolarRecordingParser` (PK-collision data loss), Ktor `expectSuccess` + no-retry on 4xx in `SyncWorker`, server rejects unknown `X-Environment` with 400.
