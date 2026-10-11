package com.zweichen.photoframe.transfer

import android.content.Context
import android.text.format.Formatter
import android.util.Log
import com.zweichen.photoframe.data.PhotoStore
import com.zweichen.photoframe.util.TransferLog
import fi.iki.elonen.NanoHTTPD
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.FileInputStream

/**
 * Serves the phone-side management page and its small JSON API. Everything is local, no internet involved.
 */
class UploadServer(
    private val context: Context,
    private val store: PhotoStore,
    private val frameName: () -> String,
    port: Int,
    private val listener: Listener,
) : NanoHTTPD(port) {

    interface Listener {
        fun onRequest()
        fun onPhotoReceived()
        fun onFinishRequested()
    }

    private val indexHtml: ByteArray by lazy { context.assets.open("web/index.html").use { it.readBytes() } }

    override fun serve(session: IHTTPSession): Response {
        listener.onRequest()
        val uri = session.uri ?: "/"
        val method = session.method
        if (method != Method.GET || !(uri.startsWith(THUMB_PREFIX) || uri.startsWith(PHOTO_PREFIX) || uri == "/api/info")) {
            TransferLog.log("${session.remoteIpAddress} $method $uri")
        }
        return try {
            when {
                method == Method.GET && (uri == "/" || uri == "/index.html") ->
                    newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", indexHtml.inputStream(), indexHtml.size.toLong())
                        .apply { addHeader("Cache-Control", "no-cache") }
                method == Method.GET && uri == "/api/info" -> json(info())
                method == Method.GET && uri == "/api/photos" -> json(photos())
                method == Method.GET && uri.startsWith(THUMB_PREFIX) -> thumbnail(uri.removePrefix(THUMB_PREFIX))
                method == Method.GET && uri.startsWith(PHOTO_PREFIX) -> original(uri.removePrefix(PHOTO_PREFIX))
                (method == Method.PUT || method == Method.POST) && uri == "/api/upload" -> upload(session)
                method == Method.POST && uri == "/api/delete" -> delete(session)
                method == Method.POST && uri == "/api/log" -> {
                    readBody(session)?.let { TransferLog.log("page ${session.remoteIpAddress}: $it") }
                    json(JSONObject().put("ok", true))
                }
                method == Method.POST && uri == "/api/finish" -> {
                    listener.onFinishRequested()
                    json(JSONObject().put("ok", true))
                }
                else -> error(Response.Status.NOT_FOUND, "not found")
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: $method $uri", e)
            TransferLog.log("request failed: $method $uri: $e")
            error(Response.Status.INTERNAL_ERROR, e.message ?: "error").apply { closeConnection(true) }
        }
    }

    private fun info() = JSONObject()
        .put("name", frameName())
        .put("count", store.count())
        .put("free", store.freeSpace())
        .put("freeText", Formatter.formatShortFileSize(context, store.freeSpace()))

    private fun photos() = JSONArray().apply {
        store.list().forEach { photo ->
            put(JSONObject().put("name", photo.name).put("size", photo.size).put("modified", photo.modified))
        }
    }

    private fun thumbnail(name: String): Response {
        val photo = store.find(name) ?: return error(Response.Status.NOT_FOUND, "not found")
        val file = store.thumbnail(photo) ?: photo.file
        val mime = if (file == photo.file) PhotoStore.mimeType(photo.name) else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, mime, FileInputStream(file), file.length())
            .apply { addHeader("Cache-Control", "max-age=86400") }
    }

    private fun original(name: String): Response {
        val photo = store.find(name) ?: return error(Response.Status.NOT_FOUND, "not found")
        return newFixedLengthResponse(Response.Status.OK, PhotoStore.mimeType(photo.name), FileInputStream(photo.file), photo.size)
            .apply { addHeader("Cache-Control", "max-age=3600") }
    }

    private fun upload(session: IHTTPSession): Response {
        val name = session.parameters["name"]?.firstOrNull()
        val length = session.headers["content-length"]?.toLongOrNull() ?: -1L
        if (name.isNullOrBlank() || length < 0) {
            TransferLog.log("upload rejected: name=$name length=$length")
            return error(Response.Status.BAD_REQUEST, "缺少文件名或长度").apply { closeConnection(true) }
        }
        val started = System.currentTimeMillis()
        val result = store.save(name, session.inputStream, length)
        TransferLog.log("upload $name (${session.headers["content-type"]}, $length bytes) -> $result in ${System.currentTimeMillis() - started} ms")
        return when (result) {
            is PhotoStore.SaveResult.Saved -> {
                listener.onPhotoReceived()
                json(JSONObject().put("ok", true).put("status", "saved").put("name", result.photo.name))
            }
            is PhotoStore.SaveResult.Duplicate ->
                json(JSONObject().put("ok", true).put("status", "duplicate").put("name", result.photo.name))
            is PhotoStore.SaveResult.Invalid ->
                // the body may not have been read completely, so don't reuse this connection
                error(Response.Status.BAD_REQUEST, result.reason).apply { closeConnection(true) }
        }
    }

    private fun delete(session: IHTTPSession): Response {
        val body = readBody(session) ?: return error(Response.Status.BAD_REQUEST, "请求无效").apply { closeConnection(true) }
        val names = JSONObject(body).optJSONArray("names") ?: JSONArray()
        val deleted = store.delete((0 until names.length()).map { names.getString(it) })
        return json(JSONObject().put("ok", true).put("deleted", deleted))
    }

    private fun readBody(session: IHTTPSession): String? {
        val length = session.headers["content-length"]?.toIntOrNull() ?: return null
        if (length > MAX_JSON_BODY) return null
        val out = ByteArrayOutputStream(length)
        val buffer = ByteArray(8192)
        var remaining = length
        while (remaining > 0) {
            val read = session.inputStream.read(buffer, 0, minOf(buffer.size, remaining))
            if (read == -1) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toString("UTF-8")
    }

    private fun json(value: Any) =
        newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", value.toString())
            .apply { addHeader("Cache-Control", "no-store") }

    private fun error(status: Response.Status, message: String) =
        newFixedLengthResponse(status, "application/json; charset=utf-8", JSONObject().put("ok", false).put("error", message).toString())

    companion object {
        private const val TAG = "UploadServer"
        private const val THUMB_PREFIX = "/api/thumb/"
        private const val PHOTO_PREFIX = "/api/photo/"
        private const val MAX_JSON_BODY = 1024 * 1024
    }
}
