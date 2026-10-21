package com.immichframe.immichframe

import android.content.Context
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import android.view.TextureView
import java.util.concurrent.Executors

/** Fit/crop scale factors for a TextureView, which otherwise stretches video to its own bounds. */
internal object VideoScaling {
    fun scale(
        viewWidth: Int,
        viewHeight: Int,
        videoWidth: Int,
        videoHeight: Int,
        crop: Boolean,
    ): Pair<Float, Float> {
        if (viewWidth <= 0 || viewHeight <= 0 || videoWidth <= 0 || videoHeight <= 0) return 1f to 1f
        val fitX = viewWidth.toFloat() / videoWidth
        val fitY = viewHeight.toFloat() / videoHeight
        val factor = if (crop) maxOf(fitX, fitY) else minOf(fitX, fitY)
        return (videoWidth * factor / viewWidth) to (videoHeight * factor / viewHeight)
    }
}

/**
 * Upper bounds for one video slide. A stalled stream or a decoder that never reports completion
 * must not freeze an unattended frame, so the slideshow timer always remains armed.
 */
internal object VideoPlaybackPolicy {
    const val STARTUP_GRACE_MILLIS = 30_000L
    const val STALL_GRACE_MILLIS = 15_000L

    /** Before the player is prepared, trust the server duration (or the photo interval). */
    fun startupWatchdogMillis(serverDurationSeconds: Double?, intervalSeconds: Int): Long {
        val expected = serverDurationSeconds?.takeIf { it > 0.0 }?.let { (it * 1_000).toLong() }
            ?: (intervalSeconds * 1_000L)
        return expected + STARTUP_GRACE_MILLIS
    }

    /** Once prepared, the decoder's duration is authoritative. */
    fun playingWatchdogMillis(durationMillis: Int, intervalSeconds: Int): Long {
        val expected = if (durationMillis > 0) durationMillis.toLong() else intervalSeconds * 1_000L
        return expected + STALL_GRACE_MILLIS
    }
}

/**
 * Streams one video at a time into a TextureView layered above the poster frame. The view stays
 * transparent until the first decoded frame arrives, so a slow or failed stream simply leaves the
 * poster visible. Uses the platform MediaPlayer to avoid a media dependency on 1 GB frames.
 */
internal class NativeVideoPlayer(
    private val context: Context,
    private val textureView: TextureView,
) : TextureView.SurfaceTextureListener {
    interface Listener {
        fun onVideoPrepared(durationMillis: Int)
        fun onVideoFinished()
        fun onVideoFailed()
    }

    private class Request(
        val uri: Uri,
        val headers: Map<String, String>,
        val crop: Boolean,
        val playAudio: Boolean,
        val revealMillis: Long,
        val listener: Listener,
    )

    private var player: MediaPlayer? = null
    private var surface: Surface? = null
    private var pending: Request? = null
    private var current: Request? = null
    private var generation = 0L
    private var prepared = false
    private var paused = false
    private var revealed = false
    private var completed = false
    private var videoWidth = 0
    private var videoHeight = 0
    /** Players handed to [releaser] whose release has not finished; see [releasePlayer]. */
    private var releasesInFlight = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    init {
        textureView.surfaceTextureListener = this
        textureView.alpha = 0f
    }

    /** False once a video has finished; its last frame stays up until the next slide. */
    val isActive: Boolean get() = (player != null && !completed) || pending != null

    fun play(
        uri: Uri,
        headers: Map<String, String>,
        crop: Boolean,
        playAudio: Boolean,
        revealMillis: Long,
        listener: Listener,
    ) {
        // Keep any fade-out of the previous video's last frame running; the new video's first
        // frame cancels it on reveal, so back-to-back videos crossfade instead of cutting.
        releasePlayer()
        paused = false
        val request = Request(uri, headers, crop, playAudio, revealMillis, listener)
        pending = request
        startPendingIfReady()
    }

    /**
     * Starts the pending request once the texture is available and no earlier player is still
     * releasing: a SurfaceTexture takes one producer at a time, so a new player attached while
     * the old one is still connected would fail and leave only the poster.
     */
    private fun startPendingIfReady() {
        if (releasesInFlight > 0) return
        val request = pending ?: return
        val texture = textureView.surfaceTexture
        if (!textureView.isAvailable || texture == null) return
        pending = null
        start(request, texture)
    }

    /** Pausing before the stream is prepared keeps it from starting once it is. */
    fun pause() {
        paused = true
        val active = player ?: return
        if (prepared && active.isPlaying) active.pause()
    }

    fun resume() {
        paused = false
        val active = player ?: return
        // A finished video stays prepared; starting it again would replay it from the beginning.
        if (prepared && !completed && !active.isPlaying) active.start()
    }

    /** Stops playback and fades the last decoded frame out alongside the next slide. */
    fun fadeOutAndRelease(fadeMillis: Long) {
        if (player == null && pending == null && textureView.alpha == 0f) return
        releasePlayer()
        textureView.animate().cancel()
        textureView.animate().alpha(0f).setDuration(fadeMillis.coerceAtLeast(0L)).start()
    }

    fun release() {
        releasePlayer()
        textureView.animate().cancel()
        textureView.alpha = 0f
    }

    private fun start(request: Request, texture: SurfaceTexture) {
        val token = ++generation
        current = request
        prepared = false
        revealed = false
        completed = false
        videoWidth = 0
        videoHeight = 0
        val mediaPlayer = MediaPlayer()
        val output = Surface(texture)
        player = mediaPlayer
        surface = output
        try {
            mediaPlayer.setDataSource(context, request.uri, request.headers)
            mediaPlayer.setSurface(output)
            if (!request.playAudio) mediaPlayer.setVolume(0f, 0f)
            mediaPlayer.setOnVideoSizeChangedListener { _, width, height ->
                if (token != generation) return@setOnVideoSizeChangedListener
                videoWidth = width
                videoHeight = height
                applyScale()
            }
            mediaPlayer.setOnPreparedListener {
                if (token != generation) return@setOnPreparedListener
                prepared = true
                if (!paused) it.start()
                request.listener.onVideoPrepared(it.duration)
            }
            mediaPlayer.setOnCompletionListener {
                if (token != generation) return@setOnCompletionListener
                completed = true
                request.listener.onVideoFinished()
            }
            mediaPlayer.setOnErrorListener { _, what, extra ->
                if (token == generation) {
                    Log.w(TAG, "Video playback failed ($what/$extra); keeping the poster frame")
                    releaseAfterFailure()
                    request.listener.onVideoFailed()
                }
                true
            }
            mediaPlayer.prepareAsync()
        } catch (error: Exception) {
            Log.w(TAG, "Unable to start video; keeping the poster frame", error)
            releaseAfterFailure()
            request.listener.onVideoFailed()
        }
    }

    /** Before the reveal the view is transparent or still fading out a previous video; let that finish. */
    private fun releaseAfterFailure() {
        if (revealed) release() else releasePlayer()
    }

    /**
     * Detaches the current player and releases it on [releaser]: reset() and release() wait for a
     * stalled HTTP prepare and would block the main thread. Its listeners are cleared first and
     * nothing here touches it after the handoff; the next video starts once it is gone.
     */
    private fun releasePlayer() {
        generation++
        pending = null
        current = null
        prepared = false
        completed = false
        val active = player
        val output = surface
        player = null
        surface = null
        if (active == null) {
            output?.release()
            return
        }
        active.setOnVideoSizeChangedListener(null)
        active.setOnPreparedListener(null)
        active.setOnCompletionListener(null)
        active.setOnErrorListener(null)
        releasesInFlight++
        releaser.execute {
            runCatching { active.reset() }
            runCatching { active.release() }
            output?.release()
            mainHandler.post {
                releasesInFlight--
                startPendingIfReady()
            }
        }
    }

    private fun applyScale() {
        val request = current ?: return
        val width = textureView.width
        val height = textureView.height
        val (scaleX, scaleY) = VideoScaling.scale(width, height, videoWidth, videoHeight, request.crop)
        textureView.setTransform(Matrix().apply { setScale(scaleX, scaleY, width / 2f, height / 2f) })
    }

    override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
        startPendingIfReady()
    }

    override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) {
        applyScale()
    }

    override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
        val listener = (current ?: pending)?.listener
        release()
        listener?.onVideoFailed()
        return true
    }

    override fun onSurfaceTextureUpdated(texture: SurfaceTexture) {
        val request = current ?: return
        // Frames only arrive after start(); waiting for prepared ignores any stale queued frame.
        if (revealed || !prepared) return
        revealed = true
        textureView.animate().cancel()
        textureView.animate().alpha(1f).setDuration(request.revealMillis.coerceAtLeast(0L)).start()
    }

    private companion object {
        const val TAG = "NativeVideo"

        /** One at a time, so releases finish in order; a stalled one delays the next video only. */
        val releaser = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ImmichFrame-video-release").apply { isDaemon = true }
        }
    }
}
