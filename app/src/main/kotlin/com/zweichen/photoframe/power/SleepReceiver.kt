package com.zweichen.photoframe.power

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.zweichen.photoframe.frameApp

class SleepReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.frameApp
        val job = when (intent.action) {
            SleepScheduler.ACTION_SLEEP -> if (app.settings.sleepEnabled) app.sleep.sleepNow() else null
            SleepScheduler.ACTION_WAKE -> if (app.settings.sleepEnabled) app.sleep.wakeUp() else null
            else -> null
        }
        // arm the next day's alarm
        SleepScheduler.reschedule(context)

        if (job != null) {
            val pending = goAsync()
            job.invokeOnCompletion { pending.finish() }
        }
    }
}
