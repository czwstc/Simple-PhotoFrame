package com.zweichen.photoframe.transfer

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import com.zweichen.photoframe.R
import com.zweichen.photoframe.util.RootShell
import com.zweichen.photoframe.util.RootWifi
import com.zweichen.photoframe.util.TransferLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.Inet4Address
import java.net.NetworkInterface
import kotlin.coroutines.resume

/**
 * Opens a Wi‑Fi network phones can join:
 * 1. with root: an open (password-less) soft AP via `cmd wifi start-softap`
 * 2. without root: the system's LocalOnlyHotspot (random SSID and password)
 * 3. otherwise: whatever network the frame is already on
 */
class HotspotController(context: Context) {
    sealed interface Result {
        data class Open(val ssid: String, val ip: String) : Result
        data class Secured(val ssid: String, val password: String, val ip: String) : Result
        data class SameNetwork(val ssid: String?, val ip: String) : Result
        data class Failed(val message: String) : Result
    }

    private val context = context.applicationContext
    private val wifi = this.context.getSystemService(WifiManager::class.java)
    private val prefs = this.context.getSharedPreferences("hotspot", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var rootSoftAp = false
    private var wifiWasEnabled = false
    private var wifiWasConnected = false
    private var macBefore: String? = null
    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null

    val ssid: String by lazy {
        val id = Settings.Secure.getString(this.context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        "PhotoFrame-" + id.takeLast(4).uppercase().ifEmpty { "0000" }
    }

    /** [useHotspot] false skips straight to the current network (debug builds use this for LAN testing). */
    suspend fun start(allowLocalOnly: Boolean, useHotspot: Boolean = true): Result = mutex.withLock {
        withContext(Dispatchers.IO) { doStart(allowLocalOnly, useHotspot) }
    }

    /** If the app died while our soft AP was up, the open hotspot would stay on forever; turn it off. */
    suspend fun cleanupAfterCrash() = mutex.withLock {
        if (!prefs.getBoolean(KEY_SOFT_AP_ACTIVE, false)) return@withLock
        withContext(Dispatchers.IO) {
            Log.i(TAG, "stopping soft AP left over from a previous run")
            RootShell.run("cmd wifi stop-softap")
            prefs.edit().putBoolean(KEY_SOFT_AP_ACTIVE, false).apply()
        }
    }

    suspend fun stop() = mutex.withLock {
        withContext(Dispatchers.IO) {
            reservation?.close()
            reservation = null
            if (rootSoftAp) {
                rootSoftAp = false
                Log.i(TAG, "stopping soft AP: ${RootShell.run("cmd wifi stop-softap").output}")
                prefs.edit().putBoolean(KEY_SOFT_AP_ACTIVE, false).apply()
                // Devices without STA+AP concurrency drop Wi‑Fi while the AP is up; most bring it back by themselves.
                if (wifiWasEnabled) restoreWifi()
            }
        }
    }

    /** The framework reconnects by itself once it sees the network again; scanning right away makes that quick. */
    private suspend fun restoreWifi() {
        delay(3_000)
        if (!wifi.isWifiEnabled) RootShell.run("cmd wifi set-wifi-enabled enabled")
        if (!wifiWasConnected) return
        val started = System.currentTimeMillis()
        while (System.currentTimeMillis() - started < RECONNECT_TIMEOUT_MS) {
            if (isWifiConnected()) {
                TransferLog.log("Wi‑Fi reconnected after ${(System.currentTimeMillis() - started) / 1000}s, mac ${currentMac()} (was $macBefore)")
                return
            }
            RootShell.run("cmd wifi start-scan")
            delay(5_000)
        }
        TransferLog.log("Wi‑Fi not reconnected within ${RECONNECT_TIMEOUT_MS / 1000}s")
    }

    private fun currentMac(): String? = RootShell.run("cat /sys/class/net/wlan0/address").output.trim().takeIf { it.count { c -> c == ':' } == 5 }

    /** The SSID the running soft AP really advertises, exactly as phones see it. */
    private fun advertisedSsid(): String? {
        val line = RootShell.run("dumpsys wifi | grep 'mApConfig.SoftApConfiguration.SSID'").output.lineSequence().firstOrNull()
        return line?.substringAfter("SSID:", "")?.trim()?.takeIf { it.isNotEmpty() && it != "null" }
    }

    private fun isWifiConnected() = RootShell.run("cmd wifi status").output.contains("connected to", ignoreCase = true)

    private suspend fun doStart(allowLocalOnly: Boolean, useHotspot: Boolean): Result {
        if (useHotspot && RootShell.isAvailable()) {
            val before = ipv4Addresses()
            wifiWasEnabled = wifi.isWifiEnabled
            wifiWasConnected = wifiWasEnabled && isWifiConnected()
            macBefore = currentMac()
            TransferLog.log("before soft AP: wifi enabled=$wifiWasEnabled connected=$wifiWasConnected mac=$macBefore")
            rootSoftAp = true
            prefs.edit().putBoolean(KEY_SOFT_AP_ACTIVE, true).commit()
            // Preferred: our own configuration through the Wi‑Fi service (exact SSID, no auto shutdown, factory MAC).
            val helper = RootShell.run("CLASSPATH=${context.applicationInfo.sourceDir} app_process /system/bin ${RootWifi::class.java.name} start $ssid")
            TransferLog.log("root helper: ${helper.output}")
            var started = helper.output.contains("result=true")
            if (!started) {
                val result = RootShell.run("cmd wifi start-softap $ssid open -b 2")
                TransferLog.log("start-softap: ${result.output}")
                started = result.isSuccess && result.output.contains("success", ignoreCase = true)
            }
            if (started) {
                val ip = awaitNewAddress(before, ADDRESS_TIMEOUT_MS)
                // Android 11's shell command quotes the SSID; show and encode whatever is really on air
                val advertised = advertisedSsid() ?: ssid
                if (ip != null) return Result.Open(advertised, ip)
                Log.w(TAG, "soft AP started but no address appeared")
            }
            RootShell.run("cmd wifi stop-softap")
            rootSoftAp = false
            prefs.edit().putBoolean(KEY_SOFT_AP_ACTIVE, false).apply()
        }

        if (useHotspot && allowLocalOnly) {
            startLocalOnly()?.let { return it }
        }

        currentNetworkAddress()?.let { return Result.SameNetwork(currentSsid(), it) }
        return Result.Failed(context.getString(R.string.transfer_no_network))
    }

    @SuppressLint("MissingPermission")
    private suspend fun startLocalOnly(): Result? {
        val before = ipv4Addresses()
        val credentials = withContext(Dispatchers.Main) {
            suspendCancellableCoroutine<Pair<String, String>?> { cont ->
                try {
                    wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                        override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                            reservation = res
                            if (cont.isActive) cont.resume(credentialsOf(res))
                        }

                        override fun onFailed(reason: Int) {
                            Log.w(TAG, "local only hotspot failed: $reason")
                            if (cont.isActive) cont.resume(null)
                        }
                    }, Handler(Looper.getMainLooper()))
                } catch (e: Exception) {
                    Log.w(TAG, "local only hotspot unavailable", e)
                    if (cont.isActive) cont.resume(null)
                }
            }
        } ?: return null
        val ip = awaitNewAddress(before, ADDRESS_TIMEOUT_MS) ?: return null
        return Result.Secured(credentials.first, credentials.second, ip)
    }

    @Suppress("DEPRECATION")
    private fun credentialsOf(res: WifiManager.LocalOnlyHotspotReservation): Pair<String, String>? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val config = res.softApConfiguration
            val name = config.ssid ?: return null
            name to config.passphrase.orEmpty()
        } else {
            val config = res.wifiConfiguration ?: return null
            config.SSID.trim('"') to config.preSharedKey.orEmpty().trim('"')
        }
    }

    /** Whether the host of [url] is still one of our addresses, i.e. the hotspot is still up. */
    fun isAddressUp(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore(':').substringBefore('/')
        return ipv4Addresses().any { it.second == host }
    }

    private suspend fun awaitNewAddress(before: Set<Pair<String, String>>, timeoutMs: Long): String? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            ipv4Addresses().firstOrNull { it !in before }?.let { return it.second }
            delay(500)
        }
        return null
    }

    private fun currentNetworkAddress(): String? =
        ipv4Addresses().firstOrNull { (name, _) -> name.startsWith("wlan") || name.startsWith("eth") }?.second

    @Suppress("DEPRECATION")
    @SuppressLint("MissingPermission")
    private fun currentSsid(): String? {
        val fromApi = try {
            wifi.connectionInfo?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == "<unknown ssid>" }
        } catch (e: Exception) {
            null
        }
        if (fromApi != null || !RootShell.isAvailable()) return fromApi
        // reading the SSID needs location permission; root can ask the Wi‑Fi service directly
        return Regex("connected to \"(.+?)\"").find(RootShell.run("cmd wifi status").output)?.groupValues?.get(1)
    }

    private fun ipv4Addresses(): Set<Pair<String, String>> = try {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { iface ->
                iface.inetAddresses.toList().filterIsInstance<Inet4Address>().map { iface.name to it.hostAddress.orEmpty() }
            }
            .toSet()
    } catch (e: Exception) {
        emptySet()
    }

    companion object {
        private const val TAG = "Hotspot"
        private const val ADDRESS_TIMEOUT_MS = 20_000L
        private const val RECONNECT_TIMEOUT_MS = 60_000L
        private const val KEY_SOFT_AP_ACTIVE = "soft_ap_active"
    }
}
