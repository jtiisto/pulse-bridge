# Phase 2 Implementation Plan — Polar Verity Sense Integration

## Overview

Add 24/7 PPI and accelerometer capture from Polar Verity Sense (PVS) via offline recording sync. Runs in parallel with the existing Garmin real-time capture pipeline. Both data streams are stored and synced to the server; priority resolution happens server-side during analysis.

**Spec**: `specs/polar_offline_sync.md`

**Prerequisite**: Phase 1 complete (Garmin real-time capture end-to-end).

---

## Step 1: Polar BLE SDK + Database Schema

**Goal**: Add the Polar SDK dependency and extend the database for accelerometer summaries.

### Tasks

1. Add `com.polar.sdk:polar-ble-sdk` to `core/ble/build.gradle.kts`
2. Verify SDK version compatibility with minSdk 35 and project dependencies
3. Create `AccelerometerSummaryEntity` with Room annotations
   - Composite PK: `(deviceId, windowStart)`
   - Fields: `magnitudeMean`, `magnitudeStd`, `magnitudeMax`, `sampleCount`, `sensorType`, `sessionId`, `isSynced`, `syncedAt`
4. Create `AccelerometerSummaryDao`
   - `insertAll(summaries)` with `OnConflictStrategy.IGNORE`
   - `getUnsyncedSummaries(limit)` — for sync
   - `markSynced(deviceId, windowStarts, syncedAt)` — after server accepts
   - `getUnsyncedCount()` — Flow for UI
5. Add entity + DAO to `PulseBridgeDatabase`
6. Database migration (version bump)
7. Unit tests for DAO

### Verification
- Project compiles with Polar SDK
- Room schema validates with new entity
- DAO tests pass (insert, query, mark synced, idempotent insert)

---

## Step 2: Polar Recording Parser

**Goal**: Parse PPI and ACC data from Polar SDK types into Room entities.

### Tasks

1. Create `PolarRecordingParser`
   - `parsePpi(recordingData, deviceId, sessionId) → List<IntervalEntity>`
     - Map each PPI sample to IntervalEntity with `sensorType = "polar_pvs"`
     - Detect gaps between consecutive samples (threshold TBD, start with 3s)
     - Compute HR from PPI if SDK doesn't provide it: `60_000 / ppiMs`
   - `parseAccSummaries(recordingData, deviceId, sessionId) → List<AccelerometerSummaryEntity>`
     - Group raw ACC samples into 1-minute windows
     - Compute per-window: `magnitude = sqrt(x² + y² + z²)`, then mean, std, max
     - Track sample count per window
2. Unit tests
   - PPI parsing with known input → expected IntervalEntity list
   - ACC downsampling with known input → expected summaries
   - Gap detection across PPI samples
   - Edge cases: empty recording, recording shorter than 1 minute, partial minute at end

### Verification
- Parser produces correct entities from mock SDK data
- 1-minute downsampling is accurate
- Gap flags are set correctly

---

## Step 3: Polar Offline Sync Orchestrator

**Goal**: Wire up connect → fetch → parse → store → delete flow.

### Tasks

1. Create `PolarOfflineSync` class
   - Constructor: `PolarBleApi`, `IntervalDao`, `AccelerometerSummaryDao`, `PolarRecordingParser`
   - `suspend fun syncDevice(deviceId: String): SyncResult`
     - Connect to device via `PolarBleApi`
     - List offline recordings
     - For each recording:
       - Fetch recording data
       - Parse PPI → IntervalEntity list
       - Parse ACC → AccelerometerSummaryEntity list
       - Insert into Room within a transaction
       - Delete recording from device only after successful insert
     - Return `SyncResult` (intervals fetched, summaries fetched, errors)
   - Handle SDK's RxJava/coroutine bridge (Polar SDK uses RxJava — bridge to coroutines)
2. Create `SyncResult` data class
3. Unit tests with mocked PolarBleApi
   - Happy path: recordings fetched, parsed, stored, deleted
   - BLE disconnect mid-fetch: partial data discarded, recordings not deleted
   - No recordings: graceful completion
   - Duplicate handling: idempotent inserts

### Verification
- Full sync flow works against mocked SDK
- Recordings only deleted after successful Room insert
- Failures don't lose data

---

## Step 4: PolarSyncService (Foreground Service)

**Goal**: Android foreground service wrapping PolarOfflineSync with wake lock and notifications.

### Tasks

1. Create `PolarSyncService` extending `Service`
   - `PARTIAL_WAKE_LOCK` acquired on start, released on completion
   - Foreground notification: "Syncing Polar data... (N recordings)"
   - Updates notification with progress
   - Calls `PolarOfflineSync.syncDevice()` for each known PVS device detected
   - Triggers `SyncWorker.enqueueSyncNow()` after fetch completes (to push to server)
   - Stops self after sync completes
2. Register in `AndroidManifest.xml`
   - `foregroundServiceType="connectedDevice|dataSync"` (same as BleCaptureService)
3. Create `PolarSyncNotification` (similar to `BleCaptureNotification`)
4. Add `PolarSyncServiceState` for UI observation (singleton StateFlow, same pattern as BleCaptureService)
5. Register in Koin

### Verification
- Service starts, shows notification, syncs, and stops
- Wake lock acquired/released correctly
- Notification shows progress

---

## Step 5: Device Detection (Background Scan)

**Goal**: Automatically detect PVS devices and trigger sync.

### Tasks

1. Create `PolarDeviceDetector`
   - `registerScanFilter(deviceAddress)` — registers PendingIntent BLE scan for known PVS
   - `unregisterScanFilter(deviceAddress)` — removes scan filter
   - `registerAllKnownDevices()` — called at app startup
2. Create `PolarScanReceiver` (BroadcastReceiver)
   - Receives PendingIntent when a matching PVS is detected
   - Starts `PolarSyncService` with the detected device address
   - Debounce: ignore if PolarSyncService is already running
3. WorkManager fallback scan
   - `PolarScanWorker` — periodic (15 min) active BLE scan for known PVS devices
   - If found, starts `PolarSyncService`
   - Registered at app startup
4. Register receiver in `AndroidManifest.xml`
5. Integration test strategy (will need real device for PendingIntent scan testing)

### Verification
- PendingIntent scan registered for known devices
- WorkManager fallback fires and scans
- PolarSyncService started when device detected
- Debounce prevents duplicate syncs

---

## Step 6: Polar Device Management (UI + Store)

**Goal**: Allow user to pair, view, and manage PVS devices.

### Tasks

1. Extend `KnownDeviceStore` to support device type (Garmin vs Polar), or create `PolarDeviceStore`
   - Store: device ID, name, type, last sync timestamp
2. Add Polar section to CaptureScreen
   - "Polar Devices" card showing known PVS devices with last-sync times
   - "Add Polar Device" button → scan → select → pair
   - "Sync Now" button per device (manual trigger)
   - "Remove" option per device
3. Extend `CaptureViewModel` with Polar state and actions
   - `polarDevices: StateFlow<List<PolarDevice>>`
   - `polarSyncState: StateFlow<PolarSyncServiceState>`
   - Actions: `addPolarDevice(id)`, `removePolarDevice(id)`, `syncPolarNow(id)`
4. Configure offline recording on newly paired device
   - Enable PPI + ACC recording
   - Set `TRIGGER_SYSTEM_START` (auto-record on power-on)
5. Update DI modules

### Verification
- User can pair a PVS device via UI
- Device appears in known devices list
- Manual sync triggers PolarSyncService
- Device can be removed

---

## Step 7: Server Extensions

**Goal**: Accept and store accelerometer summary data.

### Tasks

1. Add `accelerometer_summaries` table to server SQLite schema
   ```sql
   CREATE TABLE IF NOT EXISTS accelerometer_summaries (
       device_id TEXT NOT NULL,
       window_start INTEGER NOT NULL,
       magnitude_mean REAL NOT NULL,
       magnitude_std REAL NOT NULL,
       magnitude_max REAL NOT NULL,
       sample_count INTEGER NOT NULL,
       sensor_type TEXT NOT NULL,
       session_id TEXT,
       synced_at INTEGER,
       PRIMARY KEY (device_id, window_start)
   );
   ```
2. Add Pydantic model `AccelerometerSummary` and `AccelerometerBatch`
3. Add endpoint `POST /api/v1/accelerometer/batch`
   - Same pattern as intervals batch: `INSERT OR IGNORE`, return accepted/duplicates
4. Extend `GET /api/v1/health` to include accelerometer summary count
5. Add `AccelerometerBatchDto` to Android network layer
6. Create `AccelerometerApi` on Android side
7. Extend `SyncManager` to sync ACC summaries after syncing intervals
8. Server tests for new endpoint
9. Android unit tests for new API + sync flow

### Verification
- Server accepts ACC batch, returns correct counts
- Idempotent: re-syncing same data produces only duplicates
- Health endpoint shows ACC count
- Android SyncManager pushes both intervals and ACC summaries

---

## Step 8: Integration Testing + Refinement

**Goal**: End-to-end validation with physical PVS device.

### Tasks

1. Pair PVS device via the app
2. Verify offline recording configuration
3. Wear device, let it record for a meaningful period
4. Place on charger near phone → verify automatic detection and sync
5. Verify PPI data in Room → synced to server
6. Verify ACC summaries in Room → synced to server
7. Verify data appears correctly on server with `sensor_type = "polar_pvs"`
8. Test manual sync trigger
9. Test edge cases:
   - Device out of range during sync (graceful failure)
   - Multiple sync attempts (idempotent)
   - Long recording (12+ hours of data)
   - Both PVS devices known (when second device available)
10. Performance: sync duration for a full day of recordings
11. Tune gap threshold for PPI data based on real-world observations

### Verification
- End-to-end flow works with real hardware
- Data quality is acceptable
- Sync time is reasonable
- No data loss on errors

---

## Dependency Graph

```
Step 1 (SDK + DB schema)
  │
  ├── Step 2 (Recording parser)
  │     │
  │     └── Step 3 (Sync orchestrator)
  │           │
  │           └── Step 4 (Foreground service)
  │                 │
  │                 └── Step 5 (Device detection)
  │
  └── Step 7 (Server extensions) ← can start in parallel with Steps 2-5
        │
        └── Step 7 (Android sync for ACC)
              │
Step 6 (UI) ← depends on Steps 4, 5, and device store
  │
  └── Step 8 (Integration testing) ← depends on everything

Parallelizable: Steps 2+7(server) can run in parallel after Step 1.
```

## Refactoring Notes

### PriorityMultiplexer

Current role: selects which real-time BLE stream feeds the UI notification during capture. This remains unchanged — it only applies to the Garmin real-time path. Polar data arrives via offline fetch and bypasses the multiplexer entirely.

Both data streams are stored in Room regardless of priority. Server-side analysis resolves which source is authoritative for a given time window.

### BleCaptureService

No changes needed. Continues to handle Garmin real-time capture independently.

### BleScanner

Minor extension: when scanning for Polar pairing (Step 6), filter for Polar-specific BLE characteristics rather than just HRM service UUID. The PVS advertises differently when it has offline recordings available. Details TBD based on Polar SDK documentation.

### SyncManager / SyncWorker

Extended to sync ACC summaries in addition to intervals. Both share the same trigger mechanism (WorkManager) and the same server health monitoring.

---

## Estimated Test Coverage

| Step | New tests (approx) |
|---|---|
| Step 1 | 8-10 (DAO tests) |
| Step 2 | 10-12 (parser unit tests) |
| Step 3 | 8-10 (orchestrator unit tests) |
| Step 4 | 3-5 (service lifecycle) |
| Step 5 | 4-6 (detector + receiver) |
| Step 6 | 5-8 (ViewModel tests) |
| Step 7 | 8-10 (server + Android sync) |
| Step 8 | Manual integration |
| **Total** | **~50-60 new tests** |
