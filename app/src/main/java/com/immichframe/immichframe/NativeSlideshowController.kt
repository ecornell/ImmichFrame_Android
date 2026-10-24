package com.immichframe.immichframe

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.util.Log
import android.view.View
import android.widget.ImageView
import androidx.core.view.doOnNextLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.awaitResponse
import java.io.IOException
import java.util.concurrent.Executors

/** The delay before the next attempt after a failed image: 5 s, doubling to 5 min. */
internal class ImageRetryBackoff(
    private val initialMillis: Long = 5_000L,
    private val maxMillis: Long = 5 * 60_000L,
) {
    private var nextMillis = initialMillis

    /** The delay to wait now; the one after it doubles. */
    fun next(): Long = nextMillis.also { nextMillis = (it * 2).coerceAtMost(maxMillis) }

    /** A presented live image starts the backoff over. */
    fun reset() {
        nextMillis = initialMillis
    }
}

/**
 * The native slideshow shared by the activity and the screensaver: server settings and their
 * 30-second refresh, weather, slide building with one prefetched slide on a single decode
 * thread, presentation, optional video, the offline image cache, and retry backoff. Everything
 * runs on the main thread except slide builds and cache I/O.
 */
internal class NativeSlideshowController(
    private val context: Context,
    private val host: Host,
    /** The window's root view: slides are rendered at its size once it has been laid out. */
    private val frameView: View,
    private val imageView1: ImageView,
    private val imageView2: ImageView,
    private val overlay: OverlayController,
    /** Null where videos are not played (the screensaver); only photos are requested then. */
    private val videoPlayer: NativeVideoPlayer?,
) {
    interface Host {
        val scope: CoroutineScope

        /** False once the host is finishing; a rendered slide is then discarded. */
        fun isAlive(): Boolean

        /** The display is off: nothing is fetched, applied or advanced. */
        fun isInactive(): Boolean = false

        /** Something covers the slideshow (a camera snapshot); it holds still underneath. */
        fun isHeld(): Boolean = false

        /** Whether to ask the server for photo metadata. Called off the main thread. */
        fun includeMetadata(): Boolean = false

        /** [hasImage] is whether a frame is on screen to fall back on. */
        fun onConnectionChanged(offline: Boolean, imageSucceeded: Boolean, hasImage: Boolean) = Unit

        /** The viewer paused or resumed; see [isPausedByViewer]. */
        fun onPauseChanged(paused: Boolean) = Unit

        /** Called on the main thread right after [frame] is presented, before it can be recycled. */
        fun onPhotoShown(images: List<Helpers.PreparedImage>, source: String, frame: Bitmap) = Unit

        /** The cached or retained startup image is on screen. */
        fun onStartupImageShown() = Unit

        fun onSettingsLoadFailed(error: Throwable) = Unit
    }

    private val handler = Handler(Looper.getMainLooper())
    private val framePresenter = NativeFramePresenter(imageView1, imageView2)
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var authSecret = ""
    private var includeVideos = false
    private var weatherCall: Call<Helpers.Weather>? = null
    private var settingsCall: Call<Helpers.ServerSettings>? = null
    private var settingsRetryRunnable: Runnable? = null
    private var hasAppliedSettingsForSession = false
    private var imageJob: Job? = null
    private val playbackSession = PlaybackSession()
    private var nativeSessionToken = 0L
    private var imageGeneration = 0L
    private val retryBackoff = ImageRetryBackoff()
    var preferences = FramePreferences()

    // Kept decoded: live responses no longer carry the image as Base64.
    private var previousImage: Helpers.PreparedImage? = null
    private var currentImage: Helpers.PreparedImage? = null
    private val nextSlides = NextSlideQueue<BuiltSlide> { it.frame.bitmap.recycle() }
    private var slideJob: Job? = null
    private var slideBuildGeneration = 0L

    /** A fetched image that the last slide could not use; the next slide shows it first. */
    @Volatile private var heldImage: Helpers.PreparedImage? = null

    private val prefetchRunnable = Runnable { prefetchNextSlide() }
    private val imageTimer = PlaybackTimer(
        { task, delay -> handler.postDelayed(task, delay) }, handler::removeCallbacks,
        { serverSettings.interval * 1_000L }, ::getNextImage,
    )
    private val weatherTimer = PlaybackTimer(
        { task, delay -> handler.postDelayed(task, delay) }, handler::removeCallbacks,
        { 600_000L }, ::getWeather,
    )
    private val settingsRefreshTimer = AsyncRefreshTimer(
        { task, delay -> handler.postDelayed(task, delay) }, handler::removeCallbacks,
        SERVER_SETTINGS_REFRESH_MILLIS, ::refreshServerSettings,
    )

    /** Whether the image timer runs: false while paused, but also while stopped or asleep. */
    val isPlaying: Boolean get() = imageTimer.isRunning

    /**
     * The viewer's pause, which outlives [stop] and [start]: the display sleeping or the settings
     * screen stops the timer without pausing, and a paused frame wakes or returns still paused.
     */
    var isPausedByViewer = false
        private set

    private fun setPausedByViewer(paused: Boolean) {
        if (paused == isPausedByViewer) return
        isPausedByViewer = paused
        host.onPauseChanged(paused)
    }

    /**
     * Starts a playback session against [url]. The last cached settings and image are shown at
     * once; the live settings replace them when the server answers.
     */
    fun start(url: String, authSecret: String, clientIdentifier: String, includeVideos: Boolean) {
        nativeSessionToken = playbackSession.activate()
        hasAppliedSettingsForSession = false
        this.authSecret = authSecret
        this.includeVideos = includeVideos && videoPlayer != null
        retrofit = Helpers.createRetrofit(url, authSecret, clientIdentifier)
        apiService = retrofit!!.create(Helpers.ApiService::class.java)

        // Render the last successful image immediately at cold start. The image cache needs
        // its matching server presentation settings, so retain both independently of the
        // live request and refresh them in the background when the server returns.
        if (!host.isInactive()) {
            CachedServerSettingsStore.read(context)?.let { cachedSettings ->
                setOffline(true)
                applyServerSettings(cachedSettings)
            }
        }
        getServerSettings(
            onSuccess = { settings ->
                if (!host.isInactive()) {
                    // The server answered, so clear the indicator raised for the cached frame.
                    setOffline(false, imageSucceeded = false)
                    if (applyServerSettings(settings)) {
                        CachedServerSettingsStore.write(context, settings)
                    }
                    settingsRefreshTimer.start()
                }
            },
            onFailure = host::onSettingsLoadFailed,
            token = nativeSessionToken,
        )
    }

    /** Ends the session: timers, requests and in-flight slides stop and cannot present. */
    fun stop() {
        playbackSession.invalidate()
        stopImageTimer()
        videoPlayer?.release()
        stopWeatherTimer()
        settingsRefreshTimer.stop()
        settingsCall?.cancel()
        settingsCall = null
        settingsRetryRunnable?.let(handler::removeCallbacks)
        settingsRetryRunnable = null
        clearNextSlides()
    }

    fun destroy() {
        framePresenter.clear()
        stop()
        handler.removeCallbacksAndMessages(null)
    }

    fun pauseZoom() = framePresenter.pauseZoom()

    fun next() {
        setPausedByViewer(false)
        stopImageTimer()
        // Restart first: a prefetched video presents synchronously and arms its watchdog
        // through the running timer.
        startImageTimer()
        getNextImage()
    }

    fun previous() {
        val safePreviousImage = previousImage ?: return
        setPausedByViewer(false)
        stopImageTimer()
        showImage(
            safePreviousImage,
            afterPresented = { frame ->
                host.onPhotoShown(listOf(safePreviousImage), CurrentPhoto.SOURCE_SERVER, frame.bitmap)
            },
        )
        startImageTimer()
    }

    fun togglePause() {
        framePresenter.pauseZoom()
        setPausedByViewer(!isPausedByViewer)
        if (isPausedByViewer) {
            stopImageTimer()
        } else if (!host.isInactive() && hasAppliedSettingsForSession && playbackSession.isCurrent(nativeSessionToken)) {
            startImageTimer()
            getNextImage()
        }
        // Otherwise asleep, or between sessions: the next session starts playing.
    }

    /**
     * Shows one specific image alone: the cached image at startup, or the previous image. Live
     * playback goes through [getNextImage], which builds slides ahead of time.
     */
    private fun showImage(
        prepared: Helpers.PreparedImage,
        playVideo: Boolean = true,
        afterPresented: ((RenderedFrame) -> Unit)? = null,
    ) {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token)) return
        val imageResponse = prepared.response
        val isVideo = imageResponse.isVideo
        val generation = ++imageGeneration
        imageJob?.cancel()
        imageJob = null
        val spec = currentSlideSpec()
        if (spec == null) {
            frameView.doOnNextLayout {
                if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                    showImage(prepared, playVideo, afterPresented)
                }
            }
            return
        }
        imageJob = host.scope.launch(slideDispatcher) {
            var rendered: RenderedFrame? = null
            var handedOff = false
            try {
                rendered = renderSlide(spec, prepared, null)
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && playbackSession.isCurrent(token) && host.isAlive()) {
                        updateUI(rendered, zoomEnabled = serverSettings.imageZoom && !isVideo)
                        handedOff = true
                        if (isVideo && playVideo) startVideo(imageResponse, token) else imageTimer.reschedule()
                        afterPresented?.invoke(rendered)
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                        // A local render failure (such as a corrupt cached image) says nothing about
                        // the server, so it does not raise the offline indicator.
                        Log.e(TAG, "Unable to render image; retaining the current frame", error)
                        scheduleImageRetry()
                    }
                }
            } finally {
                if (!handedOff) rendered?.bitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private data class SlideSpec(
        val width: Int,
        val height: Int,
        val scale: FrameScale,
        val background: FrameBackground,
        val gapPx: Int,
        val featherPx: Int,
        val blurRadius: Int,
        val darkness: Int,
        val layout: String,
    )

    /** A rendered slide waiting to be presented; [image] is the newest image it shows. */
    private class BuiltSlide(
        val frame: RenderedFrame,
        val image: Helpers.PreparedImage,
        /** Every photo on the slide in display order; [image] is the last. */
        val images: List<Helpers.PreparedImage>,
        val spec: SlideSpec,
        val token: Long,
    )

    private fun updateUI(frame: RenderedFrame, zoomEnabled: Boolean) {
        val transitionMillis = (serverSettings.transitionDuration * 1_000).toLong()
        videoPlayer?.fadeOutAndRelease(transitionMillis)
        framePresenter.present(
            frame = frame,
            transitionMillis = transitionMillis,
            zoomEnabled = zoomEnabled,
            zoomDurationMillis = (serverSettings.interval * 1_000).toLong(),
            zoomFramesPerSecond = preferences.zoomFrameRate,
            transitionFramesPerSecond = preferences.transitionFrameRate,
        ) {
            overlay.showPhotoInfo(it.left, it.right)
            overlay.updateDateTimeWeather()
        }
    }

    private fun startVideo(imageResponse: Helpers.ImageResponse, token: Long) {
        val player = videoPlayer ?: return
        val assetId = imageResponse.assetId ?: return
        val baseUrl = retrofit?.baseUrl()?.toString() ?: return
        val intervalSeconds = serverSettings.interval
        // Keep the slideshow timer armed for the whole video: it advances on completion and the
        // watchdog still advances a frame whose stream stalls or never reports completion.
        imageTimer.retryAfter(
            VideoPlaybackPolicy.startupWatchdogMillis(imageResponse.durationSeconds, intervalSeconds),
        )
        player.play(
            uri = Uri.parse(Helpers.videoUrl(baseUrl, assetId)),
            headers = if (authSecret.isBlank()) emptyMap()
            else mapOf("Authorization" to "Bearer $authSecret"),
            crop = preferences.frameScale == FrameScale.CROP,
            playAudio = serverSettings.playAudio,
            revealMillis = (serverSettings.transitionDuration * 1_000).toLong(),
            listener = object : NativeVideoPlayer.Listener {
                override fun onVideoPrepared(durationMillis: Int) {
                    if (!playbackSession.isCurrent(token)) return
                    imageTimer.retryAfter(
                        VideoPlaybackPolicy.playingWatchdogMillis(durationMillis, intervalSeconds),
                    )
                }

                override fun onVideoFinished() {
                    if (playbackSession.isCurrent(token)) imageTimer.retryAfter(0L)
                }

                override fun onVideoFailed() {
                    // Show the poster like a photo for the normal interval.
                    if (playbackSession.isCurrent(token)) imageTimer.retryAfter(intervalSeconds * 1_000L)
                }
            },
        )
    }

    /** Advances live playback: presents the prefetched slide, or builds one now. */
    private fun getNextImage() {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token) || imageJob?.isActive == true ||
            !::apiService.isInitialized || host.isInactive() || host.isHeld()
        ) return
        val spec = currentSlideSpec()
        if (spec == null) {
            frameView.doOnNextLayout {
                if (playbackSession.isCurrent(token) && isPlaying) getNextImage()
            }
            return
        }
        when (val advance = nextSlides.advance { it.token == token && it.spec == spec }) {
            is NextSlideQueue.Advance.Present -> presentSlide(advance.slide, token)
            NextSlideQueue.Advance.Waiting -> Unit
            NextSlideQueue.Advance.Build -> buildSlide(token, spec, prefetch = false)
        }
    }

    private fun currentSlideSpec(): SlideSpec? {
        val width = frameView.width
        val height = frameView.height
        if (width <= 1 || height <= 1) return null
        return SlideSpec(
            width, height, preferences.frameScale, preferences.frameBackground, preferences.splitGapPx,
            preferences.edgeFeatherPx, preferences.backgroundBlurRadius, preferences.backgroundDarkness,
            serverSettings.layout,
        )
    }

    private fun renderSlide(
        spec: SlideSpec,
        first: Helpers.PreparedImage,
        second: Helpers.PreparedImage?,
    ): RenderedFrame = FrameRenderer.render(
        spec.width, spec.height, first, second, spec.scale, spec.background, spec.gapPx,
        spec.featherPx, spec.blurRadius, spec.darkness,
    )

    private fun buildSlide(token: Long, spec: SlideSpec, prefetch: Boolean) {
        val generation = slideBuildGeneration
        val api = apiService
        val includeVideos = includeVideos
        slideJob = host.scope.launch(slideDispatcher) {
            // A prefetch is not latency-critical, so it yields to frame rendering. It stays out of
            // the background cgroup, where a build that playback ends up waiting on would be throttled.
            Process.setThreadPriority(if (prefetch) PREFETCH_THREAD_PRIORITY else Process.THREAD_PRIORITY_DEFAULT)
            var built: BuiltSlide? = null
            try {
                built = fetchAndRenderSlide(api, includeVideos, spec, token)
                withContext(Dispatchers.Main) {
                    if (generation != slideBuildGeneration || !playbackSession.isCurrent(token)) {
                        return@withContext
                    }
                    val slide = built ?: return@withContext
                    built = null
                    nextSlides.onBuilt(slide)?.let { requested ->
                        // Settings may have changed while playback waited on this build.
                        if (requested.spec == currentSlideSpec()) {
                            presentSlide(requested, token)
                        } else {
                            requested.frame.bitmap.recycle()
                            getNextImage()
                        }
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) {
                    if (generation != slideBuildGeneration || !playbackSession.isCurrent(token)) {
                        return@withContext
                    }
                    // A failed prefetch leaves the current frame alone; the next advance retries.
                    if (nextSlides.onFailed()) {
                        Log.e(TAG, "Unable to load the next image; retaining the current frame", error)
                        setOffline(true)
                        scheduleImageRetry()
                    } else {
                        Log.w(TAG, "Unable to prefetch the next image: ${error.message}")
                    }
                }
            } finally {
                built?.frame?.bitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private suspend fun fetchAndRenderSlide(
        api: Helpers.ApiService,
        includeVideos: Boolean,
        spec: SlideSpec,
        token: Long,
    ): BuiltSlide {
        val held = heldImage
        heldImage = null
        var first = held ?: fetchImage(api, includeVideos)
        var second: Helpers.PreparedImage? = null
        // In split view a portrait waits for the next image: another portrait pairs with it,
        // anything else is shown alone and the portrait waits for the following slide.
        if (waitsForPortraitPair(first, spec.layout)) {
            val next = fetchImage(api, includeVideos)
            val pairing = PlaybackPolicies.pairPortrait(
                first, firstWasHeld = held != null, next, waitsForPortraitPair(next, spec.layout),
            )
            first = pairing.first
            second = pairing.second
            heldImage = pairing.held
        }
        val frame = renderSlide(spec, first, second)
        return BuiltSlide(frame, second ?: first, listOfNotNull(first, second), spec, token)
    }

    private fun waitsForPortraitPair(image: Helpers.PreparedImage, layout: String): Boolean =
        PlaybackPolicies.shouldWaitForPortraitPair(
            image.height > image.width, layout, allowPortraitPair = !image.response.isVideo,
        )

    /**
     * Fetches the next image's metadata, then its raw preview bytes. Older servers ignore
     * includeImage and inline the image as Base64, which is still accepted.
     */
    private suspend fun fetchImage(api: Helpers.ApiService, includeVideos: Boolean): Helpers.PreparedImage {
        val response = api.getImageData(
            includeVideos, includeImage = false, includeMetadata = host.includeMetadata(),
        ).awaitResponse()
        if (!response.isSuccessful) throw IOException("HTTP ${response.code()}: ${response.message()}")
        val info = response.body() ?: throw IOException("Empty image response")
        if (info.hasInlineImage) return Helpers.prepareImage(info)
        val assetId = info.assetId?.takeIf(String::isNotBlank)
            ?: throw IOException("Image response has neither an image nor an asset id")
        val image = api.getAssetBytes(assetId, "IMAGE").awaitResponse()
        if (!image.isSuccessful) throw IOException("HTTP ${image.code()} for image $assetId")
        val bytes = image.body()?.use { it.bytes() } ?: throw IOException("Empty image body for $assetId")
        return Helpers.prepareImage(info, bytes)
    }

    private fun presentSlide(slide: BuiltSlide, token: Long) {
        if (!host.isAlive()) {
            slide.frame.bitmap.recycle()
            return
        }
        ++imageGeneration
        imageJob?.cancel()
        imageJob = null
        val isVideo = slide.image.response.isVideo
        updateUI(slide.frame, zoomEnabled = serverSettings.imageZoom && !isVideo)
        if (isVideo) startVideo(slide.image.response, token)
        // The timer counted from when this slide was requested; a late build would cut it short.
        if (!isVideo) imageTimer.reschedule()
        setOffline(false)
        previousImage = currentImage
        currentImage = slide.image
        host.onPhotoShown(slide.images, CurrentPhoto.SOURCE_SERVER, slide.frame.bitmap)
        retryBackoff.reset()
        // A video poster is not cached for offline startup.
        if (!isVideo) {
            val image = slide.image
            host.scope.launch(Dispatchers.IO) { CachedImageStore.writeIfStale(context, image) }
        }
        schedulePrefetch()
    }

    /** Builds the next slide once the crossfade has finished, so the two never compete. */
    private fun schedulePrefetch() {
        handler.removeCallbacks(prefetchRunnable)
        val transitionMillis = (serverSettings.transitionDuration * 1_000).toLong()
        handler.postDelayed(prefetchRunnable, transitionMillis + PREFETCH_AFTER_TRANSITION_MILLIS)
    }

    private fun prefetchNextSlide() {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token) || !isPlaying || host.isInactive() ||
            !::apiService.isInitialized
        ) return
        val spec = currentSlideSpec() ?: return
        if (nextSlides.beginPrefetch()) buildSlide(token, spec, prefetch = true)
    }

    private fun clearNextSlides() {
        handler.removeCallbacks(prefetchRunnable)
        slideBuildGeneration++
        slideJob?.cancel()
        slideJob = null
        nextSlides.clear()
    }

    private fun scheduleImageRetry() {
        if (!isPlaying || host.isInactive()) return
        imageTimer.retryAfter(retryBackoff.next())
    }

    private fun setOffline(offline: Boolean, imageSucceeded: Boolean = !offline) {
        host.onConnectionChanged(offline, imageSucceeded, hasImage = currentImage != null)
        overlay.setOffline(offline)
    }

    private fun startImageTimer() {
        imageTimer.start()
    }

    private fun stopImageTimer() {
        imageTimer.stop()
        ++imageGeneration
        framePresenter.pauseZoom()
        videoPlayer?.pause()
        // Keep a ready or in-flight next slide for resume, but never present it while stopped.
        nextSlides.withdrawRequest()
        imageJob?.cancel()
        imageJob = null
    }

    private fun stopWeatherTimer() {
        weatherTimer.stop()
        weatherCall?.cancel()
        weatherCall = null
    }

    private fun getWeather() {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token) || host.isInactive() ||
            !::apiService.isInitialized || weatherCall != null
        ) return
        val request = apiService.getWeather()
        weatherCall = request
        request.enqueue(object : Callback<Helpers.Weather> {
            override fun onResponse(call: Call<Helpers.Weather>, response: Response<Helpers.Weather>) {
                if (weatherCall !== call || !playbackSession.isCurrent(token)) return
                weatherCall = null
                if (!host.isInactive() && response.isSuccessful) {
                    response.body()?.let(overlay::setWeather)
                }
            }

            override fun onFailure(call: Call<Helpers.Weather>, t: Throwable) {
                if (weatherCall !== call || !playbackSession.isCurrent(token)) return
                weatherCall = null
                if (!call.isCanceled) Log.e("Weather", "Failed to fetch weather: ${t.message}")
            }
        })
    }

    private fun getServerSettings(
        onSuccess: (Helpers.ServerSettings) -> Unit,
        onFailure: (Throwable) -> Unit,
        maxRetries: Int = Int.MAX_VALUE,
        retryDelayMillis: Long = 5000,
        token: Long = nativeSessionToken,
    ) {
        var retryCount = 0

        fun attemptFetch() {
            if (!playbackSession.isCurrent(token) || host.isInactive()) return
            val request = apiService.getServerSettings()
            settingsCall = request
            request.enqueue(object : Callback<Helpers.ServerSettings> {
                override fun onResponse(
                    call: Call<Helpers.ServerSettings>,
                    response: Response<Helpers.ServerSettings>
                ) {
                    if (settingsCall !== call || !playbackSession.isCurrent(token)) return
                    settingsCall = null
                    if (response.isSuccessful) {
                        val serverSettingsResponse = response.body()
                        if (serverSettingsResponse != null) {
                            onSuccess(serverSettingsResponse)
                        } else {
                            handleFailure(Exception("Empty response body"))
                        }
                    } else {
                        handleFailure(Exception("HTTP ${response.code()}: ${response.message()}"))
                    }
                }

                override fun onFailure(call: Call<Helpers.ServerSettings>, t: Throwable) {
                    if (settingsCall !== call || !playbackSession.isCurrent(token)) return
                    settingsCall = null
                    if (!call.isCanceled) handleFailure(t)
                }

                private fun handleFailure(t: Throwable) {
                    if (!playbackSession.isCurrent(token) || host.isInactive()) return
                    if (retryCount < maxRetries) {
                        retryCount++
                        Runnable { attemptFetch() }.also {
                            settingsRetryRunnable = it
                            handler.postDelayed(it, retryDelayMillis)
                        }
                    } else {
                        onFailure(t)
                    }
                }
            })
        }

        attemptFetch()
    }

    private fun refreshServerSettings(onComplete: () -> Unit) {
        getServerSettings(
            onSuccess = { settings ->
                if (applyServerSettings(settings)) {
                    CachedServerSettingsStore.write(context, settings)
                }
                onComplete()
            },
            onFailure = { error ->
                Log.w("ServerSettings", "Unable to refresh server settings: ${error.message}")
                onComplete()
            },
            maxRetries = 0,
            token = nativeSessionToken,
        )
    }

    private fun applyServerSettings(settings: Helpers.ServerSettings): Boolean {
        if (host.isInactive()) return false
        val previous = if (hasAppliedSettingsForSession) serverSettings else null
        if (previous == settings) return false

        serverSettings = settings
        hasAppliedSettingsForSession = true
        applyServerPresentation()

        if (previous == null) {
            startPlaybackAfterSettingsLoaded()
            return true
        }
        // A playing video owns the timer until it ends or its watchdog fires.
        if (previous.interval != settings.interval && videoPlayer?.isActive != true) imageTimer.reschedule()
        // A prefetched slide rendered for the old layout or colours fails its spec check and is rebuilt.
        overlay.refreshPhotoInfo()
        overlay.updateDateTimeWeather()
        updateWeatherTimer()
        return true
    }

    private fun applyServerPresentation() {
        val scaleType = if (serverSettings.imageFill) {
            ImageView.ScaleType.CENTER_CROP
        } else {
            ImageView.ScaleType.FIT_CENTER
        }
        imageView1.scaleType = scaleType
        imageView2.scaleType = scaleType
        overlay.applyPresentation(serverSettings)
    }

    private fun startPlaybackAfterSettingsLoaded() {
        val current = currentImage
        if (current != null) {
            showStartupImage(current, CurrentPhoto.SOURCE_SERVER)
        } else {
            // The cached image is up to 24 MB and its lock is shared with a background cache
            // write, so read it off the main thread.
            val token = nativeSessionToken
            val generation = imageGeneration
            host.scope.launch {
                val cached = withContext(Dispatchers.IO) { CachedImageStore.readPrepared(context) }
                // A slide presented, or playback stopped or stepped, while reading wins.
                if (!playbackSession.isCurrent(token) || currentImage != null || generation != imageGeneration) {
                    return@launch
                }
                if (cached != null) showStartupImage(cached, CurrentPhoto.SOURCE_CACHE) else getNextImage()
            }
        }
        // The startup image shows either way; a paused viewer just gets no next slide.
        if (!isPausedByViewer) startImageTimer()
        overlay.startClock()
        updateWeatherTimer()
    }

    private fun showStartupImage(image: Helpers.PreparedImage, source: String) {
        val token = nativeSessionToken
        // A lone cached portrait must be usable with no backend available. showImage also
        // waits for layout, and only a successfully presented frame becomes currentImage.
        showImage(
            image,
            playVideo = false,
            afterPresented = { frame ->
                currentImage = image
                host.onPhotoShown(listOf(image), source, frame.bitmap)
                host.onStartupImageShown()
                val generation = imageGeneration
                // getNextImage rejects an active imageJob, so wait for the render to finish.
                imageJob?.invokeOnCompletion { error ->
                    if (error == null) handler.post {
                        if (generation == imageGeneration && playbackSession.isCurrent(token)) getNextImage()
                    }
                }
            },
        )
    }

    private fun updateWeatherTimer() {
        if (serverSettings.showWeatherDescription) weatherTimer.start(0L) else stopWeatherTimer()
    }

    private companion object {
        const val TAG = "NativeFrame"
        const val PREFETCH_AFTER_TRANSITION_MILLIS = 250L
        const val PREFETCH_THREAD_PRIORITY = Process.THREAD_PRIORITY_DEFAULT + 4 * Process.THREAD_PRIORITY_LESS_FAVORABLE

        /**
         * A single thread builds slides for the activity and the screensaver alike, so a
         * prefetch never runs two decodes at once.
         */
        val slideDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ImmichFrame-slides")
        }.asCoroutineDispatcher()
    }
}
