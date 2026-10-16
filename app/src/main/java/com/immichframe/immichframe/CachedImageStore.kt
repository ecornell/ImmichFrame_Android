package com.immichframe.immichframe

import android.content.Context
import android.util.AtomicFile
import android.util.Base64
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.util.concurrent.Executors

/** A bounded, throttled last-known image cache for offline startup. */
internal object CachedImageStore {
    private const val FILE_NAME = "last_image.bin"
    private const val LEGACY_FILE_NAME = "last_image.json"
    private const val MAGIC = 0x494D4631 // IMF1
    private const val MAX_BYTES = 24 * 1024 * 1024
    private const val DEFAULT_MIN_WRITE_INTERVAL_MILLIS = 15 * 60_000L
    private val executor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "ImmichFrame-cache").apply { priority = Thread.MIN_PRIORITY }
    }
    private val lock = Any()
    private var lastSuccessfulWriteAt = 0L
    private var writePending = false

    fun writeIfStale(
        context: Context,
        image: Helpers.PreparedImage,
        minIntervalMillis: Long = DEFAULT_MIN_WRITE_INTERVAL_MILLIS,
    ) {
        val now = System.currentTimeMillis()
        val file = File(context.filesDir, FILE_NAME)
        synchronized(lock) {
            if (writePending) return
            val newestWrite = maxOf(lastSuccessfulWriteAt, file.lastModified())
            if (newestWrite > 0L && now - newestWrite < minIntervalMillis) return
            writePending = true
        }
        val appContext = context.applicationContext
        executor.execute {
            val successful = runCatching { writeNow(appContext, image) }.getOrDefault(false)
            synchronized(lock) {
                writePending = false
                if (successful) lastSuccessfulWriteAt = System.currentTimeMillis()
            }
        }
    }

    private fun writeNow(context: Context, image: Helpers.PreparedImage): Boolean {
        val response = image.response
        val thumb = runCatching {
            Base64.decode(response.thumbHashImageBase64, Base64.DEFAULT)
        }.getOrDefault(ByteArray(0))
        val date = response.photoDate.toByteArray(Charsets.UTF_8)
        val location = response.imageLocation.toByteArray(Charsets.UTF_8)
        val estimatedSize = image.encodedBytes.size + thumb.size + date.size + location.size + 32
        if (estimatedSize > MAX_BYTES) return false

        synchronized(lock) {
            val atomicFile = AtomicFile(File(context.filesDir, FILE_NAME))
            val output = atomicFile.startWrite()
            return try {
                val data = DataOutputStream(BufferedOutputStream(output))
                data.writeInt(MAGIC)
                writeBytes(data, date)
                writeBytes(data, location)
                writeBytes(data, thumb)
                writeBytes(data, image.encodedBytes)
                data.flush()
                atomicFile.finishWrite(output)
                File(context.filesDir, LEGACY_FILE_NAME).delete()
                true
            } catch (_: Exception) {
                atomicFile.failWrite(output)
                false
            }
        }
    }

    /** Reads the cached image without re-encoding its bytes; null if missing or undecodable. */
    fun readPrepared(context: Context): Helpers.PreparedImage? = synchronized(lock) {
        readBinaryPrepared(File(context.filesDir, FILE_NAME))
    } ?: read(context)?.let { legacy -> runCatching { Helpers.prepareImage(legacy) }.getOrNull() }

    fun read(context: Context): Helpers.ImageResponse? = synchronized(lock) {
        readBinary(File(context.filesDir, FILE_NAME))
            ?: readLegacy(File(context.filesDir, LEGACY_FILE_NAME))
    }

    private fun readBinary(file: File): Helpers.ImageResponse? = readBinaryParts(file)?.let { parts ->
        Helpers.ImageResponse(
            Base64.encodeToString(parts.image, Base64.NO_WRAP),
            Base64.encodeToString(parts.thumb, Base64.NO_WRAP),
            parts.date,
            parts.location,
        )
    }

    private fun readBinaryPrepared(file: File): Helpers.PreparedImage? = readBinaryParts(file)?.let { parts ->
        runCatching {
            Helpers.prepareImage(
                Helpers.ImageResponse(
                    "",
                    Base64.encodeToString(parts.thumb, Base64.NO_WRAP),
                    parts.date,
                    parts.location,
                ),
                parts.image,
            )
        }.getOrNull()
    }

    private class Parts(val date: String, val location: String, val thumb: ByteArray, val image: ByteArray)

    private fun readBinaryParts(file: File): Parts? = runCatching {
        val atomicFile = AtomicFile(file)
        DataInputStream(BufferedInputStream(atomicFile.openRead())).use { data ->
            require(atomicFile.baseFile.length() in 1..MAX_BYTES.toLong())
            require(data.readInt() == MAGIC)
            val date = readBytes(data).toString(Charsets.UTF_8)
            val location = readBytes(data).toString(Charsets.UTF_8)
            Parts(date, location, readBytes(data), readBytes(data))
        }
    }.getOrNull()

    private fun readLegacy(file: File): Helpers.ImageResponse? = runCatching {
        if (!file.isFile || file.length() !in 1..MAX_BYTES.toLong()) return null
        val json = JSONObject(file.readText())
        Helpers.ImageResponse(
            json.getString("image"),
            json.optString("thumb"),
            json.optString("date"),
            json.optString("location"),
        )
    }.getOrNull()

    private fun writeBytes(output: DataOutputStream, bytes: ByteArray) {
        output.writeInt(bytes.size)
        output.write(bytes)
    }

    private fun readBytes(input: DataInputStream): ByteArray {
        val size = input.readInt()
        require(size in 0..MAX_BYTES)
        return ByteArray(size).also(input::readFully)
    }
}
