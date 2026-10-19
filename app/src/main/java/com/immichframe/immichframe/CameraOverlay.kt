package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.TextView
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Shows a camera snapshot over everything but notifications, then hides it after the request's
 * duration. A new request replaces the one showing. Main thread only.
 */
internal class CameraOverlay(
    private val container: View,
    private val image: ImageView,
    private val caption: TextView,
    private val scope: CoroutineScope,
    /** The largest dimension worth decoding, normally the screen's. */
    private val maxDimension: () -> Int,
    /** Called once a takeover ends, unless another replaced it. */
    private val onEnded: () -> Unit,
) {
    private var job: Job? = null
    private var generation = 0L
    private var shownBitmap: Bitmap? = null
    private var active = false

    /**
     * From [show] until the takeover has ended, including while the first snapshot loads. Not the
     * job's own state, which is still active while its cleanup calls [onEnded].
     */
    val isActive: Boolean get() = active

    fun show(request: CameraRequest, url: HttpUrl) {
        val token = ++generation
        active = true
        job?.cancel()
        job = scope.launch(Dispatchers.Main) {
            val endsAt = SystemClock.elapsedRealtime() + request.durationSeconds * 1_000L
            try {
                var shown = false
                while (true) {
                    val dimension = maxDimension()
                    val bitmap = withContext(Dispatchers.IO) {
                        try {
                            fetch(url, dimension)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            // Any failure, not only I/O: an uncaught one would crash the frame.
                            Log.w(TAG, "Camera snapshot failed: ${e.message}")
                            null
                        }
                    }
                    if (bitmap != null) {
                        setBitmap(bitmap)
                        if (!shown) reveal(request.title)
                        shown = true
                    } else if (!shown) {
                        // Nothing to show: end now rather than cover the slideshow with black.
                        return@launch
                    }
                    val remaining = endsAt - SystemClock.elapsedRealtime()
                    if (request.refreshSeconds == 0 || remaining <= 0) break
                    delay(minOf(request.refreshSeconds * 1_000L, remaining))
                    if (SystemClock.elapsedRealtime() >= endsAt) break
                }
                delay(endsAt - SystemClock.elapsedRealtime())
            } finally {
                // A replacing request keeps the overlay; only the newest takeover hides it.
                if (token == generation) {
                    active = false
                    hide()
                    onEnded()
                }
            }
        }
    }

    fun dismiss() {
        job?.cancel()
    }

    private fun reveal(title: String?) {
        caption.text = title.orEmpty()
        caption.visibility = if (title.isNullOrEmpty()) View.GONE else View.VISIBLE
        container.visibility = View.VISIBLE
    }

    private fun hide() {
        container.visibility = View.GONE
        image.setImageDrawable(null)
        shownBitmap?.recycle()
        shownBitmap = null
    }

    private fun setBitmap(bitmap: Bitmap) {
        val previous = shownBitmap
        image.setImageBitmap(bitmap)
        shownBitmap = bitmap
        previous?.recycle()
    }

    private fun fetch(url: HttpUrl, maxDimension: Int): Bitmap {
        // A separate client: the ImmichFrame client adds the server secret to every request.
        client.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            val body = response.body ?: throw IOException("Empty snapshot")
            if (body.contentLength() > MAX_BYTES) throw IOException("Snapshot too large")
            val bytes = body.source().use { source ->
                if (source.request(MAX_BYTES + 1L)) throw IOException("Snapshot too large")
                source.buffer.readByteArray()
            }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("Not an image")
            val options = BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.RGB_565
                inSampleSize = Helpers.decodeSampleSize(bounds.outWidth, bounds.outHeight, maxDimension)
            }
            return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
                ?: throw IOException("Undecodable snapshot")
        }
    }

    private companion object {
        const val TAG = "CameraOverlay"
        const val MAX_BYTES = 10 * 1024 * 1024
        val client: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .build()
    }
}
