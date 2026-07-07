# Server Status Indicator

## Problem
The TopAppBar currently shows BLE connection state (dot + label), but per the UI design spec it should show **server reachability**. The BLE connection state is already displayed in the StatusCard during capture. This means:
1. When not capturing, the TopAppBar shows "Disconnected" (BLE) even if the server is fine
2. There's no way to tell if the server is reachable before starting a capture
3. During capture, the same BLE state is shown in both the TopAppBar and the StatusCard

## Solution

### New: ServerHealthMonitor (`core/network`)
- New class `ServerHealthMonitor` that periodically pings `IntervalApi.health()`
- Exposes a `StateFlow<ServerStatus>` where `ServerStatus` is `CONNECTED`, `UNREACHABLE`, or `CHECKING`
- Poll interval: 30 seconds while app is in foreground
- Uses the existing `IntervalApi.health()` endpoint — no new server-side work
- Short timeout (5s) for health checks to avoid blocking
- Injected via Koin in the existing `NetworkModule`

### Updated: CaptureState
- Add `serverStatus: ServerStatus = ServerStatus.CHECKING` field

### Updated: CaptureViewModel
- Observe `ServerHealthMonitor.status` and update `CaptureState.serverStatus`

### Updated: CaptureScreen TopAppBar
- Replace `ConnectionDot(state.connectionState)` with a new `ServerStatusDot(state.serverStatus)`
- Label shows: "Server Connected" / "Server Unreachable" / "Checking..."
- Colors: Success (green) / Error (red) / neutral (gray)

### No change to StatusCard
- The StatusCard already correctly shows BLE connection state during capture — no changes needed there

## Files Changed
| File | Change |
|------|--------|
| `core/network/.../ServerHealthMonitor.kt` | New — periodic health check with StateFlow |
| `core/network/.../ServerStatus.kt` | New — enum (CONNECTED, UNREACHABLE, CHECKING) |
| `core/network/.../di/NetworkModule.kt` | Add ServerHealthMonitor to Koin |
| `feature/capture/.../domain/model/CaptureState.kt` | Add `serverStatus` field |
| `feature/capture/.../ui/CaptureViewModel.kt` | Observe ServerHealthMonitor |
| `feature/capture/.../ui/CaptureScreen.kt` | TopAppBar dot → ServerStatusDot |

### Updated: SyncWorker / SyncManager
- On successful sync: update `ServerHealthMonitor` status to `CONNECTED`
- On sync failure (network error): update status to `UNREACHABLE`
- This gives immediate feedback without waiting for the next 30s poll

## Not in scope
- Server health check from background/service (only foreground UI)
- Retry logic beyond the regular polling interval
