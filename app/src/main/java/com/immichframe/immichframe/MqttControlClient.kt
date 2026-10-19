package com.immichframe.immichframe

import android.util.Log
import org.eclipse.paho.client.mqttv3.IMqttActionListener
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttAsyncClient
import org.eclipse.paho.client.mqttv3.MqttCallbackExtended
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttException
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

internal data class MqttSettings(
    val brokerUri: String,
    val username: String,
    val password: String,
    val deviceId: String,
    val discoveryPrefix: String = "homeassistant",
    val publishPhoto: Boolean = true,
    val photoImage: PhotoImageMode = PhotoImageMode.OFF,
    /** Whether the frame exposes a thermal zone; the W10J has none, so it gets no temperature entity. */
    val hasTemperature: Boolean = true,
) {
    val publishesPhotoImage: Boolean get() = publishPhoto && photoImage != PhotoImageMode.OFF

    val baseTopic: String get() = "immichframe/$deviceId"
}

internal object MqttPayloads {
    private fun device(settings: MqttSettings, version: String) = JSONObject()
        // A JSONArray: Android's org.json would write a Kotlin list as the string "[...]".
        .put("identifiers", JSONArray().put("immichframe_${settings.deviceId}"))
        .put("name", "ImmichFrame ${settings.deviceId.takeLast(6)}")
        .put("manufacturer", "ImmichFrame")
        .put("model", "Android Frame")
        .put("sw_version", version)

    fun discovery(settings: MqttSettings, version: String): Map<String, String> {
        val base = settings.baseTopic
        val availability = "$base/availability"
        val commonDevice = device(settings, version)
        fun config(component: String, id: String, name: String): Pair<String, JSONObject> {
            val topic = "${settings.discoveryPrefix}/$component/immichframe_${settings.deviceId}/$id/config"
            val json = JSONObject()
                .put("name", name)
                .put("unique_id", "immichframe_${settings.deviceId}_$id")
                .put("availability_topic", availability)
                .put("payload_available", "online")
                .put("payload_not_available", "offline")
                .put("device", commonDevice)
            return topic to json
        }

        val entries = mutableListOf<Pair<String, JSONObject>>()
        entries += config("switch", "display", "Display").let { (topic, json) ->
            topic to json.put("command_topic", "$base/display/set").put("state_topic", "$base/display/state")
        }
        entries += config("switch", "playback", "Playback").let { (topic, json) ->
            topic to json.put("command_topic", "$base/playback/set").put("state_topic", "$base/playback/state")
                .put("icon", "mdi:play-pause")
        }
        entries += config("number", "brightness", "Brightness").let { (topic, json) ->
            topic to json.put("command_topic", "$base/brightness/set").put("state_topic", "$base/brightness/state")
                .put("min", 0).put("max", 100).put("step", 1).put("mode", "slider").put("unit_of_measurement", "%")
        }
        val buttons = listOf(
            "next" to "Next photo", "previous" to "Previous photo", "refresh" to "Refresh",
            "dismiss" to "Dismiss overlay",
        )
        for ((id, name) in buttons) {
            entries += config("button", id, name).let { (topic, json) ->
                topic to json.put("command_topic", "$base/action/$id").put("payload_press", "PRESS")
            }
        }
        entries += config("notify", "notification", "Notification").let { (topic, json) ->
            topic to json.put("command_topic", "$base/notify")
        }
        entries += config("binary_sensor", "server_connected", "Server connected").let { (topic, json) ->
            topic to json.put("state_topic", "$base/server_connected/state").put("payload_on", "ON").put("payload_off", "OFF")
                .put("device_class", "connectivity")
        }
        entries += config("binary_sensor", "offline_fallback", "Offline fallback").let { (topic, json) ->
            topic to json.put("state_topic", "$base/offline_fallback/state").put("payload_on", "ON").put("payload_off", "OFF")
                .put("icon", "mdi:cloud-off-outline")
        }
        entries += config("sensor", "presentation_mode", "Presentation mode").let { (topic, json) ->
            topic to json.put("state_topic", "$base/presentation_mode/state")
        }
        entries += config("sensor", "version", "App version").let { (topic, json) ->
            topic to json.put("state_topic", "$base/version/state")
        }
        val photoEntries = listOf(
            config("sensor", "current_photo", "Current photo").let { (topic, json) ->
                topic to json.put("state_topic", "$base/photo/state")
                    .put("json_attributes_topic", "$base/photo/attributes")
                    .put("icon", "mdi:image")
            },
            config("sensor", "photo_taken_at", "Photo taken").let { (topic, json) ->
                topic to json.put("state_topic", "$base/photo/taken_at").put("device_class", "timestamp")
            },
        )
        // Health sensors: diagnostics, so Home Assistant lists them apart from the controls.
        fun health(id: String, name: String, vararg extra: Pair<String, Any>) =
            config("sensor", id, name).let { (topic, json) ->
                json.put("state_topic", "$base/health/$id").put("entity_category", "diagnostic")
                extra.forEach { (key, value) -> json.put(key, value) }
                topic to json
            }
        val measurement = "state_class" to "measurement"
        entries += health("app_memory", "App memory", "device_class" to "data_size", "unit_of_measurement" to "MB", measurement)
        entries += health("free_memory", "Free memory", "device_class" to "data_size", "unit_of_measurement" to "MB", measurement)
        entries += health("wifi_signal", "Wi-Fi signal", "device_class" to "signal_strength", "unit_of_measurement" to "dBm", measurement)
        entries += health("ip_address", "IP address", "icon" to "mdi:ip-network")
        entries += health("last_boot", "Last boot", "device_class" to "timestamp")
        entries += health("app_started", "App started", "device_class" to "timestamp")
        entries += health("last_photo", "Last photo", "device_class" to "timestamp")
        val temperature = health("temperature", "Temperature", "device_class" to "temperature", "unit_of_measurement" to "°C", measurement)
        val image = config("image", "current_photo_image", "Current photo image").let { (topic, json) ->
            topic to json.put("image_topic", "$base/photo/image").put("content_type", PhotoImage.CONTENT_TYPE)
        }
        val configs = entries.associate { it.first to it.second.toString() }
        // An empty retained config removes the entity, so turning reporting off leaves none behind.
        return configs + photoEntries.associate { (topic, json) ->
            topic to if (settings.publishPhoto) json.toString() else ""
        } + (image.first to if (settings.publishesPhotoImage) image.second.toString() else "") +
            (temperature.first to if (settings.hasTemperature) temperature.second.toString() else "")
    }

    /** Health topics are only part of the state once the frame has been sampled. */
    fun healthState(settings: MqttSettings, health: FrameHealth, lastPhotoMillis: Long?): Map<String, String> {
        val base = "${settings.baseTopic}/health"
        return buildMap {
            put("$base/app_memory", health.appMemoryMb?.toString() ?: PAYLOAD_NONE)
            put("$base/free_memory", health.systemMemoryFreeMb?.toString() ?: PAYLOAD_NONE)
            put("$base/wifi_signal", health.wifiSignalDbm?.toString() ?: PAYLOAD_NONE)
            put("$base/ip_address", health.ipAddress ?: PAYLOAD_NONE)
            put("$base/last_boot", CurrentPhoto.isoUtc(health.bootedAtMillis))
            put("$base/app_started", CurrentPhoto.isoUtc(health.appStartedAtMillis))
            put("$base/last_photo", lastPhotoMillis?.let(CurrentPhoto::isoUtc) ?: PAYLOAD_NONE)
            if (settings.hasTemperature) put("$base/temperature", health.temperatureC?.toString() ?: PAYLOAD_NONE)
        }
    }

    /** Photo topics are only part of the state once a photo has been shown. */
    fun photoState(settings: MqttSettings, photo: CurrentPhoto): Map<String, String> {
        val base = settings.baseTopic
        // Home Assistant's MQTT sensor reads the payload "None" as unknown, e.g. a cached photo.
        return mapOf(
            "$base/photo/state" to (photo.assetId ?: PAYLOAD_NONE),
            "$base/photo/taken_at" to (photo.takenAt ?: PAYLOAD_NONE),
            "$base/photo/attributes" to photo.toJson().toString(),
        )
    }

    /** Empty retained payloads clear the last photo, or just its image, from the broker when off. */
    fun photoCleanup(settings: MqttSettings): Map<String, String> {
        val base = settings.baseTopic
        val topics = when {
            !settings.publishPhoto -> listOf("state", "taken_at", "attributes", "image")
            !settings.publishesPhotoImage -> listOf("image")
            else -> emptyList()
        }
        return topics.associate { "$base/photo/$it" to "" }
    }

    fun photoImageTopic(settings: MqttSettings): String = "${settings.baseTopic}/photo/image"

    private const val PAYLOAD_NONE = "None"

    fun state(settings: MqttSettings, state: RemoteFrameState): Map<String, String> {
        val base = settings.baseTopic
        return mapOf(
            "$base/display/state" to if (state.displayActive) "ON" else "OFF",
            "$base/playback/state" to if (state.paused) "OFF" else "ON",
            "$base/brightness/state" to ((state.brightness ?: 1f) * 100f).toInt().coerceIn(0, 100).toString(),
            "$base/server_connected/state" to if (state.serverConnected) "ON" else "OFF",
            "$base/offline_fallback/state" to if (state.offlineFallback) "ON" else "OFF",
            "$base/presentation_mode/state" to state.presentationMode,
            "$base/version/state" to state.version,
        ) + (state.currentPhoto?.takeIf { settings.publishPhoto }?.let { photoState(settings, it) } ?: emptyMap()) +
            (state.health?.let { healthState(settings, it, state.lastImageSuccessMillis) } ?: emptyMap())
    }
}

internal fun shouldAcceptMqttMessage(topic: String, retained: Boolean): Boolean =
    !retained || topic == "homeassistant/status"

internal class MqttControlClient(
    private val settings: MqttSettings,
    private val state: RemoteStateStore,
    private val commands: RemoteCommands,
) {
    private var client: MqttAsyncClient? = null
    @Volatile private var running = false
    // Paho's automatic reconnect only applies after a first successful connection; a frame that
    // boots before Wi-Fi or the broker is up retries its first connection here instead.
    private var retryExecutor: ScheduledExecutorService? = null
    private var retryDelayMillis = INITIAL_RETRY_MILLIS
    private val stateListener: (RemoteFrameState) -> Unit = { publishState(it) }
    // Every state change reaches publishState, so only changed topics are sent: the photo
    // attributes must not be republished for each brightness tick. Guarded by itself.
    private val lastPublished = HashMap<String, String>()
    // Retained on the broker, but kept to resend after a reconnect or Home Assistant restart.
    @Volatile private var lastPhotoImage: ByteArray? = null

    fun start(): Boolean {
        if (client != null) return true
        try {
            running = true
            val mqtt = MqttAsyncClient(
                settings.brokerUri,
                "imf-${settings.deviceId}",
                MemoryPersistence(),
            )
            client = mqtt
            mqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    if (running) subscribeAndPublish()
                }
                override fun connectionLost(cause: Throwable?) = Unit
                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                override fun messageArrived(topic: String, message: MqttMessage) {
                    if (!running) return
                    if (!shouldAcceptMqttMessage(topic, message.isRetained)) {
                        Log.w(TAG, "Ignoring retained MQTT command on $topic")
                        return
                    }
                    handleMessage(topic, message.payload.toString(Charsets.UTF_8))
                }
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                // Discovery + availability + state is about 50 QoS-1 messages. Paho's default of ten
                // can drop the tail of that burst before acknowledgements arrive, especially
                // when a retained Home Assistant birth triggers a second snapshot at connect, so
                // leave room for two bursts.
                maxInflight = 128
                maxReconnectDelay = 120_000
                isHttpsHostnameVerificationEnabled = true
                if (settings.username.isNotBlank()) userName = settings.username
                if (settings.password.isNotEmpty()) password = settings.password.toCharArray()
                setWill("${settings.baseTopic}/availability", "offline".toByteArray(), 1, true)
            }
            state.addListener(stateListener)
            retryExecutor = Executors.newSingleThreadScheduledExecutor { runnable ->
                Thread(runnable, "ImmichFrame-mqtt-retry").apply { isDaemon = true }
            }
            connect(mqtt, options)
            return true
        } catch (e: Exception) {
            running = false
            retryExecutor?.shutdownNow()
            retryExecutor = null
            state.removeListener(stateListener)
            client?.let { failedClient ->
                runCatching { failedClient.disconnectForcibly(250, 250, false) }
                runCatching { failedClient.close() }
            }
            client = null
            Log.e(TAG, "Unable to start MQTT", e)
            return false
        }
    }

    private fun connect(mqtt: MqttAsyncClient, options: MqttConnectOptions) {
        mqtt.connect(options, null, object : IMqttActionListener {
            override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) {
                retryDelayMillis = INITIAL_RETRY_MILLIS
            }

            override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) {
                Log.w(TAG, "MQTT connection failed: ${exception?.message}")
                scheduleRetry(mqtt, options)
            }
        })
    }

    private fun scheduleRetry(mqtt: MqttAsyncClient, options: MqttConnectOptions) {
        if (!running || client !== mqtt) return
        val delay = retryDelayMillis
        retryDelayMillis = (delay * 2).coerceAtMost(MAX_RETRY_MILLIS)
        runCatching {
            retryExecutor?.schedule({
                if (running && client === mqtt && !mqtt.isConnected) {
                    runCatching { connect(mqtt, options) }.onFailure {
                        Log.w(TAG, "MQTT reconnect attempt failed: ${it.message}")
                        scheduleRetry(mqtt, options)
                    }
                }
            }, delay, TimeUnit.MILLISECONDS)
        }
    }

    fun stop() {
        running = false
        retryExecutor?.shutdownNow()
        retryExecutor = null
        state.removeListener(stateListener)
        val mqtt = client ?: return
        client = null
        if (mqtt.isConnected) {
            runCatching {
                mqtt.publish(
                    "${settings.baseTopic}/availability",
                    MqttMessage("offline".toByteArray()).apply { qos = 1; isRetained = true },
                ).waitForCompletion(1_000)
            }
        }
        runCatching { mqtt.disconnectForcibly(1_000, 1_000, false) }
        runCatching { mqtt.close() }
    }

    private fun subscribeAndPublish() {
        val mqtt = client ?: return
        val base = settings.baseTopic
        runCatching {
            mqtt.subscribe(
                arrayOf(
                    "$base/display/set", "$base/playback/set", "$base/brightness/set",
                    "$base/action/next", "$base/action/previous", "$base/action/refresh",
                    "$base/action/dismiss", "$base/notify", "$base/camera", "homeassistant/status",
                ),
                intArrayOf(1, 1, 1, 1, 1, 1, 1, 1, 1, 0),
            )
            publishDiscoveryAndState()
        }.onFailure { Log.w(TAG, "MQTT setup failed", it) }
    }

    private fun publishDiscoveryAndState() {
        MqttPayloads.discovery(settings, state.snapshot().version).forEach { (topic, payload) ->
            publish(topic, payload, retained = true)
        }
        MqttPayloads.photoCleanup(settings).forEach { (topic, payload) -> publish(topic, payload, retained = true) }
        lastPhotoImage?.let { publish(MqttPayloads.photoImageTopic(settings), it, retained = true) }
        publish("${settings.baseTopic}/availability", "online", retained = true)
        // A reconnect or Home Assistant restart needs the full state again.
        synchronized(lastPublished) { lastPublished.clear() }
        publishState(state.snapshot())
    }

    private fun handleMessage(topic: String, rawPayload: String) {
        val payload = rawPayload.trim()
        val base = settings.baseTopic
        when (topic) {
            "homeassistant/status" -> if (payload.equals("online", true)) publishDiscoveryAndState()
            "$base/display/set" -> when (payload.uppercase()) {
                "ON" -> commands.setDisplayActive(true)
                "OFF" -> commands.setDisplayActive(false)
            }
            "$base/playback/set" -> when (payload.uppercase()) {
                "ON" -> commands.setPaused(false)
                "OFF" -> commands.setPaused(true)
            }
            "$base/brightness/set" -> payload.toIntOrNull()?.takeIf { it in 0..100 }
                ?.let { commands.setBrightness(it / 100f) }
            "$base/action/next" -> if (payload == "PRESS") commands.next()
            "$base/action/previous" -> if (payload == "PRESS") commands.previous()
            "$base/action/refresh" -> if (payload == "PRESS") commands.refresh()
            "$base/action/dismiss" -> if (payload == "PRESS") commands.dismissOverlays()
            "$base/notify" -> FrameNotification.parse(rawPayload)?.let(commands::notify)
            "$base/camera" -> CameraRequest.parse(rawPayload)?.let(commands::showCamera)
        }
    }

    private fun publishState(frameState: RemoteFrameState) {
        // Publishing inside the lock keeps two racing updates from reaching the broker out of order.
        synchronized(lastPublished) {
            MqttPayloads.state(settings, frameState).forEach { (topic, payload) ->
                if (lastPublished[topic] == payload) return@forEach
                // A failed publish is retried with the next change or the next full resend.
                if (publish(topic, payload, retained = true)) lastPublished[topic] = payload
            }
        }
    }

    /** Publishes a JPEG of the photo on screen; ignored unless the image entity is enabled. */
    fun publishPhotoImage(jpeg: ByteArray) {
        if (!settings.publishesPhotoImage) return
        lastPhotoImage = jpeg
        publish(MqttPayloads.photoImageTopic(settings), jpeg, retained = true)
    }

    private fun publish(topic: String, payload: String, retained: Boolean): Boolean =
        publish(topic, payload.toByteArray(), retained)

    private fun publish(topic: String, payload: ByteArray, retained: Boolean): Boolean {
        if (!running) return false
        val mqtt = client ?: return false
        if (!mqtt.isConnected) return false
        return try {
            mqtt.publish(topic, MqttMessage(payload).apply {
                qos = 1
                isRetained = retained
            })
            true
        } catch (e: MqttException) {
            Log.w(TAG, "MQTT publish failed for $topic", e)
            false
        }
    }

    companion object {
        private const val TAG = "MqttControlClient"
        private const val INITIAL_RETRY_MILLIS = 5_000L
        private const val MAX_RETRY_MILLIS = 120_000L
    }
}
