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
- `GET /api/v1/health` — status + interval count per environment
- `POST /api/v1/intervals/batch` — idempotent batch ingestion
- Per-environment database files (`wellness_prod.db`, `wellness_test.db`)
- Run: `cd server && .venv/bin/uvicorn main:app --reload`
- Tests: `cd server && .venv/bin/pytest test_server.py -v`

## Current Status
Phase 1 complete (all 8 steps). 66 Android unit tests + 8 server tests + 21 instrumented tests = 95 tests, 0 failures. See `plans/phase1_implementation.md` for details.
