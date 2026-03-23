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
├── app/                    # Application shell (DI wiring, navigation)
├── core/
│   ├── common/            # Date/time utils, ID generation
│   ├── database/          # Room DB, entities, DAOs
│   ├── network/           # Ktor HTTP client for server sync
│   ├── sync/              # Connectivity monitoring, sync orchestration
│   ├── ble/               # BLE abstraction layer
│   └── ui/                # Shared theme and components
├── feature/
│   └── capture/           # BLE capture feature (data/domain/ui)
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
