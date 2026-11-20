# Wear OS Tile Widget Design: Walking Cadence

- **Date**: 2026-11-20
- **Status**: Approved

## Goal
Provide an interactive Wear OS Tile widget (`CadenceTileService`) that users can swipe to directly from the watch face to view live `STEPS_PER_MINUTE` (SPM), the 10-second rolling 8-bar graph with numeric labels, and a Start/Stop toggle button.

## Architecture
1. **Shared Singleton (`WalkingCadenceManager.getInstance(context)`)**:
   - Owns the `ExerciseClient` session, `cadenceSpm: StateFlow<Double?>`, `isTracking: StateFlow<Boolean>`, and `spmHistory: StateFlow<List<Int>>` (rolling 8 bars sampled every 10 seconds).
   - Calls `TileService.getUpdater(appContext).requestUpdate(CadenceTileService::class.java)` on session start/stop, initial cadence reading, and every 10-second bar tick.
2. **Interactive Tile (`CadenceTileService`)**:
   - Uses `androidx.wear.tiles:tiles:1.4.0` and `androidx.wear.protolayout:protolayout:1.2.0`.
   - Handles `LoadAction` with clickable ID `"toggle_tracking"` inside `onTileRequest` to start/stop the walking session directly from the Tile when `ACTIVITY_RECOGNITION` is granted (falling back to `LaunchAction` for `MainActivity` if runtime permission is still needed).
   - Renders the live SPM header, 8-slot bar chart with numeric labels above each bar, and Start/Stop pill button.
   - Sets `freshnessIntervalMillis = 10_000L` while tracking is active.
