package com.immichframe.immichframe

import android.app.ActivityManager
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Debug
import android.os.SystemClock
import org.json.JSONObject
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/** A health snapshot of the frame, reported to Home Assistant and the RPC state. */
data class FrameHealth(
    /** This app's proportional set size, as the WF1561 monitor measures it. */
    val appMemoryMb: Int?,
    val systemMemoryFreeMb: Int?,
    /** The hottest thermal zone; null on frames that expose none (the W10J). */
    val temperatureC: Double?,
    val wifiSignalDbm: Int?,
    val ipAddress: String?,
    val bootedAtMillis: Long,
    val appStartedAtMillis: Long,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("appMemoryMb", appMemoryMb ?: JSONObject.NULL)
        .put("systemMemoryFreeMb", systemMemoryFreeMb ?: JSONObject.NULL)
        .put("temperatureC", temperatureC ?: JSONObject.NULL)
        .put("wifiSignalDbm", wifiSignalDbm ?: JSONObject.NULL)
        .put("ipAddress", ipAddress ?: JSONObject.NULL)
        .put("bootedAt", CurrentPhoto.isoUtc(bootedAtMillis))
        .put("appStartedAt", CurrentPhoto.isoUtc(appStartedAtMillis))

    companion object {
        /**
         * The hottest of the raw thermal zone readings, in °C to one decimal. Kernels report
         * millidegrees, a few report whole degrees; unreadable or implausible values are skipped.
         */
        fun hottestCelsius(readings: List<String>): Double? = readings
            .mapNotNull { it.trim().toDoubleOrNull() }
            .map { if (it > 1_000) it / 1_000 else it }
            .filter { it in -40.0..150.0 }
            .maxOrNull()
            ?.let { Math.round(it * 10) / 10.0 }

        /** Rounds to the minute, so a value derived from two clocks does not change every sample. */
        fun toMinute(millis: Long): Long = (millis + 30_000) / 60_000 * 60_000

        /** Wi-Fi reports this RSSI when it has no signal reading. */
        internal const val NO_RSSI = -127
    }
}

/** Takes [FrameHealth] snapshots. Call [sample] off the main thread: the memory read is slow. */
internal class FrameHealthSampler(private val context: Context) {
    private val appStartedAtMillis = System.currentTimeMillis()

    val hasTemperature: Boolean get() = readThermalZones().isNotEmpty()

    fun sample(): FrameHealth {
        val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val systemMemory = ActivityManager.MemoryInfo().also(activityManager::getMemoryInfo)
        val appMemory = Debug.MemoryInfo().also(Debug::getMemoryInfo)
        return FrameHealth(
            appMemoryMb = appMemory.totalPss.takeIf { it > 0 }?.let { (it + 512) / 1_024 },
            systemMemoryFreeMb = (systemMemory.availMem / (1_024 * 1_024)).toInt(),
            temperatureC = FrameHealth.hottestCelsius(readThermalZones()),
            wifiSignalDbm = wifiSignal(),
            ipAddress = ipAddress(),
            bootedAtMillis = FrameHealth.toMinute(System.currentTimeMillis() - SystemClock.elapsedRealtime()),
            appStartedAtMillis = FrameHealth.toMinute(appStartedAtMillis),
        )
    }

    private fun readThermalZones(): List<String> =
        File("/sys/class/thermal").listFiles { file -> file.name.startsWith("thermal_zone") }
            .orEmpty()
            .mapNotNull { zone -> runCatching { File(zone, "temp").readText() }.getOrNull() }

    @Suppress("DEPRECATION") // connectionInfo still reports RSSI without location permission
    private fun wifiSignal(): Int? = runCatching {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        wifi.connectionInfo?.takeIf { it.networkId != -1 }?.rssi?.takeIf { it != FrameHealth.NO_RSSI }
    }.getOrNull()

    private fun ipAddress(): String? = runCatching {
        val interfaces = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            // Prefer Wi-Fi: frames have no other uplink, but a VPN or USB tether may appear too.
            .sortedBy { if (it.name.startsWith("wlan")) 0 else 1 }
        interfaces.firstNotNullOfOrNull { network ->
            network.inetAddresses.toList().firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
        }
    }.getOrNull()
}
