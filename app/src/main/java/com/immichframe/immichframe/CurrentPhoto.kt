package com.immichframe.immichframe

import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** One photo of the slide on screen; a split slide has two. */
data class PhotoSlot(val assetId: String?, val people: List<String>)

/**
 * What the frame is showing, reported to Home Assistant and the REST state. The primary fields
 * describe the newest photo of the slide; [slots] lists every photo on screen.
 */
data class CurrentPhoto(
    val assetId: String?,
    val mediaType: String,
    val durationSeconds: Double?,
    val takenAt: String?,
    val timeZone: String?,
    val photoDate: String,
    val location: String,
    val city: String?,
    val state: String?,
    val country: String?,
    val description: String?,
    val people: List<String>,
    val albums: List<String>,
    val favorite: Boolean?,
    val account: String?,
    val slots: List<PhotoSlot>,
    val source: String,
    val shownAtMillis: Long,
) {
    val layout: String get() = if (slots.size > 1) "split" else "single"

    fun toJson(): JSONObject = JSONObject()
        .put("asset_id", assetId ?: JSONObject.NULL)
        .put("media_type", mediaType)
        .put("duration", durationSeconds ?: JSONObject.NULL)
        .put("taken_at", takenAt ?: JSONObject.NULL)
        .put("time_zone", timeZone ?: JSONObject.NULL)
        .put("photo_date", photoDate)
        .put("location", location)
        .put("city", city ?: JSONObject.NULL)
        .put("state", state ?: JSONObject.NULL)
        .put("country", country ?: JSONObject.NULL)
        .put("description", description ?: JSONObject.NULL)
        .put("people", JSONArray(people))
        .put("albums", JSONArray(albums))
        .put("favorite", favorite ?: JSONObject.NULL)
        .put("account", account ?: JSONObject.NULL)
        .put("layout", layout)
        .put("slots", JSONArray(slots.map { slot ->
            JSONObject().put("asset_id", slot.assetId ?: JSONObject.NULL).put("people", JSONArray(slot.people))
        }))
        .put("source", source)
        .put("shown_at", isoUtc(shownAtMillis))

    companion object {
        const val SOURCE_SERVER = "server"
        /** Shown from the offline cache, which keeps no asset id or metadata. */
        const val SOURCE_CACHE = "cache"

        /** [images] are in slide order, so the last one is the newest. */
        fun from(
            images: List<Helpers.ImageResponse>,
            source: String,
            includePeople: Boolean,
            shownAtMillis: Long,
        ): CurrentPhoto {
            val primary = images.last()
            val metadata = primary.metadata
            fun peopleOf(image: Helpers.ImageResponse) =
                if (includePeople) image.metadata?.people.orEmpty() else emptyList()
            return CurrentPhoto(
                assetId = primary.assetId?.takeIf(String::isNotBlank),
                mediaType = if (primary.isVideo) "video" else "image",
                durationSeconds = primary.durationSeconds.takeIf { primary.isVideo },
                takenAt = metadata?.takenAt,
                timeZone = metadata?.timeZone,
                photoDate = primary.photoDate,
                location = primary.imageLocation,
                city = metadata?.city,
                state = metadata?.state,
                country = metadata?.country,
                description = metadata?.description,
                people = peopleOf(primary),
                albums = metadata?.albums.orEmpty(),
                favorite = metadata?.isFavorite,
                account = metadata?.immichServerUrl,
                slots = images.map { PhotoSlot(it.assetId?.takeIf(String::isNotBlank), peopleOf(it)) },
                source = source,
                shownAtMillis = shownAtMillis,
            )
        }

        // java.time needs API 26 and minSdk is 23. A literal Z keeps the offset parseable
        // everywhere, which pattern X (API 24) would not on the oldest devices.
        internal fun isoUtc(millis: Long): String =
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(Date(millis))
    }
}
