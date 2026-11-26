package com.example.walkingcadencepoc

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
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

    private val _statusMessage = MutableStateFlow<CadenceStatus?>(null)
    val statusMessage: StateFlow<CadenceStatus?> = _statusMessage.asStateFlow()

    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.ACTIVITY_RECOGNITION
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun permissionsToRequest(): List<String> {
        val granted = buildSet {
            if (hasPermission()) add(CadenceStartPolicy.ACTIVITY_RECOGNITION)
            if (notificationsGranted()) add(CadenceStartPolicy.POST_NOTIFICATIONS)
        }
        return CadenceStartPolicy.permissionsToRequest(Build.VERSION.SDK_INT, granted)
    }

    fun startSensorsOnly(): Boolean {
        if (!hasPermission()) {
            stopMonitoring()
            _statusMessage.value = CadenceStatus.NEED_ACTIVITY_RECOGNITION
            return false
        }
        val registered = registerSingleHardwareSensor(
            batchLatencyUs = if (isForegroundUiActive) 0 else BACKGROUND_BATCH_LATENCY_US
        )
        val status = CadenceStartPolicy.statusForSensorStart(
            activityRecognitionGranted = true,
            hasStepSensor = registered || hasAnyStepSensor(),
            sensorRegistrationSucceeded = registered
        )
        if (status != null) {
            _isTracking.value = false
            _statusMessage.value = status
            return false
        }
        _isTracking.value = true
        clearSensorStatus()
        return true
    }

    fun ensureAlwaysOnStarted(): Boolean {
        return try {
            if (!startSensorsOnly()) {
                return false
            }
            if (!CadenceAlwaysOnService.isRunning) {
                val started = CadenceAlwaysOnService.start(appContext)
                if (!started && isForegroundUiActive) {
                    _statusMessage.value = CadenceStatus.MONITOR_START_FAILED
                }
            } else if (_statusMessage.value == CadenceStatus.MONITOR_START_FAILED) {
                _statusMessage.value = null
            }
            true
        } catch (e: Exception) {
            Log.e("WalkingCadence", "Failed to start cadence monitor", e)
            _isTracking.value = false
            _statusMessage.value = CadenceStatus.MONITOR_START_FAILED
            false
        }
    }

    /**
     * The service calls this when `startForeground()` cannot succeed.
     * Stopping here avoids the system crash that follows a half-started
     * foreground service. Sensor updates can continue while the app is open.
     */
    fun noteForegroundPromotionFailed() {
        if (!hasPermission()) {
            stopMonitoring()
            _statusMessage.value = CadenceStatus.NEED_ACTIVITY_RECOGNITION
            return
        }
        if (isForegroundUiActive) {
            _statusMessage.value = CadenceStatus.MONITOR_START_FAILED
        }
    }

    /** Stops a health foreground service that can no longer meet its permission requirement. */
    fun releaseIfUnauthorized() {
        if (hasPermission()) return
        stopMonitoring()
        _statusMessage.value = CadenceStatus.NEED_ACTIVITY_RECOGNITION
    }

    fun stopMonitoring() {
        val plan = CadenceStartPolicy.planStop(
            sensorsRegistered = registeredSensor != null,
            serviceRunning = CadenceAlwaysOnService.isRunning
        )
        activeTickerJob?.cancel()
        activeTickerJob = null
        if (plan.unregisterSensors) {
            unregisterQuietly()
        }
        _isTracking.value = false
        _cadenceSpm.value = 0.0
        if (plan.stopService) {
            CadenceAlwaysOnService.stop(appContext)
        }
    }

    fun setForegroundUiActive(active: Boolean) {
        if (isForegroundUiActive == active) return
        isForegroundUiActive = active
        if (_isTracking.value && hasPermission()) {
            val registered = registerSingleHardwareSensor(
                batchLatencyUs = if (active) 0 else BACKGROUND_BATCH_LATENCY_US
            )
            if (!registered) {
                _isTracking.value = false
                _statusMessage.value = CadenceStartPolicy.statusForSensorStart(
                    activityRecognitionGranted = true,
                    hasStepSensor = hasAnyStepSensor(),
                    sensorRegistrationSucceeded = false
                )
            }
        }
    }

    fun resetHistory() {
        _spmHistory.value = emptyList()
        requestTileUpdate()
    }

    private fun registerSingleHardwareSensor(batchLatencyUs: Int): Boolean {
        if (registeredSensor != null && currentBatchLatencyUs == batchLatencyUs) return true

        // Unregister previous latency mode before re-registering
        if (registeredSensor != null) {
            unregisterQuietly()
        }

        val cadence = findStepCadenceSensor()
        val detector = runCatching {
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)
        }.getOrNull()
        // Prefer the Pixel Watch hardware step-cadence sensor, then TYPE_STEP_DETECTOR.
        // A vendor sensor that requires a permission we do not hold throws; try the next one.
        val order = CadenceStartPolicy.sensorTryOrder(
            hasCadence = cadence != null,
            hasDetector = detector != null && detector != cadence
        )
        for (kind in order) {
            val sensor = when (kind) {
                CadenceStartPolicy.StepSensorKind.CADENCE -> cadence
                CadenceStartPolicy.StepSensorKind.DETECTOR -> detector
            } ?: continue
            if (tryRegister(sensor, batchLatencyUs)) {
                registeredSensor = sensor
                currentBatchLatencyUs = batchLatencyUs
                Log.d(
                    "WalkingCadence",
                    "Registered single sensor ${sensor.name} (type=${sensor.type}) batchUs=$batchLatencyUs"
                )
                return true
            }
        }
        return false
    }

    private fun findStepCadenceSensor(): Sensor? {
        val sensors = runCatching { sensorManager.getSensorList(Sensor.TYPE_ALL) }.getOrNull()
            ?: return null
        return sensors.firstOrNull { sensor ->
            runCatching {
                sensor.type == PIXEL_STEP_CADENCE_SENSOR_TYPE ||
                    sensor.stringType.contains("step_cadence", ignoreCase = true)
            }.getOrDefault(false)
        }
    }

    private fun hasAnyStepSensor(): Boolean {
        return findStepCadenceSensor() != null ||
            runCatching { sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR) }.getOrNull() != null
    }

    private fun tryRegister(sensor: Sensor, batchLatencyUs: Int): Boolean {
        return try {
            sensorManager.registerListener(
                this,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                batchLatencyUs
            )
        } catch (e: SecurityException) {
            Log.w("WalkingCadence", "Sensor ${sensor.name} (type=${sensor.type}) denied", e)
            false
        }
    }

    private fun unregisterQuietly() {
        runCatching { sensorManager.unregisterListener(this) }
        registeredSensor = null
        currentBatchLatencyUs = -1
    }

    private fun notificationsGranted(): Boolean {
        if (Build.VERSION.SDK_INT < 33) return true
        return ContextCompat.checkSelfPermission(
            appContext,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun clearSensorStatus() {
        when (_statusMessage.value) {
            CadenceStatus.NEED_ACTIVITY_RECOGNITION,
            CadenceStatus.NO_STEP_SENSOR,
            CadenceStatus.SENSOR_ACCESS_DENIED -> _statusMessage.value = null
            CadenceStatus.MONITOR_START_FAILED, null -> Unit
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
