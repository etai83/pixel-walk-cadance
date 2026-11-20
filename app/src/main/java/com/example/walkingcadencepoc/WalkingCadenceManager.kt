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
private const val BACKGROUND_BATCH_LATENCY_US = 10_000_000 // 10s MCU hardware FIFO batching

class WalkingCadenceManager private constructor(context: Context) : SensorEventListener {

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private var activeTickerJob: Job? = null
    private var registeredSensor: Sensor? = null
    private var currentBatchLatencyUs: Int = -1
    private var isForegroundUiActive: Boolean = false

    private val stepTimestampsMs = ArrayDeque<Long>()
    private var lastActivityElapsedMs: Long = 0L
    private var lastHardwareCadenceSpm: Double? = null
    private var lastHardwareCadenceMs: Long = 0L
    private var lastPushedComplicationSpm: Int? = null

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

    fun startSensorsOnly(): Boolean {
        if (!hasPermission()) {
            return false
        }
        registerSingleHardwareSensor(
            batchLatencyUs = if (isForegroundUiActive) 0 else BACKGROUND_BATCH_LATENCY_US
        )
        _isTracking.value = true
        return true
    }

    fun ensureAlwaysOnStarted(): Boolean {
        if (!startSensorsOnly()) {
            return false
        }
        if (!CadenceAlwaysOnService.isRunning) {
            CadenceAlwaysOnService.start(appContext)
        }
        return true
    }

    fun setForegroundUiActive(active: Boolean) {
        if (isForegroundUiActive == active) return
        isForegroundUiActive = active
        if (_isTracking.value && hasPermission()) {
            registerSingleHardwareSensor(
                batchLatencyUs = if (active) 0 else BACKGROUND_BATCH_LATENCY_US
            )
        }
    }

    fun resetHistory() {
        _spmHistory.value = emptyList()
        requestTileUpdate()
    }

    private fun registerSingleHardwareSensor(batchLatencyUs: Int) {
        if (registeredSensor != null && currentBatchLatencyUs == batchLatencyUs) return

        // Unregister previous latency mode before re-registering
        if (registeredSensor != null) {
            sensorManager.unregisterListener(this)
            registeredSensor = null
        }

        // Prefer single low-power MCU Step Cadence sensor (Pixel Watch 4 type 131087);
        // fall back to TYPE_STEP_DETECTOR only on devices without hardware step_cadence.
        val sensor = sensorManager.getSensorList(Sensor.TYPE_ALL).firstOrNull {
            it.type == PIXEL_STEP_CADENCE_SENSOR_TYPE ||
                it.stringType.contains("step_cadence", ignoreCase = true)
        } ?: sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)

        if (sensor != null) {
            val ok = sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                batchLatencyUs
            )
            if (ok) {
                registeredSensor = sensor
                currentBatchLatencyUs = batchLatencyUs
            }
            Log.d(
                "WalkingCadence",
                "Registered single sensor ${sensor.name} (type=${sensor.type}) batchUs=$batchLatencyUs ok=$ok"
            )
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()
        val spm = when {
            event.sensor.type == PIXEL_STEP_CADENCE_SENSOR_TYPE ||
                event.sensor.stringType.contains("step_cadence", ignoreCase = true) -> {
                val raw = event.values.firstOrNull() ?: return
                val decoded = when {
                    raw <= 0.01f -> 0.0
                    raw in 0.2f..6.0f -> raw * 60.0
                    else -> raw.toDouble()
                }
                lastHardwareCadenceSpm = decoded
                lastHardwareCadenceMs = now
                decoded
            }

            event.sensor.type == Sensor.TYPE_STEP_DETECTOR -> {
                stepTimestampsMs.addLast(now)
                computeDetectorSpm(now)
            }

            else -> return
        }

        if (spm > 0.0) {
            lastActivityElapsedMs = now
            _cadenceSpm.value = spm
            startActiveTickerIfNeeded(initialSpm = spm.roundToInt())
        } else {
            _cadenceSpm.value = 0.0
            if (lastPushedComplicationSpm != 0) {
                pushComplicationIfChanged(0)
                requestTileUpdate()
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
        val count = stepTimestampsMs.size
        if (count < 2) {
            return if (count == 1) 60.0 else 0.0
        }
        val spanMs = (stepTimestampsMs.last() - stepTimestampsMs.first()).coerceAtLeast(250L)
        return (((count - 1) * 60_000.0) / spanMs.toDouble()).coerceIn(30.0, 220.0)
    }

    /**
     * Event-driven 10-second ticker: runs ONLY while walking activity is detected.
     * Automatically stops itself after 10 seconds of stillness (0 SPM) so there are
     * zero wakeups, zero IPC calls, and zero battery drain while stationary.
     */
    private fun startActiveTickerIfNeeded(initialSpm: Int) {
        if (activeTickerJob?.isActive == true) return

        // Push initial movement sample immediately
        _spmHistory.value = (_spmHistory.value + initialSpm).takeLast(MAX_BARS)
        pushComplicationIfChanged(initialSpm)
        requestTileUpdate()

        activeTickerJob = scope.launch {
            while (isActive) {
                delay(SAMPLE_INTERVAL_MS)
                val now = SystemClock.elapsedRealtime()
                pruneOldSteps(now)

                val isStationary = (now - lastActivityElapsedMs) > STEP_WINDOW_MS
                if (isStationary) {
                    _cadenceSpm.value = 0.0
                    _spmHistory.value = (_spmHistory.value + 0).takeLast(MAX_BARS)
                    pushComplicationIfChanged(0)
                    requestTileUpdate()
                    Log.d("WalkingCadence", "Stationary >10s: set 0 SPM and stopping idle ticker")
                    break
                }

                val currentSpm = lastHardwareCadenceSpm
                    ?.takeIf { now - lastHardwareCadenceMs <= STEP_WINDOW_MS }
                    ?: computeDetectorSpm(now)

                _cadenceSpm.value = currentSpm
                val rounded = currentSpm.roundToInt()
                _spmHistory.value = (_spmHistory.value + rounded).takeLast(MAX_BARS)
                pushComplicationIfChanged(rounded)
                requestTileUpdate()
                Log.d("WalkingCadence", "Active 10s tick: spm=$rounded")
            }
            activeTickerJob = null
        }
    }

    private fun pushComplicationIfChanged(spm: Int) {
        if (lastPushedComplicationSpm == spm) return
        lastPushedComplicationSpm = spm
        runCatching {
            ComplicationDataSourceUpdateRequester.create(
                appContext,
                ComponentName(appContext, CadenceComplicationService::class.java)
            ).requestUpdateAll()
        }
    }

    private fun requestTileUpdate() {
        runCatching {
            TileService.getUpdater(appContext).requestUpdate(CadenceTileService::class.java)
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
