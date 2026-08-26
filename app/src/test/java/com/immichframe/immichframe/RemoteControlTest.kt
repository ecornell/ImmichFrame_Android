package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlTest {
    private class RecordingCommands(private val state: RemoteStateStore) : RemoteCommands {
        var nextCount = 0
        var refreshCount = 0
        override fun setDisplayActive(active: Boolean) { state.update { it.copy(displayActive = active) } }
        override fun setPaused(paused: Boolean) { state.update { it.copy(paused = paused) } }
        override fun togglePaused() = setPaused(!state.snapshot().paused)
        override fun setBrightness(brightness: Float?) { state.update { it.copy(brightness = brightness) } }
        override fun next() { nextCount++ }
        override fun previous() = Unit
        override fun refresh() { refreshCount++ }
        override fun openSettings() = Unit
    }

    @Test fun routesVersionedStateAndIdempotentControls() {
        val state = RemoteStateStore(RemoteFrameState(version = "1.2.3"))
        val commands = RecordingCommands(state)
        val router = RemoteApiRouter(state, commands)

        assertEquals(200, router.route("GET", "/api/v1/state", null).status)
        assertEquals(200, router.route("PUT", "/api/v1/display", "{\"active\":false}").status)
        assertFalse(state.snapshot().displayActive)
        assertEquals(200, router.route("PUT", "/api/v1/playback", "{\"paused\":true}").status)
        assertTrue(state.snapshot().paused)
        assertEquals(200, router.route("PUT", "/api/v1/brightness", "{\"brightness\":0.4}").status)
        assertEquals(0.4f, state.snapshot().brightness!!, 0.001f)
        assertEquals(200, router.route("POST", "/api/v1/actions/next", "{}").status)
        assertEquals(1, commands.nextCount)
    }

    @Test fun rejectsMalformedAndOutOfRangeInputs() {
        val state = RemoteStateStore()
        val router = RemoteApiRouter(state, RecordingCommands(state))
        assertEquals(400, router.route("PUT", "/api/v1/display", "not-json").status)
        assertEquals(400, router.route("PUT", "/api/v1/brightness", "{\"brightness\":2}").status)
        assertEquals(404, router.route("POST", "/api/v1/actions/reboot", "{}").status)
    }

    @Test fun mqttDiscoveryUsesStableIdsAndNeverLeaksCredentials() {
        val settings = MqttSettings(
            brokerUri = "ssl://broker:8883",
            username = "frame-user",
            password = "top-secret",
            deviceId = "0123456789abcdef",
        )
        val discovery = MqttPayloads.discovery(settings, "1.2.3")
        val joined = discovery.entries.joinToString("\n") { "${it.key} ${it.value}" }

        assertTrue(discovery.keys.any { it.contains("/switch/") && it.endsWith("/display/config") })
        assertTrue(discovery.keys.any { it.contains("/button/") && it.endsWith("/next/config") })
        assertTrue(joined.contains("immichframe_0123456789abcdef_display"))
        assertFalse(joined.contains("top-secret"))
        assertFalse(joined.contains("frame-user"))
        assertFalse(joined.contains("ssl://broker"))
    }

    @Test fun clientRateLimitsAreIndependentAndBounded() {
        val limits = ClientRateLimiters(limit = 1, windowMillis = 100, maxClients = 2)
        assertTrue(limits.allow("one", 0))
        assertFalse(limits.allow("one", 1))
        assertTrue(limits.allow("two", 1))
        assertTrue(limits.allow("three", 1))
        // "one" was the least recently used entry and was evicted.
        assertTrue(limits.allow("one", 2))
    }
}
