package com.zweichen.photoframe.data

import android.content.Context
import android.content.SharedPreferences
import androidx.annotation.StringRes
import androidx.core.content.edit
import com.zweichen.photoframe.R
import java.util.concurrent.CopyOnWriteArraySet

enum class Effect(@StringRes val label: Int) {
    KEN_BURNS(R.string.effect_ken_burns),
    FADE(R.string.effect_fade),
    SLIDE(R.string.effect_slide),
    NONE(R.string.effect_none),
    RANDOM(R.string.effect_random),
}

enum class PlayOrder(@StringRes val label: Int) {
    RANDOM(R.string.order_random),
    NEWEST(R.string.order_newest),
    OLDEST(R.string.order_oldest),
}

enum class ScaleMode(@StringRes val label: Int) {
    FIT_BLUR(R.string.scale_fit),
    FILL(R.string.scale_fill),
}

class FrameSettings(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("frame_settings", Context.MODE_PRIVATE)
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    var effect: Effect
        get() = enumValue(KEY_EFFECT, Effect.KEN_BURNS)
        set(value) = put { putString(KEY_EFFECT, value.name) }

    var intervalSeconds: Int
        get() = prefs.getInt(KEY_INTERVAL, 10)
        set(value) = put { putInt(KEY_INTERVAL, value) }

    var order: PlayOrder
        get() = enumValue(KEY_ORDER, PlayOrder.RANDOM)
        set(value) = put { putString(KEY_ORDER, value.name) }

    var scaleMode: ScaleMode
        get() = enumValue(KEY_SCALE, ScaleMode.FIT_BLUR)
        set(value) = put { putString(KEY_SCALE, value.name) }

    var showClock: Boolean
        get() = prefs.getBoolean(KEY_CLOCK, true)
        set(value) = put { putBoolean(KEY_CLOCK, value) }

    var sleepEnabled: Boolean
        get() = prefs.getBoolean(KEY_SLEEP_ENABLED, false)
        set(value) = put { putBoolean(KEY_SLEEP_ENABLED, value) }

    /** Minutes after midnight. */
    var sleepStartMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_START, 23 * 60)
        set(value) = put { putInt(KEY_SLEEP_START, value) }

    /** Minutes after midnight. */
    var sleepEndMinutes: Int
        get() = prefs.getInt(KEY_SLEEP_END, 7 * 60)
        set(value) = put { putInt(KEY_SLEEP_END, value) }

    fun addListener(listener: () -> Unit) = listeners.add(listener)

    fun removeListener(listener: () -> Unit) = listeners.remove(listener)

    private fun put(block: SharedPreferences.Editor.() -> Unit) {
        prefs.edit(action = block)
        listeners.forEach { it() }
    }

    private inline fun <reified T : Enum<T>> enumValue(key: String, default: T): T {
        val name = prefs.getString(key, null) ?: return default
        return enumValues<T>().firstOrNull { it.name == name } ?: default
    }

    companion object {
        val INTERVALS = listOf(5, 10, 30, 60, 5 * 60, 15 * 60)

        private const val KEY_EFFECT = "effect"
        private const val KEY_INTERVAL = "interval_seconds"
        private const val KEY_ORDER = "order"
        private const val KEY_SCALE = "scale_mode"
        private const val KEY_CLOCK = "show_clock"
        private const val KEY_SLEEP_ENABLED = "sleep_enabled"
        private const val KEY_SLEEP_START = "sleep_start"
        private const val KEY_SLEEP_END = "sleep_end"
    }
}
