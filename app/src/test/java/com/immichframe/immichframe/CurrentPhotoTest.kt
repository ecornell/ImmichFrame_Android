package com.immichframe.immichframe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentPhotoTest {
    private fun image(
        assetId: String?,
        people: List<String>? = null,
        assetType: Int? = 0,
        durationSeconds: Double? = null,
        metadata: Boolean = true,
    ) = Helpers.ImageResponse(
        randomImageBase64 = "",
        thumbHashImageBase64 = "",
        photoDate = "07/04/2019",
        imageLocation = "Lake George,New York",
        assetId = assetId,
        assetType = assetType,
        durationSeconds = durationSeconds,
        metadata = if (!metadata) null else Helpers.ImageMetadata(
            takenAt = "2019-07-04T22:22:10+00:00",
            timeZone = "America/New_York",
            people = people,
            albums = listOf("Summer 2019"),
            city = "Lake George",
            isFavorite = true,
            immichServerUrl = "https://immich.example",
        ),
    )

    @Test fun singlePhotoReportsItsMetadata() {
        val photo = CurrentPhoto.from(listOf(image("a1", listOf("Mom"))), CurrentPhoto.SOURCE_SERVER, true, 0L)
        val json = photo.toJson()

        assertEquals("a1", json.getString("asset_id"))
        assertEquals("image", json.getString("media_type"))
        assertEquals("2019-07-04T22:22:10+00:00", json.getString("taken_at"))
        assertEquals("America/New_York", json.getString("time_zone"))
        assertEquals("Mom", json.getJSONArray("people").getString(0))
        assertEquals("Summer 2019", json.getJSONArray("albums").getString(0))
        assertEquals("Lake George,New York", json.getString("location"))
        assertTrue(json.getBoolean("favorite"))
        assertEquals("https://immich.example", json.getString("account"))
        assertEquals("single", json.getString("layout"))
        assertEquals(1, json.getJSONArray("slots").length())
        assertEquals("server", json.getString("source"))
        assertEquals("1970-01-01T00:00:00Z", json.getString("shown_at"))
        assertTrue(json.isNull("duration"))
        assertTrue(json.isNull("description"))
    }

    @Test fun splitSlideDescribesTheNewestPhotoAndListsBoth() {
        val photo = CurrentPhoto.from(
            listOf(image("left", listOf("Sam")), image("right", listOf("Mom", "Sam"))),
            CurrentPhoto.SOURCE_SERVER, true, 0L,
        )
        val json = photo.toJson()

        assertEquals("right", json.getString("asset_id"))
        assertEquals("split", json.getString("layout"))
        val slots = json.getJSONArray("slots")
        assertEquals("left", slots.getJSONObject(0).getString("asset_id"))
        assertEquals("Sam", slots.getJSONObject(0).getJSONArray("people").getString(0))
        assertEquals("right", slots.getJSONObject(1).getString("asset_id"))
    }

    @Test fun videoReportsItsDuration() {
        val photo = CurrentPhoto.from(
            listOf(image("v1", assetType = 1, durationSeconds = 12.5)), CurrentPhoto.SOURCE_SERVER, true, 0L,
        )
        assertEquals("video", photo.mediaType)
        assertEquals(12.5, photo.toJson().getDouble("duration"), 0.0)
    }

    @Test fun peopleAreLeftOutWhenNotIncluded() {
        val photo = CurrentPhoto.from(
            listOf(image("left", listOf("Sam")), image("right", listOf("Mom"))),
            CurrentPhoto.SOURCE_SERVER, false, 0L,
        )
        val json = photo.toJson().toString()
        assertFalse(json.contains("Mom"))
        assertFalse(json.contains("Sam"))
    }

    @Test fun cachedPhotoHasOnlyItsDisplayStrings() {
        val photo = CurrentPhoto.from(
            listOf(image(null, metadata = false)), CurrentPhoto.SOURCE_CACHE, true, 0L,
        )
        val json = photo.toJson()
        assertTrue(json.isNull("asset_id"))
        assertTrue(json.isNull("taken_at"))
        assertEquals(0, json.getJSONArray("people").length())
        assertEquals("07/04/2019", json.getString("photo_date"))
        assertEquals("cache", json.getString("source"))
    }

    @Test fun remoteStateIncludesTheCurrentPhoto() {
        val photo = CurrentPhoto.from(listOf(image("a1")), CurrentPhoto.SOURCE_SERVER, true, 0L)
        assertTrue(RemoteFrameState().toJson().isNull("currentPhoto"))
        val json: JSONObject = RemoteFrameState(currentPhoto = photo).toJson()
        assertEquals("a1", json.getJSONObject("currentPhoto").getString("asset_id"))
    }
}
