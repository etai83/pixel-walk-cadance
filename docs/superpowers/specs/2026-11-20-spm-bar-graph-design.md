# 10-Second SPM Bar Graph Design

- **Date**: 2026-11-20
- **Status**: Approved

## Goal
Display a live-updating bar graph on the Wear OS screen that records and renders the current `STEPS_PER_MINUTE` (SPM) snapshot every 10 seconds alongside a numeric value label per bar.

## Architecture & Data Flow
- `CadenceScreen` in `MainActivity.kt` observes `manager.cadenceSpm` and `manager.isTracking`.
- A `LaunchedEffect(isTracking)` coroutine manages a rolling `mutableStateListOf<Int>()` capped at `MAX_BARS = 8` (covering 80 seconds of walking history):
  1. When `isTracking` transitions to `true`, clears previous history.
  2. Suspends until the first non-null `cadenceSpm` sample arrives (`manager.cadenceSpm.filterNotNull().first()`).
  3. Appends the initial SPM integer bar immediately, then loops every `10_000L` ms (`delay(10_000L)`), appending the latest `manager.cadenceSpm.value?.toInt()` and dropping the oldest element when size reaches `8`.

## UI Layout (Round Wear OS Display)
- **Header**: Current live `XXX SPM` readout (`20.sp`, bold).
- **Middle (`CadenceBarGraph`)**: 8 fixed vertical bar columns (`76.dp` height) with:
  - Numeric SPM label (`9.sp`) above each populated bar.
  - Rounded vertical bar (`RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp)`) scaled dynamically against `[40..max(180, maxHistory)]` SPM.
  - Latest 10-second bar highlighted in cyan (`#00E5FF`); previous bars in `MaterialTheme.colors.primary`; unpopulated future slots shown as subtle baseline ticks.
- **Footer**: Compact `Start` / `Stop` toggle button (`34.dp` height).
