# Advertising Probe During Connect

## Status: APPROVED (requested 2026-08-07 after field failure)

## Problem
Field event 2026-08-07 (uploaded diagnostics from that morning's session):
seven consecutive Garmin connect attempts aborted by the 15 s watchdog with
zero GATT callbacks — the phone never heard the strap. The strap's LED
(3 flashes / 5 s = active, open connection) showed it was awake and connected
elsewhere, almost certainly held via ANT+ by the watch. The HRM 200 stops BLE
advertising in this state, and the app could only say "attempt failed", which
reads identically to interference or a rejected connect. Diagnosing cost a
session; the signal to disambiguate was available over the air the whole time.

## Decision
While a Garmin connect attempt is in flight, run a parallel address-filtered
BLE scan (the "probe"). When the watchdog aborts the attempt, report WHICH
failure happened:

- probe heard an advertisement → "strap is advertising but the connection
  failed" (interference / rejected — retrying is sensible)
- probe heard nothing all window → "strap not advertising — likely held by
  another device (watch/ANT+) or asleep" (retrying won't help; free the strap)
- probe unavailable (scan failed / no permission) → the previous generic
  message (never claim "not advertising" on probe failure)

## Behavior
- Probe starts alongside the connect watchdog and stops on the first heard
  advertisement (one confirmation per attempt is enough), on CONNECTED, on
  watchdog abort, or on disconnect() — it never outlives the attempt.
- The verdict lands in three places: the diagnostic log (with RSSI when
  heard), the retrying `connectionDetail` message, and the final
  "Unable to connect" message — so the capture UI shows the diagnosis live.
- Reconnect attempts after a mid-session drop use the same connect() path and
  get the same probe.

## Implementation
- `BleScanner.advertisements(address)` — address-filtered `callbackFlow`
  emitting RSSI per advertisement (framework glue, coverage-excluded like the
  rest of `BleScanner`).
- `GarminHrmConnection` takes an injectable `advertisementProbe:
  ((String) -> Flow<Int>)?` (null = no probing, keeps old behavior); verdict
  state machine lives here, in the covered/tested class.
- `BleCaptureService` wires `bleScanner::advertisements` via Koin.

## Testing
`GarminHrmConnectionTest` drives the probe with test flows: heard/none/failed
verdicts in watchdog messages, probe cancellation on CONNECTED, per-attempt
verdict reset. The scan itself is device-only and verified in the field.
