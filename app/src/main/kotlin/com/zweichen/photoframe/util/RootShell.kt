package com.zweichen.photoframe.util

import android.util.Log
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Runs shell commands as root. Blocking — call from a background thread.
 *
 * Supports both the AOSP/Rockchip userdebug `su` (`su 0 sh -c cmd`) and Magisk/SuperSU style (`su -c cmd`).
 */
object RootShell {
    private const val TAG = "RootShell"
    private val SU_PATHS = listOf("/system/xbin/su", "/system/bin/su", "/sbin/su", "/su/bin/su", "/debug_ramdisk/su")

    data class Result(val exitCode: Int, val output: String) {
        val isSuccess get() = exitCode == 0
    }

    @Volatile
    private var resolved = false

    @Volatile
    private var argsBuilder: ((String) -> Array<String>)? = null

    @Synchronized
    fun isAvailable(): Boolean {
        if (!resolved) {
            argsBuilder = detect()
            resolved = true
            Log.i(TAG, "root available: ${argsBuilder != null}")
        }
        return argsBuilder != null
    }

    fun run(command: String, timeoutMs: Long = 20_000): Result {
        val builder = if (isAvailable()) argsBuilder!! else return Result(-1, "root unavailable")
        return exec(builder(command), timeoutMs)
    }

    private fun detect(): ((String) -> Array<String>)? {
        val su = SU_PATHS.firstOrNull { File(it).exists() } ?: return null
        val candidates = listOf<(String) -> Array<String>>(
            { cmd -> arrayOf(su, "0", "sh", "-c", cmd) },
            { cmd -> arrayOf(su, "-c", cmd) },
        )
        return candidates.firstOrNull { builder ->
            val result = exec(builder("id"), 5_000)
            result.isSuccess && result.output.contains("uid=0")
        }
    }

    private fun exec(args: Array<String>, timeoutMs: Long): Result {
        return try {
            val process = ProcessBuilder(*args).redirectErrorStream(true).start()
            process.outputStream.close()
            val output = StringBuilder()
            val reader = Thread {
                try {
                    process.inputStream.bufferedReader().forEachLine { output.appendLine(it) }
                } catch (_: Exception) {
                }
            }.apply { start() }
            if (!process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)) {
                process.destroy()
                return Result(-1, "timeout")
            }
            reader.join(1_000)
            Result(process.exitValue(), output.toString().trim())
        } catch (e: Exception) {
            Log.w(TAG, "exec failed: ${args.joinToString(" ")}", e)
            Result(-1, e.message ?: "exec failed")
        }
    }
}
