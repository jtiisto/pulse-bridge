# Live Tachogram Chart

## Status: IMPLEMENTED — on-device visual verification pending

## Problem

During an active capture there is no live visualization of the incoming beat
data — the StatusCard shows only the latest HR number. The user wants a
monitor-style live chart on the capture screen: a scrolling display with a
10-second window and grid lines.

Raw ECG waveform is not available over standard BLE (the strap streams HR +
RR intervals), so the chart is an **RR tachogram**: instantaneous heart rate
derived from each RR interval, drawn as a stepped line — a true data plot with
an EKG-monitor aesthetic (grid, continuous scroll).

## Solution

### New: `TachogramBuffer` (`feature/capture/domain`)

Pure-Kotlin state holder that turns `HeartRateSample`s into drawable points.
No Android dependencies — fully unit-testable. Injected clock
(`() -> Long`, elapsed-realtime ms).

- Each RR interval becomes one beat point: `BeatPoint(timeMs, hrBpm)` where
  `hrBpm = 60_000f / rrMs`.
- `rrIntervalMs <= 0` samples are skipped (known sensor artifact — same rule
  as HRV analysis).
- Samples with an empty RR list (HR-only notifications) contribute a point at
  arrival time using `heartRateBpm` as fallback.
- Beat times come from a cumulative beat clock: each RR advances the clock by
  its duration, so rhythm spacing is faithful. The clock re-anchors to arrival
  time when it drifts more than 2 s (BLE stall, gap, first sample).
- Points older than `now - 12 s` are trimmed (10 s window + 2 s margin).
- `reset()` clears state when capture stops.
- Exposes `points(): List<BeatPoint>` (immutable snapshot).

### New: `TachogramChart` composable (`feature/capture/ui`)

Compose `Canvas`, no chart library.

- **Window**: fixed 10 s; right edge = now. Scroll is frame-driven
  (`withFrameNanos`), so the trace slides continuously between beats.
- **Trace**: stepped line (tachogram convention) in the theme's primary color;
  gaps > 3 s draw no connecting segment (visual break instead of a false line).
- **Grid**: vertical line every 1 s (scrolling with time); horizontal lines at
  20 bpm steps with small labels on the right edge. Grid uses low-alpha
  `onSurfaceVariant` — monitor feel without fighting the dark theme.
- **Y-range**: adaptive — padded to the visible min/max, rounded to 20 bpm
  steps, minimum span 60 bpm, only rescales when data leaves the current range
  (hysteresis, no per-frame jitter).
- **Height**: ~160 dp card matching existing card styling.

### Updated: `CaptureRepository`

- Inject `PriorityMultiplexer`; expose
  `beatStream: Flow<HeartRateSample> = multiplexer.authoritativeStream`.
  The underlying per-connection flow is a hot `MutableSharedFlow`, so a second
  collector is safe and receives exactly what the capture service records.

### Updated: `CaptureViewModel`

- While `isCapturing` is true, collect `beatStream` into a `TachogramBuffer`
  and publish snapshots as `chartPoints: StateFlow<List<BeatPoint>>`.
- Buffer resets when capture stops (chart starts clean each session).

### Updated: `CaptureScreen`

- New chart card rendered directly below the `StatusCard`, only while
  `state.isCapturing` (per approved design decision).

### Updated: `CaptureModule`

- Wire `PriorityMultiplexer` into `CaptureRepository`.

## Out of Scope

- Raw ECG waveform (not available over BLE).
- Polar Verity Sense real-time streaming (Polar path is offline sync; if a
  future phase adds PVS live streaming through the multiplexer, the chart
  picks it up automatically).
- Historical scrubbing/zoom — the chart is live-only.

## Performance

- ≤ ~40 points in the window at 200 bpm; per-frame work is one path rebuild.
- Frame loop runs only while the chart is composed (capturing + screen on).
- No new dependencies, no database or server changes.

## Testing

- `TachogramBufferTest` (JUnit 5): RR→HR conversion, zero-RR skip, HR-only
  fallback, cumulative beat clock + re-anchor on gap, window trimming, reset.
- Y-range logic extracted as a pure function and tested (rounding, min span,
  hysteresis).
- `CaptureViewModelTest`: beats flowing while capturing update `chartPoints`;
  stopping capture clears them; no collection when not capturing.
- Instrumented/visual verification on emulator deferred to device session
  (with `/adb-deploy`).
