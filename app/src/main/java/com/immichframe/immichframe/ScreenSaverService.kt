package com.immichframe.immichframe

import android.annotation.SuppressLint
import android.os.Handler
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.Toast
import androidx.preference.PreferenceManager
import androidx.core.view.doOnNextLayout
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit

class ScreenSaverService : DreamService() {
    private var webViewRetryScope: CoroutineScope? = null
    private lateinit var web: WebFrameController
    private lateinit var webViewContainer: FrameLayout
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var overlay: OverlayController
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var useWebView = true
    private var framePrefs = FramePreferences()
    private var imageCall: Call<Helpers.ImageResponse>? = null
    private var weatherCall: Call<Helpers.Weather>? = null
    private var settingsCall: Call<Helpers.ServerSettings>? = null
    private var settingsRetryRunnable: Runnable? = null
    private var hasAppliedSettingsForSession = false
    private var imageJob: Job? = null
    private val playbackSession = PlaybackSession()
    private var nativeSessionToken = 0L
    @Volatile private var imageGeneration = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var previousImage: Helpers.ImageResponse? = null
    private var currentImage: Helpers.ImageResponse? = null
    private var portraitCache: Helpers.PreparedImage? = null
    private var portraitCacheGeneration = -1L
    private val portraitCacheLock = Any()
    private lateinit var framePresenter: NativeFramePresenter
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
    @SuppressLint("ClickableViewAccessibility")
    override fun onDreamingStarted() {
        super.onDreamingStarted()
        webViewRetryScope = CoroutineScope(Dispatchers.Main + Job())
        isFullscreen = true
        isInteractive = true
        setContentView(R.layout.screen_saver_view)
        webViewContainer = findViewById(R.id.webViewContainer)
        web = WebFrameController(
            this, webViewContainer, scope = { webViewRetryScope }, isActive = { useWebView },
            openLinksInNewTask = true,
            configureView = { view ->
                view.setOnTouchListener { _, _ ->
                    finish()
                    true
                }
            },
        )
        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        framePresenter = NativeFramePresenter(imageView1, imageView2)
        overlay = OverlayController(
            findViewById(R.id.txtDateTime),
            findViewById(R.id.photoInfoContainer),
            findViewById(R.id.txtPhotoInfoLeft),
            findViewById(R.id.txtPhotoInfoRight),
            findViewById(R.id.txtOfflineIndicator),
            shouldTick = { !useWebView },
        )

        loadSettings()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        if (::framePresenter.isInitialized) framePresenter.clear()
        if (::overlay.isInitialized) overlay.stopClock()
        stopNativePlayback()
        webViewRetryScope?.cancel()
        webViewRetryScope = null
        if (::web.isInitialized) web.destroy()
        handler.removeCallbacksAndMessages(null)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    previousAction()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> {
                    nextAction()
                    return true
                }
                else -> {
                    finish()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun previousAction() {
        if (useWebView) {
            // Simulate a key press
            web.view?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            web.view?.dispatchKeyEvent(event)
        } else {
            val safePreviousImage = previousImage
            if (safePreviousImage != null) {
                stopImageTimer()
                showImage(safePreviousImage)
                startImageTimer()
            }
        }
    }

    private fun nextAction() {
        if (useWebView) {
            // Simulate a key press
            web.view?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            web.view?.dispatchKeyEvent(event)
        } else {
            stopImageTimer()
            getNextImage()
            startImageTimer()
        }
    }

    private fun showImage(
        imageResponse: Helpers.ImageResponse,
        persist: Boolean = true,
        allowPortraitPair: Boolean = true,
        afterPresented: (() -> Unit)? = null,
    ) {
        val scope = webViewRetryScope ?: return
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token)) return
        val generation = ++imageGeneration
        imageJob?.cancel()
        imageJob = null
        val width = window.decorView.width
        val height = window.decorView.height
        if (width <= 1 || height <= 1) {
            window.decorView.doOnNextLayout {
                if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                    showImage(imageResponse, persist, allowPortraitPair, afterPresented)
                }
            }
            return
        }
        imageJob = scope.launch(Dispatchers.IO) {
            var rendered: RenderedFrame? = null
            var handedOff = false
            try {
                val prepared = Helpers.prepareImage(imageResponse)
                if (generation != imageGeneration) return@launch
                val isPortrait = prepared.height > prepared.width
                var first = prepared
                var second: Helpers.PreparedImage? = null
                var waitingForPair = false
                synchronized(portraitCacheLock) {
                    if (generation != imageGeneration) return@launch
                    if (PlaybackPolicies.shouldWaitForPortraitPair(
                            isPortrait, serverSettings.layout, allowPortraitPair,
                        )) {
                        val cached = portraitCache
                        if (cached != null && portraitCacheGeneration == generation - 1L) {
                            first = cached
                            second = prepared
                            portraitCache = null
                            portraitCacheGeneration = -1L
                        } else {
                            portraitCache = prepared
                            portraitCacheGeneration = generation
                            waitingForPair = true
                        }
                    } else {
                        portraitCache = null
                        portraitCacheGeneration = -1L
                    }
                }
                if (waitingForPair) {
                    handler.postDelayed({ if (generation == imageGeneration) getNextImage() }, 100L)
                    return@launch
                }
                rendered = FrameRenderer.render(
                    width, height, first, second, framePrefs.frameScale, framePrefs.frameBackground, framePrefs.splitGapPx,
                    framePrefs.edgeFeatherPx, framePrefs.backgroundBlurRadius, framePrefs.backgroundDarkness,
                )
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                        updateUI(rendered)
                        handedOff = true
                        afterPresented?.invoke()
                    }
                }
                if (persist && handedOff && generation == imageGeneration) {
                    CachedImageStore.writeIfStale(applicationContext, prepared)
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                        Log.e("NativeFrame", "Unable to render dream image; retaining the current frame", error)
                        setOfflineIndicator(true)
                        scheduleImageRetry()
                    }
                }
            } finally {
                if (!handedOff) rendered?.bitmap?.takeUnless { it.isRecycled }?.recycle()
            }
        }
    }

    private fun updateUI(frame: RenderedFrame) {
        framePresenter.present(
            frame = frame,
            transitionMillis = (serverSettings.transitionDuration * 1_000).toLong(),
            zoomEnabled = serverSettings.imageZoom,
            zoomDurationMillis = (serverSettings.interval * 1_000).toLong(),
            zoomFramesPerSecond = framePrefs.zoomFrameRate,
            transitionFramesPerSecond = framePrefs.transitionFrameRate,
        ) {
            overlay.showPhotoInfo(it.left, it.right)
            overlay.updateDateTimeWeather()
        }
    }

    private fun getNextImage() {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token) || imageCall?.isExecuted == true ||
            imageJob?.isActive == true || !::apiService.isInitialized
        ) return
        val call = apiService.getImageData(includeVideos = false, includeImage = true, includeMetadata = false)
        imageCall = call
        call.enqueue(object : Callback<Helpers.ImageResponse> {
            override fun onResponse(
                call: Call<Helpers.ImageResponse>,
                response: Response<Helpers.ImageResponse>
            ) {
                if (imageCall !== call || !playbackSession.isCurrent(token)) return
                imageCall = null
                if (response.isSuccessful) {
                    val imageResponse = response.body()
                    if (imageResponse != null) {
                        showImage(imageResponse, afterPresented = {
                            if (playbackSession.isCurrent(token)) {
                                setOfflineIndicator(false)
                                previousImage = currentImage
                                currentImage = imageResponse
                            }
                        })
                    } else {
                        setOfflineIndicator(true)
                        scheduleImageRetry()
                    }
                } else {
                    // The offline indicator already shows this; a toast every retry would not stop.
                    Log.w("ScreenSaver", "Failed to load image (HTTP ${response.code()})")
                    setOfflineIndicator(true)
                    scheduleImageRetry()
                }
            }

            override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                if (imageCall !== call || !playbackSession.isCurrent(token)) return
                imageCall = null
                if (!call.isCanceled) {
                    setOfflineIndicator(true)
                    scheduleImageRetry()
                }
            }
        })
    }

    private fun scheduleImageRetry() {
        imageTimer.retryAfter(5_000L)
    }

    private fun startImageTimer() {
        imageTimer.start()
    }

    private fun setOfflineIndicator(offline: Boolean) {
        if (::overlay.isInitialized) overlay.setOffline(offline)
    }

    private fun stopImageTimer() {
        imageTimer.stop()
        ++imageGeneration
        if (::framePresenter.isInitialized) framePresenter.pauseZoom()
        imageCall?.cancel()
        imageCall = null
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
        if (!playbackSession.isCurrent(token) || !::apiService.isInitialized || weatherCall != null) return
        val request = apiService.getWeather()
        weatherCall = request
        request.enqueue(object : Callback<Helpers.Weather> {
            override fun onResponse(
                call: Call<Helpers.Weather>,
                response: Response<Helpers.Weather>
            ) {
                if (weatherCall !== call || !playbackSession.isCurrent(token)) return
                weatherCall = null
                if (response.isSuccessful) {
                    val weatherResponse = response.body()
                    if (weatherResponse != null) overlay.setWeather(weatherResponse)
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
            if (!playbackSession.isCurrent(token)) return
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
                    if (!playbackSession.isCurrent(token)) return
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
                    CachedServerSettingsStore.write(applicationContext, settings)
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

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        framePrefs = FramePreferences.load(prefs, resources.displayMetrics.density)
        overlay.setPreferences(framePrefs)
        stopNativePlayback()
        var savedUrl = prefs.getString("webview_url", "") ?: ""
        useWebView = prefs.getBoolean("useWebView", false)
        val authSecret = prefs.getString("authSecret", "") ?: ""

        webViewContainer.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        overlay.hide() // shown after server settings are applied

        if (useWebView) {
            web.load(prefs, savedUrl, authSecret)
        } else {
            if (!Helpers.isValidServerUrl(savedUrl)) {
                // A dream cannot open settings; end it rather than crash the shared process.
                Log.w("ScreenSaver", "Invalid server URL; ending the screensaver")
                finish()
                return
            }
            nativeSessionToken = playbackSession.activate()
            hasAppliedSettingsForSession = false
            web.destroy()
            retrofit = Helpers.createRetrofit(
                savedUrl, authSecret,
                Helpers.clientIdentifier(
                    PreferenceManager.getDefaultSharedPreferences(applicationContext), "screensaver",
                ),
            )
            apiService = retrofit!!.create(Helpers.ApiService::class.java)
            CachedServerSettingsStore.read(applicationContext)?.let { cachedSettings ->
                setOfflineIndicator(true)
                applyServerSettings(cachedSettings)
            }
            getServerSettings(
                onSuccess = { settings ->
                    if (applyServerSettings(settings)) {
                        CachedServerSettingsStore.write(applicationContext, settings)
                    }
                    settingsRefreshTimer.start()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this,
                        "Failed to load server settings: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                },
                token = nativeSessionToken,
            )
        }
    }

    private fun stopNativePlayback() {
        playbackSession.invalidate()
        stopImageTimer()
        stopWeatherTimer()
        settingsRefreshTimer.stop()
        settingsCall?.cancel()
        settingsCall = null
        settingsRetryRunnable?.let(handler::removeCallbacks)
        settingsRetryRunnable = null
        synchronized(portraitCacheLock) {
            portraitCache = null
            portraitCacheGeneration = -1L
        }
    }

    private fun applyServerSettings(settings: Helpers.ServerSettings): Boolean {
        val previous = if (hasAppliedSettingsForSession) serverSettings else null
        if (previous == settings) return false

        serverSettings = settings
        hasAppliedSettingsForSession = true
        applyServerPresentation()

        if (previous == null) {
            startPlaybackAfterSettingsLoaded()
            return true
        }
        if (previous.interval != settings.interval) imageTimer.reschedule()
        if (previous.layout != settings.layout) {
            synchronized(portraitCacheLock) {
                portraitCache = null
                portraitCacheGeneration = -1L
            }
        }
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
        val scope = webViewRetryScope
        if (current != null || scope == null) {
            showStartupImage(current)
        } else {
            // The cached image is up to 24 MB; read it off the main thread.
            val token = nativeSessionToken
            scope.launch {
                val cached = withContext(Dispatchers.IO) { CachedImageStore.read(applicationContext) }
                if (playbackSession.isCurrent(token) && currentImage == null) showStartupImage(cached)
            }
        }
        startImageTimer()
        overlay.startClock()
        updateWeatherTimer()
    }

    private fun showStartupImage(cachedImage: Helpers.ImageResponse?) {
        if (cachedImage != null) {
            val token = nativeSessionToken
            showImage(
                cachedImage,
                persist = false,
                allowPortraitPair = false,
                afterPresented = {
                    currentImage = cachedImage
                    val generation = imageGeneration
                    imageJob?.invokeOnCompletion { error ->
                        if (error == null) handler.post {
                            if (generation == imageGeneration && playbackSession.isCurrent(token)) getNextImage()
                        }
                    }
                },
            )
        } else {
            getNextImage()
        }
    }

    private fun updateWeatherTimer() {
        if (serverSettings.showWeatherDescription) weatherTimer.start(0L) else stopWeatherTimer()
    }

}
