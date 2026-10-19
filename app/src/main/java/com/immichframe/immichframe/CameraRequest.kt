package com.immichframe.immichframe

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONException
import org.json.JSONObject

/**
 * A camera snapshot shown full screen for a while, e.g. a doorbell press. [url] is usually a Home
 * Assistant camera's `entity_picture`, a relative path carrying a short-lived access token.
 */
data class CameraRequest(
    val url: String,
    val title: String?,
    val durationSeconds: Int,
    /** Zero shows the first snapshot only; otherwise the same URL is fetched again this often. */
    val refreshSeconds: Int,
    val wake: Boolean = false,
) {
    companion object {
        const val DEFAULT_SECONDS = 30
        const val MIN_SECONDS = 5
        // Home Assistant rotates camera tokens every 5 minutes and keeps the last two, so a URL
        // from entity_picture is valid for at least this long.
        const val MAX_SECONDS = 300
        const val MAX_REFRESH_SECONDS = 30
        private const val MAX_TITLE = 100

        /** A JSON object, or a bare URL. */
        fun parse(payload: String): CameraRequest? {
            val text = payload.trim()
            if (text.startsWith("{")) {
                return try {
                    fromJson(JSONObject(text))
                } catch (_: JSONException) {
                    null
                }
            }
            return create(text, null, null, null, false)
        }

        /** Null without a non-blank string "url". */
        fun fromJson(json: JSONObject): CameraRequest? {
            val url = json.opt("url") as? String ?: return null
            return create(
                url,
                json.opt("title") as? String,
                (json.opt("duration") as? Number)?.toInt(),
                (json.opt("refresh") as? Number)?.toInt(),
                json.opt("wake") == true,
            )
        }

        private fun create(
            url: String,
            title: String?,
            durationSeconds: Int?,
            refreshSeconds: Int?,
            wake: Boolean,
        ): CameraRequest? {
            val trimmed = url.trim()
            if (trimmed.isEmpty()) return null
            return CameraRequest(
                url = trimmed,
                title = title?.trim()?.take(MAX_TITLE)?.takeIf(String::isNotEmpty),
                durationSeconds = (durationSeconds ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS),
                refreshSeconds = (refreshSeconds ?: 0).coerceIn(0, MAX_REFRESH_SECONDS),
                wake = wake,
            )
        }

        /**
         * Resolves [url] against the configured Home Assistant [baseUrl]. Anyone who can publish
         * to the broker can send a command, so the frame fetches only from that origin: a relative
         * path resolves against it, and an absolute URL must have the same scheme, host and port.
         */
        fun resolve(baseUrl: String, url: String): HttpUrl? {
            val base = baseUrl.trim().toHttpUrlOrNull() ?: return null
            val resolved = base.resolve(url.trim()) ?: return null
            val sameOrigin = resolved.scheme == base.scheme && resolved.host == base.host &&
                resolved.port == base.port
            return resolved.takeIf { sameOrigin }
        }
    }
}
