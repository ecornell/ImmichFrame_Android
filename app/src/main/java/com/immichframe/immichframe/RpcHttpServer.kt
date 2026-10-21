package com.immichframe.immichframe

import fi.iki.elonen.NanoHTTPD
import org.json.JSONObject
import java.io.File
import java.util.LinkedHashMap

class RpcHttpServer(
    private val expectedToken: () -> String,
    private val state: RemoteStateStore,
    private val commands: RemoteCommands,
    hostname: String? = null,
    port: Int = PORT,
) : NanoHTTPD(hostname, port) {
    private val router = RemoteApiRouter(state, commands)
    private val rateLimiters = ClientRateLimiters()
    private val failedAuthRateLimiters = ClientRateLimiters(limit = 30)

    init {
        // NanoHTTPD's default starts an unbounded thread per connection.
        setAsyncRunner(BoundedAsyncRunner(MAX_CONNECTIONS))
    }

    /** Whether this request's body has been read off the connection. */
    private class BodyState(session: IHTTPSession) {
        private val length = session.headers["content-length"]?.toLongOrNull()
        // A PUT or POST without Content-Length may still carry a body that cannot be delimited.
        val hasBody = length?.let { it > 0 } ?: (session.method == Method.PUT || session.method == Method.POST)
        var read = false
    }

    override fun serve(session: IHTTPSession): Response {
        val body = BodyState(session)
        val response = respond(session, body)
        // NanoHTTPD reads the next keep-alive request from the same stream, so an unread body
        // would be parsed as that request's start. Close instead; nothing here reads it otherwise.
        if (body.hasBody && !body.read) response.closeConnection(true)
        return response
    }

    private fun respond(session: IHTTPSession, bodyState: BodyState): Response {
        if (session.method == Method.GET && session.uri == "/health") {
            return json(Response.Status.OK, JSONObject().put("available", true))
        }

        val clientAddress = session.remoteIpAddress.orEmpty()
        // Refuse a client over the failed-auth limit before comparing, so it cannot keep guessing.
        if (failedAuthRateLimiters.isLimited(clientAddress)) {
            return json(Response.Status.TOO_MANY_REQUESTS, JSONObject().put("ok", false).put("error", "Rate limited"))
        }
        val supplied = RpcSecurity.bearerToken(session.headers["authorization"])
        if (!RpcSecurity.authorized(expectedToken(), supplied)) {
            if (!failedAuthRateLimiters.allow(clientAddress)) {
                return json(Response.Status.TOO_MANY_REQUESTS, JSONObject().put("ok", false).put("error", "Rate limited"))
            }
            return json(Response.Status.UNAUTHORIZED, JSONObject().put("ok", false).put("error", "Unauthorized"))
        }
        if (!rateLimiters.allow(clientAddress)) {
            return json(Response.Status.TOO_MANY_REQUESTS, JSONObject().put("ok", false).put("error", "Rate limited"))
        }

        if (!session.uri.startsWith("/api/v1/")) return serveLegacy(session)
        val body = if (session.method == Method.PUT || session.method == Method.POST) {
            val length = session.headers["content-length"]?.toLongOrNull()
                ?: return badRequest("Content-Length is required")
            if (length !in 0..MAX_BODY_BYTES.toLong()) {
                return badRequest("Request body too large")
            }
            if (session.headers["content-type"]?.substringBefore(';')?.trim()?.lowercase() != "application/json") {
                return badRequest("Content-Type must be application/json")
            }
            try {
                val files = HashMap<String, String>()
                session.parseBody(files)
                bodyState.read = true
                // NanoHTTPD keeps a POST body in "postData" but writes a PUT body to a temp file
                // under "content"; Content-Length is already bounded above.
                val body = if (session.method == Method.PUT) {
                    files["content"]?.let { File(it).readText(Charsets.UTF_8) }.orEmpty()
                } else {
                    files["postData"].orEmpty()
                }
                body.take(MAX_BODY_BYTES + 1)
            } catch (_: Exception) {
                return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "Invalid request body"))
            }
        } else null
        if (body != null && body.length > MAX_BODY_BYTES) {
            return json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", "Request body too large"))
        }
        val result = router.route(session.method.name, session.uri, body)
        return json(status(result.status), result.json)
    }

    private fun badRequest(error: String): Response =
        json(Response.Status.BAD_REQUEST, JSONObject().put("ok", false).put("error", error))

    private fun serveLegacy(session: IHTTPSession): Response {
        if (session.method != Method.POST) {
            return newFixedLengthResponse(Response.Status.METHOD_NOT_ALLOWED, MIME_PLAINTEXT, "POST required")
        }
        return when (session.uri) {
            "/off", "/dim" -> { commands.setDisplayActive(false); newFixedLengthResponse("Frame off") }
            "/on", "/undim" -> { commands.setDisplayActive(true); newFixedLengthResponse("Frame on") }
            "/next" -> { commands.next(); newFixedLengthResponse("Next") }
            "/previous" -> { commands.previous(); newFixedLengthResponse("Previous") }
            "/pause" -> { commands.togglePaused(); newFixedLengthResponse("Pause") }
            "/settings" -> { commands.openSettings(); newFixedLengthResponse("Settings") }
            "/brightness" -> {
                val brightness = session.parameters["value"]?.firstOrNull()?.toFloatOrNull()
                if (brightness != null && brightness in -1f..1f) {
                    commands.setBrightness(brightness.takeUnless { it < 0f })
                    newFixedLengthResponse("Brightness set to $brightness")
                } else {
                    newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "Brightness parameter missing or invalid (0.00 to 1.00, or -1.00 for system default)",
                    )
                }
            }
            else -> newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Unknown command")
        }
    }

    private fun json(status: Response.Status, value: JSONObject): Response =
        newFixedLengthResponse(status, "application/json", value.toString())

    private fun status(code: Int): Response.Status = when (code) {
        200 -> Response.Status.OK
        400 -> Response.Status.BAD_REQUEST
        401 -> Response.Status.UNAUTHORIZED
        404 -> Response.Status.NOT_FOUND
        429 -> Response.Status.TOO_MANY_REQUESTS
        else -> Response.Status.INTERNAL_ERROR
    }

    companion object {
        const val PORT = 53287
        private const val MAX_BODY_BYTES = 4096
        /** Home Assistant polls with one or two connections; more are refused, not queued. */
        const val MAX_CONNECTIONS = 8
    }
}

/**
 * Runs each connection on its own thread like NanoHTTPD's default, but closes a connection
 * arriving while [maxConnections] are open. Only NanoHTTPD's accept thread calls [exec], so the
 * check cannot race another exec; a connection ending concurrently only frees a slot.
 */
internal class BoundedAsyncRunner(private val maxConnections: Int) : NanoHTTPD.DefaultAsyncRunner() {
    override fun exec(clientHandler: NanoHTTPD.ClientHandler) {
        if (running.size >= maxConnections) {
            clientHandler.close()
            return
        }
        super.exec(clientHandler)
    }
}

internal class ClientRateLimiters(
    private val limit: Int = 60,
    private val windowMillis: Long = 60_000,
    private val maxClients: Int = 64,
) {
    private val clients = object : LinkedHashMap<String, SlidingWindowRateLimiter>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, SlidingWindowRateLimiter>?): Boolean =
            size > maxClients
    }

    @Synchronized
    fun allow(client: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        clients.getOrPut(client.ifBlank { "unknown" }) {
            SlidingWindowRateLimiter(limit, windowMillis)
        }.allow(nowMillis)

    @Synchronized
    fun isLimited(client: String, nowMillis: Long = System.currentTimeMillis()): Boolean =
        clients[client.ifBlank { "unknown" }]?.isLimited(nowMillis) == true
}
