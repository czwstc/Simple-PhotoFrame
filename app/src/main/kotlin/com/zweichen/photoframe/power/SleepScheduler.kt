package com.zweichen.photoframe.power

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import com.zweichen.photoframe.frameApp
import java.util.Calendar

/** Keeps two alarms armed: one for the daily sleep time, one for the wake-up time. */
object SleepScheduler {
    private const val TAG = "SleepScheduler"
    const val ACTION_SLEEP = "com.zweichen.photoframe.action.SLEEP"
    const val ACTION_WAKE = "com.zweichen.photoframe.action.WAKE"

    fun reschedule(context: Context) {
        val settings = context.frameApp.settings
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val sleepIntent = pendingIntent(context, ACTION_SLEEP)
        val wakeIntent = pendingIntent(context, ACTION_WAKE)
        alarmManager.cancel(sleepIntent)
        alarmManager.cancel(wakeIntent)

        if (!settings.sleepEnabled || settings.sleepStartMinutes == settings.sleepEndMinutes) return
        setAlarm(alarmManager, nextTrigger(settings.sleepStartMinutes), sleepIntent)
        setAlarm(alarmManager, nextTrigger(settings.sleepEndMinutes), wakeIntent)
        Log.i(TAG, "scheduled sleep ${settings.sleepStartMinutes} wake ${settings.sleepEndMinutes}")
    }

    /** True if [nowMinutes] (minutes after midnight) falls into the [start, end) window, which may wrap midnight. */
    fun isInWindow(nowMinutes: Int, start: Int, end: Int): Boolean = when {
        start == end -> false
        start < end -> nowMinutes in start until end
        else -> nowMinutes >= start || nowMinutes < end
    }

    fun nowMinutes(): Int = Calendar.getInstance().let { it.get(Calendar.HOUR_OF_DAY) * 60 + it.get(Calendar.MINUTE) }

    private fun nextTrigger(minutes: Int): Long {
        val now = Calendar.getInstance()
        val trigger = (now.clone() as Calendar).apply {
            set(Calendar.HOUR_OF_DAY, minutes / 60)
            set(Calendar.MINUTE, minutes % 60)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (!trigger.after(now)) trigger.add(Calendar.DAY_OF_YEAR, 1)
        return trigger.timeInMillis
    }

    private fun setAlarm(alarmManager: AlarmManager, time: Long, intent: PendingIntent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, intent)
        }
    }

    private fun pendingIntent(context: Context, action: String): PendingIntent {
        val intent = Intent(context, SleepReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(context, action.hashCode(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
