# Keep Screen On While Capturing

## Status: APPROVED (scope chosen 2026-08-01)

## Problem
During a workout the user watches the live tachogram and signal-quality
indicator. Android's default screen timeout dims and locks the display
mid-session, hiding the live view.

## Decision
Keep the screen awake **only while a capture session is active**. Chosen over
"always while app is foreground" (screen would never dim while idling in
Settings) and over adding a Settings toggle (unneeded knob for a behavior
that is only ever wanted during capture).

## Behavior
- While `BleCaptureServiceState.isRunning` is true and the app is in the
  foreground, the screen does not dim or lock (`FLAG_KEEP_SCREEN_ON`
  semantics via `View.keepScreenOn`).
- Applies app-wide, not just on the capture screen — navigating to Settings
  mid-capture keeps the screen awake, since the session is still running.
- When capture stops (or the app leaves the foreground), normal system
  dimming/locking resumes. Background capture is unaffected: the foreground
  service keeps recording with the screen off, as before.
- No permissions required; `FLAG_KEEP_SCREEN_ON` is not a wake lock.

## Implementation
`KeepScreenOnWhileCapturing` composable at the app root (`PulseBridgeApp`),
observing the singleton `MutableStateFlow<BleCaptureServiceState>`
(`bleCaptureStateQualifier`) — the same source of truth the capture UI and
foreground service share — and mirroring `isRunning` onto
`LocalView.current.keepScreenOn`. A `DisposableEffect` clears the flag on
disposal.

## Testing
Pure view glue with no branching logic (identity mapping from `isRunning` to
`keepScreenOn`); no unit test — verified on-device: screen must stay awake
past the system timeout during capture and dim normally after stopping.
