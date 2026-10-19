package com.immichframe.immichframe

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.security.SecureRandom
import java.util.concurrent.CopyOnWriteArrayList

data class RemoteFrameState(
    val displayActive: Boolean = true,
    val paused: Boolean = false,
    val brightness: Float? = null,
    val serverConnected: Boolean = false,
    val offlineFallback: Boolean = false,
    val presentationMode: String = "native",
    val version: String = "unknown",
    val lastImageSuccessMillis: Long? = null,
    /** Null until a photo is shown with photo reporting enabled. */
    val currentPhoto: CurrentPhoto? = null,
    /** Null until the first sample, taken only while RPC or MQTT is enabled. */
    val health: FrameHealth? = null,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("available", true)
        .put("displayActive", displayActive)
        .put("paused", paused)
        .put("playback", if (paused) "paused" else "playing")
        .put("brightness", brightness ?: JSONObject.NULL)
        .put("brightnessMode", if (brightness == null) "system" else "manual")
        .put("serverConnected", serverConnected)
        .put("offlineFallback", offlineFallback)
        .put("presentationMode", presentationMode)
        .put("version", version)
        .put("lastImageSuccessMillis", lastImageSuccessMillis ?: JSONObject.NULL)
        .put("currentPhoto", currentPhoto?.toJson() ?: JSONObject.NULL)
        .put("health", health?.toJson() ?: JSONObject.NULL)
}

class RemoteStateStore(initial: RemoteFrameState = RemoteFrameState()) {
    private val listeners = CopyOnWriteArrayList<(RemoteFrameState) -> Unit>()
    @Volatile private var value = initial

    fun snapshot(): RemoteFrameState = value

    fun update(block: (RemoteFrameState) -> RemoteFrameState): RemoteFrameState {
        val updated = synchronized(this) {
            block(value).also { value = it }
        }
        listeners.forEach { it(updated) }
        return updated
    }

    fun addListener(listener: (RemoteFrameState) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (RemoteFrameState) -> Unit) {
        listeners.remove(listener)
    }
}

interface RemoteCommands {
    fun setDisplayActive(active: Boolean)
    fun setPaused(paused: Boolean)
    fun togglePaused()
    fun setBrightness(brightness: Float?)
    fun next()
    fun previous()
    fun refresh()
    fun openSettings()
    fun notify(notification: FrameNotification)
    /** False when the request's URL is outside the configured Home Assistant URL. */
    fun showCamera(request: CameraRequest): Boolean
    /** Ends a camera takeover and any notifications. */
    fun dismissOverlays()
}

internal object RemoteIdentity {
    fun getOrCreate(prefs: SharedPreferences): String {
        prefs.getString(KEY, null)?.takeIf { it.matches(Regex("[a-f0-9]{16}")) }?.let { return it }
        val bytes = ByteArray(8).also(SecureRandom()::nextBytes)
        val generated = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(KEY, generated).apply()
        return generated
    }

    private const val KEY = "mqttDeviceId"
}

internal data class ApiResult(val status: Int, val json: JSONObject)

internal class RemoteApiRouter(
    private val state: RemoteStateStore,
    private val commands: RemoteCommands,
) {
    fun route(method: String, uri: String, body: String?): ApiResult {
        fun ok(): ApiResult = ApiResult(200, JSONObject().put("ok", true).put("state", state.snapshot().toJson()))
        fun error(status: Int, message: String) = ApiResult(status, JSONObject().put("ok", false).put("error", message))
        fun objectBody(): JSONObject? = try {
            if (body.isNullOrBlank()) null else JSONObject(body)
        } catch (_: Exception) { null }

        return when {
            method == "GET" && uri == "/api/v1/info" -> ApiResult(
                200,
                JSONObject()
                    .put("name", "ImmichFrame")
                    .put("version", state.snapshot().version)
                    .put("apiVersion", 1)
                    .put("capabilities", JSONArray(listOf("display", "playback", "brightness", "next", "previous", "refresh", "currentPhoto", "notify", "camera", "dismiss", "health"))),
            )
            method == "GET" && uri == "/api/v1/state" -> ApiResult(200, state.snapshot().toJson())
            method == "PUT" && uri == "/api/v1/display" -> {
                val active = objectBody()?.opt("active") as? Boolean
                    ?: return error(400, "JSON boolean 'active' is required")
                commands.setDisplayActive(active)
                ok()
            }
            method == "PUT" && uri == "/api/v1/playback" -> {
                val paused = objectBody()?.opt("paused") as? Boolean
                    ?: return error(400, "JSON boolean 'paused' is required")
                commands.setPaused(paused)
                ok()
            }
            method == "PUT" && uri == "/api/v1/brightness" -> {
                val json = objectBody() ?: return error(400, "JSON body is required")
                val raw = json.opt("brightness")
                val brightness = when (raw) {
                    JSONObject.NULL, "system" -> null
                    is Number -> raw.toFloat().takeIf { it in 0f..1f }
                        ?: return error(400, "brightness must be between 0 and 1")
                    else -> return error(400, "brightness must be a number or null")
                }
                commands.setBrightness(brightness)
                ok()
            }
            method == "POST" && uri == "/api/v1/actions/next" -> { commands.next(); ok() }
            method == "POST" && uri == "/api/v1/actions/previous" -> { commands.previous(); ok() }
            method == "POST" && uri == "/api/v1/actions/refresh" -> { commands.refresh(); ok() }
            method == "POST" && uri == "/api/v1/actions/notify" -> {
                val notification = objectBody()?.let(FrameNotification::fromJson)
                    ?: return error(400, "JSON string 'message' is required")
                commands.notify(notification)
                ok()
            }
            method == "POST" && uri == "/api/v1/actions/camera" -> {
                val request = objectBody()?.let(CameraRequest::fromJson)
                    ?: return error(400, "JSON string 'url' is required")
                if (!commands.showCamera(request)) {
                    return error(400, "url must be on the configured Home Assistant URL")
                }
                ok()
            }
            method == "POST" && uri == "/api/v1/actions/dismiss" -> { commands.dismissOverlays(); ok() }
            uri.startsWith("/api/v1/") -> error(404, "Unknown endpoint")
            else -> error(404, "Not found")
        }
    }
}
