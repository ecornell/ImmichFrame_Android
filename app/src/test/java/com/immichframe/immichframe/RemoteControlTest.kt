package com.immichframe.immichframe

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteControlTest {
    private class RecordingCommands(private val state: RemoteStateStore) : RemoteCommands {
        var nextCount = 0
        var refreshCount = 0
        val notifications = mutableListOf<FrameNotification>()
        val cameras = mutableListOf<CameraRequest>()
        var dismissCount = 0
        override fun setDisplayActive(active: Boolean) { state.update { it.copy(displayActive = active) } }
        override fun setPaused(paused: Boolean) { state.update { it.copy(paused = paused) } }
        override fun togglePaused() = setPaused(!state.snapshot().paused)
        override fun setBrightness(brightness: Float?) { state.update { it.copy(brightness = brightness) } }
        override fun next() { nextCount++ }
        override fun previous() = Unit
        override fun refresh() { refreshCount++ }
        override fun openSettings() = Unit
        override fun notify(notification: FrameNotification) { notifications += notification }
        var cameraAccepted = true
        override fun showCamera(request: CameraRequest): Boolean {
            cameras += request
            return cameraAccepted
        }
        override fun dismissOverlays() { dismissCount++ }
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

    @Test fun notifyRouteShowsAValidNotificationAndRejectsOthers() {
        val state = RemoteStateStore()
        val commands = RecordingCommands(state)
        val router = RemoteApiRouter(state, commands)

        assertEquals(200, router.route("POST", "/api/v1/actions/notify", """{"message":"Washer done","title":"Laundry"}""").status)
        assertEquals(FrameNotification("Washer done", "Laundry", FrameNotification.DEFAULT_SECONDS), commands.notifications.single())
        assertEquals(400, router.route("POST", "/api/v1/actions/notify", "{}").status)
        assertEquals(400, router.route("POST", "/api/v1/actions/notify", "Washer done").status)
        assertEquals(1, commands.notifications.size)
        val capabilities = router.route("GET", "/api/v1/info", null).json.get("capabilities")
        // On a device a Kotlin list would serialize as one string; the JVM org.json used here
        // would wrap it either way, so this documents the type rather than catching a regression.
        assertTrue(capabilities is JSONArray)
        assertTrue((capabilities as JSONArray).toString().contains("\"notify\""))
    }

    @Test fun cameraAndDismissRoutesDispatchValidCommands() {
        val state = RemoteStateStore()
        val commands = RecordingCommands(state)
        val router = RemoteApiRouter(state, commands)

        assertEquals(200, router.route("POST", "/api/v1/actions/camera", """{"url":"/api/camera_proxy/camera.door?token=t"}""").status)
        assertEquals("/api/camera_proxy/camera.door?token=t", commands.cameras.single().url)
        assertEquals(400, router.route("POST", "/api/v1/actions/camera", "{}").status)
        // A URL outside the configured Home Assistant URL is refused, not silently dropped.
        commands.cameraAccepted = false
        val refused = router.route("POST", "/api/v1/actions/camera", """{"url":"http://other.host/x.jpg"}""")
        assertEquals(400, refused.status)
        assertTrue(refused.json.getString("error").contains("Home Assistant URL"))
        assertEquals(200, router.route("POST", "/api/v1/actions/dismiss", "{}").status)
        assertEquals(1, commands.dismissCount)
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
        val notify = JSONObject(discovery.getValue("homeassistant/notify/immichframe_0123456789abcdef/notification/config"))
        assertEquals("immichframe/0123456789abcdef/notify", notify.getString("command_topic"))
        assertEquals("immichframe_0123456789abcdef_notification", notify.getString("unique_id"))
        val dismiss = JSONObject(discovery.getValue("homeassistant/button/immichframe_0123456789abcdef/dismiss/config"))
        assertEquals("immichframe/0123456789abcdef/action/dismiss", dismiss.getString("command_topic"))
        assertTrue(joined.contains("immichframe_0123456789abcdef_display"))
        assertFalse(joined.contains("top-secret"))
        assertFalse(joined.contains("frame-user"))
        assertFalse(joined.contains("ssl://broker"))
    }

    @Test fun mqttPhotoEntitiesAreDiscoveredAndPublishedOnceAPhotoIsShown() {
        val settings = MqttSettings("tcp://broker:1883", "", "", "0123456789abcdef")
        val base = settings.baseTopic
        val discovery = MqttPayloads.discovery(settings, "1.2.3")
        val photoConfig = JSONObject(discovery.getValue(
            "homeassistant/sensor/immichframe_0123456789abcdef/current_photo/config"))
        assertEquals("immichframe_0123456789abcdef_current_photo", photoConfig.getString("unique_id"))
        assertEquals("$base/photo/state", photoConfig.getString("state_topic"))
        assertEquals("$base/photo/attributes", photoConfig.getString("json_attributes_topic"))
        val takenConfig = JSONObject(discovery.getValue(
            "homeassistant/sensor/immichframe_0123456789abcdef/photo_taken_at/config"))
        assertEquals("timestamp", takenConfig.getString("device_class"))
        assertEquals("immichframe_0123456789abcdef_photo_taken_at", takenConfig.getString("unique_id"))

        assertFalse(MqttPayloads.state(settings, RemoteFrameState()).containsKey("$base/photo/state"))
        // A cached photo has no asset id or capture time; "None" makes the sensors unknown.
        val cached = CurrentPhoto.from(
            listOf(Helpers.ImageResponse("", "", "07/04/2019", "")), CurrentPhoto.SOURCE_CACHE, true, 0L,
        )
        val state = MqttPayloads.state(settings, RemoteFrameState(currentPhoto = cached))
        assertEquals("None", state["$base/photo/state"])
        assertEquals("None", state["$base/photo/taken_at"])
        assertEquals("cache", JSONObject(state.getValue("$base/photo/attributes")).getString("source"))
    }

    @Test fun disabledPhotoReportingRemovesTheEntitiesAndTheLastPhoto() {
        val settings = MqttSettings("tcp://broker:1883", "", "", "0123456789abcdef", publishPhoto = false)
        val base = settings.baseTopic
        val discovery = MqttPayloads.discovery(settings, "1.2.3")
        assertEquals("", discovery["homeassistant/sensor/immichframe_0123456789abcdef/current_photo/config"])
        assertEquals("", discovery["homeassistant/sensor/immichframe_0123456789abcdef/photo_taken_at/config"])

        val photo = CurrentPhoto.from(
            listOf(Helpers.ImageResponse("", "", "", "", assetId = "a1")), CurrentPhoto.SOURCE_SERVER, true, 0L,
        )
        assertFalse(MqttPayloads.state(settings, RemoteFrameState(currentPhoto = photo)).keys.any { "/photo/" in it })
        assertEquals(
            mapOf(
                "$base/photo/state" to "", "$base/photo/taken_at" to "", "$base/photo/attributes" to "",
                "$base/photo/image" to "",
            ),
            MqttPayloads.photoCleanup(settings),
        )
        // The image entity follows photo reporting, whatever its own setting.
        val imageOnly = settings.copy(photoImage = PhotoImageMode.THUMBNAIL)
        assertEquals("", MqttPayloads.discovery(imageOnly, "1.2.3")[IMAGE_CONFIG])
    }

    @Test fun photoImageEntityIsOptInAndClearedWhenOff() {
        val off = MqttSettings("tcp://broker:1883", "", "", "0123456789abcdef")
        val base = off.baseTopic
        assertEquals(PhotoImageMode.OFF, off.photoImage)
        assertEquals("", MqttPayloads.discovery(off, "1.2.3")[IMAGE_CONFIG])
        // Reporting the photo but not its image clears only a previously published image.
        assertEquals(mapOf("$base/photo/image" to ""), MqttPayloads.photoCleanup(off))

        for (mode in listOf(PhotoImageMode.BLURRED, PhotoImageMode.THUMBNAIL)) {
            val on = off.copy(photoImage = mode)
            val config = JSONObject(MqttPayloads.discovery(on, "1.2.3").getValue(IMAGE_CONFIG))
            assertEquals("immichframe_0123456789abcdef_current_photo_image", config.getString("unique_id"))
            assertEquals("$base/photo/image", config.getString("image_topic"))
            assertEquals("image/jpeg", config.getString("content_type"))
            assertTrue(MqttPayloads.photoCleanup(on).isEmpty())
        }
    }

    @Test fun photoImageModeParsesStoredValuesAndDefaultsToOff() {
        assertEquals(PhotoImageMode.THUMBNAIL, PhotoImageMode.parse("THUMBNAIL"))
        assertEquals(PhotoImageMode.BLURRED, PhotoImageMode.parse("BLURRED"))
        assertEquals(PhotoImageMode.OFF, PhotoImageMode.parse(null))
        assertEquals(PhotoImageMode.OFF, PhotoImageMode.parse("thumbnail"))
    }

    @Test fun retainedCommandsAreRejectedButHomeAssistantBirthIsAccepted() {
        assertFalse(shouldAcceptMqttMessage("immichframe/device/action/next", retained = true))
        assertFalse(shouldAcceptMqttMessage("immichframe/device/display/set", retained = true))
        assertTrue(shouldAcceptMqttMessage("immichframe/device/action/next", retained = false))
        assertTrue(shouldAcceptMqttMessage("homeassistant/status", retained = true))
    }

    @Test fun limitCheckDoesNotRecordAnAttempt() {
        val limits = ClientRateLimiters(limit = 2, windowMillis = 100)
        assertFalse(limits.isLimited("a", 0))
        assertTrue(limits.allow("a", 0))
        assertFalse(limits.isLimited("a", 1))
        assertTrue(limits.allow("a", 1))
        assertTrue(limits.isLimited("a", 2))
        assertFalse(limits.isLimited("a", 100))
        assertFalse(limits.isLimited("b", 2))
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

    private companion object {
        const val IMAGE_CONFIG = "homeassistant/image/immichframe_0123456789abcdef/current_photo_image/config"
    }
}
