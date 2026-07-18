# Diagnostic Log Buffer

## Status: IMPLEMENTED — field use pending

## Problem

BLE failures happen at the gym, where ADB isn't available. The connect-retry
UI shows *that* attempts fail but not *why* — GATT status codes, scan
visibility, and timing live only in logcat. We need the app to capture that
detail and ship it to the server on demand so failures can be diagnosed
after the fact. This also covers the upcoming Polar Verity Sense field test.

## Solution

### New: `DiagnosticLog` (`core/common`)

Koin single. Thread-safe in-memory ring buffer (default capacity 1000) of
`LogEntry(timestampMs, tag, message)`. Entries also mirror to
`android.util.Log` so ADB sessions see them live. In-memory only in v1 —
the failure windows of interest happen while the app is alive and
foregrounded. `snapshot(): List<LogEntry>`, `clear()`, capacity
constructor-configurable.

### Instrumentation points

- `GarminHrmConnection`: connect() start, connectGatt null/exception,
  watchdog fired, every `onConnectionStateChange` **with raw status code**
  (the 133s), `onServicesDiscovered` status, descriptor-write result,
  reconnect scheduling (attempt #, delay), retry exhaustion.
- `BleScanner`: scan start/stop, `onScanFailed` error codes, each device
  that passes the HRM filter (name, address, RSSI).
- `BleCaptureService`: capture start/stop, inactivity timeout fired.
- `PolarSyncService` + `PolarOfflineSync`: sync lifecycle, per-recording
  results, errors (ready for the PVS field test).
- `SyncWorker`: sync outcome (success / retry / rejected), one line each.

### New: server endpoint

`POST /api/v1/diagnostics/upload` — body `{device_info, entries:
[{timestamp_ms, tag, message}]}`. Writes one JSONL file per upload to
`server/data/diagnostics/diag_<env>_<unix-ms>.jsonl` (env from the existing
X-Environment header; no DB schema change). Returns `{stored: n, file}`.
No size cap needed at ring-buffer scale (~1000 lines).

### New: `DiagnosticsApi` (`core/network`)

Thin Ktor client for the endpoint, same client/config as the other APIs.

### UI trigger

Settings screen gains a "Diagnostics" card: entry count + **Upload
diagnostics log** button → uploads snapshot, shows success (file name /
count) or failure. Buffer is NOT cleared on upload (re-upload is harmless;
files are per-upload).

## Out of scope (v1)

- Persistence across process death, log levels, automatic upload, retention
  policy on the server, UI log viewer.

## Testing

- `DiagnosticLogTest`: capacity eviction, ordering, snapshot isolation,
  clear.
- Server: upload happy path, env routing to file name, empty entries, 400 on
  bad env (existing validation applies).
- `DiagnosticsApiTest` with MockEngine.
- SettingsViewModel upload state transitions (JVM).
