package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.json.JSONObject
import org.junit.Test

class FrameHealthTest {
    private val health = FrameHealth(
        appMemoryMb = 75,
        systemMemoryFreeMb = 412,
        temperatureC = 61.3,
        wifiSignalDbm = -56,
        ipAddress = "192.168.1.216",
        bootedAtMillis = 0L,
        appStartedAtMillis = 60_000L,
    )
    private val settings = MqttSettings("tcp://broker:1883", "", "", "0123456789abcdef")
    private val base = "immichframe/0123456789abcdef/health"

    @Test fun hottestZoneIsReportedInCelsius() {
        // The WF1561's soc-thermal and gpu-thermal zones, in millidegrees.
        assertEquals(61.3, FrameHealth.hottestCelsius(listOf("59545\n", "61250\n"))!!, 0.0)
        assertEquals(45.0, FrameHealth.hottestCelsius(listOf("45"))!!, 0.0)
        assertNull(FrameHealth.hottestCelsius(emptyList()))
        assertNull(FrameHealth.hottestCelsius(listOf("", "error", "-273000", "999999")))
    }

    @Test fun derivedTimesAreRoundedToTheMinute() {
        assertEquals(120_000L, FrameHealth.toMinute(119_999L))
        assertEquals(120_000L, FrameHealth.toMinute(149_999L))
        assertEquals(180_000L, FrameHealth.toMinute(150_000L))
    }

    @Test fun remoteStateIncludesHealthOnceSampled() {
        assertTrue(RemoteFrameState().toJson().isNull("health"))
        val json = RemoteFrameState(health = health).toJson().getJSONObject("health")
        assertEquals(75, json.getInt("appMemoryMb"))
        assertEquals(61.3, json.getDouble("temperatureC"), 0.0)
        assertEquals("1970-01-01T00:01:00Z", json.getString("appStartedAt"))
    }

    @Test fun healthEntitiesAreDiagnosticSensors() {
        val discovery = MqttPayloads.discovery(settings, "1.2.3")
        fun config(id: String) = JSONObject(discovery.getValue("homeassistant/sensor/immichframe_0123456789abcdef/$id/config"))
        for (id in listOf("app_memory", "free_memory", "wifi_signal", "ip_address", "last_boot", "app_started", "last_photo", "temperature")) {
            val config = config(id)
            assertEquals("diagnostic", config.getString("entity_category"))
            assertEquals("$base/$id", config.getString("state_topic"))
            assertEquals("immichframe_0123456789abcdef_$id", config.getString("unique_id"))
        }
        assertEquals("data_size", config("app_memory").getString("device_class"))
        assertEquals("MB", config("app_memory").getString("unit_of_measurement"))
        assertEquals("signal_strength", config("wifi_signal").getString("device_class"))
        assertEquals("timestamp", config("last_photo").getString("device_class"))
        assertEquals("°C", config("temperature").getString("unit_of_measurement"))
    }

    @Test fun framesWithoutAThermalZoneGetNoTemperatureEntity() {
        val noThermal = settings.copy(hasTemperature = false)
        assertEquals("", MqttPayloads.discovery(noThermal, "1.2.3")["homeassistant/sensor/immichframe_0123456789abcdef/temperature/config"])
        assertFalse(MqttPayloads.healthState(noThermal, health, null).containsKey("$base/temperature"))
    }

    @Test fun healthStateIsPublishedOnceSampled() {
        assertFalse(MqttPayloads.state(settings, RemoteFrameState()).keys.any { "/health/" in it })
        val state = MqttPayloads.state(settings, RemoteFrameState(health = health, lastImageSuccessMillis = 120_000L))
        assertEquals("75", state["$base/app_memory"])
        assertEquals("-56", state["$base/wifi_signal"])
        assertEquals("61.3", state["$base/temperature"])
        assertEquals("1970-01-01T00:02:00Z", state["$base/last_photo"])
        // Values a frame cannot read are unknown in Home Assistant.
        val unread = MqttPayloads.healthState(settings, health.copy(wifiSignalDbm = null, ipAddress = null), null)
        assertEquals("None", unread["$base/wifi_signal"])
        assertEquals("None", unread["$base/ip_address"])
        assertEquals("None", unread["$base/last_photo"])
    }
}
