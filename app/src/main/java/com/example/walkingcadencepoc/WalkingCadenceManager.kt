package com.example.walkingcadencepoc

import android.content.Context
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.guava.await

class WalkingCadenceManager(context: Context) {

    private val exerciseClient = HealthServices.getClient(context).exerciseClient

    private val _cadenceSpm = MutableStateFlow<Double?>(null)
    val cadenceSpm: StateFlow<Double?> = _cadenceSpm.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            if (update.exerciseStateInfo.state.isEnded) {
                _isTracking.value = false
                _cadenceSpm.value = null
                return
            }

            val latestMetrics = update.latestMetrics
            val spmPoints = latestMetrics.getData(DataType.STEPS_PER_MINUTE)

            // Grab the most recent cadence reading
            spmPoints.lastOrNull()?.let { point ->
                _cadenceSpm.value = point.value.toDouble()
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            _isTracking.value = false
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    suspend fun startWalkingSession(): Boolean {
        // 1. Verify hardware/OS supports STEPS_PER_MINUTE for WALKING
        val capabilities = exerciseClient.getCapabilitiesAsync().await()
        val walkCaps = capabilities.getExerciseTypeCapabilities(ExerciseType.WALKING)
        if (DataType.STEPS_PER_MINUTE !in walkCaps.supportedDataTypes) {
            return false
        }

        // 2. Configure the session
        val config = ExerciseConfig.builder(ExerciseType.WALKING)
            .setDataTypes(setOf(DataType.STEPS_PER_MINUTE))
            .setIsAutoPauseAndResumeEnabled(false)
            .setIsGpsEnabled(false) // Keep GPS off to save battery for pure cadence
            .build()

        // 3. Register callback and start
        exerciseClient.setUpdateCallback(callback)
        exerciseClient.startExerciseAsync(config).await()
        _isTracking.value = true
        return true
    }

    suspend fun stopWalkingSession() {
        exerciseClient.endExerciseAsync().await()
        exerciseClient.clearUpdateCallbackAsync(callback).await()
        _isTracking.value = false
        _cadenceSpm.value = null
    }
}
