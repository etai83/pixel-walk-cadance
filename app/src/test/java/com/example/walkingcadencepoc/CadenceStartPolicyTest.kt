package com.example.walkingcadencepoc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CadenceStartPolicyTest {

    @Test
    fun activityRecognitionBlocksMonitoringUntilItIsGranted() {
        assertFalse(CadenceStartPolicy.canStartMonitoring(emptySet()))
        assertFalse(
            CadenceStartPolicy.canStartMonitoring(setOf(CadenceStartPolicy.POST_NOTIFICATIONS))
        )
        assertTrue(
            CadenceStartPolicy.canStartMonitoring(setOf(CadenceStartPolicy.ACTIVITY_RECOGNITION))
        )
    }

    @Test
    fun permissionRequestIncludesNotificationsOnlyOnApi33AndDoesNotBlockStart() {
        val granted = emptySet<String>()
        assertEquals(
            listOf(CadenceStartPolicy.ACTIVITY_RECOGNITION),
            CadenceStartPolicy.permissionsToRequest(sdkInt = 32, granted = granted)
        )
        assertEquals(
            listOf(
                CadenceStartPolicy.ACTIVITY_RECOGNITION,
                CadenceStartPolicy.POST_NOTIFICATIONS
            ),
            CadenceStartPolicy.permissionsToRequest(sdkInt = 34, granted = granted)
        )

        val activityOnly = setOf(CadenceStartPolicy.ACTIVITY_RECOGNITION)
        assertEquals(
            listOf(CadenceStartPolicy.POST_NOTIFICATIONS),
            CadenceStartPolicy.permissionsToRequest(sdkInt = 34, granted = activityOnly)
        )
        assertTrue(CadenceStartPolicy.canStartMonitoring(activityOnly))
    }

    @Test
    fun missingSensorOrRejectedRegistrationIsAStatusNotACrash() {
        assertEquals(
            CadenceStatus.NEED_ACTIVITY_RECOGNITION,
            CadenceStartPolicy.statusForSensorStart(
                activityRecognitionGranted = false,
                hasStepSensor = true,
                sensorRegistrationSucceeded = false
            )
        )
        assertEquals(
            CadenceStatus.NO_STEP_SENSOR,
            CadenceStartPolicy.statusForSensorStart(
                activityRecognitionGranted = true,
                hasStepSensor = false,
                sensorRegistrationSucceeded = false
            )
        )
        assertEquals(
            CadenceStatus.SENSOR_ACCESS_DENIED,
            CadenceStartPolicy.statusForSensorStart(
                activityRecognitionGranted = true,
                hasStepSensor = true,
                sensorRegistrationSucceeded = false
            )
        )
        assertNull(
            CadenceStartPolicy.statusForSensorStart(
                activityRecognitionGranted = true,
                hasStepSensor = true,
                sensorRegistrationSucceeded = true
            )
        )
    }

    @Test
    fun sensorOrderPrefersHardwareCadenceThenFallsBackToStepDetector() {
        assertEquals(
            listOf(
                CadenceStartPolicy.StepSensorKind.CADENCE,
                CadenceStartPolicy.StepSensorKind.DETECTOR
            ),
            CadenceStartPolicy.sensorTryOrder(hasCadence = true, hasDetector = true)
        )
        assertEquals(
            listOf(CadenceStartPolicy.StepSensorKind.DETECTOR),
            CadenceStartPolicy.sensorTryOrder(hasCadence = false, hasDetector = true)
        )
        assertTrue(
            CadenceStartPolicy.sensorTryOrder(hasCadence = false, hasDetector = false).isEmpty()
        )
    }

    @Test
    fun failedForegroundPromotionMustNotRestartTheService() {
        assertFalse(CadenceStartPolicy.RESTART_AFTER_PROMOTION_FAILURE)
        assertTrue(CadenceStartPolicy.FOREGROUND_CHANNEL_IMPORTANCE >= 2)
    }

    @Test
    fun stoppingWhenNothingIsRunningDoesNotTouchSensorsOrTheService() {
        assertEquals(
            StopPlan(unregisterSensors = false, stopService = false),
            CadenceStartPolicy.planStop(sensorsRegistered = false, serviceRunning = false)
        )
    }

    @Test
    fun stoppingReleasesOnlyWhatIsActuallyRunning() {
        assertEquals(
            StopPlan(unregisterSensors = true, stopService = true),
            CadenceStartPolicy.planStop(sensorsRegistered = true, serviceRunning = true)
        )
        assertEquals(
            StopPlan(unregisterSensors = false, stopService = true),
            CadenceStartPolicy.planStop(sensorsRegistered = false, serviceRunning = true)
        )
    }
}
