# Watch Face Complication Design: 10-Second Walking Cadence (SPM)

- **Date**: 2026-11-20
- **Status**: Approved

## Goal
Provide a Watch Face Complication (`CadenceComplicationService`) that can replace circular watch face complication slots (such as the Sunset `20:32 pm` indicator on the Pixel Watch Active watch face) to display the walking cadence (`SPM`) updated every 10 seconds.

## Architecture
1. **`CadenceComplicationService` (`SuspendingComplicationDataSourceService`)**:
   - Supports `SHORT_TEXT`, `RANGED_VALUE`, and `LONG_TEXT`.
   - Renders:
     - Monochromatic walking icon (`@drawable/ic_walk`) at top
     - Large numeric cadence (`148` when tracking, `--` when stopped) in center
     - `SPM` title label at bottom
     - For `RANGED_VALUE` slots: circular progress arc scaled across `0f..180f` SPM.
   - Tapping the complication launches `MainActivity`.
2. **10-Second Push Updates (`WalkingCadenceManager`)**:
   - Every 10 seconds inside `startHistoryTickerIfNeeded()` (plus on session start, stop, and initial cadence reading), calls `ComplicationDataSourceUpdateRequester.create(appContext, ComponentName(appContext, CadenceComplicationService::class.java)).requestUpdateAll()`.
