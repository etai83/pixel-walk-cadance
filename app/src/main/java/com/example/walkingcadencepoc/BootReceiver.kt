package com.example.walkingcadencepoc

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        WalkingCadenceManager.getInstance(context).ensureAlwaysOnStarted()
    }
}
