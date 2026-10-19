package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CameraRequestTest {
    private val base = "http://homeassistant.local:8123"
    private val picture = "/api/camera_proxy/camera.front_door?token=abc123"

    @Test fun jsonCommandCarriesTitleTimingAndWake() {
        val request = CameraRequest.parse(
            """{"url":"$picture","title":"Front door","duration":60,"refresh":2,"wake":true}""",
        )!!
        assertEquals(picture, request.url)
        assertEquals("Front door", request.title)
        assertEquals(60, request.durationSeconds)
        assertEquals(2, request.refreshSeconds)
        assertTrue(request.wake)
    }

    @Test fun bareUrlUsesTheDefaults() {
        val request = CameraRequest.parse("  $picture ")!!
        assertEquals(picture, request.url)
        assertNull(request.title)
        assertEquals(CameraRequest.DEFAULT_SECONDS, request.durationSeconds)
        assertEquals(0, request.refreshSeconds)
        assertFalse(request.wake)
    }

    @Test fun invalidCommandsAreRejected() {
        assertNull(CameraRequest.parse(""))
        assertNull(CameraRequest.parse("{not json"))
        assertNull(CameraRequest.parse("""{"title":"No url"}"""))
        assertNull(CameraRequest.parse("""{"url":42}"""))
        assertFalse(CameraRequest.parse("""{"url":"/x","wake":"true"}""")!!.wake)
    }

    @Test fun timingIsBoundedByTheCameraTokenLifetime() {
        val long = CameraRequest.parse("""{"url":"/x","duration":3600,"refresh":600}""")!!
        assertEquals(CameraRequest.MAX_SECONDS, long.durationSeconds)
        assertEquals(CameraRequest.MAX_REFRESH_SECONDS, long.refreshSeconds)
        val short = CameraRequest.parse("""{"url":"/x","duration":1,"refresh":-5}""")!!
        assertEquals(CameraRequest.MIN_SECONDS, short.durationSeconds)
        assertEquals(0, short.refreshSeconds)
    }

    @Test fun relativeAndSameOriginUrlsResolveAgainstHomeAssistant() {
        assertEquals("$base$picture", CameraRequest.resolve(base, picture).toString())
        assertEquals("$base$picture", CameraRequest.resolve("$base/", "$base$picture").toString())
    }

    @Test fun otherOriginsAreNeverFetched() {
        assertNull(CameraRequest.resolve(base, "http://frigate.local:5000/api/front/latest.jpg"))
        assertNull(CameraRequest.resolve(base, "//evil.example/x.jpg"))
        assertNull(CameraRequest.resolve(base, "https://homeassistant.local:8123$picture"))
        assertNull(CameraRequest.resolve(base, "http://homeassistant.local:9999$picture"))
        assertNull(CameraRequest.resolve(base, "file:///sdcard/x.jpg"))
        // Without a configured Home Assistant URL nothing is fetched.
        assertNull(CameraRequest.resolve("", picture))
        assertNull(CameraRequest.resolve("not a url", picture))
    }
}
