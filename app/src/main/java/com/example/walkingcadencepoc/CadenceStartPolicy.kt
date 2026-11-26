package com.example.walkingcadencepoc

/**
 * Pure decisions for starting and stopping cadence monitoring.
 *
 * Wear OS 4/5 (API 34+) throws [SecurityException] from
 * `Service.startForeground(..., FOREGROUND_SERVICE_TYPE_HEALTH)` unless
 * [ACTIVITY_RECOGNITION] (or another health-type prerequisite) is already
 * granted. Starting the service with `startForegroundService()` and then
 * swallowing that exception skips the required `startForeground()` call, and
 * the system kills the process with `ForegroundServiceDidNotStartInTimeException`.
 * These helpers keep that path from starting, and tell the service to stop
 * instead of retrying, when promotion still fails.
 */
object CadenceStartPolicy {

    const val ACTIVITY_RECOGNITION = "android.permission.ACTIVITY_RECOGNITION"
    const val POST_NOTIFICATIONS = "android.permission.POST_NOTIFICATIONS"

    /**
     * [android.app.NotificationManager.IMPORTANCE_LOW].
     * `IMPORTANCE_MIN` (1) is rejected for foreground-service notifications on
     * API 34+, which makes `startForeground()` fail.
     */
    const val FOREGROUND_CHANNEL_IMPORTANCE = 2

    /** Do not sticky-restart a service that could not enter the foreground. */
    const val RESTART_AFTER_PROMOTION_FAILURE = false

    enum class StepSensorKind {
        CADENCE,
        DETECTOR
    }

    fun permissionsToRequest(sdkInt: Int, granted: Set<String>): List<String> {
        val missing = mutableListOf<String>()
        if (ACTIVITY_RECOGNITION !in granted) {
            missing += ACTIVITY_RECOGNITION
        }
        if (sdkInt >= 33 && POST_NOTIFICATIONS !in granted) {
            missing += POST_NOTIFICATIONS
        }
        return missing
    }

    /** Step sensors and the health foreground-service type both require this. */
    fun canStartMonitoring(granted: Set<String>): Boolean {
        return ACTIVITY_RECOGNITION in granted
    }

    fun sensorTryOrder(hasCadence: Boolean, hasDetector: Boolean): List<StepSensorKind> {
        return buildList {
            if (hasCadence) add(StepSensorKind.CADENCE)
            if (hasDetector) add(StepSensorKind.DETECTOR)
        }
    }

    fun statusForSensorStart(
        activityRecognitionGranted: Boolean,
        hasStepSensor: Boolean,
        sensorRegistrationSucceeded: Boolean
    ): CadenceStatus? {
        if (!activityRecognitionGranted) return CadenceStatus.NEED_ACTIVITY_RECOGNITION
        if (!hasStepSensor) return CadenceStatus.NO_STEP_SENSOR
        if (!sensorRegistrationSucceeded) return CadenceStatus.SENSOR_ACCESS_DENIED
        return null
    }

    fun planStop(sensorsRegistered: Boolean, serviceRunning: Boolean): StopPlan {
        return StopPlan(
            unregisterSensors = sensorsRegistered,
            stopService = serviceRunning
        )
    }
}

enum class CadenceStatus {
    NEED_ACTIVITY_RECOGNITION,
    NO_STEP_SENSOR,
    SENSOR_ACCESS_DENIED,
    MONITOR_START_FAILED
}

data class StopPlan(
    val unregisterSensors: Boolean,
    val stopService: Boolean
)
