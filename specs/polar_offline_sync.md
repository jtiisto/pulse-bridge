# Polar Verity Sense — Offline Recording Sync

## Status: APPROVED — implementation in progress

## Problem

The system needs 24/7 PPI (pulse-to-pulse interval) and accelerometer data from Polar Verity Sense (PVS) devices to enable overnight HRV analysis and sleep window detection. PVS records autonomously to internal memory — the app must detect the device, fetch recordings, and sync data to the server.

This is fundamentally different from the Garmin real-time BLE stream. The Garmin path remains unchanged; Polar is an additive, parallel data pipeline.

## Solution

A new `PolarSyncService` (foreground service) that:
1. Detects known PVS devices via background BLE scanning
2. Connects using the Polar BLE SDK
3. Fetches all offline recordings from device memory
4. Parses PPI intervals → `intervals` table (same as Garmin, different `sensor_type`)
5. Parses accelerometer data → 1-minute magnitude summaries → new `accelerometer_summaries` table
6. Deletes fetched recordings from the device
7. Triggers sync to push data to server

## Data Mapping

### PPI → IntervalEntity (existing table)

Each PPI sample from the Polar SDK maps to one `IntervalEntity`:

| IntervalEntity field | Source |
|---|---|
| `deviceId` | PVS device ID (Polar format, e.g. `"1A2B3C4D"`) |
| `timestampDevice` | Sample timestamp from recording |
| `timestampPhone` | Time of fetch (not recording time) |
| `heartRateBpm` | From PPI sample (SDK provides HR alongside PPI) |
| `rrIntervalMs` | PPI value in ms |
| `rrSequenceIndex` | Sequential index within recording |
| `isGap` | True if gap between consecutive samples exceeds threshold |
| `windowLabel` | null (server assigns during analysis) |
| `sensorType` | `"polar_pvs"` |
| `sessionId` | Recording-derived session ID |
| `isSynced` | false |

### Accelerometer → AccelerometerSummaryEntity (new table)

Raw accelerometer data (52Hz, 3-axis) is downsampled on-device to 1-minute magnitude summaries:

```
magnitude = sqrt(x² + y² + z²)
```

Per 1-minute window:

| Field | Description |
|---|---|
| `deviceId` | PVS device ID |
| `windowStart` | Start of 1-minute window (epoch ms) |
| `magnitudeMean` | Mean magnitude over window |
| `magnitudeStd` | Std deviation of magnitude (movement variability) |
| `magnitudeMax` | Peak magnitude in window |
| `sampleCount` | Number of raw samples in window (expect ~3120 at 52Hz) |
| `sensorType` | `"polar_pvs"` |
| `sessionId` | Recording-derived session ID |
| `isSynced` | false |

**Why 1-minute resolution**: The sleep window detector operates at 1-minute granularity (`rolling_magnitude(acc_stream, window='1min')`). Storing raw 52Hz would be ~4.5M samples/day vs ~1,440 summaries/day — 3,000× more data with no analytical benefit for the current use case.

**Composite PK**: `(deviceId, windowStart)` — same idempotency pattern as intervals.

## Device Detection

### PendingIntent-based BLE Scan (primary)

Register a background BLE scan filter for known PVS device addresses using `BluetoothLeScanner.startScan(filters, settings, PendingIntent)`. Android wakes the app when a matching device is detected — no polling needed.

- Registered at app startup and after each device pairing
- Survives app process death (system-level scan)
- Low power: scan filter runs in Bluetooth hardware
- A `BroadcastReceiver` catches the PendingIntent and starts `PolarSyncService`

### WorkManager Periodic Scan (fallback)

A periodic WorkManager task (every 15 minutes — WorkManager floor) does a brief active BLE scan as a fallback in case the PendingIntent scan misses a device (some OEMs throttle background BLE).

### Manual Trigger (UI)

A "Sync Now" button in the UI starts a scan + sync immediately. Essential for testing and for cases where the user wants immediate results.

## Sync Flow

```
Device detected (PendingIntent / WorkManager / manual)
  │
  ▼
PolarSyncService starts as foreground service
  │── Acquire PARTIAL_WAKE_LOCK
  │── Show "Syncing Polar data..." notification
  │
  ▼
Connect to PVS via Polar BLE SDK
  │
  ▼
List offline recordings on device
  │
  ▼
For each recording:
  ├── Fetch recording data
  ├── Parse PPI samples → List<IntervalEntity>
  │   └── Detect gaps (threshold TBD, likely ~3s same as Garmin)
  ├── Parse ACC samples → compute 1-min summaries → List<AccelerometerSummaryEntity>
  ├── Insert into Room (within transaction)
  │   └── INSERT OR IGNORE (idempotent — safe if recording fetched twice)
  └── Delete recording from device (only after successful Room insert)
  │
  ▼
Trigger SyncWorker to push to server
  │
  ▼
Release wake lock
Dismiss notification
Stop service
```

### Error Handling

- **BLE disconnect mid-fetch**: Recordings remain on device (not deleted until successfully stored). Next sync attempt will re-fetch. Idempotent inserts prevent duplicates.
- **Partial recording fetch**: If a recording fetch fails partway, discard the partial data and retry on next sync. Do not delete the recording from the device.
- **Room insert failure**: Do not delete recording from device. Log error, continue to next recording.
- **No recordings found**: Normal case (device just paired, or already synced). Log and stop gracefully.

## Device Management

### Pairing Flow

1. User taps "Add Polar Device" in UI
2. App scans for PVS devices (using existing `BleScanner` with Polar filter)
3. User selects device from list
4. App saves device to `KnownDeviceStore` (extend existing, or separate `PolarDeviceStore`)
5. App registers PendingIntent BLE scan filter for this device address
6. App configures offline recording on the device if not already enabled:
   - Set `TRIGGER_SYSTEM_START` — recording starts automatically on power-on
   - Configure PPI + ACC recording features

### Two-Device Support

- Both PVS devices are stored as known devices
- App syncs whichever device it detects — no role assignment
- UI shows both devices with last-sync timestamps
- Either device can be removed independently

## Priority / Overlap

During workouts, both Garmin (real-time) and Polar (offline recording) capture data for the same time window. **Both are stored without client-side filtering.** Priority resolution happens server-side during analysis:

- Garmin ECG-grade RR intervals are authoritative during exercise windows
- Polar PPG-based PPI is authoritative for all other windows
- `sensor_type` and `timestampDevice` provide the data needed for server-side resolution

## Server Changes

### New Endpoint: `POST /api/v1/accelerometer/batch`

Accepts batch of accelerometer summaries:

```json
{
  "summaries": [
    {
      "device_id": "1A2B3C4D",
      "window_start": 1711152000000,
      "magnitude_mean": 1.02,
      "magnitude_std": 0.15,
      "magnitude_max": 2.81,
      "sample_count": 3120,
      "sensor_type": "polar_pvs",
      "session_id": "rec_2026-03-23_001"
    }
  ]
}
```

Response: same pattern as intervals batch (`accepted`, `duplicates`, `total_received`).

### New Table: `accelerometer_summaries`

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

`INSERT OR IGNORE` for idempotent sync — same pattern as intervals.

### Existing Endpoint: No Changes

`POST /api/v1/intervals/batch` already accepts any `sensor_type`. Polar PPI data flows through the same endpoint with `sensor_type = "polar_pvs"`.

## Android Permissions

No new permissions beyond what Phase 1 already declares:
- `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT` — already declared
- `FOREGROUND_SERVICE` with `connectedDevice|dataSync` — already declared
- `WAKE_LOCK` — already declared

## Dependencies

New Gradle dependency in `core/ble/build.gradle.kts`:

```kotlin
implementation("com.polar.sdk:polar-ble-sdk:6.0.0")  // verify latest version
```

The Polar SDK brings its own BLE handling — we do NOT use raw Android BLE APIs for Polar devices.

## Files to Add

| File | Purpose |
|---|---|
| `core/ble/.../polar/PolarOfflineSync.kt` | Orchestrates connect → fetch → parse → store → delete |
| `core/ble/.../polar/PolarRecordingParser.kt` | Parses PPI and ACC from SDK data types |
| `core/ble/.../polar/PolarDeviceDetector.kt` | PendingIntent scan registration + WorkManager fallback |
| `core/ble/.../service/PolarSyncService.kt` | Foreground service wrapping PolarOfflineSync |
| `core/database/.../entity/AccelerometerSummaryEntity.kt` | Room entity |
| `core/database/.../dao/AccelerometerSummaryDao.kt` | Room DAO |
| `core/network/.../dto/AccelerometerBatchDto.kt` | Network DTO |
| `core/network/.../AccelerometerApi.kt` | API client for ACC endpoint |
| `server/models.py` | Extend with AccelerometerSummary model |
| `server/database.py` | Add accelerometer_summaries table + insert logic |

## Files to Modify

| File | Change |
|---|---|
| `core/ble/build.gradle.kts` | Add Polar SDK dependency |
| `core/ble/.../di/BleModule.kt` | Register Polar components in Koin |
| `core/ble/.../device/KnownDeviceStore.kt` | Support Polar device type (or create separate store) |
| `core/database/.../PulseBridgeDatabase.kt` | Add AccelerometerSummaryEntity + DAO |
| `core/sync/.../SyncManager.kt` | Sync ACC summaries alongside intervals |
| `core/sync/.../SyncWorker.kt` | Trigger ACC sync |
| `feature/capture/.../ui/CaptureScreen.kt` | Polar device management UI |
| `feature/capture/.../ui/CaptureViewModel.kt` | Polar sync state + actions |
| `app/src/main/AndroidManifest.xml` | Register PolarSyncService + BroadcastReceiver |
| `server/main.py` | Add accelerometer batch endpoint |

## Open Questions

1. **Polar SDK offline recording API surface**: The exact method signatures and data types need verification against the latest SDK version. The spec describes the logical flow; implementation will adapt to the SDK's actual API.
2. **Recording configuration**: Does the PVS need one-time configuration via the SDK to enable offline recording with PPI + ACC? Or is this done via the Polar Flow app? Need to test with the physical device.
3. **Device ID format**: Polar SDK uses device IDs like `"1A2B3C4D"` (not MAC addresses). Confirm format and use consistently.
4. **Gap threshold for PPI**: Garmin uses 3 seconds. PPI from optical sensor may have different characteristics — may need a different threshold. TBD during testing.
