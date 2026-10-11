package com.zweichen.photoframe.transfer

import android.util.Log
import com.zweichen.photoframe.FrameApp
import com.zweichen.photoframe.util.TransferLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** One "transfer photos" session: the hotspot plus the upload server, alive while the transfer screen is shown. */
class TransferSession(private val app: FrameApp) {
    sealed interface State {
        object Idle : State
        object Starting : State
        data class Ready(val info: Info) : State
        data class Failed(val message: String) : State
    }

    /** [password] is null for an open network; [ssid] is null if the phone should use the frame's current network. */
    data class Info(val ssid: String?, val password: String?, val url: String, val sameNetwork: Boolean)

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _received = MutableStateFlow(0)
    val received: StateFlow<Int> = _received.asStateFlow()

    private val _finishRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val finishRequests: SharedFlow<Unit> = _finishRequests.asSharedFlow()

    val isActive get() = _state.value !is State.Idle

    private val hotspot = HotspotController(app).also { hotspot ->
        app.appScope.launch { hotspot.cleanupAfterCrash() }
    }
    private var server: UploadServer? = null
    private var startJob: Job? = null
    private var watchdog: Job? = null

    @Volatile
    private var lastActivity = 0L
    private var idleTimeout = IDLE_TIMEOUT_MS

    private val serverListener = object : UploadServer.Listener {
        override fun onRequest() {
            lastActivity = System.currentTimeMillis()
        }

        override fun onPhotoReceived() = _received.update { it + 1 }

        override fun onFinishRequested() {
            _finishRequests.tryEmit(Unit)
        }
    }

    fun start(allowLocalOnly: Boolean, useHotspot: Boolean = true, idleTimeoutMs: Long = IDLE_TIMEOUT_MS) {
        if (_state.value is State.Starting || _state.value is State.Ready) return
        _state.value = State.Starting
        _received.value = 0
        lastActivity = System.currentTimeMillis()
        idleTimeout = idleTimeoutMs

        startJob = app.appScope.launch {
            try {
                TransferLog.log("session start (hotspot=$useHotspot, localOnly=$allowLocalOnly)")
                val srv = withContext(Dispatchers.IO) { startServer() }
                server = srv
                val result = hotspot.start(allowLocalOnly, useHotspot)
                TransferLog.log("hotspot: $result, server port ${srv.listeningPort}")
                _state.value = when (result) {
                    is HotspotController.Result.Open ->
                        State.Ready(Info(result.ssid, null, url(result.ip, srv.listeningPort), sameNetwork = false))
                    is HotspotController.Result.Secured ->
                        State.Ready(Info(result.ssid, result.password, url(result.ip, srv.listeningPort), sameNetwork = false))
                    is HotspotController.Result.SameNetwork ->
                        State.Ready(Info(result.ssid, null, url(result.ip, srv.listeningPort), sameNetwork = true))
                    is HotspotController.Result.Failed -> {
                        stopServer()
                        State.Failed(result.message)
                    }
                }
                (_state.value as? State.Ready)?.let { startWatchdog(it.info) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "start failed", e)
                stopServer()
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun stop() {
        if (_state.value is State.Idle) return
        TransferLog.log("session stop, ${_received.value} photos received")
        startJob?.cancel()
        watchdog?.cancel()
        _state.value = State.Idle
        val srv = server
        server = null
        app.appScope.launch(NonCancellable) {
            withContext(Dispatchers.IO) { srv?.stop() }
            hotspot.stop()
        }
    }

    /**
     * Ends the session by itself, so it never depends on the transfer screen being visible: after [idleTimeout]
     * without requests, or when the system has shut our hotspot down (Android does that after 10 minutes without
     * clients).
     */
    private fun startWatchdog(info: Info) {
        watchdog?.cancel()
        watchdog = app.appScope.launch {
            var hotspotMissing = 0
            while (isActive) {
                delay(minOf(WATCHDOG_INTERVAL_MS, idleTimeout / 4))
                val idle = System.currentTimeMillis() - lastActivity > idleTimeout
                hotspotMissing = if (!info.sameNetwork && !hotspot.isAddressUp(info.url)) hotspotMissing + 1 else 0
                if (idle || hotspotMissing >= 2) {
                    TransferLog.log(if (idle) "idle for too long, closing" else "hotspot went away, closing")
                    _finishRequests.tryEmit(Unit)
                    stop()
                    break
                }
            }
        }
    }

    private fun startServer(): UploadServer {
        var lastError: IOException? = null
        for (port in PORTS) {
            val srv = UploadServer(app, app.store, { hotspot.ssid }, port, serverListener)
            try {
                srv.start(SOCKET_TIMEOUT_MS, true)
                Log.i(TAG, "server listening on $port")
                return srv
            } catch (e: IOException) {
                lastError = e
            }
        }
        throw lastError ?: IOException("no free port")
    }

    private suspend fun stopServer() {
        val srv = server ?: return
        server = null
        withContext(Dispatchers.IO) { srv.stop() }
    }

    private fun url(ip: String, port: Int) = if (port == 80) "http://$ip" else "http://$ip:$port"

    companion object {
        private const val TAG = "TransferSession"
        private val PORTS = 8080..8090
        private const val SOCKET_TIMEOUT_MS = 30_000
        private const val IDLE_TIMEOUT_MS = 20 * 60_000L
        private const val WATCHDOG_INTERVAL_MS = 15_000L
    }
}
