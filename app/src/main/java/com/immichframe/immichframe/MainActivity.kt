package com.immichframe.immichframe

import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.KeyguardManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
import android.view.TextureView
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowCompat
import androidx.preference.PreferenceManager
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import retrofit2.Call
import retrofit2.Callback
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.awaitResponse
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible
import androidx.core.view.doOnNextLayout

class MainActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private lateinit var webViewContainer: FrameLayout
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var videoView: TextureView
    private lateinit var videoPlayer: NativeVideoPlayer
    private lateinit var photoInfoContainer: LinearLayout
    private lateinit var txtPhotoInfoLeft: TextView
    private lateinit var txtPhotoInfoRight: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var txtOfflineIndicator: TextView
    private lateinit var btnPrevious: View
    private lateinit var btnPause: View
    private lateinit var btnNext: View
    private lateinit var dimOverlay: View
    private lateinit var swipeRefreshLayout: View
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var rpcServer: RpcHttpServer? = null
    private var mqttClient: MqttControlClient? = null
    private var remoteConfigurationKey: String? = null
    private val remoteState = RemoteStateStore()
    private val remoteCommands: RemoteCommands by lazy {
        object : RemoteCommands {
            override fun setDisplayActive(active: Boolean) {
                remoteState.update { it.copy(displayActive = active) }
                runOnUiThread { setFrameActive(active) }
            }

            override fun setPaused(paused: Boolean) {
                val current = remoteState.snapshot().paused
                if (current == paused) return
                remoteState.update { it.copy(paused = paused) }
                runOnUiThread { pauseAction() }
            }

            override fun togglePaused() {
                setPaused(!remoteState.snapshot().paused)
            }

            override fun setBrightness(brightness: Float?) {
                remoteState.update { it.copy(brightness = brightness) }
                runOnUiThread { screenBrightnessAction(brightness ?: -1f) }
            }

            override fun next() = runOnUiThread { nextAction() }
            override fun previous() = runOnUiThread { previousAction() }
            override fun refresh() = runOnUiThread { nextAction() }
            override fun openSettings() = runOnUiThread { settingsAction(authorizedOverride = true) }
        }
    }
    private var weatherCall: Call<Helpers.Weather>? = null
    private var settingsCall: Call<Helpers.ServerSettings>? = null
    private var settingsRetryRunnable: Runnable? = null
    private var hasAppliedSettingsForSession = false
    private var imageJob: Job? = null
    private val playbackSession = PlaybackSession()
    private var nativeSessionToken = 0L
    @Volatile private var imageGeneration = 0L
    private var imageRetryMillis = 5_000L
    private var useWebView = true
    private var nativeVideos = true
    private var nativeAuthSecret = ""
    private var frameBackground = FrameBackground.THUMBHASH
    private var frameScale = FrameScale.FIT
    private var splitGapPx = 6
    private var overlayFadeSeconds = 0
    private var burnInEnabled = true
    private var clockSizeSp = 40
    private var metadataSizeSp = 24
    private var overlayScrimAlpha = 96
    private var safeMarginDp = 24
    private var edgeFeatherPx = 32
    private var backgroundBlurRadius = 16
    private var backgroundDarkness = 84
    private var zoomFrameRate = 30
    private val burnInController = OverlayBurnInController()
    private var showCurrentDate = true
    private var currentWeather = ""
    private val isImageTimerRunning get() = imageTimer.isRunning
    private val handler = Handler(Looper.getMainLooper())
    // Kept decoded: live responses no longer carry the image as Base64.
    private var previousImage: Helpers.PreparedImage? = null
    private var currentImage: Helpers.PreparedImage? = null
    private var lastFrameMetaLeft: FrameMeta? = null
    private var lastFrameMetaRight: FrameMeta? = null
    private val nextSlides = NextSlideQueue<BuiltSlide> { it.frame.bitmap.recycle() }
    private var slideJob: Job? = null
    private var slideBuildGeneration = 0L
    private val prefetchRunnable = Runnable { prefetchNextSlide() }
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
    private val activeCheckRunnable = object : Runnable {
        override fun run() {
            checkActiveTime()
            handler.postDelayed(this, 30000)
        }
    }
    private val clockRunnable = object : Runnable {
        override fun run() {
            if (::serverSettings.isInitialized && !useWebView && isFrameInactive != true) {
                updateDateTimeWeather()
                // Move static overlays slightly to reduce panel burn-in.
                val step = ((System.currentTimeMillis() / 60_000L) % 5).toFloat() - 2f
                val distance = if (burnInEnabled) 12f else 0f
                val (x, y) = burnInController.offset(System.currentTimeMillis() / 300_000L, distance)
                txtDateTime.translationX = x
                txtDateTime.translationY = y
                photoInfoContainer.translationX = -x
                photoInfoContainer.translationY = -y
            }
            handler.postDelayed(this, 60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }
    private var isFrameInactive: Boolean? = null
    private var isManualOverride = false
    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_ON -> onScreenTurnedOn()
                Intent.ACTION_SCREEN_OFF -> onScreenTurnedOff()
            }
        }
    }
    private val settingsLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) {
                loadSettings()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        //force dark mode
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.main_view)
        hideSystemUI()

        // Clean up settings of the removed Screen Dimming feature (replaced by Active Times)
        PreferenceManager.getDefaultSharedPreferences(applicationContext).edit()
            .remove("screenDim")
            .remove("dim_time_range")
            .remove("dimStartHour")
            .remove("dimStartMinute")
            .remove("dimEndHour")
            .remove("dimEndMinute")
            .apply()

        webViewContainer = findViewById(R.id.webViewContainer)
        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        framePresenter = NativeFramePresenter(imageView1, imageView2, handler)
        videoView = findViewById(R.id.videoView)
        videoPlayer = NativeVideoPlayer(applicationContext, videoView)
        photoInfoContainer = findViewById(R.id.photoInfoContainer)
        txtPhotoInfoLeft = findViewById(R.id.txtPhotoInfoLeft)
        txtPhotoInfoRight = findViewById(R.id.txtPhotoInfoRight)
        txtDateTime = findViewById(R.id.txtDateTime)
        txtOfflineIndicator = findViewById(R.id.txtOfflineIndicator)
        btnPrevious = findViewById(R.id.btnPrevious)
        btnPause = findViewById(R.id.btnPause)
        btnNext = findViewById(R.id.btnNext)
        dimOverlay = findViewById(R.id.dimOverlay)
        swipeRefreshLayout = findViewById(R.id.swipeRefreshLayout)

        val swipeRefreshLayout = findViewById<SwipeRefreshLayout>(R.id.swipeRefreshLayout)
        swipeRefreshLayout.setOnRefreshListener {
            swipeRefreshLayout.isRefreshing = false
            settingsAction()
        }

        btnPrevious.setOnClickListener {
            val toast = Toast.makeText(this, "Previous", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.START, 0, 0)
            toast.show()
            previousAction()
        }

        btnPause.setOnClickListener {
            val toast = Toast.makeText(this, "Pause", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER, 0, 0)
            toast.show()
            pauseAction()
        }

        btnNext.setOnClickListener {
            val toast = Toast.makeText(this, "Next", Toast.LENGTH_SHORT)
            toast.setGravity(Gravity.CENTER_VERTICAL or Gravity.END, 0, 0)
            toast.show()
            nextAction()
        }

        remoteState.update {
            it.copy(
                presentationMode = if (useWebView) "webview" else "native",
                version = appVersionName(),
                paused = !isImageTimerRunning,
                displayActive = isFrameInactive != true,
            )
        }
        configureRemoteInterfaces()

        registerReceiver(
            screenStateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_ON)
                addAction(Intent.ACTION_SCREEN_OFF)
            },
        )

        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val savedUrl = prefs.getString("webview_url", "") ?: ""

        if (savedUrl.isBlank()) {
            val intent = Intent(this@MainActivity, SettingsActivity::class.java)
            settingsLauncher.launch(intent)
        } else {
            loadSettings()
        }
    }

    /**
     * Shows one specific image alone: the cached image at startup, or the previous image. Live
     * playback goes through [getNextImage], which builds slides ahead of time.
     */
    private fun showImage(
        prepared: Helpers.PreparedImage,
        playVideo: Boolean = true,
        afterPresented: (() -> Unit)? = null,
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
            window.decorView.doOnNextLayout {
                if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                    showImage(prepared, playVideo, afterPresented)
                }
            }
            return
        }
        imageJob = lifecycleScope.launch(Dispatchers.IO) {
            var rendered: RenderedFrame? = null
            var handedOff = false
            try {
                rendered = renderSlide(spec, prepared, null)
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && playbackSession.isCurrent(token) &&
                        !isFinishing && !isDestroyed
                    ) {
                        updateUI(rendered, zoomEnabled = serverSettings.imageZoom && !isVideo)
                        handedOff = true
                        if (isVideo && playVideo) startVideo(imageResponse, token)
                        afterPresented?.invoke()
                    }
                }
            } catch (error: Exception) {
                if (error is CancellationException) throw error
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && playbackSession.isCurrent(token)) {
                        Log.e("NativeFrame", "Unable to render image; retaining the current frame", error)
                        setOfflineIndicator(true)
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
        val gapColor: Int,
        val featherPx: Int,
        val blurRadius: Int,
        val darkness: Int,
        val layout: String,
    )

    /** A rendered slide waiting to be presented; [image] is the newest image it shows. */
    private class BuiltSlide(
        val frame: RenderedFrame,
        val image: Helpers.PreparedImage,
        val spec: SlideSpec,
        val token: Long,
    )

    private fun updateUI(frame: RenderedFrame, zoomEnabled: Boolean) {
        val transitionMillis = (serverSettings.transitionDuration * 1_000).toLong()
        videoPlayer.fadeOutAndRelease(transitionMillis)
        framePresenter.present(
            frame = frame,
            transitionMillis = transitionMillis,
            zoomEnabled = zoomEnabled,
            zoomDurationMillis = (serverSettings.interval * 1_000).toLong(),
            zoomFramesPerSecond = zoomFrameRate,
        ) {
            lastFrameMetaLeft = it.left
            lastFrameMetaRight = it.right
            updatePhotoInfo(it.left, it.right)
            updateDateTimeWeather()
        }
    }

    private fun startVideo(imageResponse: Helpers.ImageResponse, token: Long) {
        val assetId = imageResponse.assetId ?: return
        val baseUrl = retrofit?.baseUrl()?.toString() ?: return
        val intervalSeconds = serverSettings.interval
        // Keep the slideshow timer armed for the whole video: it advances on completion and the
        // watchdog still advances a frame whose stream stalls or never reports completion.
        imageTimer.retryAfter(
            VideoPlaybackPolicy.startupWatchdogMillis(imageResponse.durationSeconds, intervalSeconds),
        )
        videoPlayer.play(
            uri = Uri.parse(Helpers.videoUrl(baseUrl, assetId)),
            headers = if (nativeAuthSecret.isBlank()) emptyMap()
            else mapOf("Authorization" to "Bearer $nativeAuthSecret"),
            crop = frameScale == FrameScale.CROP,
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

    private fun updatePhotoInfo(left: FrameMeta, right: FrameMeta?) {
        fun text(meta: FrameMeta) = buildString {
            if (serverSettings.showPhotoDate && meta.date.isNotEmpty()) append(meta.date)
            if (serverSettings.showImageLocation && meta.location.isNotEmpty()) {
                if (isNotEmpty()) append("\n")
                append(meta.location)
            }
        }
        val leftText = if (right == null) "" else text(left)
        val rightText = text(right ?: left)
        txtPhotoInfoLeft.text = leftText
        txtPhotoInfoRight.text = rightText
        txtPhotoInfoLeft.visibility = if (leftText.isBlank()) View.GONE else View.VISIBLE
        txtPhotoInfoRight.visibility = if (rightText.isBlank()) View.GONE else View.VISIBLE
        photoInfoContainer.animate().cancel()
        photoInfoContainer.alpha = 1f
        if (overlayFadeSeconds > 0) {
            photoInfoContainer.animate().alpha(0f).setStartDelay(overlayFadeSeconds * 1000L).start()
        }
    }

    private fun updateDateTimeWeather() {
        if (serverSettings.showClock) {
            val currentDateTime = Calendar.getInstance().time

            val formattedDate = try {
                SimpleDateFormat(serverSettings.photoDateFormat, Locale.getDefault()).format(
                    currentDateTime
                )
            } catch (_: Exception) {
                ""
            }

            val formattedTime = try {
                SimpleDateFormat(serverSettings.clockFormat, Locale.getDefault()).format(
                    currentDateTime
                )
            } catch (_: Exception) {
                ""
            }

            val dt = if (showCurrentDate && formattedDate.isNotEmpty()) {
                "$formattedDate\n$formattedTime"
            } else {
                formattedTime
            }

            txtDateTime.text = SpannableString(dt).apply {
                val start =
                    if (showCurrentDate && formattedDate.isNotEmpty()) formattedDate.length + 1 else 0
                setSpan(RelativeSizeSpan(2f), start, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }

        if (serverSettings.showWeatherDescription) {
            txtDateTime.append(currentWeather)
        }
    }

    /** Advances live playback: presents the prefetched slide, or builds one now. */
    private fun getNextImage() {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token) || imageJob?.isActive == true ||
            !::apiService.isInitialized || isFrameInactive == true
        ) return
        val spec = currentSlideSpec()
        if (spec == null) {
            window.decorView.doOnNextLayout { if (playbackSession.isCurrent(token)) getNextImage() }
            return
        }
        when (val advance = nextSlides.advance { it.token == token && it.spec == spec }) {
            is NextSlideQueue.Advance.Present -> presentSlide(advance.slide, token)
            NextSlideQueue.Advance.Waiting -> Unit
            NextSlideQueue.Advance.Build -> buildSlide(token, spec, prefetch = false)
        }
    }

    private fun currentSlideSpec(): SlideSpec? {
        val width = window.decorView.width
        val height = window.decorView.height
        if (width <= 1 || height <= 1) return null
        val gapColor = runCatching {
            (serverSettings.primaryColor?.takeIf(String::isNotBlank) ?: "#000000").toColorInt()
        }.getOrDefault(Color.BLACK)
        return SlideSpec(
            width, height, frameScale, frameBackground, splitGapPx, gapColor, edgeFeatherPx,
            backgroundBlurRadius, backgroundDarkness, serverSettings.layout,
        )
    }

    private fun renderSlide(
        spec: SlideSpec,
        first: Helpers.PreparedImage,
        second: Helpers.PreparedImage?,
    ): RenderedFrame = FrameRenderer.render(
        spec.width, spec.height, first, second, spec.scale, spec.background, spec.gapPx,
        spec.gapColor, spec.featherPx, spec.blurRadius, spec.darkness,
    )

    private fun buildSlide(token: Long, spec: SlideSpec, prefetch: Boolean) {
        val generation = slideBuildGeneration
        val api = apiService
        val includeVideos = nativeVideos
        slideJob = lifecycleScope.launch(slideDispatcher) {
            // A prefetch is not latency-critical; keep it from competing with frame rendering.
            Process.setThreadPriority(
                if (prefetch) Process.THREAD_PRIORITY_BACKGROUND else Process.THREAD_PRIORITY_DEFAULT,
            )
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
                        Log.e("NativeFrame", "Unable to load the next image; retaining the current frame", error)
                        setOfflineIndicator(true)
                        scheduleImageRetry()
                    } else {
                        Log.w("NativeFrame", "Unable to prefetch the next image: ${error.message}")
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
        var first = fetchImage(api, includeVideos)
        var second: Helpers.PreparedImage? = null
        // In split view a portrait waits for the next image: another portrait pairs with it,
        // anything else is shown alone in its place.
        if (waitsForPortraitPair(first, spec.layout)) {
            val next = fetchImage(api, includeVideos)
            if (waitsForPortraitPair(next, spec.layout)) second = next else first = next
        }
        val frame = renderSlide(spec, first, second)
        return BuiltSlide(frame, second ?: first, spec, token)
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
        val response = api.getImageData(includeVideos, includeImage = false).awaitResponse()
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
        if (isFinishing || isDestroyed) {
            slide.frame.bitmap.recycle()
            return
        }
        ++imageGeneration
        imageJob?.cancel()
        imageJob = null
        val isVideo = slide.image.response.isVideo
        updateUI(slide.frame, zoomEnabled = serverSettings.imageZoom && !isVideo)
        if (isVideo) startVideo(slide.image.response, token)
        setOfflineIndicator(false)
        previousImage = currentImage
        currentImage = slide.image
        imageRetryMillis = 5_000L
        // A video poster is not cached for offline startup.
        if (!isVideo) {
            val image = slide.image
            lifecycleScope.launch(Dispatchers.IO) { CachedImageStore.writeIfStale(applicationContext, image) }
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
        if (!playbackSession.isCurrent(token) || !isImageTimerRunning || isFrameInactive == true ||
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
        if (!isImageTimerRunning || isFrameInactive == true) return
        imageTimer.retryAfter(imageRetryMillis)
        imageRetryMillis = (imageRetryMillis * 2).coerceAtMost(5 * 60_000L)
    }

    private fun setOfflineIndicator(offline: Boolean) {
        remoteState.update {
            it.copy(
                serverConnected = !offline,
                offlineFallback = offline && currentImage != null,
                lastImageSuccessMillis = if (offline) it.lastImageSuccessMillis else System.currentTimeMillis(),
            )
        }
        if (!::txtOfflineIndicator.isInitialized) return
        txtOfflineIndicator.animate().cancel()
        if (!offline) {
            txtOfflineIndicator.visibility = View.GONE
            return
        }
        txtOfflineIndicator.alpha = 1f
        txtOfflineIndicator.visibility = View.VISIBLE
        txtOfflineIndicator.animate()
            .alpha(0f)
            .setStartDelay(8_000L)
            .setDuration(500L)
            .withEndAction { txtOfflineIndicator.visibility = View.GONE }
            .start()
    }

    private fun startImageTimer() {
        imageTimer.start()
    }

    private fun stopImageTimer() {
        imageTimer.stop()
        ++imageGeneration
        if (::framePresenter.isInitialized) framePresenter.pauseZoom()
        if (::videoPlayer.isInitialized) videoPlayer.pause()
        // Keep a ready or in-flight next slide for resume, but never present it while stopped.
        nextSlides.withdrawRequest()
        imageJob?.cancel()
        imageJob = null
    }

    private fun startWeatherTimer() {
        weatherTimer.start(0L)
    }

    private fun stopWeatherTimer() {
        weatherTimer.stop()
        weatherCall?.cancel()
        weatherCall = null
    }

    private fun getWeather() {
        val token = nativeSessionToken
        if (!playbackSession.isCurrent(token) || isFrameInactive == true ||
            !::apiService.isInitialized || weatherCall != null
        ) return
        val request = apiService.getWeather()
        weatherCall = request
        request.enqueue(object : Callback<Helpers.Weather> {
            override fun onResponse(
                call: Call<Helpers.Weather>,
                response: Response<Helpers.Weather>
            ) {
                if (weatherCall !== call || !playbackSession.isCurrent(token)) return
                weatherCall = null
                if (isFrameInactive != true && response.isSuccessful) {
                    val weatherResponse = response.body()
                    if (weatherResponse != null) {
                        currentWeather =
                            "\n ${weatherResponse.location}, ${"%.1f".format(weatherResponse.temperature)}${weatherResponse.unit} \n ${weatherResponse.description}"
                        updateDateTimeWeather()
                    }
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
            if (!playbackSession.isCurrent(token) || useWebView || isFrameInactive == true) return
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
                    if (!playbackSession.isCurrent(token) || useWebView || isFrameInactive == true) return
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

    @SuppressLint("SetJavaScriptEnabled")
    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        frameBackground = runCatching {
            FrameBackground.valueOf(prefs.getString("frameBackground", "THUMBHASH")!!)
        }.getOrDefault(FrameBackground.THUMBHASH)
        frameScale = runCatching {
            FrameScale.valueOf(prefs.getString("frameScale", "FIT")!!)
        }.getOrDefault(FrameScale.FIT)
        splitGapPx = (prefs.getInt("splitGapDp", 6) * resources.displayMetrics.density).toInt()
        overlayFadeSeconds = prefs.getInt("overlayFadeSeconds", 0)
        burnInEnabled = prefs.getBoolean("burnInEnabled", true)
        clockSizeSp = prefs.getInt("clockSizeSp", 40)
        metadataSizeSp = prefs.getInt("metadataSizeSp", 24)
        overlayScrimAlpha = prefs.getInt("overlayScrimAlpha", 96)
        safeMarginDp = prefs.getInt("safeMarginDp", 24)
        edgeFeatherPx = (prefs.getInt("edgeFeatherDp", 32) * resources.displayMetrics.density).toInt()
        backgroundBlurRadius = prefs.getInt("backgroundBlurRadius", 16).coerceIn(0, 24)
        backgroundDarkness = prefs.getInt("backgroundDarkness", 84).coerceIn(0, 200)
        zoomFrameRate = prefs.getInt("zoomFrameRate", 30).coerceIn(10, 60)
        showCurrentDate = prefs.getBoolean("showCurrentDate", true)
        stopNativePlayback()
        var savedUrl = prefs.getString("webview_url", "") ?: ""
        useWebView = prefs.getBoolean("useWebView", false)
        nativeVideos = prefs.getBoolean("nativeVideos", true)
        val authSecret = prefs.getString("authSecret", "") ?: ""
        nativeAuthSecret = authSecret
        val settingsLock = prefs.getBoolean("settingsLock", false)
        val activeTimes = prefs.getBoolean("activeTimes", false)
        remoteState.update {
            it.copy(
                presentationMode = if (useWebView) "webview" else "native",
                version = appVersionName(),
            )
        }
        configureRemoteInterfaces()

        webViewContainer.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        videoView.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnPrevious.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnPause.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnNext.visibility = if (useWebView) View.GONE else View.VISIBLE
        swipeRefreshLayout.isEnabled = !settingsLock
        photoInfoContainer.visibility = View.GONE // enabled after server settings are applied
        txtDateTime.visibility = View.GONE // enabled after server settings are applied

        if (activeTimes) {
            handler.removeCallbacks(activeCheckRunnable)
            handler.post(activeCheckRunnable)
        } else {
            handler.removeCallbacks(activeCheckRunnable)
            if (isFrameInactive != false) {
                setFrameActive(true)
            }
        }
        if (useWebView) {
            val activeWebView = ensureWebView()

            activeWebView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean {
                    val url = request?.url
                    if (url != null) {
                        // Open the URL in the default browser
                        val intent = Intent(Intent.ACTION_VIEW, url)
                        startActivity(intent)
                        return true
                    }
                    return false
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    super.onReceivedError(view, request, error)

                    if (request?.isForMainFrame == true && error != null) {
                        view?.loadUrl("file:///android_asset/error_page.html")

                        Handler(Looper.getMainLooper()).postDelayed({
                            val errorCode = error.errorCode
                            val errorDescription = error.description.toString().replace("'", "\\'")
                            view?.evaluateJavascript("showError('$errorCode', '$errorDescription')", null)
                        }, 500)
                    }
                    handler.postDelayed({
                        //check url again in case the user has changed it
                        var currentUrl = prefs.getString("webview_url", "")?.trim() ?: ""
                        currentUrl = if (authSecret.isNotEmpty()) {
                            currentUrl
                        } else {
                            currentUrl
                        }
                        if (currentUrl.isNotEmpty()) {
                            activeWebView.loadUrl(
                                currentUrl,
                                if (authSecret.isBlank()) emptyMap()
                                else mapOf("Authorization" to "Bearer $authSecret"),
                            )
                        }
                    }, 5000)
                }
            }
            activeWebView.settings.javaScriptEnabled = true
            activeWebView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            activeWebView.settings.domStorageEnabled = true
            loadWebViewWithRetry(savedUrl, headers = if (authSecret.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $authSecret"))
        } else {
            nativeSessionToken = playbackSession.activate()
            hasAppliedSettingsForSession = false
            destroyWebView()
            retrofit = Helpers.createRetrofit(savedUrl, authSecret)
            apiService = retrofit!!.create(Helpers.ApiService::class.java)

            // Render the last successful image immediately at cold start. The image cache needs
            // its matching server presentation settings, so retain both independently of the
            // live request and refresh them in the background when the server returns.
            if (isFrameInactive != true) {
                CachedServerSettingsStore.read(applicationContext)?.let { cachedSettings ->
                    setOfflineIndicator(true)
                    applyServerSettings(cachedSettings)
                }
            }
            getServerSettings(
                onSuccess = { settings ->
                    if (isFrameInactive != true) {
                        if (applyServerSettings(settings)) {
                            CachedServerSettingsStore.write(applicationContext, settings)
                        }
                        settingsRefreshTimer.start()
                    }
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
        if (::videoPlayer.isInitialized) videoPlayer.release()
        stopWeatherTimer()
        settingsRefreshTimer.stop()
        settingsCall?.cancel()
        settingsCall = null
        settingsRetryRunnable?.let(handler::removeCallbacks)
        settingsRetryRunnable = null
        clearNextSlides()
    }

    private fun applyServerSettings(settings: Helpers.ServerSettings): Boolean {
        if (isFrameInactive == true) return false
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
        if (previous.interval != settings.interval && !videoPlayer.isActive) imageTimer.reschedule()
        // A prefetched slide rendered for the old layout or colours fails its spec check and is rebuilt.
        lastFrameMetaLeft?.let { updatePhotoInfo(it, lastFrameMetaRight) }
        updateDateTimeWeather()
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

        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            photoInfoContainer.visibility = View.VISIBLE
            val infoSize = metadataSizeSp.toFloat()
            txtPhotoInfoLeft.textSize = infoSize
            txtPhotoInfoRight.textSize = infoSize
            val color = serverSettings.primaryColor?.let {
                runCatching { it.toColorInt() }.getOrDefault(Color.WHITE)
            } ?: Color.WHITE
            txtPhotoInfoLeft.setTextColor(color)
            txtPhotoInfoRight.setTextColor(color)
            applyOverlayStyle()
        } else {
            photoInfoContainer.visibility = View.GONE
        }
        if (serverSettings.showClock) {
            txtDateTime.visibility = View.VISIBLE
            txtDateTime.textSize = clockSizeSp / 2f
            txtDateTime.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            txtDateTime.setTextColor(
                serverSettings.primaryColor?.let {
                    runCatching { it.toColorInt() }.getOrDefault(Color.WHITE)
                } ?: Color.WHITE,
            )
            applyOverlayStyle()
        } else {
            txtDateTime.visibility = View.GONE
        }
    }

    private fun startPlaybackAfterSettingsLoaded() {
        val cachedImage = currentImage ?: CachedImageStore.readPrepared(applicationContext)
        if (cachedImage != null) {
            val token = nativeSessionToken
            // A lone cached portrait must be usable with no backend available. showImage also
            // waits for layout, and only a successfully presented frame becomes currentImage.
            showImage(
                cachedImage,
                playVideo = false,
                afterPresented = {
                    currentImage = cachedImage
                    remoteState.update { it.copy(offlineFallback = !it.serverConnected) }
                    val generation = imageGeneration
                    // getNextImage rejects an active imageJob, so wait for the render to finish.
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
        startImageTimer()
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
        updateWeatherTimer()
    }

    private fun updateWeatherTimer() {
        if (serverSettings.showWeatherDescription) startWeatherTimer() else stopWeatherTimer()
    }

    private fun previousAction() {
        if (useWebView) {
            // Simulate a key press
            webView?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            dispatchKeyEvent(event)
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
            webView?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            dispatchKeyEvent(event)
        } else {
            stopImageTimer()
            // Restart first: a prefetched video presents synchronously and arms its watchdog
            // through the running timer.
            startImageTimer()
            getNextImage()
        }
        remoteState.update { it.copy(paused = false) }
    }

    private fun pauseAction() {
        if (useWebView) {
            // Simulate a key press
            webView?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_SPACE)
            dispatchKeyEvent(event)
        } else {
            framePresenter.pauseZoom()
            if (isImageTimerRunning) {
                stopImageTimer()
            } else {
                startImageTimer()
                getNextImage()
            }
        }
        remoteState.update { it.copy(paused = !isImageTimerRunning) }
    }

    private fun settingsAction(authorizedOverride: Boolean = false) {
        val locked = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean("settingsLock", false)
        if (locked && !authorizedOverride) return
        val intent = Intent(this, SettingsActivity::class.java)
        stopNativePlayback()
        settingsLauncher.launch(intent)
    }

    private fun screenBrightnessAction(brightness: Float) {
        val lp = window.attributes
        if (brightness < 0) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        } else {
            lp.screenBrightness = brightness
        }
        window.attributes = lp
        remoteState.update { it.copy(brightness = brightness.takeUnless { value -> value < 0f }) }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> {
                    settingsAction()
                    return true
                }

                KeyEvent.KEYCODE_DPAD_CENTER -> {
                    pauseAction()
                    return true
                }
            }
            if (!useWebView) {
                when (event.keyCode) {
                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                        previousAction()
                        return true
                    }

                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                        nextAction()
                        return true
                    }

                    KeyEvent.KEYCODE_SPACE -> {
                        pauseAction()
                        return true
                    }
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    @SuppressLint("NewApi")
    @Suppress("DEPRECATION")
    private fun hideSystemUI() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // For API 30 and above
            WindowCompat.setDecorFitsSystemWindows(window, false)
            window.insetsController?.let { controller ->
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior =
                    WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            // For API 21 to 29
            window.decorView.systemUiVisibility = (
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            or View.SYSTEM_UI_FLAG_FULLSCREEN
                            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    )
        }
    }

    private fun checkActiveTime() {
        // A physical/manual wake temporarily overrides the inactive schedule. Keep the regular
        // schedule check from putting the content pipeline back to sleep until the display is
        // turned off and onScreenTurnedOff() explicitly ends the override.
        if (isManualOverride) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val schedule = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))
        val shouldBeActive = Helpers.isActiveNow(schedule, Calendar.getInstance())

        if (shouldBeActive && isFrameInactive != false) {
            setFrameActive(true)
        } else if (!shouldBeActive) {
            if (isFrameInactive != true) {
                setFrameActive(false)
            } else {
                // Already inactive: re-arm in case the schedule changed and the
                // existing wake alarm now points at a stale time.
                scheduleWakeAlarm()
            }
        }
    }

    private fun setFrameActive(active: Boolean) {
        remoteState.update { it.copy(displayActive = active) }
        if (active) {
            isFrameInactive = false
            cancelWakeAlarm()
            // Power the screen back on and show over the keyguard
            wakeScreen()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(true)
                setTurnScreenOn(true)
                val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
                keyguardManager.requestDismissKeyguard(this, null)
            }
            window.addFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
            )
            val lp = window.attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
            window.attributes = lp
            if (dimOverlay.isVisible) {
                dimOverlay.animate()
                    .alpha(0f)
                    .setDuration(500L)
                    .withEndAction {
                        dimOverlay.visibility = View.GONE
                        loadSettings()
                    }
                    .start()
            } else {
                loadSettings()
            }
        } else {
            isFrameInactive = true
            // Stop refreshing content and invalidate in-flight native callbacks while inactive.
            stopNativePlayback()
            if (useWebView) {
                webView?.loadUrl("about:blank")
            }
            dimOverlay.apply {
                visibility = View.VISIBLE
                alpha = 0.99f
            }
            val lp = window.attributes
            lp.screenBrightness = 0f
            window.attributes = lp
            // Stop forcing the screen to stay on and allow it to power off
            window.clearFlags(
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
                        or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                setShowWhenLocked(false)
                setTurnScreenOn(false)
            }
            // Wake the device back up at the next scheduled active time
            scheduleWakeAlarm()
            // Actively turn the screen off and sleep the device (requires device admin)
            lockDeviceIfPossible()
        }
    }

    private fun onScreenTurnedOn() {
        // Manual power-button override: when the schedule has put the frame to sleep and the
        // user turns the screen on, temporarily show the frame without forcing it to stay on.
        if (isFrameInactive == true && !isManualOverride) {
            isManualOverride = true
            showFrameTemporarily()
        }
    }

    private fun onScreenTurnedOff() {
        // The manual override ended (screen timed out or was turned off); re-evaluate the
        // current schedule before deciding whether to sleep again. The user may have disabled
        // Active Times or a new active period may have started while they were viewing.
        if (isManualOverride) {
            isManualOverride = false
            val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            val activeTimes = prefs.getBoolean("activeTimes", false)
            val schedule = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))
            val shouldBeActive =
                !activeTimes || Helpers.isActiveNow(schedule, Calendar.getInstance())
            setFrameActive(shouldBeActive)
        }
    }

    private fun showFrameTemporarily() {
        // The frame remains schedule-inactive conceptually, but content and clock refreshes use
        // this flag as their execution gate. Mark the visible session active; screen-off will
        // restore the scheduled state through setFrameActive().
        isFrameInactive = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val keyguardManager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            keyguardManager.requestDismissKeyguard(this, null)
        }
        // Show over the keyguard but do NOT add FLAG_KEEP_SCREEN_ON, so the device's normal
        // screen timeout still applies and the schedule resumes once the screen turns off.
        window.addFlags(
            WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                    or WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                    or WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD
        )
        val lp = window.attributes
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
        if (dimOverlay.isVisible) {
            dimOverlay.animate()
                .alpha(0f)
                .setDuration(500L)
                .withEndAction {
                    dimOverlay.visibility = View.GONE
                    loadSettings()
                }
                .start()
        } else {
            loadSettings()
        }
    }

    private fun wakeScreen() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager

        @Suppress("DEPRECATION")
        val wakeLock = powerManager.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
            "immichframe:activeWake",
        )
        // Briefly wake the screen, then auto-release; FLAG_KEEP_SCREEN_ON keeps it on afterwards
        wakeLock.acquire(3000L)
    }

    private fun lockDeviceIfPossible() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        if (dpm.isAdminActive(FrameDeviceAdminReceiver.componentName(this))) {
            try {
                dpm.lockNow()
            } catch (e: SecurityException) {
                Log.w("MainActivity", "Unable to lock device: ${e.message}")
            }
        } else {
            Log.i(
                "MainActivity",
                "Device admin not enabled; screen will only dim. Enable it in Settings to fully sleep.",
            )
        }
    }

    private fun wakeAlarmPendingIntent(): PendingIntent {
        val intent = Intent(this, ScheduleWakeReceiver::class.java).apply {
            action = ScheduleWakeReceiver.ACTION_WAKE
        }
        return PendingIntent.getBroadcast(
            this,
            ScheduleWakeReceiver.REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
    }

    private fun scheduleWakeAlarm() {
        FrameScheduleManager.schedule(applicationContext)
    }

    private fun cancelWakeAlarm() {
        FrameScheduleManager.cancel(applicationContext)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            hideSystemUI()
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        configureRemoteInterfaces()
        if (isImageTimerRunning) videoPlayer.resume()
        // Re-evaluate the schedule whenever the activity returns to the foreground. This is
        // essential when the wake alarm brings an already-running instance forward (which does
        // not re-run onCreate), so the screen powers on immediately instead of waiting for the
        // next periodic check.
        if (isManualOverride) return
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        if (prefs.getBoolean("activeTimes", false)) {
            checkActiveTime()
        } else if (isFrameInactive != false) {
            // Active Times was turned off while the frame was asleep: restore it and drop any
            // pending wake alarm so disabling the feature fully clears its side effects.
            cancelWakeAlarm()
            setFrameActive(true)
        }
    }

    override fun onPause() {
        if (::framePresenter.isInitialized) framePresenter.pauseZoom()
        if (::videoPlayer.isInitialized) videoPlayer.pause()
        super.onPause()
    }

    override fun onDestroy() {
        if (::framePresenter.isInitialized) framePresenter.clear()
        if (::videoPlayer.isInitialized) videoPlayer.release()
        destroyWebView()
        super.onDestroy()
        rpcServer?.stop()
        rpcServer = null
        mqttClient?.stop()
        mqttClient = null
        stopNativePlayback()
        unregisterReceiver(screenStateReceiver)
        handler.removeCallbacksAndMessages(null)
    }

    private fun loadWebViewWithRetry(
        url: String,
        attempt: Int = 1,
        maxAttempts: Int = Int.MAX_VALUE,
        headers: Map<String, String> = emptyMap(),
    ) {
        lifecycleScope.launch {
            if (!useWebView) return@launch
            val reachable = withContext(Dispatchers.IO) {
                Helpers.isServerReachable(url)
            }

            if (!useWebView) return@launch
            if (reachable) {
                webView?.loadUrl(url, headers)
            } else if (attempt <= maxAttempts) {
                Toast.makeText(
                    this@MainActivity,
                    "Connecting to server... Attempt $attempt of $maxAttempts",
                    Toast.LENGTH_SHORT
                ).show()

                delay(5_000)
                loadWebViewWithRetry(url, attempt + 1, maxAttempts, headers)
            } else {
                Toast.makeText(
                    this@MainActivity,
                    "Could not connect to server after $maxAttempts attempts",
                    Toast.LENGTH_LONG
                ).show()

                webView?.loadUrl(url, headers)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        hideSystemUI()
    }

    private fun configureRemoteInterfaces() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val rpcEnabled = prefs.getBoolean("rpcEnabled", false)
        val token = prefs.getString("rpcToken", "").orEmpty()
        val mqttEnabled = prefs.getBoolean("mqttEnabled", false)
        val brokerUri = prefs.getString("mqttBrokerUri", "").orEmpty().trim()
        val mqttUsername = prefs.getString("mqttUsername", "").orEmpty()
        val mqttPassword = prefs.getString("mqttPassword", "").orEmpty()
        val deviceId = RemoteIdentity.getOrCreate(prefs)
        val configurationKey = listOf(
            rpcEnabled, token, mqttEnabled, brokerUri, mqttUsername, mqttPassword, deviceId,
        ).joinToString("\u0000")
        if (configurationKey == remoteConfigurationKey) return

        rpcServer?.stop()
        rpcServer = null
        mqttClient?.stop()
        mqttClient = null
        var configured = true

        if (rpcEnabled && token.isNotBlank()) {
            val server = RpcHttpServer(
                expectedToken = {
                    PreferenceManager.getDefaultSharedPreferences(applicationContext)
                        .getString("rpcToken", "").orEmpty()
                },
                state = remoteState,
                commands = remoteCommands,
            )
            try {
                server.start()
                rpcServer = server
            } catch (e: Exception) {
                Log.e("MainActivity", "RPC server unavailable", e)
                server.stop()
                configured = false
            }
        }
        if (mqttEnabled && brokerUri.isNotBlank()) {
            val candidate = MqttControlClient(
                MqttSettings(brokerUri, mqttUsername, mqttPassword, deviceId),
                remoteState,
                remoteCommands,
            )
            if (candidate.start()) {
                mqttClient = candidate
            } else {
                configured = false
            }
        }
        remoteConfigurationKey = configurationKey.takeIf { configured }
    }

    @Suppress("DEPRECATION")
    private fun appVersionName(): String = runCatching {
        packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown"
    }.getOrDefault("unknown")

    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(this).also { view ->
            view.setBackgroundColor(Color.BLACK)
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            webViewContainer.addView(view)
            webView = view
        }
    }

    private fun destroyWebView() {
        webView?.let { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            webViewContainer.removeView(view)
            view.destroy()
        }
        webView = null
    }

    private fun applyOverlayStyle() {
        val density = resources.displayMetrics.density
        val horizontal = (12 * density).toInt()
        val vertical = (6 * density).toInt()
        val radius = 10 * density
        fun style(view: TextView, alpha: Int = overlayScrimAlpha) {
            val color = view.currentTextColor
            val luminance = (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000
            val scrim = if (luminance >= 128) Color.BLACK else Color.WHITE
            view.background = GradientDrawable().apply {
                setColor(Color.argb(alpha.coerceIn(0, 255), Color.red(scrim), Color.green(scrim), Color.blue(scrim)))
                cornerRadius = radius
            }
            view.setPadding(horizontal, vertical, horizontal, vertical)
        }
        style(txtDateTime)
        style(txtOfflineIndicator)
        val metadataAlpha = (overlayScrimAlpha * 0.8f).toInt()
        style(txtPhotoInfoLeft, metadataAlpha)
        style(txtPhotoInfoRight, metadataAlpha)
        val margin = (safeMarginDp * density).toInt()
        (txtDateTime.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.setMargins(margin, margin, margin, margin)
            txtDateTime.layoutParams = it
        }
        (photoInfoContainer.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.setMargins(margin, margin, margin, margin)
            photoInfoContainer.layoutParams = it
        }
    }

    private companion object {
        const val PREFETCH_AFTER_TRANSITION_MILLIS = 250L

        /** A single thread builds slides, so a prefetch never runs two decodes at once. */
        val slideDispatcher = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "ImmichFrame-slides")
        }.asCoroutineDispatcher()
    }
}
