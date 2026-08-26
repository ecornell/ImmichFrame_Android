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
import org.json.JSONObject

internal data class MqttSettings(
    val brokerUri: String,
    val username: String,
    val password: String,
    val deviceId: String,
    val discoveryPrefix: String = "homeassistant",
) {
    val baseTopic: String get() = "immichframe/$deviceId"
}

internal object MqttPayloads {
    private fun device(settings: MqttSettings, version: String) = JSONObject()
        .put("identifiers", listOf("immichframe_${settings.deviceId}"))
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
        for ((id, name) in listOf("next" to "Next photo", "previous" to "Previous photo", "refresh" to "Refresh")) {
            entries += config("button", id, name).let { (topic, json) ->
                topic to json.put("command_topic", "$base/action/$id").put("payload_press", "PRESS")
            }
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
        return entries.associate { it.first to it.second.toString() }
    }

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
        )
    }
}

internal class MqttControlClient(
    private val settings: MqttSettings,
    private val state: RemoteStateStore,
    private val commands: RemoteCommands,
) {
    private var client: MqttAsyncClient? = null
    private val stateListener: (RemoteFrameState) -> Unit = { publishState(it) }

    fun start() {
        if (client != null) return
        try {
            val mqtt = MqttAsyncClient(
                settings.brokerUri,
                "imf-${settings.deviceId}",
                MemoryPersistence(),
            )
            client = mqtt
            mqtt.setCallback(object : MqttCallbackExtended {
                override fun connectComplete(reconnect: Boolean, serverURI: String?) {
                    subscribeAndPublish()
                }
                override fun connectionLost(cause: Throwable?) = Unit
                override fun deliveryComplete(token: IMqttDeliveryToken?) = Unit
                override fun messageArrived(topic: String, message: MqttMessage) {
                    handleMessage(topic, message.payload.toString(Charsets.UTF_8))
                }
            })
            val options = MqttConnectOptions().apply {
                isAutomaticReconnect = true
                isCleanSession = true
                connectionTimeout = 10
                keepAliveInterval = 30
                maxReconnectDelay = 120_000
                isHttpsHostnameVerificationEnabled = true
                if (settings.username.isNotBlank()) userName = settings.username
                if (settings.password.isNotEmpty()) password = settings.password.toCharArray()
                setWill("${settings.baseTopic}/availability", "offline".toByteArray(), 1, true)
            }
            state.addListener(stateListener)
            mqtt.connect(options, null, object : IMqttActionListener {
                override fun onSuccess(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?) = Unit
                override fun onFailure(asyncActionToken: org.eclipse.paho.client.mqttv3.IMqttToken?, exception: Throwable?) {
                    Log.w(TAG, "MQTT connection failed: ${exception?.message}")
                }
            })
        } catch (e: Exception) {
            state.removeListener(stateListener)
            client = null
            Log.e(TAG, "Unable to start MQTT", e)
        }
    }

    fun stop() {
        state.removeListener(stateListener)
        val mqtt = client ?: return
        runCatching {
            if (mqtt.isConnected) {
                mqtt.publish(
                    "${settings.baseTopic}/availability",
                    MqttMessage("offline".toByteArray()).apply { qos = 1; isRetained = true },
                ).waitForCompletion(1_000)
                mqtt.disconnectForcibly(1_000, 1_000, false)
            }
            mqtt.close()
        }
        client = null
    }

    private fun subscribeAndPublish() {
        val mqtt = client ?: return
        val base = settings.baseTopic
        runCatching {
            mqtt.subscribe(
                arrayOf(
                    "$base/display/set", "$base/playback/set", "$base/brightness/set",
                    "$base/action/next", "$base/action/previous", "$base/action/refresh",
                    "homeassistant/status",
                ),
                intArrayOf(1, 1, 1, 1, 1, 1, 0),
            )
            publishDiscoveryAndState()
        }.onFailure { Log.w(TAG, "MQTT setup failed", it) }
    }

    private fun publishDiscoveryAndState() {
        MqttPayloads.discovery(settings, state.snapshot().version).forEach { (topic, payload) ->
            publish(topic, payload, retained = true)
        }
        publish("${settings.baseTopic}/availability", "online", retained = true)
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
        }
    }

    private fun publishState(frameState: RemoteFrameState) {
        MqttPayloads.state(settings, frameState).forEach { (topic, payload) -> publish(topic, payload, retained = true) }
    }

    private fun publish(topic: String, payload: String, retained: Boolean) {
        val mqtt = client ?: return
        if (!mqtt.isConnected) return
        try {
            mqtt.publish(topic, MqttMessage(payload.toByteArray()).apply {
                qos = 1
                isRetained = retained
            })
        } catch (e: MqttException) {
            Log.w(TAG, "MQTT publish failed for $topic", e)
        }
    }

    companion object { private const val TAG = "MqttControlClient" }
}
