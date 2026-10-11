package com.zweichen.photoframe.transfer

import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

object QrCode {
    /**
     * Renders [text] with one pixel per module; the drawable scales it up without filtering so the edges stay crisp.
     */
    fun drawable(resources: Resources, text: String): BitmapDrawable {
        val hints = mapOf(
            EncodeHintType.CHARACTER_SET to "UTF-8",
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
            EncodeHintType.MARGIN to 2,
        )
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix[i % matrix.width, i / matrix.width]) Color.BLACK else Color.WHITE
        }
        val bitmap = Bitmap.createBitmap(pixels, matrix.width, matrix.height, Bitmap.Config.ARGB_8888)
        return BitmapDrawable(resources, bitmap).apply {
            isFilterBitmap = false
            setAntiAlias(false)
        }
    }

    /** Standard Wi‑Fi QR payload understood by the Android and iOS cameras. */
    fun wifiPayload(ssid: String, password: String?): String {
        return if (password.isNullOrEmpty()) {
            "WIFI:T:nopass;S:${escape(ssid)};;"
        } else {
            "WIFI:T:WPA;S:${escape(ssid)};P:${escape(password)};;"
        }
    }

    private fun escape(value: String) = value.replace(Regex("([\\\\;,:\"])"), "\\\\$1")
}
