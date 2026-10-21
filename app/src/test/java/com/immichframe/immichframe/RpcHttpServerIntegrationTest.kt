package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger

class RpcHttpServerIntegrationTest {
    @Test fun loopbackServerEnforcesAuthenticationAndServesState() {
        val state = RemoteStateStore(RemoteFrameState(version = "integration"))
        val nextCount = AtomicInteger()
        val pausedRequests = mutableListOf<Boolean>()
        val displayRequests = mutableListOf<Boolean>()
        val server = RpcHttpServer(
            expectedToken = { "test-token" },
            state = state,
            commands = object : RemoteCommands {
                override fun setDisplayActive(active: Boolean) { displayRequests += active }
                override fun setPaused(paused: Boolean) { pausedRequests += paused }
                override fun togglePaused() = Unit
                override fun setBrightness(brightness: Float?) = Unit
                override fun next() { nextCount.incrementAndGet() }
                override fun previous() = Unit
                override fun refresh() = Unit
                override fun openSettings() = Unit
                override fun notify(notification: FrameNotification) = Unit
                override fun showCamera(request: CameraRequest) = true
                override fun dismissOverlays() = Unit
            },
            hostname = "127.0.0.1",
            port = 0,
        )
        try {
            server.start(2_000, false)
            val port = server.listeningPort
            assertEquals(200, request(port, "/health").first)
            assertEquals(401, request(port, "/api/v1/state").first)
            val (status, body) = request(port, "/api/v1/state", "Bearer test-token")
            assertEquals(200, status)
            assertTrue(body.contains("\"version\":\"integration\""))
            assertEquals(401, request(port, "/api/v1/actions/next", "Bearer wrong", "POST", "{}").first)
            assertEquals(0, nextCount.get())
            assertEquals(400, request(port, "/api/v1/display", "Bearer test-token", "PUT", "bad-json").first)
            assertEquals(400, request(port, "/api/v1/actions/next", "Bearer test-token", "POST", "x".repeat(5_000)).first)
            assertEquals(0, nextCount.get())
            assertEquals(200, request(port, "/api/v1/actions/next", "Bearer test-token", "POST", "{}").first)
            assertEquals(1, nextCount.get())
            // PUT bodies arrive through a different NanoHTTPD path than POST bodies.
            assertEquals(200, request(port, "/api/v1/playback", "Bearer test-token", "PUT", "{\"paused\":true}").first)
            assertEquals(200, request(port, "/api/v1/display", "Bearer test-token", "PUT", "{\"active\":false}").first)
            assertEquals(listOf(true), pausedRequests)
            assertEquals(listOf(false), displayRequests)
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 20_000) fun aLegacyPostBodyIsNotParsedAsTheNextKeepAliveRequest() {
        val nextCount = AtomicInteger()
        val server = server(nextCount)
        try {
            server.start(5_000, false)
            // Pipelined: the unread "{}" body would otherwise prefix the second request line.
            val replies = exchange(
                server.listeningPort,
                legacyPost("/next", "{}") + legacyPost("/next", ""),
            )
            assertEquals(1, nextCount.get())
            assertEquals("one response, then the connection closes: $replies", 1, statusLines(replies))
            assertTrue(replies.startsWith("HTTP/1.1 200"))
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 20_000) fun aBodylessLegacyPostKeepsTheConnectionOpen() {
        val nextCount = AtomicInteger()
        val server = server(nextCount)
        try {
            server.start(5_000, false)
            val replies = exchange(
                server.listeningPort,
                legacyPost("/next", "") + legacyPost("/next", "", close = true),
            )
            assertEquals(2, nextCount.get())
            assertEquals(replies, 2, statusLines(replies))
        } finally {
            server.stop()
        }
    }

    @Test(timeout = 20_000) fun connectionsBeyondTheLimitAreClosed() {
        val server = server(AtomicInteger())
        val idle = mutableListOf<Socket>()
        try {
            server.start(10_000, false)
            val port = server.listeningPort
            repeat(RpcHttpServer.MAX_CONNECTIONS) { idle += connect(port) }
            // Each idle connection's handler is blocked reading its request line.
            Thread.sleep(300)
            connect(port).use { extra ->
                extra.soTimeout = 3_000
                val closed = try {
                    extra.getInputStream().read() == -1
                } catch (_: SocketException) {
                    true // reset
                } catch (_: SocketTimeoutException) {
                    false
                }
                assertTrue("the connection over the limit was left open", closed)
            }
            idle.forEach { it.close() }
            idle.clear()
            // Closed handlers free their slots.
            Thread.sleep(300)
            assertEquals(200, request(port, "/health").first)
        } finally {
            idle.forEach { it.close() }
            server.stop()
        }
    }

    private fun server(nextCount: AtomicInteger) = RpcHttpServer(
        expectedToken = { "test-token" },
        state = RemoteStateStore(),
        commands = object : RemoteCommands {
            override fun setDisplayActive(active: Boolean) = Unit
            override fun setPaused(paused: Boolean) = Unit
            override fun togglePaused() = Unit
            override fun setBrightness(brightness: Float?) = Unit
            override fun next() { nextCount.incrementAndGet() }
            override fun previous() = Unit
            override fun refresh() = Unit
            override fun openSettings() = Unit
            override fun notify(notification: FrameNotification) = Unit
            override fun showCamera(request: CameraRequest) = true
            override fun dismissOverlays() = Unit
        },
        hostname = "127.0.0.1",
        port = 0,
    )

    private fun legacyPost(path: String, body: String, close: Boolean = false): String =
        "POST $path HTTP/1.1\r\nHost: 127.0.0.1\r\nAuthorization: Bearer test-token\r\n" +
            (if (close) "Connection: close\r\n" else "") +
            "Content-Length: ${body.toByteArray().size}\r\n\r\n$body"

    private fun connect(port: Int) = Socket().apply { connect(InetSocketAddress("127.0.0.1", port), 2_000) }

    /** Sends [raw] on one connection and reads everything until the server closes it. */
    private fun exchange(port: Int, raw: String): String = connect(port).use { socket ->
        socket.soTimeout = 8_000
        socket.getOutputStream().apply { write(raw.toByteArray()); flush() }
        val received = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        try {
            while (true) {
                val count = socket.getInputStream().read(buffer)
                if (count < 0) break
                received.write(buffer, 0, count)
            }
        } catch (_: SocketException) {
            // A close with unread request bytes can reset; what arrived is enough.
        }
        received.toString(Charsets.UTF_8.name())
    }

    private fun statusLines(replies: String) = Regex("HTTP/1\\.1 \\d{3}").findAll(replies).count()

    private fun request(
        port: Int,
        path: String,
        authorization: String? = null,
        method: String = "GET",
        body: String? = null,
    ): Pair<Int, String> {
        val connection = URL("http://127.0.0.1:$port$path")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 2_000
        connection.readTimeout = 2_000
        connection.requestMethod = method
        authorization?.let { connection.setRequestProperty("Authorization", it) }
        return try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                val bytes = body.toByteArray(Charsets.UTF_8)
                connection.setFixedLengthStreamingMode(bytes.size)
                connection.outputStream.use { it.write(bytes) }
            }
            val status = connection.responseCode
            // A rejected oversized upload can reset the socket with unread request bytes.
            // Mutation tests assert the response status and command side effects, not its body.
            if (method != "GET") return status to ""
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            status to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            connection.disconnect()
        }
    }
}
