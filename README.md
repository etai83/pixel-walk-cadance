# Walking Cadence POC (Wear OS)

Standalone Wear OS app in Kotlin using Health Services API (`ExerciseClient`) to start `ExerciseType.WALKING` session and stream live `DataType.STEPS_PER_MINUTE` (cadence) to screen.

## Structure

- `app/build.gradle.kts` — Wear Compose, Health Services Client (`1.1.0-alpha05`), Coroutines-Guava (`1.8.1`).
- `app/src/main/AndroidManifest.xml` — Requests `android.permission.ACTIVITY_RECOGNITION` and declares standalone Wear OS watch app.
- `app/src/main/java/com/example/walkingcadencepoc/WalkingCadenceManager.kt` — Capability check, session start/stop, preemption state sync, and `StateFlow<Double?>` cadence stream.
- `app/src/main/java/com/example/walkingcadencepoc/MainActivity.kt` — Runtime permission prompt, `FLAG_KEEP_SCREEN_ON`, and Wear Compose Start/Stop cadence UI.

## Architectural Constraints

1. **Single Active Exercise Rule**: Wear OS allows only one active `ExerciseClient` session at a time. Starting this POC while Fitbit Exercise is tracking a walk preempts (ends) the Fitbit workout—and vice versa.
2. **Watch Face Complication Throttling**: Bridging `cadenceSpm` `StateFlow` to a `ComplicationDataSourceService` will be throttled by `ComplicationDataSourceUpdateRequester` to preserve battery life (no smooth 1-second push updates on watch face).
