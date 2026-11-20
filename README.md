# Pixel Walk Cadence (Wear OS)

Standalone Wear OS app, interactive Tile widget, and Watch Face Complication for Google Pixel Watch (tested on **Pixel Watch 4**) that monitors walking cadence (**STEPS_PER_MINUTE / SPM**) automatically in the background without preempting Fitbit workouts.

## What Was Built

1. **Always-On Hardware Step Cadence Engine ([`WalkingCadenceManager.kt`](app/src/main/java/com/example/walkingcadencepoc/WalkingCadenceManager.kt))**
   - Uses Pixel Watch's native low-power hardware co-processor sensor (`com.google.sensor.step_cadence`, type `131087`) with automatic fallback to `Sensor.TYPE_STEP_DETECTOR` (`18`) rather than Health Services `ExerciseClient`—so it runs 24/7 like a step counter and **never conflicts with or preempts Fitbit Exercise sessions**.
   - Exposes reactive `StateFlow`s for live SPM (`cadenceSpm`), active state (`isTracking`), and a rolling 8-bar history (`spmHistory`) sampled every **10 seconds**.
   - **Battery-optimized**:
     - Registers only a single hardware sensor with **`10_000_000` us (10s) MCU FIFO batching** while in the background (switching to `0` us only while `MainActivity` is visible in the foreground).
     - Event-driven 10s ticker runs **only while walking** and automatically stops after 10 seconds of stillness (`0 SPM`), eliminating idle wakeups.
     - Deduplicates complication updates so the watch face never re-renders identical values.

2. **Watch App UI with 10-Second Rolling Bar Graph ([`MainActivity.kt`](app/src/main/java/com/example/walkingcadencepoc/MainActivity.kt))**
   - Auto-requests `ACTIVITY_RECOGNITION` permission on first launch and starts monitoring immediately (no manual Start button needed).
   - Displays current `SPM`, a rolling 8-bar graph (`CadenceBarGraph`) updating every 10 seconds with numeric value labels above each bar (latest bar highlighted in cyan `#00E5FF`), and a `Reset` button to clear the chart.
   - Uses `FLAG_KEEP_SCREEN_ON` while open for live walk testing.

3. **Wear OS Tile Widget ([`CadenceTileService.kt`](app/src/main/java/com/example/walkingcadencepoc/CadenceTileService.kt))**
   - Swipeable Wear OS Tile (`Walking Cadence`) built with `androidx.wear.tiles:tiles` + ProtoLayout.
   - Shows the live `SPM` header (tap to open the full app), the 10-second rolling 8-bar chart, and an in-place `Reset` button (`LoadAction`).
   - Push-updated on cadence changes (`freshnessIntervalMillis = 0L` to avoid background polling).

4. **Watch Face Complication ([`CadenceComplicationService.kt`](app/src/main/java/com/example/walkingcadencepoc/CadenceComplicationService.kt))**
   - `SuspendingComplicationDataSourceService` advertising `RANGED_VALUE` to match the circular progress ring style of the Pixel Watch **Active** watch face (e.g., Heart Rate indicator): circular arc (`0..180` SPM), centered numeric SPM, and bottom walking icon ([`ic_walk.xml`](app/src/main/res/drawable/ic_walk.xml)).
   - Pushes updates every 10 seconds while walking via `ComplicationDataSourceUpdateRequester`; tapping opens `MainActivity`.

5. **Background Keep-Alive & Boot Recovery ([`CadenceAlwaysOnService.kt`](app/src/main/java/com/example/walkingcadencepoc/CadenceAlwaysOnService.kt), [`BootReceiver.kt`](app/src/main/java/com/example/walkingcadencepoc/BootReceiver.kt))**
   - Local-only (`setLocalOnly(true)`) `foregroundServiceType="health"` service guarded against duplicate foreground/service starts.
   - Automatically resumes monitoring on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.

## Project Structure

- [`app/build.gradle.kts`](app/build.gradle.kts) — Wear Compose (`1.4.0`), Wear Tiles (`1.4.0`), ProtoLayout (`1.2.0`), Watch Face Complications Data Source KTX (`1.2.1`), Health Services Client (`1.1.0-alpha05`), Coroutines-Guava (`1.8.1`).
- [`app/src/main/AndroidManifest.xml`](app/src/main/AndroidManifest.xml) — Standalone Wear OS configuration, `ACTIVITY_RECOGNITION` / `FOREGROUND_SERVICE_HEALTH` / `RECEIVE_BOOT_COMPLETED` permissions, `CadenceAlwaysOnService`, `BootReceiver`, `CadenceTileService`, and `CadenceComplicationService`.
- [`docs/superpowers/specs/`](docs/superpowers/specs/) — Design specs written for the 10-second bar graph, Wear OS Tile widget, Watch Face Complication, and always-on hardware cadence engine.

## Build & Install

Open the project in Android Studio, or build and install over `adb` from the command line:

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
./gradlew installDebug
```

### Adding the Complication or Tile on Pixel Watch

- **Watch Face Complication**: Long-press the watch face -> **Edit** -> swipe to **Complications** -> tap any circular slot (e.g. Sunset) -> select **Walking Cadence**.
- **Wear OS Tile**: Long-press any existing Tile -> swipe to the end -> tap **`+`** -> select **Walking Cadence** (or add via the **Google Pixel Watch** companion phone app under **Tiles**).
