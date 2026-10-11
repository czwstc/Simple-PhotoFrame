package com.zweichen.photoframe.util

import android.net.MacAddress
import android.os.IBinder

/**
 * Entry point run as root through `app_process` (see [HotspotController][com.zweichen.photoframe.transfer.HotspotController]).
 *
 * `cmd wifi start-softap` on Android 11 wraps the SSID in literal quotes and keeps the 10 minute auto shutdown,
 * so we build the soft AP configuration ourselves and hand it to the Wi‑Fi service. Root passes its permission checks.
 *
 * Usage: `app_process /system/bin com.zweichen.photoframe.util.RootWifi start <ssid>`
 * Prints `result=true|false` and the BSSID used.
 */
object RootWifi {
    private const val BAND_2GHZ = 1

    @JvmStatic
    fun main(args: Array<String>) {
        try {
            when (args.getOrNull(0)) {
                "start" -> start(args.getOrNull(1) ?: error("missing ssid"))
                else -> println("usage: start <ssid>")
            }
        } catch (e: Throwable) {
            println("error=${e.cause ?: e}")
        }
    }

    private fun start(ssid: String) {
        val wifi = wifiService()
        // Using the factory MAC for the AP means the client interface still has it once the AP is gone; otherwise
        // some drivers keep the AP's random MAC and the router hands the frame a new IP after every transfer.
        val factoryMac = runCatching {
            @Suppress("UNCHECKED_CAST")
            (wifi.javaClass.getMethod("getFactoryMacAddresses").invoke(wifi) as Array<String>).firstOrNull()
        }.getOrNull()

        val started = factoryMac?.let { startTethered(wifi, ssid, it) } ?: false
        if (started) {
            println("result=true bssid=$factoryMac")
        } else {
            println("result=${startTethered(wifi, ssid, null)} bssid=random")
        }
    }

    private fun startTethered(wifi: Any, ssid: String, bssid: String?): Boolean {
        val builderClass = Class.forName("android.net.wifi.SoftApConfiguration\$Builder")
        val builder = builderClass.getConstructor().newInstance()
        builderClass.getMethod("setSsid", String::class.java).invoke(builder, ssid)
        builderClass.getMethod("setBand", Int::class.javaPrimitiveType).invoke(builder, BAND_2GHZ)
        builderClass.getMethod("setAutoShutdownEnabled", Boolean::class.javaPrimitiveType).invoke(builder, false)
        if (bssid != null) {
            builderClass.getMethod("setBssid", MacAddress::class.java).invoke(builder, MacAddress.fromString(bssid))
        }
        val config = builderClass.getMethod("build").invoke(builder)
        val method = wifi.javaClass.getMethod("startTetheredHotspot", Class.forName("android.net.wifi.SoftApConfiguration"))
        return method.invoke(wifi, config) as Boolean
    }

    private fun wifiService(): Any {
        val binder = Class.forName("android.os.ServiceManager").getMethod("getService", String::class.java).invoke(null, "wifi") as IBinder
        return Class.forName("android.net.wifi.IWifiManager\$Stub").getMethod("asInterface", IBinder::class.java).invoke(null, binder)!!
    }
}
