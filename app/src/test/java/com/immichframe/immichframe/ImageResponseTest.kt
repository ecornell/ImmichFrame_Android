package com.immichframe.immichframe

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    @Test fun requestedMetadataIsParsedAndAbsentMetadataIsNull() {
        val response = Gson().fromJson(
            """{"randomImageBase64":"","thumbHashImageBase64":"","photoDate":"07/04/2019","imageLocation":"",
               "assetId":"8f3c","metadata":{"takenAt":"2019-07-04T22:22:10+00:00","people":["Mom"],
               "albums":["Summer 2019"],"isFavorite":true,"country":null}}""",
            Helpers.ImageResponse::class.java,
        )
        val metadata = response.metadata!!
        assertEquals("2019-07-04T22:22:10+00:00", metadata.takenAt)
        assertEquals(listOf("Mom"), metadata.people)
        assertEquals(listOf("Summer 2019"), metadata.albums)
        assertEquals(true, metadata.isFavorite)
        // Keys an older server never sends stay null rather than taking the Kotlin defaults.
        assertNull(metadata.description)

        val older = Gson().fromJson(
            """{"randomImageBase64":"","thumbHashImageBase64":"","photoDate":"","imageLocation":"","assetId":"8f3c"}""",
            Helpers.ImageResponse::class.java,
        )
        assertNull(older.metadata)
    }

    @Test fun serverUrlsWithoutASchemeAreRejectedInsteadOfCrashing() {
        assertTrue(Helpers.isValidServerUrl("http://192.168.1.24:8080"))
        assertTrue(Helpers.isValidServerUrl("https://frame.example/sub/"))
        assertFalse(Helpers.isValidServerUrl("192.168.1.24:8080"))
        assertFalse(Helpers.isValidServerUrl(""))
    }
}
