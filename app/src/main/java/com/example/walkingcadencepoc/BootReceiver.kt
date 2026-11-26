package com.example.walkingcadencepoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            return
        }
        // Boot is allowed to start a foreground service. Package-replaced and a
        // missing permission must not crash the process if the start is rejected.
        runCatching {
            WalkingCadenceManager.getInstance(context).ensureAlwaysOnStarted()
        }
    }
}
