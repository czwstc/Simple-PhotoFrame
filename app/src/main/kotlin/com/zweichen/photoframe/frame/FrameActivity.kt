package com.zweichen.photoframe.frame

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.WindowManager
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.zweichen.photoframe.databinding.ActivityFrameBinding
import com.zweichen.photoframe.frameApp
import com.zweichen.photoframe.transfer.TransferActivity
import com.zweichen.photoframe.util.enterImmersive
import com.zweichen.photoframe.util.showOverLockScreen
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

/** The photo frame itself. Also registered as the HOME activity so the device boots straight into it. */
class FrameActivity : AppCompatActivity() {
    private lateinit var binding: ActivityFrameBinding
    private lateinit var slideshow: SlideshowController
    private lateinit var panel: SettingsPanel

    private val handler = Handler(Looper.getMainLooper())
    private val dateFormat = SimpleDateFormat("M月d日 EEEE", Locale.CHINA)
    private val settingsListener: () -> Unit = { applyClockVisibility() }
    private var nightIdlePending = false
    private val nightIdle = Runnable {
        nightIdlePending = false
        if (frameApp.sleep.isInSleepWindow()) frameApp.sleep.sleepNow()
    }

    private val timeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateDate()
            ensureNightIdleTimer()
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityFrameBinding.inflate(layoutInflater)
        setContentView(binding.root)
        showOverLockScreen()
        enterImmersive()

        slideshow = SlideshowController(this, binding.stage, binding.layerA, binding.layerB, frameApp.store, frameApp.settings) { empty ->
            binding.emptyState.isVisible = empty
        }
        panel = SettingsPanel(this, binding.settingsPanel, frameApp.settings, frameApp.store, ::openTransfer) { open ->
            binding.settingsButton.isVisible = !open
        }

        binding.settingsButton.setOnClickListener { panel.open() }
        binding.emptyTransferButton.setOnClickListener { openTransfer() }
        binding.sleepOverlay.setOnClickListener { frameApp.sleep.cancelSoftSleep() }

        val gestures = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.x - start.x
                if (abs(dx) < SWIPE_MIN_DISTANCE || abs(dx) < abs(e2.y - start.y)) return false
                if (dx < 0) slideshow.next() else slideshow.previous()
                return true
            }
        })
        binding.root.setOnTouchListener { _, event -> gestures.onTouchEvent(event) }

        // this is the home screen: back only closes the panel
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (panel.isOpen) panel.close()
            }
        })

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                frameApp.sleep.softSleeping.collect(::applySoftSleep)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        Log.d(TAG, "onStart")
        slideshow.start()
        frameApp.settings.addListener(settingsListener)
        registerReceiver(timeReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
        })
        updateDate()
        applyClockVisibility()
    }

    override fun onResume() {
        super.onResume()
        Log.d(TAG, "onResume")
        enterImmersive()
        restartNightIdleTimer()
    }

    override fun onPause() {
        super.onPause()
        Log.d(TAG, "onPause")
        cancelNightIdleTimer()
    }

    override fun onStop() {
        super.onStop()
        Log.d(TAG, "onStop")
        slideshow.stop()
        frameApp.settings.removeListener(settingsListener)
        unregisterReceiver(timeReceiver)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // HOME pressed while already here
        panel.close()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) enterImmersive()
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        panel.onUserInteraction()
        restartNightIdleTimer()
    }

    private fun openTransfer() {
        startActivity(Intent(this, TransferActivity::class.java))
    }

    private fun updateDate() {
        binding.clockDate.text = dateFormat.format(Date())
    }

    private fun applyClockVisibility() {
        binding.clock.isVisible = frameApp.settings.showClock
    }

    /** Without root the screen can't really be turned off, so we fake it with a black, dimmed overlay. */
    private fun applySoftSleep(sleeping: Boolean) {
        binding.sleepOverlay.isVisible = sleeping
        window.attributes = window.attributes.apply {
            screenBrightness = if (sleeping) 0f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (sleeping) {
            panel.close()
            slideshow.stop()
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) slideshow.start()
        }
    }

    /** Someone woke the frame up during the night: show photos, but go back to sleep after a while. */
    private fun restartNightIdleTimer() {
        cancelNightIdleTimer()
        ensureNightIdleTimer()
    }

    private fun ensureNightIdleTimer() {
        if (nightIdlePending || !lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (frameApp.sleep.isInSleepWindow()) {
            nightIdlePending = true
            handler.postDelayed(nightIdle, NIGHT_IDLE_MS)
        }
    }

    private fun cancelNightIdleTimer() {
        nightIdlePending = false
        handler.removeCallbacks(nightIdle)
    }

    companion object {
        private const val TAG = "FrameActivity"
        private const val SWIPE_MIN_DISTANCE = 120
        private const val NIGHT_IDLE_MS = 5 * 60_000L
    }
}
