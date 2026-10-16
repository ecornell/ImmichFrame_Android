package com.immichframe.immichframe

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageResponseTest {
    @Test fun metadataOnlyResponseNeedsASeparateByteFetch() {
        val response = Gson().fromJson(
            """{"randomImageBase64":"","thumbHashImageBase64":"aGFzaA==","photoDate":"10/15/2026",
               "imageLocation":"","assetId":"a2ec8a53-7494-475f-b42e-3e4f0e17de9d","assetType":0}""",
            Helpers.ImageResponse::class.java,
        )
        assertFalse(response.hasInlineImage)
        assertEquals("a2ec8a53-7494-475f-b42e-3e4f0e17de9d", response.assetId)
    }

    @Test fun olderServersStillInlineTheImage() {
        // A server without includeImage support ignores the parameter and sends Base64.
        val response = Gson().fromJson(
            """{"randomImageBase64":"/9j/4AAQ","thumbHashImageBase64":"","photoDate":"","imageLocation":""}""",
            Helpers.ImageResponse::class.java,
        )
        assertTrue(response.hasInlineImage)
    }
}
