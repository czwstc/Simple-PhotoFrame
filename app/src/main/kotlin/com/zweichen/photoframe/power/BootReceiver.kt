package com.zweichen.photoframe.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Re-arms the sleep alarms after a reboot or a clock change. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        SleepScheduler.reschedule(context)
    }
}
