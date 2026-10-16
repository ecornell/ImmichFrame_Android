package com.immichframe.immichframe

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.util.Base64
import retrofit2.Call
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query
import okhttp3.ResponseBody
import androidx.core.graphics.scale
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.Calendar
import java.util.concurrent.TimeUnit

object Helpers {
    fun cssFontSizeToSp(cssSize: String?, context: Context, baseFontSizePx: Float = 16f): Float {
        val resources = context.resources
        val displayMetrics = resources.displayMetrics
        val fontScale = resources.configuration.fontScale
        val density = displayMetrics.density

        // Handle null cssSize
        val effectiveCssSize = cssSize ?: "medium"

        return when {
            effectiveCssSize.equals("xx-small", ignoreCase = true) -> 8f * fontScale
            effectiveCssSize.equals("x-small", ignoreCase = true) -> 10f * fontScale
            effectiveCssSize.equals("small", ignoreCase = true) -> 12f * fontScale
            effectiveCssSize.equals("medium", ignoreCase = true) -> 16f * fontScale
            effectiveCssSize.equals("large", ignoreCase = true) -> 20f * fontScale
            effectiveCssSize.equals("x-large", ignoreCase = true) -> 24f * fontScale
            effectiveCssSize.equals("xx-large", ignoreCase = true) -> 32f * fontScale

            effectiveCssSize.endsWith("px", ignoreCase = true) -> {
                val px = effectiveCssSize.removeSuffix("px").toFloatOrNull() ?: baseFontSizePx
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("pt", ignoreCase = true) -> {
                val pt = effectiveCssSize.removeSuffix("pt").toFloatOrNull() ?: baseFontSizePx
                val px = pt * (density * 160f / 72f)
                px / (density * fontScale)
            }

            effectiveCssSize.endsWith("em", ignoreCase = true) -> {
                val em = effectiveCssSize.removeSuffix("em").toFloatOrNull() ?: 1f
                val px = em * baseFontSizePx
                px / (density * fontScale)
            }

            else -> 16f * fontScale
        }
    }

    fun mergeImages(leftImage: Bitmap, rightImage: Bitmap, lineColor: Int): Bitmap {
        val lineWidth = 10
        val targetHeight = maxOf(leftImage.height, rightImage.height) // Use max height

        val totalWidth = leftImage.width + rightImage.width + lineWidth

        val result = Bitmap.createBitmap(totalWidth, targetHeight, Bitmap.Config.RGB_565)
        val canvas = Canvas(result)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        canvas.drawBitmap(leftImage, 0f, 0f, paint)

        // Draw dividing line
        paint.color = lineColor
        canvas.drawRect(
            leftImage.width.toFloat(), // Line starts after left image
            0f, (leftImage.width + lineWidth).toFloat(), targetHeight.toFloat(), paint
        )

        canvas.drawBitmap(rightImage, (leftImage.width + lineWidth).toFloat(), 0f, paint)

        return result
    }

    fun decodeBitmapFromBytes(data: String, maxDimension: Int = Int.MAX_VALUE): Bitmap {
        val decodedImage = Base64.decode(data, Base64.DEFAULT)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(decodedImage, 0, decodedImage.size, bounds)
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = if (maxDimension == Int.MAX_VALUE) 1 else {
                decodeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
        }
        return requireNotNull(BitmapFactory.decodeByteArray(decodedImage, 0, decodedImage.size, options)) {
            "Invalid image payload"
        }
    }

    fun imageDimensions(data: String): Pair<Int, Int> {
        val decoded = Base64.decode(data, Base64.DEFAULT)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(decoded, 0, decoded.size, options)
        return options.outWidth.coerceAtLeast(1) to options.outHeight.coerceAtLeast(1)
    }

    fun reduceBitmapQuality(bitmap: Bitmap, maxSize: Int = 1000): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        val (newWidth, newHeight) = scaledDimensions(width, height, maxSize)
        if (newWidth == width && newHeight == height) return bitmap
        return bitmap.scale(newWidth, newHeight)
    }

    fun scaledDimensions(width: Int, height: Int, maxSize: Int): Pair<Int, Int> {
        require(width > 0 && height > 0 && maxSize > 0)
        if (width <= maxSize && height <= maxSize) return width to height
        val factor = maxSize.toFloat() / maxOf(width, height)
        return (width * factor).toInt().coerceAtLeast(1) to
            (height * factor).toInt().coerceAtLeast(1)
    }

    data class ImageResponse(
        val randomImageBase64: String,
        val thumbHashImageBase64: String,
        val photoDate: String,
        val imageLocation: String,
        // Present only on servers that support includeVideos; for a video the image is its poster.
        val assetId: String? = null,
        val assetType: Int? = null,
        val durationSeconds: Double? = null,
    ) {
        val isVideo: Boolean get() = assetType == ASSET_TYPE_VIDEO && !assetId.isNullOrBlank()

        /** False when the client asked to fetch the image bytes separately and the server agreed. */
        val hasInlineImage: Boolean get() = randomImageBase64.isNotEmpty()
    }

    // Immich AssetTypeEnum ordinal as serialized by the ImmichFrame server.
    private const val ASSET_TYPE_VIDEO = 1

    fun videoUrl(baseUrl: String, assetId: String): String =
        baseUrl.toHttpUrl().newBuilder()
            .addPathSegments("api/Asset")
            .addPathSegment(assetId)
            .addPathSegment("Asset")
            .addQueryParameter("assetType", "VIDEO")
            .build()
            .toString()

    data class PreparedImage(
        val response: ImageResponse,
        val encodedBytes: ByteArray,
        val width: Int,
        val height: Int,
    )

    fun prepareImage(response: ImageResponse): PreparedImage =
        prepareImage(response, Base64.decode(response.randomImageBase64, Base64.DEFAULT))

    fun prepareImage(response: ImageResponse, bytes: ByteArray): PreparedImage {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Invalid image payload" }
        return PreparedImage(response, bytes, bounds.outWidth, bounds.outHeight)
    }

    internal fun decodeSampleSize(width: Int, height: Int, targetDimension: Int): Int {
        require(width > 0 && height > 0 && targetDimension > 0)
        var sample = 1
        val largest = maxOf(width, height)
        while (largest / (sample * 2) >= targetDimension) sample *= 2
        return sample
    }

    fun decodePreparedBitmap(image: PreparedImage, targetDimension: Int): Bitmap {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.RGB_565
            inSampleSize = decodeSampleSize(image.width, image.height, targetDimension)
        }
        return requireNotNull(
            BitmapFactory.decodeByteArray(image.encodedBytes, 0, image.encodedBytes.size, options),
        ) { "Invalid image payload" }
    }

    data class ServerSettings(
        val margin: String,
        val interval: Int,
        val transitionDuration: Double,
        val downloadImages: Boolean,
        val renewImagesDuration: Int,
        val showClock: Boolean,
        val clockFormat: String,
        val showPhotoDate: Boolean,
        val photoDateFormat: String,
        val showImageDesc: Boolean,
        val showPeopleDesc: Boolean,
        val showImageLocation: Boolean,
        val imageLocationFormat: String,
        val primaryColor: String?,
        val secondaryColor: String,
        val style: String,
        val baseFontSize: String?,
        val showWeatherDescription: Boolean,
        val unattendedMode: Boolean,
        val imageZoom: Boolean,
        val imageFill: Boolean,
        val layout: String,
        val language: String,
        val playAudio: Boolean = false,
    )

    data class Weather(
        val location: String,
        val temperature: Double,
        val unit: String,
        val temperatureUnit: String,
        val description: String,
        val iconId: String
    )

    interface ApiService {
        /** With includeImage=false a supporting server omits the Base64 image; see [getAssetBytes]. */
        @GET("api/Asset/RandomImageAndInfo")
        fun getImageData(
            @Query("includeVideos") includeVideos: Boolean,
            @Query("includeImage") includeImage: Boolean,
        ): Call<ImageResponse>

        /** The raw preview bytes (a video's poster) for [ImageResponse.assetId] with assetType IMAGE. */
        @GET("api/Asset/{id}/Asset")
        fun getAssetBytes(@Path("id") id: String, @Query("assetType") assetType: String): Call<ResponseBody>

        @GET("api/Config")
        fun getServerSettings(): Call<ServerSettings>

        @GET("api/Weather")
        fun getWeather(): Call<Weather>
    }

    /** Retrofit throws on a base URL without an http(s) scheme and host, e.g. "192.168.1.5:8080". */
    fun isValidServerUrl(url: String): Boolean = url.toHttpUrlOrNull() != null

    /**
     * How this device names itself to the server, e.g. "WF1561 3f9a" or "WF1561 3f9a screensaver".
     * The server strips '-' and '_' from identifiers, so the parts are separated by spaces.
     */
    fun clientIdentifier(model: String, deviceId: String, source: String? = null): String =
        listOfNotNull(model.trim().ifEmpty { "Android" }, deviceId.take(4), source).joinToString(" ")

    fun clientIdentifier(prefs: SharedPreferences, source: String? = null): String =
        clientIdentifier(Build.MODEL.orEmpty(), RemoteIdentity.getOrCreate(prefs), source)

    /** The server records shown assets and dedupes webhooks per client, so every request names it. */
    fun withClientIdentifier(url: HttpUrl, clientIdentifier: String): HttpUrl =
        if (clientIdentifier.isBlank() || url.queryParameter("clientIdentifier") != null) url
        else url.newBuilder().addQueryParameter("clientIdentifier", clientIdentifier).build()

    fun createRetrofit(baseUrl: String, authSecret: String, clientIdentifier: String = ""): Retrofit {
        val normalizedBaseUrl = if (!baseUrl.endsWith("/")) "$baseUrl/" else baseUrl

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(45, TimeUnit.SECONDS)
            .addInterceptor { chain ->
            val namedRequest = chain.request()
            val originalRequest = namedRequest.newBuilder()
                .url(withClientIdentifier(namedRequest.url, clientIdentifier)).build()

            val request = if (authSecret.isNotEmpty()) {
                originalRequest.newBuilder().addHeader("Authorization", "Bearer $authSecret")
                    .build()
            } else {
                originalRequest
            }

            chain.proceed(request)
        }.build()

        return Retrofit.Builder().baseUrl(normalizedBaseUrl).client(client)
            .addConverterFactory(GsonConverterFactory.create()).build()
    }

    private val reachabilityClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    fun isServerReachable(url: String): Boolean {
        return try {
            val request = Request.Builder()
                .url(url)
                .head()
                .build()
            reachabilityClient.newCall(request).execute().use {
                true // any HTTP response = reachable
            }
        } catch (e: Exception) {
            false
        }
    }

    // --- Active schedule ---------------------------------------------------
    // Days use java.util.Calendar constants: SUNDAY=1 .. SATURDAY=7.

    data class ActiveRange(val start: String, val end: String) // "HH:mm"
    data class ActiveRule(val days: Set<Int>, val ranges: List<ActiveRange>)
    data class ActiveSchedule(val rules: List<ActiveRule>)

    private fun timeToMinutes(time: String): Int? {
        val parts = time.split(":")
        if (parts.size != 2) return null
        val hour = parts[0].toIntOrNull() ?: return null
        val minute = parts[1].toIntOrNull() ?: return null
        if (hour !in 0..23 || minute !in 0..59) return null
        return hour * 60 + minute
    }

    fun parseActiveSchedule(json: String?): ActiveSchedule {
        if (json.isNullOrBlank()) return ActiveSchedule(emptyList())
        return try {
            val rulesArr = JSONObject(json).optJSONArray("rules") ?: JSONArray()
            val rules = mutableListOf<ActiveRule>()
            for (i in 0 until rulesArr.length()) {
                val ruleObj = rulesArr.getJSONObject(i)
                val daysArr = ruleObj.optJSONArray("days") ?: JSONArray()
                val days = mutableSetOf<Int>()
                for (j in 0 until daysArr.length()) days.add(daysArr.getInt(j))
                val rangesArr = ruleObj.optJSONArray("ranges") ?: JSONArray()
                val ranges = mutableListOf<ActiveRange>()
                for (j in 0 until rangesArr.length()) {
                    val rangeObj = rangesArr.getJSONObject(j)
                    ranges.add(ActiveRange(rangeObj.getString("start"), rangeObj.getString("end")))
                }
                rules.add(ActiveRule(days, ranges))
            }
            ActiveSchedule(rules)
        } catch (_: Exception) {
            ActiveSchedule(emptyList())
        }
    }

    fun serializeActiveSchedule(schedule: ActiveSchedule): String {
        val rulesArr = JSONArray()
        for (rule in schedule.rules) {
            val daysArr = JSONArray()
            rule.days.sorted().forEach { daysArr.put(it) }
            val rangesArr = JSONArray()
            for (range in rule.ranges) {
                rangesArr.put(JSONObject().put("start", range.start).put("end", range.end))
            }
            rulesArr.put(JSONObject().put("days", daysArr).put("ranges", rangesArr))
        }
        return JSONObject().put("rules", rulesArr).toString()
    }

    /**
     * Returns whether the frame should be active right now.
     * An empty schedule means "always active" so enabling the feature without
     * configuring it never blacks out the screen.
     * Overnight ranges (start >= end) are anchored to the day they start on.
     */
    fun isActiveNow(schedule: ActiveSchedule, now: Calendar): Boolean {
        if (schedule.rules.isEmpty()) return true
        val today = now.get(Calendar.DAY_OF_WEEK)
        val yesterday = if (today == Calendar.SUNDAY) Calendar.SATURDAY else today - 1
        val nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)

        for (rule in schedule.rules) {
            for (range in rule.ranges) {
                val start = timeToMinutes(range.start) ?: continue
                val end = timeToMinutes(range.end) ?: continue
                when {
                    start < end -> {
                        // Same-day range
                        if (rule.days.contains(today) && nowMinutes in start until end) return true
                    }

                    start > end -> {
                        // Overnight range: part before midnight belongs to today's rule,
                        // part after midnight belongs to yesterday's rule.
                        if (rule.days.contains(today) && nowMinutes >= start) return true
                        if (rule.days.contains(yesterday) && nowMinutes < end) return true
                    }

                    else -> {
                        // start == end -> treat as active all day
                        if (rule.days.contains(today)) return true
                    }
                }
            }
        }
        return false
    }

    /**
     * Returns the next inactive-to-active transition after [now], or null if the schedule has no
     * rules (always active) or no transition is found within the next eight days. Calendar-based
     * minute steps intentionally follow local-time daylight-saving transitions.
     */
    fun nextActiveStart(schedule: ActiveSchedule, now: Calendar): Calendar? {
        if (schedule.rules.isEmpty()) return null
        val cursor = now.clone() as Calendar
        // Round the instant, not wall-clock fields: Calendar.set() can resolve an ambiguous
        // autumn hour to its later occurrence and skip the transition in the repeated hour.
        cursor.timeInMillis = now.timeInMillis - now.get(Calendar.SECOND) * 1_000L -
            now.get(Calendar.MILLISECOND)
        var wasActive = isActiveNow(schedule, cursor)
        cursor.add(Calendar.MINUTE, 1)
        val deadline = now.timeInMillis + 8L * 24 * 60 * 60 * 1_000
        while (cursor.timeInMillis <= deadline) {
            val active = isActiveNow(schedule, cursor)
            if (!wasActive && active) return cursor
            wasActive = active
            cursor.add(Calendar.MINUTE, 1)
        }
        return null
    }

}
