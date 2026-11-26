package com.example.walkingcadencepoc

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

private const val CHANNEL_ID = "cadence_always_on_channel_v2"
private const val NOTIFICATION_ID = 1001
private const val TAG = "WalkingCadence"

class CadenceAlwaysOnService : Service() {

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        if (!promoteToForeground()) {
            abandonStart()
            return
        }
        WalkingCadenceManager.getInstance(applicationContext).startSensorsOnly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!promoteToForeground()) {
            abandonStart()
            return if (CadenceStartPolicy.RESTART_AFTER_PROMOTION_FAILURE) {
                START_STICKY
            } else {
                START_NOT_STICKY
            }
        }
        WalkingCadenceManager.getInstance(applicationContext).startSensorsOnly()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Returns false when the health foreground service cannot be promoted.
     * Callers must [stopSelf] immediately. Catching the failure and continuing
     * leaves a `startForegroundService()` start unfinished, and the system then
     * crashes the process.
     */
    private fun promoteToForeground(): Boolean {
        if (foregroundStarted) return true
        val manager = WalkingCadenceManager.getInstance(applicationContext)
        if (!manager.hasPermission()) {
            Log.w(TAG, "Skipping health foreground service: activity recognition is not granted")
            return false
        }
        return try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Walking Cadence Monitor",
                CadenceStartPolicy.FOREGROUND_CHANNEL_IMPORTANCE
            ).apply {
                setShowBadge(false)
                description = "Keeps walking cadence available on the watch face"
            }
            nm.createNotificationChannel(channel)

            val launchIntent = Intent(this, MainActivity::class.java)
            val contentIntent = PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Walking Cadence Active")
                .setContentText("Low-power hardware step monitor")
                .setSmallIcon(R.drawable.ic_walk)
                .setContentIntent(contentIntent)
                .setLocalOnly(true)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
                .build()

            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
            } else {
                0
            }
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
            foregroundStarted = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Could not promote cadence monitor to the foreground", e)
            false
        }
    }

    private fun abandonStart() {
        isRunning = false
        WalkingCadenceManager.getInstance(applicationContext).noteForegroundPromotionFailed()
        stopSelf()
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context): Boolean {
            if (isRunning) return true
            val appCtx = context.applicationContext
            if (!WalkingCadenceManager.getInstance(appCtx).hasPermission()) {
                return false
            }
            return try {
                val intent = Intent(appCtx, CadenceAlwaysOnService::class.java)
                appCtx.startForegroundService(intent)
                true
            } catch (e: Exception) {
                Log.w(TAG, "Could not start cadence foreground service", e)
                false
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext
            runCatching {
                appCtx.stopService(Intent(appCtx, CadenceAlwaysOnService::class.java))
            }.onFailure { error ->
                Log.w(TAG, "Could not stop cadence foreground service", error)
            }
        }
    }
}
