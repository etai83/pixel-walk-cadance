package com.example.walkingcadencepoc

import android.content.ComponentName
import android.content.Context
import android.util.Log
import androidx.health.services.client.ExerciseUpdateCallback
import androidx.health.services.client.HealthServices
import androidx.health.services.client.data.Availability
import androidx.health.services.client.data.DataType
import androidx.health.services.client.data.ExerciseConfig
import androidx.health.services.client.data.ExerciseLapSummary
import androidx.health.services.client.data.ExerciseType
import androidx.health.services.client.data.ExerciseUpdate
import androidx.wear.tiles.TileService
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

const val MAX_BARS = 8
const val SAMPLE_INTERVAL_MS = 10_000L

class WalkingCadenceManager private constructor(context: Context) {

    private val appContext = context.applicationContext
    private val exerciseClient = HealthServices.getClient(appContext).exerciseClient
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var historyJob: Job? = null

    private val _cadenceSpm = MutableStateFlow<Double?>(null)
    val cadenceSpm: StateFlow<Double?> = _cadenceSpm.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _spmHistory = MutableStateFlow<List<Int>>(emptyList())
    val spmHistory: StateFlow<List<Int>> = _spmHistory.asStateFlow()

    private val callback = object : ExerciseUpdateCallback {
        override fun onExerciseUpdateReceived(update: ExerciseUpdate) {
            val state = update.exerciseStateInfo.state
            if (state.isEnded) {
                _isTracking.value = false
                _cadenceSpm.value = null
                stopHistoryTicker()
                requestWidgetUpdates()
                return
            }
            if (!_isTracking.value) {
                _isTracking.value = true
                startHistoryTickerIfNeeded()
            }

            val latestMetrics = update.latestMetrics
            val spmPoints = latestMetrics.getData(DataType.STEPS_PER_MINUTE)

            // Grab the most recent cadence reading
            spmPoints.lastOrNull()?.let { point ->
                val raw = point.value
                // Wear OS 5 emulator SyntheticConfigurationDataStore serializes custom
                // STEPS_PER_MINUTE as IEEE-754 float bits read as int32 by Synthetic HAL.
                val spm = if (raw > 1000L) {
                    Float.fromBits(raw.toInt()).toDouble()
                } else {
                    raw.toDouble()
                }
                val wasNull = _cadenceSpm.value == null
                Log.d("WalkingCadence", "STEPS_PER_MINUTE update: raw=$raw decoded=$spm")
                _cadenceSpm.value = spm
                if (wasNull) {
                    requestWidgetUpdates()
                }
            }
        }

        override fun onLapSummaryReceived(lapSummary: ExerciseLapSummary) {}
        override fun onRegistered() {}
        override fun onRegistrationFailed(throwable: Throwable) {
            _isTracking.value = false
            stopHistoryTicker()
            requestWidgetUpdates()
        }
        override fun onAvailabilityChanged(dataType: DataType<*, *>, availability: Availability) {}
    }

    private fun startHistoryTickerIfNeeded() {
        if (historyJob?.isActive == true) return
        historyJob = scope.launch {
            _spmHistory.value = emptyList()
            val firstSpm = _cadenceSpm.filterNotNull().first().toInt()
            _spmHistory.value = listOf(firstSpm)
            requestWidgetUpdates()
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                _cadenceSpm.value?.toInt()?.let { latestSpm ->
                    _spmHistory.value = (_spmHistory.value + latestSpm).takeLast(MAX_BARS)
                    requestWidgetUpdates()
                }
            }
        }
    }

    private fun stopHistoryTicker() {
        historyJob?.cancel()
        historyJob = null
    }

    private fun requestWidgetUpdates() {
        runCatching {
            TileService.getUpdater(appContext).requestUpdate(CadenceTileService::class.java)
        }
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                appContext,
                ComponentName(appContext, CadenceComplicationService::class.java)
            ).requestUpdateAll()
        }
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
        startHistoryTickerIfNeeded()
        requestWidgetUpdates()
        return true
    }

    suspend fun stopWalkingSession() {
        stopHistoryTicker()
        runCatching {
            exerciseClient.endExerciseAsync().await()
            exerciseClient.clearUpdateCallbackAsync(callback).await()
        }
        _isTracking.value = false
        _cadenceSpm.value = null
        requestWidgetUpdates()
    }

    companion object {
        @Volatile
        private var instance: WalkingCadenceManager? = null

        fun getInstance(context: Context): WalkingCadenceManager {
            return instance ?: synchronized(this) {
                instance ?: WalkingCadenceManager(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }
}
