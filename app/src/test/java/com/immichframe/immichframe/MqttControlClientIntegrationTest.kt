package com.immichframe.immichframe

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MqttControlClientIntegrationTest {
    @Test(timeout = 25_000) fun realClientPublishesDiscoveryHandlesCommandsAndResynchronizesOnBirth() {
        TestBroker().use { broker ->
            val state = RemoteStateStore(RemoteFrameState(version = "integration"))
            val next = CountDownLatch(1)
            val client = MqttControlClient(
                MqttSettings("tcp://127.0.0.1:${broker.port}", "", "", "integration"),
                state,
                object : RemoteCommands {
                    override fun setDisplayActive(active: Boolean) { state.update { it.copy(displayActive = active) } }
                    override fun setPaused(paused: Boolean) { state.update { it.copy(paused = paused) } }
                    override fun togglePaused() = setPaused(!state.snapshot().paused)
                    override fun setBrightness(brightness: Float?) { state.update { it.copy(brightness = brightness) } }
                    override fun next() = next.countDown()
                    override fun previous() = Unit
                    override fun refresh() = Unit
                    override fun openSettings() = Unit
                },
            )
            val base = "immichframe/integration"
            val discovery = "homeassistant/switch/immichframe_integration/display/config"
            try {
                assertTrue(client.start())
                assertTrue(broker.awaitPublication(discovery).retained)
                assertEquals("online", broker.awaitPublication("$base/availability").payload)
                assertEquals("integration", broker.awaitPublication("$base/version/state").payload)

                broker.publish("$base/display/set", "OFF")
                assertEquals("OFF", broker.awaitPublication("$base/display/state").payload)
                assertFalse(state.snapshot().displayActive)
                broker.publish("$base/brightness/set", "25")
                // Only changed topics are sent, so the unchanged display state is not resent.
                assertEquals(listOf("$base/brightness/state"), broker.topicsThrough("$base/brightness/state"))
                assertEquals(0.25f, state.snapshot().brightness!!, 0.001f)

                val photo = CurrentPhoto.from(
                    listOf(Helpers.ImageResponse("", "", "07/04/2019", "", assetId = "a1")),
                    CurrentPhoto.SOURCE_SERVER, true, 0L,
                )
                state.update { it.copy(currentPhoto = photo) }
                assertEquals("a1", broker.awaitPublication("$base/photo/state").payload)
                assertEquals("None", broker.awaitPublication("$base/photo/taken_at").payload)
                assertEquals("a1", JSONObject(broker.awaitPublication("$base/photo/attributes").payload).getString("asset_id"))
                broker.publish("$base/brightness/set", "30")
                assertEquals(listOf("$base/brightness/state"), broker.topicsThrough("$base/brightness/state"))
                broker.publish("$base/action/next", "PRESS")
                assertTrue("next command was not delivered", next.await(5, TimeUnit.SECONDS))

                broker.publish("homeassistant/status", "online", retained = true)
                assertTrue(broker.awaitPublication(discovery).retained)
                assertEquals("online", broker.awaitPublication("$base/availability").payload)
                // A Home Assistant restart gets the full state, including unchanged topics.
                assertEquals("OFF", broker.awaitPublication("$base/display/state").payload)
                assertTrue(broker.awaitPublication("$base/photo/attributes").retained)
                client.stop()
                assertEquals("offline", broker.awaitPublication("$base/availability").payload)
            } finally {
                client.stop()
            }
        }
    }

    @Test(timeout = 25_000) fun photoImageIsPublishedRetainedAndResentAfterBirth() {
        TestBroker().use { broker ->
            val state = RemoteStateStore(RemoteFrameState(version = "integration"))
            val client = MqttControlClient(
                MqttSettings(
                    "tcp://127.0.0.1:${broker.port}", "", "", "integration",
                    photoImage = PhotoImageMode.THUMBNAIL,
                ),
                state,
                object : RemoteCommands {
                    override fun setDisplayActive(active: Boolean) = Unit
                    override fun setPaused(paused: Boolean) = Unit
                    override fun togglePaused() = Unit
                    override fun setBrightness(brightness: Float?) = Unit
                    override fun next() = Unit
                    override fun previous() = Unit
                    override fun refresh() = Unit
                    override fun openSettings() = Unit
                },
            )
            val base = "immichframe/integration"
            try {
                assertTrue(client.start())
                val config = broker.awaitPublication(
                    "homeassistant/image/immichframe_integration/current_photo_image/config")
                assertEquals("$base/photo/image", JSONObject(config.payload).getString("image_topic"))
                broker.awaitPublication("$base/availability")

                client.publishPhotoImage("jpeg-bytes".toByteArray())
                val image = broker.awaitPublication("$base/photo/image")
                assertEquals("jpeg-bytes", image.payload)
                assertTrue(image.retained)

                broker.publish("homeassistant/status", "online", retained = true)
                assertEquals("jpeg-bytes", broker.awaitPublication("$base/photo/image").payload)
            } finally {
                client.stop()
            }
        }
    }

    /** Bounded, loopback-only MQTT 3.1.1 peer, not a mock of the client's transport/callbacks.
     * Implements only CONNECT, SUBSCRIBE, QoS 0/1 PUBLISH, PING, and disconnect for this test.
     */
    private class TestBroker : AutoCloseable {
        data class Publication(val topic: String, val payload: String, val retained: Boolean)
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = server.localPort
        @Volatile private var socket: Socket? = null
        @Volatile private var closed = false
        private val subscriptions = linkedSetOf<String>()
        private val publications = LinkedBlockingQueue<Publication>()
        private val executor = Executors.newSingleThreadExecutor()
        private val worker = executor.submit {
            try {
                server.accept().use { connection ->
                    socket = connection
                    connection.soTimeout = 10_000
                    val input = DataInputStream(connection.getInputStream())
                    while (!closed) {
                        val header = input.read()
                        if (header < 0) break
                        var length = 0
                        var multiplier = 1
                        do {
                            require(multiplier <= 128 * 128 * 128)
                            val digit = input.readUnsignedByte()
                            length += (digit and 127) * multiplier
                            multiplier *= 128
                        } while (digit and 128 != 0)
                        require(length <= 65_536) { "Oversized test packet" }
                        val bytes = ByteArray(length).also(input::readFully)
                        val packet = DataInputStream(ByteArrayInputStream(bytes))
                        when (header ushr 4) {
                            1 -> send(0x20, byteArrayOf(0, 0)) // CONNACK
                            3 -> {
                                val topic = packet.readUTF()
                                val qos = (header ushr 1) and 3
                                require(qos in 0..1)
                                val id = if (qos == 1) packet.readUnsignedShort() else 0
                                publications.put(Publication(topic, packet.readBytes().toString(Charsets.UTF_8), header and 1 != 0))
                                if (qos == 1) send(0x40, byteArrayOf((id ushr 8).toByte(), id.toByte()))
                            }
                            8 -> {
                                val id = packet.readUnsignedShort()
                                val grants = mutableListOf<Byte>()
                                synchronized(subscriptions) {
                                    while (packet.available() > 0) {
                                        subscriptions.add(packet.readUTF())
                                        grants.add(packet.readByte())
                                    }
                                }
                                send(0x90, byteArrayOf((id ushr 8).toByte(), id.toByte()) + grants.toByteArray())
                            }
                            12 -> send(0xD0, byteArrayOf())
                            14 -> break
                            else -> error("Unexpected MQTT packet ${header ushr 4}")
                        }
                    }
                }
            } catch (error: SocketException) {
                if (!closed) throw error
            }
        }

        fun publish(topic: String, payload: String, retained: Boolean = false) {
            assertTrue("client did not subscribe to $topic", synchronized(subscriptions) { topic in subscriptions })
            val bytes = ByteArrayOutputStream()
            DataOutputStream(bytes).use { it.writeUTF(topic); it.write(payload.toByteArray()) }
            send(if (retained) 0x31 else 0x30, bytes.toByteArray())
        }

        /** The next publication to [topic], or to any topic when it is null. */
        fun awaitPublication(topic: String?): Publication {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (true) {
                val remaining = deadline - System.nanoTime()
                val message = if (remaining > 0) publications.poll(remaining, TimeUnit.NANOSECONDS) else null
                if (message == null) {
                    if (worker.isDone) worker.get() // Preserve a broker assertion as the root cause.
                    error("Timed out waiting for $topic")
                }
                if (topic == null || message.topic == topic) return message
            }
        }

        /** Topics published up to and including the next [topic]. */
        fun topicsThrough(topic: String): List<String> {
            val seen = mutableListOf<String>()
            while (true) {
                val message = awaitPublication(null)
                seen += message.topic
                if (message.topic == topic) return seen
            }
        }

        @Synchronized private fun send(header: Int, body: ByteArray) {
            val output = requireNotNull(socket).getOutputStream()
            output.write(header)
            var length = body.size
            do {
                var digit = length % 128
                length /= 128
                if (length > 0) digit = digit or 128
                output.write(digit)
            } while (length > 0)
            output.write(body)
            output.flush()
        }

        override fun close() {
            closed = true
            socket?.close()
            server.close()
            executor.shutdownNow()
            try {
                worker.get(2, TimeUnit.SECONDS)
            } finally {
                assertTrue("broker thread leaked", executor.awaitTermination(2, TimeUnit.SECONDS))
            }
        }
    }
}
