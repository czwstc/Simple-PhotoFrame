package com.zweichen.photoframe.frame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Blurs and darkens an already small bitmap; used as the backdrop behind photos that don't fill the screen.
 */
class BlurTransformation(private val radius: Int = 6) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val result = pool.get(toTransform.width, toTransform.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawBitmap(toTransform, 0f, 0f, null)
            drawColor(Color.argb(90, 0, 0, 0))
        }
        stackBlur(result, radius)
        return result
    }

    override fun equals(other: Any?) = other is BlurTransformation && other.radius == radius

    override fun hashCode() = ID.hashCode() * 31 + radius

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("$ID$radius".toByteArray(CHARSET))
    }

    companion object {
        private const val ID = "com.zweichen.photoframe.BlurTransformation"

        /** Two-pass box blur approximation, plenty for a ~64px bitmap. */
        private fun stackBlur(bitmap: Bitmap, radius: Int) {
            val w = bitmap.width
            val h = bitmap.height
            val pixels = IntArray(w * h)
            bitmap.getPixels(pixels, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            repeat(3) {
                boxBlur(pixels, tmp, w, h, radius, horizontal = true)
                boxBlur(tmp, pixels, w, h, radius, horizontal = false)
            }
            bitmap.setPixels(pixels, 0, w, 0, 0, w, h)
        }

        private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val outer = if (horizontal) h else w
            val inner = if (horizontal) w else h
            for (o in 0 until outer) {
                for (i in 0 until inner) {
                    var a = 0
                    var red = 0
                    var g = 0
                    var b = 0
                    var n = 0
                    for (k in -r..r) {
                        val p = (i + k).coerceIn(0, inner - 1)
                        val c = if (horizontal) src[o * w + p] else src[p * w + o]
                        a += c ushr 24
                        red += (c shr 16) and 0xff
                        g += (c shr 8) and 0xff
                        b += c and 0xff
                        n++
                    }
                    val idx = if (horizontal) o * w + i else i * w + o
                    dst[idx] = ((a / n) shl 24) or ((red / n) shl 16) or ((g / n) shl 8) or (b / n)
                }
            }
        }
    }
}
