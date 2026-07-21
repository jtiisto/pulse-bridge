# Wellness-Sync: Phase 1 — App Structure + Garmin HRM BLE Capture

## Implementation Status (updated 2026-03-23)

| Step | Status | Notes |
|------|--------|-------|
| 1. Project Scaffolding | **DONE** | All modules, build files, theme, manifest, navigation. `assembleDebug` passes (234 tasks). |
| 2. Database Layer | **DONE** | Room DB with 3 entities, 3 DAOs. 21 instrumented tests pass on emulator. |
| 3. BLE Models and Buffer | **DONE** | HeartRateSample, IntervalBuffer (configurable flush), ReconnectionStrategy. 17 unit tests pass. |
| 4. BLE Connection Layer | **DONE** (unit tests) | HrmCharacteristicParser, PriorityMultiplexer, GarminHrmConnection, BleScanner. 21 unit tests pass. Real BLE testing pending (needs Garmin strap). |
| 5. Foreground Service | **DONE** | BleCaptureService (START_STICKY), BleCaptureNotification, KnownDeviceStore, BleModule/DatabaseModule Koin DI. 6 new unit tests. |
| 6. Capture Feature UI | **DONE** | CaptureState/Event/Effect MVI, CaptureRepository, CaptureViewModel, CaptureScreen (status/device/sync/data cards), runtime permissions. 11 new unit tests. |
| 7. Network, Sync, Server | **DONE** | FastAPI server (health + batch endpoints, per-env SQLite), Ktor HTTP client, IntervalApi, SyncManager, SyncWorker (WorkManager), settings screen (env toggle + clear data). 19 new tests (3 network + 8 sync + 8 server). |
| 8. DI Wiring & Integration | **DONE** | All Koin modules wired (database, ble, network, sync, capture). Periodic sync on app start. End-to-end pipeline ready. |

**Test totals:** 66 unit tests + 8 server tests + 21 instrumented tests = 95 tests, 0 failures.

**Post-implementation quality fixes applied:**
- `ReconnectionStrategy`: `Duration.parse()` crash → `cappedMs.milliseconds`
- `IntervalBuffer`: multiple `System.currentTimeMillis()` calls → compute once per sample
- `PriorityMultiplexer`: race condition → `synchronized` block on register/unregister
- `GarminHrmConnection`: GATT leak on reconnect → close before new connect; `lastSampleTimestamp` race → `AtomicLong`

---

## Capture Feature Spec

> This spec covers the BLE capture feature end-to-end (Steps 3-6). Steps 3-4 are implemented and tested. Steps 5-6 are pending.

### Goal

Continuously capture heart rate and RR interval data from a Garmin HRM chest strap over BLE, persist it locally in Room, and expose capture state to the UI — all surviving app backgrounding via a foreground service.

### Existing Interfaces (Steps 3-4, implemented)

The following are already built and tested:

| Component | Module | Role |
|-----------|--------|------|
| `BleDeviceConnection` | `core/ble` | Interface — `connectionState: StateFlow<ConnectionState>`, `heartRateData: Flow<HeartRateSample>`, `connect()`, `disconnect()` |
| `GarminHrmConnection` | `core/ble` | Scans for HRM Service (`0x180D`), subscribes to HR Measurement (`0x2A37`), parses RR intervals, auto-reconnects |
| `HrmCharacteristicParser` | `core/ble` | Parses `0x2A37` byte format — 8/16-bit HR, RR present flag, multiple RR per notification (1/1024s resolution) |
| `PriorityMultiplexer` | `core/ble` | Single-source passthrough in Phase 1; `authoritativeStream: Flow<HeartRateSample>` from highest-priority active sensor |
| `BleScanner` | `core/ble` | Wraps `BluetoothLeScanner`, filters for `0x180D`, emits `Flow<DiscoveredDevice>` |
| `IntervalBuffer` | `core/ble` | Accumulates `HeartRateSample` in memory, flushes to `IntervalDao` on timer (default 10s) or max buffer size (200). Maps samples → `IntervalEntity` rows (one per RR interval). |
| `ReconnectionStrategy` | `core/ble` | Exponential backoff: 1s initial, 2x multiplier, 30s max, unlimited attempts |
| `IntervalDao` | `core/database` | `insertAll(IGNORE)`, `getUnsyncedIntervals(limit)`, `markSynced()`, `getUnsyncedCount(): Flow<Int>` |
| `DeviceSessionDao` | `core/database` | `insert`, `update`, `getActiveSession(deviceId)`, `getRecentSessions(limit): Flow` |
| `SyncStatusDao` | `core/database` | Singleton row — `observe(): Flow`, `get()`, `upsert()` |

### Step 5: Foreground Service — `BleCaptureService`

**Purpose:** Persistent BLE capture that survives app backgrounding and device sleep.

**Service declaration:**
```xml
<service
    android:name=".core.ble.service.BleCaptureService"
    android:foregroundServiceType="connectedDevice|dataSync"
    android:exported="false" />
```

**Lifecycle:**
1. UI sends `startService()` with device address as intent extra
2. Service promotes to foreground immediately (`startForeground()` with notification)
3. Acquires `PARTIAL_WAKE_LOCK` (tagged `PulseBridge:BleCapture`)
4. Creates a `DeviceSessionEntity` (UUID, start time, device info)
5. Creates `GarminHrmConnection` → calls `connect()`
6. Registers connection's `heartRateData` flow with `PriorityMultiplexer`
7. Collects `authoritativeStream` → feeds samples to `IntervalBuffer` (with session ID)
8. `IntervalBuffer` flushes to Room on its configured timer
9. On stop: flushes buffer, closes session (end time, total intervals, avg HR), releases wake lock, calls `disconnect()`, stops foreground

**State publication (singleton StateFlow via Koin):**
- `BleCaptureServiceState` data class: `connectionState`, `currentHr`, `deviceName`, `sessionId`, `intervalCount`, `isRunning`
- Published via a Koin singleton `MutableStateFlow<BleCaptureServiceState>` — service writes, UI reads
- Chosen over bound service pattern: simpler, no lifecycle binding ceremony, works naturally with Compose's reactive model. Only downside (state lost on process kill) is mitigated by START_STICKY restart repopulating the state.
- Service updates state on every connection state change and every HR sample

**Notification (`BleCaptureNotification`):**
- Channel: `ble_capture` ("BLE Capture" — importance LOW for silent ongoing notification)
- Content: connection status + live HR (e.g. "Connected — 142 bpm" / "Reconnecting...")
- Ongoing, non-dismissible while service runs
- Tap action: opens `MainActivity`

**Service restart (`START_STICKY`):**
- `onStartCommand` returns `START_STICKY` — Android automatically restarts the service after a process kill
- On restart (null intent): service checks Room for an open session (no `endTime`) via `DeviceSessionDao.getActiveSession()`
- If an open session exists, the service resumes capture using the session's `deviceId` — no manual intervention needed
- If no open session, the service stops itself (nothing to resume)
- This enables the "just put on the strap and work out" experience — the app finds the strap automatically

**Error handling:**
- BLE disconnect → `ReconnectionStrategy` handles automatic reconnect, service stays alive
- Fatal error (BLE adapter off, permissions revoked) → stop service, update state with error
- Process kill → Android restarts service via START_STICKY; service re-reads last session from Room and resumes

### Step 6: Capture Feature UI — MVI Pattern

**State:**
```kotlin
data class CaptureState(
    val isCapturing: Boolean = false,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val currentHr: Int? = null,
    val deviceName: String? = null,
    val intervalCount: Int = 0,
    val unsyncedCount: Int = 0,
    val lastSyncTime: Long? = null,
    val error: String? = null,
    val permissionsGranted: Boolean = false,
)
```

**Events:**
```kotlin
sealed interface CaptureEvent {
    data class StartCapture(val deviceAddress: String) : CaptureEvent
    data object StopCapture : CaptureEvent
    data object StartScan : CaptureEvent
    data class RemoveKnownDevice(val address: String) : CaptureEvent
    data object DismissError : CaptureEvent
}
```

**Effects (one-shot):**
```kotlin
sealed interface CaptureEffect {
    data class ShowError(val message: String) : CaptureEffect
}
```

**Repository (`CaptureRepository`):**
- Bridges service state (`BleCaptureServiceState` flow), Room queries (`unsyncedCount`, `syncStatus`), and scan results
- Provides: `serviceState: Flow`, `unsyncedCount: Flow<Int>`, `syncStatus: Flow<SyncStatusEntity?>`
- Actions: `startCapture(context, deviceAddress)`, `stopCapture(context)`

**ViewModel (`CaptureViewModel`):**
- Combines repository flows into `CaptureState` via `StateFlow`
- Processes `CaptureEvent` → delegates to repository
- Exposes `CaptureEffect` via `Channel` for one-shot UI effects

**Device selection UX:**
- **Known devices** stored in SharedPreferences (address + name pairs)
- On scan, known devices shown at top of list with "previously connected" label
- If a known device is discovered during scan, auto-connect to it (no user action needed)
- If no known device found, show discovered HRM devices for manual selection
- First successful connection saves the device as "known" for future auto-connect
- **Remove known device**: swipe-to-dismiss or long-press on known device list, in case user accidentally selects wrong strap (e.g. someone else's in a gym)
- This ensures "just works" after first pairing, even with multiple HRM straps in BLE range

**Screen (`CaptureScreen`):**
- **Status Card** (gradient surface): connection state dot + label, device name, live HR (large text, animates on update), session duration
- **Device Section**: known devices list (with remove option), scan button, discovered devices during active scan
- **Sync Status Card**: "Synced up to [time]" or "X intervals pending", last sync time, "Sync Now" button (secondary, disabled until Step 7)
- **Data Summary Card**: total interval count, buffer size
- **Start/Stop button**: primary CTA, toggles based on `isCapturing`
- **Permission flow**: runtime request for `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `POST_NOTIFICATIONS` before first capture

**Styling:** follows `specs/initial_ui_design.md` — dark theme, custom color palette, Material 3 mapping.

### Test / Production Environment Separation

**Problem:** Need to test the full pipeline (BLE capture → sync → server → analysis) with real devices without polluting production data, and without requiring a rebuild to switch modes.

**App-side:**
- Runtime toggle in the app UI (settings or prominent switch on capture screen) to select `test` or `production` environment
- Stored in SharedPreferences, persists across restarts, defaults to `production`
- App sends the environment as a header (`X-Environment: test` or `X-Environment: production`) with every sync request
- Local Room database is shared — no per-environment separation on the app side (the app is just a capture bridge)
- **Clear local data** action in the app to wipe Room database for testing cleanup

**Server-side:**
- Server reads the `X-Environment` header and routes to a separate SQLite database file per environment (e.g. `pulse_bridge_prod.db`, `pulse_bridge_test.db`)
- Both databases have identical schema — analysis can run against either
- Test data cleanup is manual (delete the test DB file)
- No API endpoint for cleanup needed

**Why:** Rebuilding the app to switch environments is slow and disrupts testing flow. Runtime toggle + separate server DBs gives full isolation with zero friction. Analysis works identically on both — just point at the right DB.

### Behavior Rules

1. **Strap-as-exercise-signal**: The Garmin HRM strap is a workout-only device. Its BLE presence/absence is the exercise window signal. No manual workout start/stop — the app just captures all data while connected.
2. **Session boundaries**: A `DeviceSessionEntity` is created on connect and closed on disconnect. Sessions are never manually started/stopped.
3. **Data loss prevention**: Buffer flushes every 10s by default. Explicit flush on service stop. Room stores indefinitely.
4. **No hardcoded parameters**: Buffer flush interval, max buffer size, reconnection delays — all configurable via `BufferConfig` and `ReconnectionConfig`.
5. **Sync deferred**: "Sync Now" button is present but disabled until Step 7 (network/sync). Unsynced count and last sync time display zeros/null gracefully.

### Test Plan

| Layer | Framework | What |
|-------|-----------|------|
| `BleCaptureService` | JUnit 5 + MockK | Service state transitions, session creation/closure, buffer flush on stop, wake lock acquire/release |
| `CaptureViewModel` | JUnit 5 + Turbine | State emission on events, flow combination, error propagation |
| `CaptureRepository` | JUnit 5 + MockK | Flow bridging, service start/stop delegation |
| Integration | Emulator | Full UI flow — permission grant, scan, connect, see live HR, stop, verify intervals in Room |

---

## Context

This is the native Android app component of the Personal Health Monitoring Pipeline (see `plans/overall_plan.md`). The app acts as a local "Android Bridge" — capturing heart rate and RR interval data from BLE sensors, storing locally in Room, and batch-syncing to a server over WiFi.

Phase 1 focuses on: **project scaffolding and Garmin HRM strap BLE integration**. The architecture must accommodate future Polar Verity Sense integration (Phase 2) without major refactoring.

The project follows the same conventions as the sibling `wellness` app at `/home/jtiisto/dev/native/wellness/`.

## Design Principles

- **No hardcoded assumptions**: Operational parameters (buffer flush interval, sync batch size, reconnection delays, etc.) are exposed as configuration with sensible defaults. Values are tuned through real-world iteration, not guessed upfront.
- **Data loss prevention is the top priority**: Shorter flush intervals preferred over I/O efficiency. Data must survive process kills, crashes, and extended offline periods.
- **Sync on any connection**: User has unlimited mobile data. Sync fires on any network (WiFi or cellular), not WiFi-only. Server is behind Tailscale and may be unreachable — failed syncs retry automatically with backoff. Room stores data indefinitely until sync succeeds.
- **Automatic sync with manual fallback**: WorkManager handles automatic opportunistic sync (fires when network available, retries on failure with backoff). A manual "Sync Now" button provides user control when needed.
- **Strap-as-exercise-signal**: The Garmin HRM chest strap is a workout-only device — its BLE presence/absence is the exercise window signal. No manual start/stop in this app. The app captures all data from all active sources, tagged by `sensorType`. Workout boundary analysis (correlating strap activity with PWA start/stop signals, HR patterns, etc.) is server-side.
- **Single capture path (BLE only)**: Phone is always present during workouts. ANT+ dongle → Linux server path is out of scope — adds a second ingestion pipeline with no user-facing benefit.
- **UI is temporary, architecture is not**: UI will be redesigned in Stitch later. Keep Compose screens thin and state-driven so they can be swapped without touching ViewModels/Repositories. Initial design follows the Wellness PWA dark theme — see `specs/initial_ui_design.md`.

## Module Structure

```
pulse-bridge/
├── app/                              # Application shell
├── core/
│   ├── common/                       # Shared utilities, date/time helpers
│   ├── database/                     # Room database, entities, DAOs
│   ├── network/                      # Ktor HTTP client for server sync
│   ├── sync/                         # Connectivity monitoring, sync orchestration
│   ├── ble/                          # BLE abstraction layer (NEW vs wellness)
│   └── ui/                           # Shared theme and components
├── feature/
│   └── capture/                      # BLE capture feature (data/domain/ui)
├── specs/                            # Feature specs (approved before implementation)
├── plans/                            # Reference docs (not code)
├── gradle/libs.versions.toml
├── build.gradle.kts
├── settings.gradle.kts
└── CLAUDE.md
```

Base package: `dev.jtiisto.pulsebridge`

## Implementation Steps

Each step is a testable, deployable increment.

### Step 1: Project Scaffolding ✅

Create the Gradle multi-module project that compiles and installs on the emulator (blank screen).

**Files to create:**
- `build.gradle.kts` (root) — plugin declarations, no apply
- `settings.gradle.kts` — all module includes, rootProject.name = "pulse-bridge"
- `gradle.properties` — JVM args, AndroidX, non-transitive R classes
- `gradle/libs.versions.toml` — copy from wellness, no new deps needed for Phase 1
- `app/build.gradle.kts` — applicationId `dev.jtiisto.pulsebridge`, compileSdk/minSdk/targetSdk = 35, JDK 21
- `app/src/main/AndroidManifest.xml` — declare BLE permissions and foreground service types upfront
- `app/src/main/kotlin/.../PulseBridgeApplication.kt` — Koin init
- `app/src/main/kotlin/.../MainActivity.kt` — setContent with theme
- `app/src/main/kotlin/.../di/AppModule.kt` — empty, wires sub-modules later
- `app/src/main/kotlin/.../navigation/PulseBridgeNavHost.kt` — single route for now
- `core/common/build.gradle.kts` + `DateTimeUtils.kt`
- `core/ui/build.gradle.kts` + `Theme.kt`, `Color.kt`, `Type.kt` (per `specs/initial_ui_design.md`)
- All other module `build.gradle.kts` stubs (database, network, sync, ble, feature/capture)
- `CLAUDE.md` — project-level dev process and spec references

**Manifest permissions** (declared now, requested at runtime later):
```xml
BLUETOOTH_SCAN, BLUETOOTH_CONNECT, FOREGROUND_SERVICE,
FOREGROUND_SERVICE_CONNECTED_DEVICE, FOREGROUND_SERVICE_DATA_SYNC,
POST_NOTIFICATIONS, INTERNET, ACCESS_NETWORK_STATE, WAKE_LOCK
<uses-feature android:name="android.hardware.bluetooth_le" android:required="true" />
```

**Verify:** `./gradlew assembleDebug` succeeds, installs on emulator, shows themed blank screen.

**Reference files:**
- `/home/jtiisto/dev/native/wellness/build.gradle.kts`
- `/home/jtiisto/dev/native/wellness/settings.gradle.kts`
- `/home/jtiisto/dev/native/wellness/app/build.gradle.kts`
- `/home/jtiisto/dev/native/wellness/gradle/libs.versions.toml`

---

### Step 2: Database Layer ✅

Room database with schema designed for high-frequency interval storage.

**Entities:**

`IntervalEntity` — one row per RR interval:
- PK: `(deviceId: String, timestampDevice: Long)`
- `timestampPhone: Long` — phone wall clock at receipt (fallback)
- `heartRateBpm: Int`
- `rrIntervalMs: Int` — single RR value
- `rrSequenceIndex: Int` — order within a single BLE notification
- `isGap: Boolean` — gap flag, never interpolate
- `windowLabel: String?` — populated by server post-sync (sleep/exercise/daytime)
- `sensorType: String` — "garmin_hrm" or "polar_pvs"
- `sessionId: String?` — FK to session
- `isSynced: Boolean` + `syncedAt: Long?`
- Indices on: `timestampDevice`, `isSynced`, `windowLabel`

`DeviceSessionEntity` — auto-created when a BLE device connects, closed when it disconnects:
- PK: `sessionId: String` (UUID)
- `deviceId`, `sensorType`, `startTime`, `endTime`, `totalIntervals`, `averageHr`
- No manual start/stop — the strap connecting/disconnecting defines session boundaries
- Server uses these to identify workout windows

`SyncStatusEntity`:
- Singleton (PK = 1), tracks last sync time, pending count, last error

**Key DAO queries:**
- `insertAll(intervals)` with IGNORE conflict strategy
- `getUnsyncedIntervals(limit)` ordered by timestampDevice
- `markSynced(deviceId, timestamps, syncedAt)`
- `getUnsyncedCount()` as Flow (for UI)
- `getIntervalsInRange(start, end)`

**Tests:** JUnit 5 with Room in-memory database — DAO queries, conflict resolution, composite PK behavior, sync marking.

**Verify:** All DAO tests pass.

---

### Step 3: BLE Models and Buffer ✅

Data models and the write buffer — testable without BLE hardware.

**Models** (`core/ble/model/`):
- `BleDevice` — sealed class with `GarminHrm` and `PolarPvs` subtypes
- `HeartRateSample(deviceId, timestampDevice, heartRateBpm, rrIntervalsMs: List<Int>, sensorPriority, isGapBefore)`
- `ConnectionState` — enum: DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING
- `SensorPriority` — enum: GARMIN_ECG(rank=1), POLAR_PPG(rank=2)

**IntervalBuffer** (`core/ble/buffer/`):
- Accumulates `HeartRateSample` in memory
- Flushes to Room via `IntervalDao` at a configurable interval (default: short, e.g. 5-10s to minimize data loss — tuned through iteration)
- Also flushes on explicit `flush()` call (service stop, app backgrounding)
- Maps `HeartRateSample` → `IntervalEntity` rows (one per RR interval in the sample)
- Flush interval, max buffer size exposed as config parameters

**ReconnectionStrategy** (`core/ble/reconnect/`):
- Exponential backoff with configurable initial delay, multiplier, and max delay
- Defaults: 1s initial, 2x multiplier, 30s max — tunable

**Tests:** Buffer flush timing, batch accumulation, forced flush, backoff math.

**Verify:** All unit tests pass.

---

### Step 4: BLE Connection Layer ✅ (unit tests)

Actual BLE scanning and GATT connection to Garmin HRM.

**Interface** (`BleDeviceConnection`):
```kotlin
interface BleDeviceConnection {
    val deviceId: String
    val connectionState: StateFlow<ConnectionState>
    val heartRateData: Flow<HeartRateSample>
    suspend fun connect()
    suspend fun disconnect()
}
```

**GarminHrmConnection:**
- Scans for BLE HRM Service (UUID `0x180D`)
- Subscribes to HR Measurement characteristic (UUID `0x2A37`)
- Parses the characteristic byte format:
  - Bit 0 of flags = HR format (8-bit or 16-bit)
  - Bit 4 = RR intervals present
  - RR values are uint16 in 1/1024 second resolution
  - Multiple RR intervals possible per notification
- Auto-reconnects using `ReconnectionStrategy`

**PriorityMultiplexer:**
- Registers/unregisters sensor `Flow<HeartRateSample>` sources by priority
- `authoritativeStream: Flow<HeartRateSample>` emits from highest-priority active source
- Phase 1: single-source passthrough; architecture ready for Phase 2
- Phase 2: Garmin strap connecting = exercise started (auto-priority switch). Garmin disconnecting = exercise ended (Polar resumes as authoritative). All data captured regardless — multiplexer controls authoritative tagging, not data capture.

**BleScanner:**
- Wraps `BluetoothLeScanner` with scan filters for `0x180D`
- Emits discovered devices as Flow

**Tests:** Byte parsing of `0x2A37` (8-bit HR, 16-bit HR, with/without RR, multiple RR per packet, energy expended field). Multiplexer passthrough and priority switching. Mockk for BluetoothGatt callbacks.

**Verify:** Parsing tests pass. Connect to real Garmin HRM strap on emulator/device and confirm RR data flows.

---

### Step 5: Foreground Service

Persistent BLE capture that survives backgrounding.

**BleCaptureService:**
- `foregroundServiceType="connectedDevice|dataSync"`
- Manages `BleDeviceConnection` lifecycle
- Owns `IntervalBuffer`, triggers flush on stop
- Publishes state via Koin-scoped `StateFlow` for UI observation
- `PARTIAL_WAKE_LOCK` during active capture (release on stop)

**BleCaptureNotification:**
- Notification channel setup
- Ongoing notification showing connection status + live HR

**Manifest:**
```xml
<service
    android:name=".core.ble.service.BleCaptureService"
    android:foregroundServiceType="connectedDevice|dataSync"
    android:exported="false" />
```

**Verify:** Service starts, survives app backgrounding, notification shows live HR, intervals accumulate in Room.

---

### Step 6: Capture Feature UI

User-facing MVI screen for BLE capture control. Follow `specs/initial_ui_design.md` for styling. Keep screens thin — all state comes from ViewModel, UI is easily replaceable later (Stitch redesign).

**MVI pattern** (`feature/capture/domain/`):
- `CaptureState` — connectionState, currentHr, rrCount, bufferSize, syncStatus (last sync time, pending count, "synced up to" timestamp), error
- `CaptureEvent` — StartCapture, StopCapture, SelectDevice, SyncNow
- `CaptureEffect` — NavigateToDeviceList, ShowError

**Repository** (`feature/capture/data/CaptureRepository.kt`):
- Bridges service state, Room queries, and sync status to ViewModel

**UI** (`feature/capture/ui/`):
- `CaptureViewModel` — processes events, updates state via StateFlow
- `CaptureScreen`:
  - Connection status card (device name, state, live HR)
  - Sync status display: "Synced up to [timestamp]" or "X intervals pending" + last sync time
  - "Sync Now" button (manual trigger)
  - Interval count, buffer status
- Runtime permission request flow for BLUETOOTH_SCAN, BLUETOOTH_CONNECT, POST_NOTIFICATIONS

**Tests:** ViewModel state transitions with Turbine.

**Verify:** Full UI flow — scan, connect, see live HR, start/stop session, see interval count grow.

---

### Step 7: Network, Sync, and Server Endpoint

Batch sync unsynced intervals to a real server endpoint.

**Server endpoint** (minimal Python/FastAPI ingestion service):
- POST `/api/v1/intervals/batch` — accepts JSON array of interval records
- Validates payload, writes to server-side SQLite with gap flags
- Returns acknowledgment with count of accepted records
- Runs on the existing dev server infrastructure
- Location TBD — could live in this repo under `server/` or in a separate project

**Network** (`core/network/`):
- `ServerConfig` — base URL, timeouts (configurable)
- `HttpClientProvider` — Ktor client with OkHttp engine, content negotiation, JSON serialization
- `IntervalBatchDto` — serializable batch of intervals for POST
- `SyncResponseDto` — server acknowledgment with accepted count
- `IntervalApi` — POST batch, receive sync confirmation

**Sync** (`core/sync/`):
- `ConnectivityMonitor` — NetworkCallback for any network (WiFi or cellular, user has unlimited data)
- `SyncManager` — queries unsynced intervals, batches into DTOs, POSTs, marks synced on success. On failure (server behind Tailscale, unreachable), data stays in Room indefinitely — no data expiration.
- `SyncWorker` (WorkManager) — automatic opportunistic sync: fires when any network available, retries with backoff on failure (server unreachable). Configurable sync interval.
- Manual "Sync Now" action exposed to UI for user-triggered sync

**Tests:** Ktor mock engine for request/response. SyncManager logic with mock DAO and API. Server endpoint tested with pytest.

**Verify:** End-to-end — intervals sync from Room to server SQLite when connection available, marked as synced in Room.

---

### Step 8: DI Wiring and Integration

Connect all Koin modules and verify end-to-end.

**Koin modules** (one per core/feature module):
- `BleModule`, `DatabaseModule`, `NetworkModule`, `SyncModule`, `CaptureModule`
- `AppModule` includes all sub-modules

**Verify (end-to-end):**
1. App installs and shows capture screen
2. Scan finds Garmin HRM strap
3. Connect → live HR displayed
4. RR intervals buffer and flush to Room
5. On network → batch sync fires → intervals marked synced
6. Service survives backgrounding
7. Auto-reconnect on BLE disconnect

## Key Design Decisions

1. **One row per RR interval** (not per BLE notification) — simpler querying at the cost of more rows. At ~1 HR notification/sec with 1-2 RR values each, this is ~120 rows/minute — manageable even with aggressive flush intervals.

2. **Device-level timestamps as PK** — integrity independent of phone clock, per the overall plan's design principles.

3. **PriorityMultiplexer exists in Phase 1 as passthrough** — zero overhead, but the interface is in place so Phase 2 Polar integration slots in without rearchitecting data flow.

4. **`core/ble` is a separate module** (not in the wellness reference project) — BLE is the distinguishing capability of this app and warrants its own module boundary.

5. **minSdk 35** — no need for location permissions for BLE scanning (removed in API 31+).

6. **No hardcoded operational parameters** — buffer flush interval, sync batch size, reconnection delays, and sync frequency are all configurable. Start with conservative defaults (prioritize data safety), iterate based on real-world battery and performance impact.

7. **Indefinite local storage** — Room retains all unsynced data with no TTL or expiration. Server may be unreachable for extended periods (Tailscale not running). Sync catches up automatically when connection is restored.

8. **Singleton StateFlow for service ↔ UI communication** — Koin singleton `MutableStateFlow<BleCaptureServiceState>` that the service writes to and the ViewModel reads from. Chosen over bound service: simpler, no lifecycle binding ceremony, works naturally with Compose. State loss on process kill is mitigated by START_STICKY repopulating it.

9. **START_STICKY service restart** — `onStartCommand` returns `START_STICKY`. On restart after process kill, service checks Room for an open session (no `endTime`) and resumes capture automatically. Enables "just put on the strap and work out" — no manual app interaction needed.

10. **Smart device selection with known-device auto-connect** — Previously connected devices stored in SharedPreferences (address + name). On scan, if a known device is discovered, auto-connect without user action. Unknown devices shown in a list for manual selection. Known devices can be removed (in case of accidental selection, e.g. someone else's strap in a gym). After first pairing, subsequent connections are fully automatic.

11. **Runtime test/production environment toggle** — App UI toggle (SharedPreferences, defaults to production) sends `X-Environment` header with sync requests. Server routes to separate SQLite DB files per environment (`pulse_bridge_prod.db`, `pulse_bridge_test.db`). Identical schema — analysis runs on either. Test cleanup is manual file deletion. Local Room database is shared (no app-side separation). "Clear local data" action available for testing cleanup.

## Workflow

This project uses **spec-driven development** (same as the wellness sibling):
- `specs/` directory with one spec per feature
- Each spec must be approved before implementation begins
- Spec format: Goal, API/Interface, Behavior, Dependencies, Open Questions
- Specs are living documents — updated if understanding changes

The capture feature spec is included inline in this document (see "Capture Feature Spec" section above).

## Testing & Emulator Requirements

| Step | Testing | Emulator needed? |
|------|---------|-----------------|
| 1. Scaffolding | `./gradlew assembleDebug` | No (build only) |
| 2. Database | JUnit 5 unit tests | No |
| 3. BLE Models/Buffer | JUnit 5 unit tests | No |
| 4. BLE Connection | Unit tests + real BLE | Yes + Garmin strap |
| 5. Foreground Service | Integration test | Yes + Garmin strap |
| 6. Capture UI | Full UI flow | Yes + Garmin strap |
| 7. Network/Sync | Unit tests + E2E | Yes + server running |
| 8. Integration | Full pipeline | Yes + server + strap |

## Caveats

- Buffer flush interval is a tradeoff between data loss risk and I/O overhead — start conservative (short interval), increase if battery impact is measurable
- Real BLE testing requires the physical Garmin HRM strap; unit tests cover byte parsing
- ~~Server endpoint location (in-repo vs separate project) to be decided in Step 7~~ — **Resolved: server lives under `server/` in this repo**
- Tailscale reachability is unpredictable — sync logic must handle both instant success and days-long offline gracefully

## Next Steps (Post-Phase 1)

1. **Real device testing** — connect to Garmin HRM strap, verify end-to-end BLE capture → Room → server sync
2. **Server deployment** — run FastAPI server on dev server infrastructure behind Tailscale
3. **Phase 2** — Polar Verity Sense integration (PriorityMultiplexer already in place)
