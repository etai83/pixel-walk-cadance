package com.example.walkingcadencepoc

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder

private const val CHANNEL_ID = "cadence_always_on_channel"
private const val NOTIFICATION_ID = 1001

class CadenceAlwaysOnService : Service() {

    private var foregroundStarted = false

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        ensureForegroundOnce()
        WalkingCadenceManager.getInstance(applicationContext).startSensorsOnly()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureForegroundOnce()
        WalkingCadenceManager.getInstance(applicationContext).startSensorsOnly()
        return START_STICKY
    }

    override fun onDestroy() {
        isRunning = false
        foregroundStarted = false
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureForegroundOnce() {
        if (foregroundStarted) return
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Walking Cadence Monitor",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)

        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Walking Cadence Active")
            .setContentText("Low-power hardware step monitor")
            .setSmallIcon(R.drawable.ic_walk)
            .setLocalOnly(true)
            .setOngoing(true)
            .build()

        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundStarted = true
        }
    }

    companion object {
        @Volatile
        var isRunning: Boolean = false
            private set

        fun start(context: Context) {
            if (isRunning) return
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CadenceAlwaysOnService::class.java)
                appCtx.startForegroundService(intent)
            }
        }
    }
}
