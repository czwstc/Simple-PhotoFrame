package com.zweichen.photoframe.frame

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.resource.bitmap.CenterCrop
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import com.zweichen.photoframe.data.Effect
import com.zweichen.photoframe.data.FrameSettings
import com.zweichen.photoframe.data.PhotoStore
import com.zweichen.photoframe.data.PhotoStore.Photo
import com.zweichen.photoframe.data.PlayOrder
import com.zweichen.photoframe.data.ScaleMode
import kotlin.random.Random

/**
 * Drives the slideshow: picks the next photo, loads it into the hidden [SlideLayer] and animates it in.
 */
class SlideshowController(
    private val activity: AppCompatActivity,
    private val stage: View,
    layerA: SlideLayer,
    layerB: SlideLayer,
    private val store: PhotoStore,
    private val settings: FrameSettings,
    private val onEmptyChanged: (Boolean) -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var front = layerA
    private var back = layerB

    private var photos: List<Photo> = emptyList()
    private val queue = ArrayDeque<Photo>()
    private val history = ArrayList<Photo>()
    private var current: Photo? = null

    private var running = false
    private var transition: Animator? = null
    private var consecutiveFailures = 0
    private var lastOrder = settings.order
    private var lastScaleMode = settings.scaleMode
    private var lastInterval = settings.intervalSeconds

    private val advance = Runnable { showNext(forward = true) }
    private val storeListener: () -> Unit = { if (running) onPhotosChanged() }
    private val settingsListener: () -> Unit = { if (running) onSettingsChanged() }

    fun start() {
        if (running) return
        Log.d(TAG, "start")
        running = true
        store.addListener(storeListener)
        settings.addListener(settingsListener)
        onSettingsChanged()
        val changed = onPhotosChanged()
        if (!changed && current != null) {
            front.kenBurns?.resume()
            scheduleNext()
        }
    }

    fun stop() {
        if (!running) return
        Log.d(TAG, "stop")
        running = false
        handler.removeCallbacks(advance)
        store.removeListener(storeListener)
        settings.removeListener(settingsListener)
        transition?.end()
        front.kenBurns?.pause()
    }

    fun next() = showNext(forward = true, manual = true)

    fun previous() = showNext(forward = false, manual = true)

    /** Re-reads the photo list. Returns true if a new photo was put on screen because of it. */
    private fun onPhotosChanged(): Boolean {
        val updated = store.list()
        val oldNames = photos.mapTo(HashSet()) { it.name }
        val newNames = updated.mapTo(HashSet()) { it.name }
        val added = updated.filter { it.name !in oldNames }
        val firstLoad = photos.isEmpty() && current == null
        photos = updated

        queue.removeAll { it.name !in newNames }
        history.removeAll { it.name !in newNames }
        if (!firstLoad) {
            // freshly uploaded photos are shown next, in upload order
            added.sortedByDescending { it.modified }.forEach { queue.addFirst(it) }
        }

        onEmptyChanged(photos.isEmpty())
        if (photos.isEmpty()) {
            showNothing()
            return false
        }

        val currentGone = current?.let { it.name !in newNames } ?: true
        if (currentGone || (added.isNotEmpty() && !firstLoad)) {
            showNext(forward = true)
            return true
        }
        return false
    }

    private fun onSettingsChanged() {
        val order = settings.order
        val scaleMode = settings.scaleMode
        val interval = settings.intervalSeconds
        if (order != lastOrder) {
            queue.clear()
        }
        val reloadCurrent = scaleMode != lastScaleMode
        val reschedule = interval != lastInterval
        lastOrder = order
        lastScaleMode = scaleMode
        lastInterval = interval

        if (reloadCurrent && current != null) {
            current?.let { display(it, forward = true, effect = Effect.FADE, addToHistory = false) }
        } else if (reschedule && current != null && transition == null) {
            scheduleNext()
        }
    }

    private fun showNext(forward: Boolean, manual: Boolean = false) {
        if (!running) return
        transition?.end()
        handler.removeCallbacks(advance)
        val next = if (forward) pickNext() else pickPrevious()
        if (next == null) {
            if (photos.isEmpty()) showNothing() else scheduleNext()
            return
        }
        display(next, forward, if (manual) Effect.SLIDE else resolveEffect(), addToHistory = true)
    }

    private fun pickNext(): Photo? {
        if (photos.isEmpty()) return null
        if (queue.isEmpty()) refillQueue()
        return queue.removeFirstOrNull()
    }

    private fun pickPrevious(): Photo? {
        if (history.size < 2) return null
        queue.addFirst(history.removeAt(history.lastIndex))
        return history.removeAt(history.lastIndex)
    }

    private fun refillQueue() {
        val ordered = when (settings.order) {
            PlayOrder.RANDOM -> photos.shuffled().let { list ->
                // avoid showing the same photo twice in a row across rounds
                if (list.size > 1 && list.first().name == current?.name) list.drop(1) + list.first() else list
            }
            PlayOrder.NEWEST -> photos.sortedByDescending { it.modified }
            PlayOrder.OLDEST -> photos.sortedBy { it.modified }
        }
        queue.addAll(ordered)
    }

    private fun resolveEffect(): Effect = when (val effect = settings.effect) {
        Effect.RANDOM -> listOf(Effect.KEN_BURNS, Effect.FADE, Effect.SLIDE).random()
        else -> effect
    }

    private fun display(photo: Photo, forward: Boolean, effect: Effect, addToHistory: Boolean) {
        val layer = back
        val generation = ++layer.generation
        layer.resetTransform()
        layer.alpha = 0f
        layer.photoName = photo.name

        val (width, height) = targetSize()
        val fill = settings.scaleMode == ScaleMode.FILL
        val glide = Glide.with(activity)

        if (fill) {
            glide.clear(layer.backdrop)
            layer.backdrop.visibility = View.GONE
        } else {
            layer.backdrop.visibility = View.VISIBLE
            val backdropWidth = 64
            val backdropHeight = (64f * height / width).toInt().coerceAtLeast(16)
            glide.load(photo.file)
                .override(backdropWidth, backdropHeight)
                .transform(CenterCrop(), BlurTransformation())
                .dontAnimate()
                .into(layer.backdrop)
        }

        layer.photo.scaleType = if (fill) ImageView.ScaleType.CENTER_CROP else ImageView.ScaleType.FIT_CENTER
        val request = glide.load(photo.file)
            .override((width * OVERSCAN).toInt(), (height * OVERSCAN).toInt())
            .dontAnimate()
        (if (fill) request.centerCrop() else request.fitCenter())
            .listener(object : RequestListener<Drawable> {
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean,
                ): Boolean {
                    handler.post {
                        if (running && generation == layer.generation) {
                            transitionTo(layer, photo, forward, effect, addToHistory)
                        }
                    }
                    return false
                }

                override fun onLoadFailed(e: GlideException?, model: Any?, target: Target<Drawable>, isFirstResource: Boolean): Boolean {
                    Log.w(TAG, "failed to load ${photo.name}", e)
                    handler.post {
                        if (running && generation == layer.generation) onLoadFailed()
                    }
                    return false
                }
            })
            .into(layer.photo)
    }

    private fun onLoadFailed() {
        consecutiveFailures++
        if (consecutiveFailures > photos.size) {
            consecutiveFailures = 0
            handler.postDelayed(advance, RETRY_DELAY_MS)
        } else {
            showNext(forward = true)
        }
    }

    private fun transitionTo(layer: SlideLayer, photo: Photo, forward: Boolean, effect: Effect, addToHistory: Boolean) {
        Log.d(TAG, "show ${photo.name} effect=$effect")
        consecutiveFailures = 0
        current = photo
        if (addToHistory) {
            history.add(photo)
            if (history.size > MAX_HISTORY) history.removeAt(0)
        }

        val old = front
        layer.bringToFront()
        val animator: Animator? = when (effect) {
            Effect.FADE, Effect.KEN_BURNS, Effect.RANDOM -> ObjectAnimator.ofFloat(layer, View.ALPHA, 0f, 1f).apply {
                duration = FADE_MS
            }
            Effect.SLIDE -> {
                layer.alpha = 1f
                val distance = stage.width.toFloat().takeIf { it > 0 } ?: activity.resources.displayMetrics.widthPixels.toFloat()
                val direction = if (forward) 1f else -1f
                AnimatorSet().apply {
                    playTogether(
                        ObjectAnimator.ofFloat(layer, View.TRANSLATION_X, direction * distance, 0f),
                        ObjectAnimator.ofFloat(old, View.TRANSLATION_X, old.translationX, -direction * distance),
                    )
                    duration = SLIDE_MS
                    interpolator = DecelerateInterpolator(1.6f)
                }
            }
            Effect.NONE -> null
        }
        if (effect == Effect.KEN_BURNS) startKenBurns(layer)

        val finish = {
            transition = null
            old.alpha = 0f
            old.resetTransform()
            old.photoName = null
            Glide.with(activity).clear(old.photo)
            Glide.with(activity).clear(old.backdrop)
            front = layer
            back = old
            if (running) scheduleNext()
        }

        if (animator == null) {
            layer.alpha = 1f
            finish()
        } else {
            animator.addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) = finish()
            })
            transition = animator
            animator.start()
        }
    }

    private fun startKenBurns(layer: SlideLayer) {
        val zoomIn = Random.nextBoolean()
        val from = if (zoomIn) 1f else KEN_BURNS_SCALE
        val to = if (zoomIn) KEN_BURNS_SCALE else 1f
        layer.pivotX = layer.width * Random.nextDouble(0.2, 0.8).toFloat()
        layer.pivotY = layer.height * Random.nextDouble(0.2, 0.8).toFloat()
        layer.scaleX = from
        layer.scaleY = from
        layer.kenBurns = ObjectAnimator.ofPropertyValuesHolder(
            layer,
            PropertyValuesHolder.ofFloat(View.SCALE_X, from, to),
            PropertyValuesHolder.ofFloat(View.SCALE_Y, from, to),
        ).apply {
            duration = settings.intervalSeconds * 1000L + FADE_MS * 2
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun scheduleNext() {
        handler.removeCallbacks(advance)
        if (running && photos.isNotEmpty()) {
            handler.postDelayed(advance, settings.intervalSeconds * 1000L)
        }
    }

    private fun showNothing() {
        handler.removeCallbacks(advance)
        transition?.end()
        current = null
        queue.clear()
        history.clear()
        listOf(front, back).forEach { layer ->
            layer.generation++
            layer.alpha = 0f
            layer.resetTransform()
            layer.photoName = null
            Glide.with(activity).clear(layer.photo)
            Glide.with(activity).clear(layer.backdrop)
        }
    }

    private fun targetSize(): Pair<Int, Int> {
        val metrics = activity.resources.displayMetrics
        val width = stage.width.takeIf { it > 0 } ?: metrics.widthPixels
        val height = stage.height.takeIf { it > 0 } ?: metrics.heightPixels
        return width to height
    }

    companion object {
        private const val TAG = "Slideshow"
        private const val FADE_MS = 1200L
        private const val SLIDE_MS = 700L
        private const val KEN_BURNS_SCALE = 1.12f
        private const val OVERSCAN = 1.2f
        private const val MAX_HISTORY = 50
        private const val RETRY_DELAY_MS = 30_000L
    }
}
