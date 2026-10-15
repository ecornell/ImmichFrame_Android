package com.immichframe.immichframe

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeVideoTest {
    @Test fun fitLetterboxesAndCropFillsTheView() {
        // A 16:9 video on a 4:3 view: fit shrinks height, crop widens width.
        val (fitX, fitY) = VideoScaling.scale(1024, 768, 1920, 1080, crop = false)
        assertEquals(1f, fitX, 0.001f)
        assertEquals(0.75f, fitY, 0.001f)
        val (cropX, cropY) = VideoScaling.scale(1024, 768, 1920, 1080, crop = true)
        assertEquals(1.333f, cropX, 0.001f)
        assertEquals(1f, cropY, 0.001f)
    }

    @Test fun unknownVideoSizeLeavesTheViewUntransformed() {
        assertEquals(1f to 1f, VideoScaling.scale(1024, 768, 0, 0, crop = false))
        assertEquals(1f to 1f, VideoScaling.scale(0, 0, 1920, 1080, crop = true))
    }

    @Test fun watchdogUsesServerDurationThenDecoderDuration() {
        assertEquals(20_201L + VideoPlaybackPolicy.STARTUP_GRACE_MILLIS,
            VideoPlaybackPolicy.startupWatchdogMillis(20.201, intervalSeconds = 10))
        assertEquals(45_000L + VideoPlaybackPolicy.STALL_GRACE_MILLIS,
            VideoPlaybackPolicy.playingWatchdogMillis(45_000, intervalSeconds = 10))
    }

    @Test fun watchdogFallsBackToTheIntervalWithoutADuration() {
        assertEquals(10_000L + VideoPlaybackPolicy.STARTUP_GRACE_MILLIS,
            VideoPlaybackPolicy.startupWatchdogMillis(null, intervalSeconds = 10))
        assertEquals(10_000L + VideoPlaybackPolicy.STARTUP_GRACE_MILLIS,
            VideoPlaybackPolicy.startupWatchdogMillis(0.0, intervalSeconds = 10))
        assertEquals(10_000L + VideoPlaybackPolicy.STALL_GRACE_MILLIS,
            VideoPlaybackPolicy.playingWatchdogMillis(-1, intervalSeconds = 10))
    }

    @Test fun videoUrlTargetsTheRangedAssetEndpoint() {
        assertEquals(
            "http://192.168.1.24:8080/api/Asset/a2ec8a53-7494-475f-b42e-3e4f0e17de9d/Asset?assetType=VIDEO",
            Helpers.videoUrl("http://192.168.1.24:8080/", "a2ec8a53-7494-475f-b42e-3e4f0e17de9d"),
        )
        assertEquals(
            "https://frame.example/sub/api/Asset/id/Asset?assetType=VIDEO",
            Helpers.videoUrl("https://frame.example/sub/", "id"),
        )
    }

    @Test fun serverVideoResponseIsRecognised() {
        val response = Gson().fromJson(
            """{"randomImageBase64":"","thumbHashImageBase64":"","photoDate":"","imageLocation":"",
               "assetId":"a2ec8a53-7494-475f-b42e-3e4f0e17de9d","assetType":1,"durationSeconds":20.201}""",
            Helpers.ImageResponse::class.java,
        )
        assertTrue(response.isVideo)
        assertEquals(20.201, response.durationSeconds!!, 0.0001)
    }

    @Test fun imagesAndOlderServersAreNotVideos() {
        val image = Gson().fromJson(
            """{"randomImageBase64":"","thumbHashImageBase64":"","photoDate":"","imageLocation":"",
               "assetId":"id","assetType":0,"durationSeconds":null}""",
            Helpers.ImageResponse::class.java,
        )
        assertFalse(image.isVideo)
        val legacy = Gson().fromJson(
            """{"randomImageBase64":"","thumbHashImageBase64":"","photoDate":"","imageLocation":""}""",
            Helpers.ImageResponse::class.java,
        )
        assertFalse(legacy.isVideo)
        assertNull(legacy.assetId)
    }
}
