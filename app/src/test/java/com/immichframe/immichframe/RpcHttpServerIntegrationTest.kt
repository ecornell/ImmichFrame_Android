package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.HttpURLConnection
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
