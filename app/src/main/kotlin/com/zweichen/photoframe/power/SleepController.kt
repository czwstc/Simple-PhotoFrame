package com.zweichen.photoframe.power

import android.content.Intent
import android.os.PowerManager
import android.util.Log
import com.zweichen.photoframe.FrameApp
import com.zweichen.photoframe.frame.FrameActivity
import com.zweichen.photoframe.util.RootShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Turns the screen off and on. With root we press the real sleep / wakeup keys; without it the frame shows
 * a black, zero-brightness overlay instead ("soft sleep").
 */
class SleepController(private val app: FrameApp) {
    private val _softSleeping = MutableStateFlow(false)
    val softSleeping: StateFlow<Boolean> = _softSleeping.asStateFlow()

    fun isInSleepWindow(): Boolean {
        val settings = app.settings
        return settings.sleepEnabled &&
            SleepScheduler.isInWindow(SleepScheduler.nowMinutes(), settings.sleepStartMinutes, settings.sleepEndMinutes)
    }

    fun sleepNow(): Job = app.appScope.launch {
        if (app.transfer.isActive) {
            Log.i(TAG, "transfer in progress, not sleeping")
            return@launch
        }
        Log.i(TAG, "going to sleep")
        val turnedOff = withContext(Dispatchers.IO) {
            RootShell.isAvailable() && RootShell.run("input keyevent KEYCODE_SLEEP").isSuccess
        }
        if (!turnedOff) _softSleeping.value = true
    }

    fun wakeUp(): Job = app.appScope.launch {
        Log.i(TAG, "waking up")
        _softSleeping.value = false
        val component = "${app.packageName}/${FrameActivity::class.java.name}"
        val woke = withContext(Dispatchers.IO) {
            RootShell.isAvailable() &&
                RootShell.run("input keyevent KEYCODE_WAKEUP").isSuccess &&
                RootShell.run("am start -n $component").isSuccess
        }
        if (!woke) {
            val power = app.getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            power.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "PhotoFrame:wake",
            ).acquire(10_000)
            app.startActivity(Intent(app, FrameActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** The viewer tapped the black overlay. */
    fun cancelSoftSleep() {
        _softSleeping.value = false
    }

    companion object {
        private const val TAG = "SleepController"
    }
}
