package com.immichframe.immichframe

import org.json.JSONException
import org.json.JSONObject

/** A message shown over the slideshow for a while, sent from Home Assistant or the RPC API. */
data class FrameNotification(val message: String, val title: String?, val durationSeconds: Int) {
    companion object {
        const val DEFAULT_SECONDS = 10
        const val MIN_SECONDS = 3
        const val MAX_SECONDS = 300
        private const val MAX_MESSAGE = 500
        private const val MAX_TITLE = 100

        /**
         * Home Assistant's MQTT notify entity publishes only the message text, so a plain payload
         * is the message. A JSON object adds a title and duration (via mqtt.publish, or a message
         * that is itself JSON).
         */
        fun parse(payload: String): FrameNotification? {
            val text = payload.trim()
            if (text.startsWith("{")) {
                try {
                    return fromJson(JSONObject(text))
                } catch (_: JSONException) {
                    // Not JSON after all: show it as written.
                }
            }
            return create(text, null, null)
        }

        /** Null without a non-blank string "message". */
        fun fromJson(json: JSONObject): FrameNotification? {
            val message = json.opt("message") as? String ?: return null
            val title = json.opt("title") as? String
            val duration = (json.opt("duration") as? Number)?.toInt()
            return create(message, title, duration)
        }

        private fun create(message: String, title: String?, durationSeconds: Int?): FrameNotification? {
            val body = message.trim().take(MAX_MESSAGE)
            if (body.isEmpty()) return null
            return FrameNotification(
                message = body,
                title = title?.trim()?.take(MAX_TITLE)?.takeIf(String::isNotEmpty),
                durationSeconds = (durationSeconds ?: DEFAULT_SECONDS).coerceIn(MIN_SECONDS, MAX_SECONDS),
            )
        }
    }
}

/**
 * Notifications waiting to be shown, one at a time. A burst keeps only the newest [capacity], so
 * a backlog cannot keep the screen covered long after the events it reports.
 */
class NotificationQueue(private val capacity: Int = 3) {
    private val pending = ArrayDeque<FrameNotification>()

    val size: Int get() = pending.size

    /** Returns the notification dropped to make room, if any. */
    fun offer(notification: FrameNotification): FrameNotification? {
        val dropped = if (pending.size >= capacity) pending.removeFirst() else null
        pending.addLast(notification)
        return dropped
    }

    fun poll(): FrameNotification? = pending.removeFirstOrNull()

    fun clear() = pending.clear()
}
