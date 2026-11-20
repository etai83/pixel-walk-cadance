# Always-On Hardware Walking Cadence Design

- **Date**: 2026-11-20
- **Status**: Approved

## Goal
Run walking cadence (`STEPS_PER_MINUTE`) detection continuously and automatically in the background—just like the built-in step counter—without requiring the user to tap Start, and without preempting Fitbit workouts.

## Architecture
1. **Hardware Sensor Hub (`WalkingCadenceManager`)**:
   - Registers `SensorEventListener` on Pixel Watch 4's dedicated hardware step cadence sensor (`com.google.sensor.step_cadence`, type `131087`) and Android's hardware `Sensor.TYPE_STEP_DETECTOR` (`18`).
   - Computes real-time SPM from `step_cadence` and rolling 10-second inter-step intervals from `TYPE_STEP_DETECTOR`.
   - Automatically resets SPM to `0` when no steps are detected for 10 seconds.
   - Updates `spmHistory` (rolling 8 bars), the Watch Face Complication (`CadenceComplicationService`), and the Wear OS Tile (`CadenceTileService`) every 10 seconds.
2. **Background Keep-Alive (`CadenceAlwaysOnService` + `BootReceiver`)**:
   - Lightweight `foregroundServiceType="health"` service started automatically from `MainActivity`, `CadenceComplicationService`, `CadenceTileService`, and `BootReceiver` (`BOOT_COMPLETED` / `MY_PACKAGE_REPLACED`) so Wear OS `SensorService` delivers sensor events continuously even when `MainActivity` is closed.
