# Walking Cadence POC (Wear OS)

Standalone Wear OS app and interactive Tile widget in Kotlin using Health Services API (`ExerciseClient`) to start `ExerciseType.WALKING` session, stream live `DataType.STEPS_PER_MINUTE` (cadence), and render a 10-second rolling 8-bar graph.

## Structure

- `app/build.gradle.kts` — Wear Compose (`1.4.0`), Wear Tiles (`1.4.0`), ProtoLayout (`1.2.0`), Health Services Client (`1.1.0-alpha05`), Coroutines-Guava (`1.8.1`).
- `app/src/main/AndroidManifest.xml` — Requests `android.permission.ACTIVITY_RECOGNITION`, declares standalone Wear OS watch app, and registers `CadenceTileService` (`BIND_TILE_PROVIDER`).
- `app/src/main/java/com/example/walkingcadencepoc/WalkingCadenceManager.kt` — Singleton manager handling capability check, session start/stop, preemption state sync, Wear OS 5 emulator IEEE-754 float-bit decoding, 10-second rolling 8-bar history (`spmHistory`), and `TileService.getUpdater` triggers.
- `app/src/main/java/com/example/walkingcadencepoc/MainActivity.kt` — Runtime permission prompt, `FLAG_KEEP_SCREEN_ON`, live SPM readout, 10-second rolling 8-bar chart (`CadenceBarGraph`), and Start/Stop toggle.
- `app/src/main/java/com/example/walkingcadencepoc/CadenceTileService.kt` — Interactive Wear OS Tile widget rendering live SPM header, 8-bar chart with numeric labels, and in-place Start/Stop button (`LoadAction`).

## Architectural Constraints

1. **Single Active Exercise Rule**: Wear OS allows only one active `ExerciseClient` session at a time. Starting this POC while Fitbit Exercise is tracking a walk preempts (ends) the Fitbit workout—and vice versa.
2. **Watch Face Complication Throttling**: Bridging `cadenceSpm` `StateFlow` to a `ComplicationDataSourceService` will be throttled by `ComplicationDataSourceUpdateRequester` to preserve battery life (no smooth 1-second push updates on watch face).
