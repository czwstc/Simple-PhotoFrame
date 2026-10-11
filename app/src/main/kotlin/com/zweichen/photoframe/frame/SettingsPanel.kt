package com.zweichen.photoframe.frame

import android.app.TimePickerDialog
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.format.Formatter
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import com.zweichen.photoframe.R
import com.zweichen.photoframe.data.Effect
import com.zweichen.photoframe.data.FrameSettings
import com.zweichen.photoframe.data.PhotoStore
import com.zweichen.photoframe.data.PlayOrder
import com.zweichen.photoframe.data.ScaleMode
import com.zweichen.photoframe.databinding.ViewSettingsPanelBinding

/** The translucent panel that slides in from the right edge of the frame. */
class SettingsPanel(
    private val activity: AppCompatActivity,
    private val binding: ViewSettingsPanelBinding,
    private val settings: FrameSettings,
    private val store: PhotoStore,
    private val onTransfer: () -> Unit,
    private val onOpenChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { close() }
    private val refreshers = mutableListOf<() -> Unit>()
    private val density = activity.resources.displayMetrics.density

    val isOpen get() = binding.root.isVisible

    init {
        binding.root.setOnClickListener { close() }
        binding.panelClose.setOnClickListener { close() }
        binding.panelTransfer.setOnClickListener {
            close()
            onTransfer()
        }

        addChoices(binding.effectChoices, Effect.values().map { it to activity.getString(it.label) }, { settings.effect }) {
            settings.effect = it
        }
        addChoices(binding.intervalChoices, FrameSettings.INTERVALS.map { it to formatInterval(it) }, { settings.intervalSeconds }) {
            settings.intervalSeconds = it
        }
        addChoices(binding.orderChoices, PlayOrder.values().map { it to activity.getString(it.label) }, { settings.order }) {
            settings.order = it
        }
        addChoices(binding.scaleChoices, ScaleMode.values().map { it to activity.getString(it.label) }, { settings.scaleMode }) {
            settings.scaleMode = it
        }

        binding.clockSwitch.setOnCheckedChangeListener { _, checked -> settings.showClock = checked }
        binding.sleepSwitch.setOnCheckedChangeListener { _, checked ->
            settings.sleepEnabled = checked
            refreshSleepTimes()
        }
        binding.sleepStartButton.setOnClickListener {
            pickTime(settings.sleepStartMinutes) { settings.sleepStartMinutes = it }
        }
        binding.sleepEndButton.setOnClickListener {
            pickTime(settings.sleepEndMinutes) { settings.sleepEndMinutes = it }
        }
        binding.systemSettingsButton.setOnClickListener {
            close()
            activity.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    fun open() {
        if (isOpen) return
        refresh()
        binding.root.visibility = View.VISIBLE
        onOpenChanged(true)
        binding.panel.translationX = PANEL_WIDTH_DP * density
        binding.panel.animate().translationX(0f).setDuration(ANIMATION_MS).start()
        binding.panel.scrollTo(0, 0)
        scheduleAutoClose()
    }

    fun close() {
        handler.removeCallbacks(autoClose)
        if (!isOpen) return
        binding.panel.animate()
            .translationX(PANEL_WIDTH_DP * density)
            .setDuration(ANIMATION_MS)
            .withEndAction {
                binding.root.visibility = View.GONE
                onOpenChanged(false)
            }
            .start()
    }

    fun onUserInteraction() {
        if (isOpen) scheduleAutoClose()
    }

    private fun scheduleAutoClose() {
        handler.removeCallbacks(autoClose)
        handler.postDelayed(autoClose, AUTO_CLOSE_MS)
    }

    private fun refresh() {
        refreshers.forEach { it() }
        binding.clockSwitch.isChecked = settings.showClock
        binding.sleepSwitch.isChecked = settings.sleepEnabled
        refreshSleepTimes()
        binding.photoStats.text = activity.getString(
            R.string.photo_stats,
            store.count(),
            Formatter.formatShortFileSize(activity, store.freeSpace()),
        )
    }

    private fun refreshSleepTimes() {
        binding.sleepTimes.isVisible = settings.sleepEnabled
        binding.sleepStartButton.text = "${activity.getString(R.string.sleep_start)}  ${formatTime(settings.sleepStartMinutes)}"
        binding.sleepEndButton.text = "${activity.getString(R.string.sleep_end)}  ${formatTime(settings.sleepEndMinutes)}"
    }

    private fun pickTime(minutes: Int, onPicked: (Int) -> Unit) {
        handler.removeCallbacks(autoClose)
        TimePickerDialog(activity, { _, hour, minute ->
            onPicked(hour * 60 + minute)
            refreshSleepTimes()
        }, minutes / 60, minutes % 60, true).apply {
            setOnDismissListener { scheduleAutoClose() }
            show()
        }
    }

    /** Lays the options out in rows of three toggle chips; exactly one is selected. */
    private fun <T> addChoices(container: LinearLayout, options: List<Pair<T, String>>, selected: () -> T, onSelect: (T) -> Unit) {
        val views = mutableListOf<Pair<T, TextView>>()
        options.chunked(CHOICES_PER_ROW).forEachIndexed { rowIndex, rowOptions ->
            val row = LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            container.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                if (rowIndex > 0) topMargin = (8 * density).toInt()
            })
            for (i in 0 until CHOICES_PER_ROW) {
                val option = rowOptions.getOrNull(i)
                val view = TextView(activity).apply {
                    gravity = Gravity.CENTER
                    minHeight = (48 * density).toInt()
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
                    setTextColor(ContextCompat.getColor(activity, R.color.text_primary))
                    if (option != null) {
                        text = option.second
                        background = ContextCompat.getDrawable(activity, R.drawable.bg_choice)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            onSelect(option.first)
                            views.forEach { (value, v) -> v.isSelected = value == option.first }
                        }
                    } else {
                        visibility = View.INVISIBLE
                    }
                }
                row.addView(view, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (i > 0) marginStart = (8 * density).toInt()
                })
                if (option != null) views.add(option.first to view)
            }
        }
        refreshers.add {
            val current = selected()
            views.forEach { (value, view) -> view.isSelected = value == current }
        }
    }

    private fun formatInterval(seconds: Int) = if (seconds < 60) {
        activity.getString(R.string.seconds, seconds)
    } else {
        activity.getString(R.string.minutes, seconds / 60)
    }

    private fun formatTime(minutes: Int) = String.format("%02d:%02d", minutes / 60, minutes % 60)

    companion object {
        private const val PANEL_WIDTH_DP = 460
        private const val CHOICES_PER_ROW = 3
        private const val ANIMATION_MS = 220L
        private const val AUTO_CLOSE_MS = 60_000L
    }
}
