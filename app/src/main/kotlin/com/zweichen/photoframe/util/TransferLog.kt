package com.zweichen.photoframe.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Small persistent log of transfer sessions (`files/transfer.log`), so a failed upload from someone's phone can be
 * diagnosed afterwards — adb is unreachable while the hotspot is up.
 */
object TransferLog {
    private const val TAG = "TransferLog"
    private const val MAX_SIZE = 256 * 1024L
    private val timeFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    private var file: File? = null

    fun init(context: Context) {
        file = File(context.filesDir, "transfer.log")
    }

    @Synchronized
    fun log(message: String) {
        Log.i(TAG, message)
        val target = file ?: return
        try {
            if (target.length() > MAX_SIZE) {
                target.renameTo(File(target.parentFile, "transfer.log.1"))
            }
            target.appendText("${timeFormat.format(Date())} $message\n")
        } catch (e: Exception) {
            Log.w(TAG, "can't write log", e)
        }
    }
}
