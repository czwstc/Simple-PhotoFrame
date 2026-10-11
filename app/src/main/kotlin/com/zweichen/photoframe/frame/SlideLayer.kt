package com.zweichen.photoframe.frame

import android.animation.Animator
import android.content.Context
import android.util.AttributeSet
import android.widget.FrameLayout
import android.widget.ImageView

/** One full-screen slide: a blurred backdrop plus the photo itself. Two of these are cross-faded. */
class SlideLayer @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) : FrameLayout(context, attrs) {
    val backdrop = ImageView(context).apply { scaleType = ImageView.ScaleType.CENTER_CROP }
    val photo = ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER }

    /** Incremented on every load so stale Glide callbacks can be ignored. */
    var generation = 0

    /** Name of the photo currently shown in this layer. */
    var photoName: String? = null

    /** Slow zoom running on this layer while it is on screen. */
    var kenBurns: Animator? = null

    init {
        addView(backdrop, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(photo, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        alpha = 0f
    }

    fun resetTransform() {
        animate().cancel()
        kenBurns?.cancel()
        kenBurns = null
        scaleX = 1f
        scaleY = 1f
        translationX = 0f
        translationY = 0f
        pivotX = width / 2f
        pivotY = height / 2f
    }
}
