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

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        WalkingCadenceManager.getInstance(applicationContext).ensureAlwaysOnStarted()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground()
        WalkingCadenceManager.getInstance(applicationContext).ensureAlwaysOnStarted()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground() {
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
            .setContentText("Monitoring step cadence every 10s")
            .setSmallIcon(R.drawable.ic_walk)
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
        }
    }

    companion object {
        fun start(context: Context) {
            runCatching {
                val appCtx = context.applicationContext
                val intent = Intent(appCtx, CadenceAlwaysOnService::class.java)
                appCtx.startForegroundService(intent)
            }
        }
    }
}
