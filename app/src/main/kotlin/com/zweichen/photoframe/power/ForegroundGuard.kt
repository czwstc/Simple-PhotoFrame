package com.zweichen.photoframe.power

import android.app.Activity
import android.app.AlarmManager
import android.app.Application
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import com.zweichen.photoframe.frame.FrameActivity
import com.zweichen.photoframe.frameApp
import com.zweichen.photoframe.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Every 5 minutes, brings the frame back to the front if something else (system settings, another app) has been
 * covering it for a while. Never wakes a screen that is off.
 */
object ForegroundGuard : Application.ActivityLifecycleCallbacks {
    private const val TAG = "ForegroundGuard"
    private const val ACTION_CHECK = "com.zweichen.photoframe.action.CHECK_FOREGROUND"
    private const val CHECK_INTERVAL_MS = 5 * 60_000L

    /** Someone who just left the frame (e.g. to change a system setting) gets at least this long. */
    private const val GRACE_MS = 3 * 60_000L

    private var startedActivities = 0
    private var backgroundSince = SystemClock.elapsedRealtime()

    val isForeground get() = startedActivities > 0

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        scheduleNext(app)
    }

    /** One-shot alarm re-armed on every check; repeating alarms may drift by several minutes. */
    private fun scheduleNext(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val at = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
        // non-wakeup: there is nothing to do while the device sleeps
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.ELAPSED_REALTIME, at, checkIntent(context))
        } else {
            alarmManager.setExact(AlarmManager.ELAPSED_REALTIME, at, checkIntent(context))
        }
    }

    fun check(context: Context, onDone: () -> Unit) {
        scheduleNext(context)
        val app = context.frameApp
        val power = context.getSystemService(PowerManager::class.java)
        val awayFor = SystemClock.elapsedRealtime() - backgroundSince
        if (isForeground || !power.isInteractive || awayFor < GRACE_MS) {
            onDone()
            return
        }
        Log.i(TAG, "frame not in front for ${awayFor / 1000}s, bringing it back")
        app.appScope.launch {
            val component = "${context.packageName}/${FrameActivity::class.java.name}"
            val started = withContext(Dispatchers.IO) {
                RootShell.isAvailable() && RootShell.run("am start -n $component").isSuccess
            }
            if (!started) {
                context.startActivity(Intent(context, FrameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            onDone()
        }
    }

    private fun checkIntent(context: Context): PendingIntent {
        val intent = Intent(context, Receiver::class.java).setAction(ACTION_CHECK)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    override fun onActivityStarted(activity: Activity) {
        startedActivities++
    }

    override fun onActivityStopped(activity: Activity) {
        startedActivities = (startedActivities - 1).coerceAtLeast(0)
        if (startedActivities == 0) backgroundSince = SystemClock.elapsedRealtime()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    class Receiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val pending = goAsync()
            check(context) { pending.finish() }
        }
    }
}
