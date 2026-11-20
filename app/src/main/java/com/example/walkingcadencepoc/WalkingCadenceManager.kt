package com.example.walkingcadencepoc

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
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
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

const val MAX_BARS = 8
const val SAMPLE_INTERVAL_MS = 10_000L
private const val PIXEL_STEP_CADENCE_SENSOR_TYPE = 131087
private const val STEP_WINDOW_MS = 10_000L

class WalkingCadenceManager private constructor(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var historyJob: Job? = null
    private var sensorsRegistered = false

    private val stepTimestampsMs = ArrayDeque<Long>()
    private var lastStepElapsedMs: Long = 0L
    private var lastHardwareCadenceSpm: Double? = null
    private var lastHardwareCadenceMs: Long = 0L

    private val _cadenceSpm = MutableStateFlow<Double?>(0.0)
    val cadenceSpm: StateFlow<Double?> = _cadenceSpm.asStateFlow()

    private val _isTracking = MutableStateFlow(false)
    val isTracking: StateFlow<Boolean> = _isTracking.asStateFlow()

    private val _spmHistory = MutableStateFlow<List<Int>>(emptyList())
    val spmHistory: StateFlow<List<Int>> = _spmHistory.asStateFlow()

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun ensureAlwaysOnStarted(): Boolean {
        if (!hasPermission()) {
            return false
        }
        CadenceAlwaysOnService.start(appContext)
        registerHardwareSensorsIfNeeded()
        startHistoryTickerIfNeeded()
        _isTracking.value = true
        return true
    }

    fun resetHistory() {
        _spmHistory.value = emptyList()
        requestWidgetUpdates()
    }

    private fun registerHardwareSensorsIfNeeded() {
        if (sensorsRegistered) return

        // 1. Google Pixel Watch native hardware Step Cadence sensor (type 131087)
        val stepCadenceSensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.type == PIXEL_STEP_CADENCE_SENSOR_TYPE ||
                it.stringType.contains("step_cadence", ignoreCase = true)
        }
        if (stepCadenceSensor != null) {
            val ok = sensorManager.registerListener(
                this,
                stepCadenceSensor,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d("WalkingCadence", "Registered hardware Step Cadence sensor (${stepCadenceSensor.name}): $ok")
        }

        // 2. Standard Android hardware Step Detector (type 18)
        val stepDetector = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        if (stepDetector != null) {
            val ok = sensorManager.registerListener(
                this,
                stepDetector,
                SensorManager.SENSOR_DELAY_NORMAL
            )
            Log.d("WalkingCadence", "Registered TYPE_STEP_DETECTOR: $ok")
        }

        // 3. Standard Android hardware Step Counter (type 19) as additional wake trigger
        val stepCounter = sensorManager.getDefaultSensor(Sensor.TYPE_STEP_COUNTER)
        if (stepCounter != null) {
            sensorManager.registerListener(
                this,
                stepCounter,
                SensorManager.SENSOR_DELAY_NORMAL
            )
        }

        sensorsRegistered = true
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        when {
            event.sensor.type == PIXEL_STEP_CADENCE_SENSOR_TYPE ||
                event.sensor.stringType.contains("step_cadence", ignoreCase = true) -> {
                val raw = event.values.firstOrNull() ?: return
                // Handle both steps/sec (0.3..6.0 Hz) and steps/min (>6.0 SPM)
                val spm = when {
                    raw <= 0.01f -> 0.0
                    raw in 0.2f..6.0f -> (raw * 60.0)
                    else -> raw.toDouble()
                }
                lastHardwareCadenceSpm = spm
                lastHardwareCadenceMs = now
                if (spm > 0.0) {
                    lastStepElapsedMs = now
                }
                Log.d("WalkingCadence", "Hardware step_cadence event: raw=$raw spm=$spm")
                updateLiveCadence(spm)
            }

            event.sensor.type == Sensor.TYPE_STEP_DETECTOR -> {
                lastStepElapsedMs = now
                stepTimestampsMs.addLast(now)
                pruneOldSteps(now)
                val calculatedSpm = computeDetectorSpm(now)
                Log.d("WalkingCadence", "TYPE_STEP_DETECTOR event: stepsIn10s=${stepTimestampsMs.size} spm=$calculatedSpm")
                updateLiveCadence(calculatedSpm)
            }

            event.sensor.type == Sensor.TYPE_STEP_COUNTER -> {
                lastStepElapsedMs = now
                stepTimestampsMs.addLast(now)
                pruneOldSteps(now)
                if (now - lastHardwareCadenceMs > 3_000L) {
                    val calculatedSpm = computeDetectorSpm(now)
                    if (calculatedSpm > 0.0) {
                        updateLiveCadence(calculatedSpm)
                    }
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun pruneOldSteps(nowElapsedMs: Long) {
        while (stepTimestampsMs.isNotEmpty() && nowElapsedMs - stepTimestampsMs.first() > STEP_WINDOW_MS) {
            stepTimestampsMs.removeFirst()
        }
    }

    private fun computeDetectorSpm(nowElapsedMs: Long): Double {
        pruneOldSteps(nowElapsedMs)
        // Prefer hardware step_cadence sensor if updated within last 4 seconds
        val hwSpm = lastHardwareCadenceSpm
        if (hwSpm != null && hwSpm > 0.0 && nowElapsedMs - lastHardwareCadenceMs <= 4_000L) {
            return hwSpm
        }

        val count = stepTimestampsMs.size
        if (count < 2) {
            return if (count == 1) 60.0 else 0.0
        }
        val spanMs = (stepTimestampsMs.last() - stepTimestampsMs.first()).coerceAtLeast(250L)
        val intervalSpm = ((count - 1) * 60_000.0) / spanMs.toDouble()
        return intervalSpm.coerceIn(30.0, 220.0)
    }

    private fun updateLiveCadence(spm: Double) {
        val previous = _cadenceSpm.value ?: 0.0
        _cadenceSpm.value = spm
        if (_spmHistory.value.isEmpty() && spm > 0.0) {
            _spmHistory.value = listOf(spm.roundToInt())
            requestWidgetUpdates()
        } else if ((previous == 0.0 && spm > 0.0) || (previous > 0.0 && spm == 0.0)) {
            requestWidgetUpdates()
        }
    }

    private fun startHistoryTickerIfNeeded() {
        if (historyJob?.isActive == true) return
        historyJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                pruneOldSteps(now)

                // Decay to 0 SPM if no steps or cadence events arrived in last 10 seconds
                val currentSpm = if (now - lastStepElapsedMs > STEP_WINDOW_MS) {
                    0.0
                } else {
                    val hwSpm = lastHardwareCadenceSpm
                    if (hwSpm != null && now - lastHardwareCadenceMs <= STEP_WINDOW_MS) {
                        hwSpm
                    } else {
                        computeDetectorSpm(now)
                    }
                }

                _cadenceSpm.value = currentSpm
                val rounded = currentSpm.roundToInt()
                _spmHistory.value = (_spmHistory.value + rounded).takeLast(MAX_BARS)
                Log.d("WalkingCadence", "10s tick: spm=$rounded history=${_spmHistory.value}")
                requestWidgetUpdates()
            }
        }
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
