package com.immichframe.immichframe

import android.annotation.SuppressLint
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.service.dreams.DreamService
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageView
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.preference.PreferenceManager
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import androidx.core.graphics.toColorInt

class ScreenSaverService : DreamService() {
    private var webViewRetryScope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var webView: WebView? = null
    private lateinit var webViewContainer: FrameLayout
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var photoInfoContainer: LinearLayout
    private lateinit var txtPhotoInfoLeft: TextView
    private lateinit var txtPhotoInfoRight: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var txtOfflineIndicator: TextView
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var isWeatherTimerRunning = false
    private var useWebView = true
    private var frameBackground = FrameBackground.THUMBHASH
    private var frameScale = FrameScale.FIT
    private var splitGapPx = 6
    private var overlayFadeSeconds = 0
    private var burnInEnabled = true
    private val burnInController = OverlayBurnInController()
    private var clockSizeSp = 40
    private var metadataSizeSp = 24
    private var overlayScrimAlpha = 96
    private var safeMarginDp = 24
    private var edgeFeatherPx = 32
    private var backgroundBlurRadius = 16
    private var backgroundDarkness = 84
    private var zoomFrameRate = 30
    private var showCurrentDate = true
    private var currentWeather = ""
    private var isImageTimerRunning = false
    private var imageCall: Call<Helpers.ImageResponse>? = null
    private var imageJob: Job? = null
    @Volatile private var imageGeneration = 0L
    private val handler = Handler(Looper.getMainLooper())
    private var previousImage: Helpers.ImageResponse? = null
    private var currentImage: Helpers.ImageResponse? = null
    private var portraitCache: Helpers.PreparedImage? = null
    private var portraitCacheGeneration = -1L
    private val portraitCacheLock = Any()
    private lateinit var framePresenter: NativeFramePresenter
    private val imageRunnable = object : Runnable {
        override fun run() {
            if (isImageTimerRunning) {
                handler.postDelayed(this, (serverSettings.interval * 1000).toLong())
                getNextImage()
            }
        }
    }
    private val weatherRunnable = object : Runnable {
        override fun run() {
            if (isWeatherTimerRunning) {
                handler.postDelayed(this, 600000)
                getWeather()
            }
        }
    }
    @SuppressLint("ClickableViewAccessibility")
    override fun onDreamingStarted() {
        super.onDreamingStarted()
        webViewRetryScope = CoroutineScope(Dispatchers.Main + Job())
        isFullscreen = true
        isInteractive = true
        setContentView(R.layout.screen_saver_view)
        webViewContainer = findViewById(R.id.webViewContainer)
        imageView1 = findViewById(R.id.imageView1)
        imageView2 = findViewById(R.id.imageView2)
        framePresenter = NativeFramePresenter(imageView1, imageView2, handler)
        photoInfoContainer = findViewById(R.id.photoInfoContainer)
        txtPhotoInfoLeft = findViewById(R.id.txtPhotoInfoLeft)
        txtPhotoInfoRight = findViewById(R.id.txtPhotoInfoRight)
        txtDateTime = findViewById(R.id.txtDateTime)
        txtOfflineIndicator = findViewById(R.id.txtOfflineIndicator)

        acquireWakeLock()
        loadSettings()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        if (::framePresenter.isInitialized) framePresenter.clear()
        webViewRetryScope?.cancel()
        webViewRetryScope = null
        stopImageTimer()
        destroyWebView()
        releaseWakeLock()
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
            webView?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT)
            webView?.dispatchKeyEvent(event)
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
            webView?.dispatchKeyEvent(event)
        } else {
            stopImageTimer()
            getNextImage()
            startImageTimer()
        }
    }

    private fun showImage(
        imageResponse: Helpers.ImageResponse,
        persist: Boolean = true,
        afterPresented: (() -> Unit)? = null,
    ) {
        val scope = webViewRetryScope ?: return
        val generation = ++imageGeneration
        imageJob?.cancel()
        imageJob = scope.launch(Dispatchers.IO) {
            var rendered: RenderedFrame? = null
            var handedOff = false
            try {
                val width = window.decorView.width.coerceAtLeast(1)
                val height = window.decorView.height.coerceAtLeast(1)
                val prepared = Helpers.prepareImage(imageResponse)
                if (generation != imageGeneration) return@launch
                val isPortrait = prepared.height > prepared.width
                var first = prepared
                var second: Helpers.PreparedImage? = null
                var waitingForPair = false
                synchronized(portraitCacheLock) {
                    if (generation != imageGeneration) return@launch
                    if (isPortrait && serverSettings.layout == "splitview") {
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
                val gapColor = runCatching {
                    (serverSettings.primaryColor?.takeIf(String::isNotBlank) ?: "#000000").toColorInt()
                }.getOrDefault(Color.BLACK)
                rendered = FrameRenderer.render(
                    width, height, first, second, frameScale, frameBackground, splitGapPx, gapColor,
                    edgeFeatherPx, backgroundBlurRadius, backgroundDarkness,
                )
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration) {
                        updateUI(rendered)
                        handedOff = true
                        afterPresented?.invoke()
                    }
                }
                if (persist && generation == imageGeneration) {
                    CachedImageStore.writeIfStale(applicationContext, prepared)
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
            zoomFramesPerSecond = zoomFrameRate,
        ) {
            updatePhotoInfo(it.left, it.right)
            val distance = if (burnInEnabled) 12f else 0f
            val (x, y) = burnInController.offset(System.currentTimeMillis() / 300_000L, distance)
            txtDateTime.translationX = x
            txtDateTime.translationY = y
            photoInfoContainer.translationX = -x
            photoInfoContainer.translationY = -y
            updateDateTimeWeather()
        }
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

    private fun getNextImage() {
        if (imageCall?.isExecuted == true || imageJob?.isActive == true || !::apiService.isInitialized) return
        val call = apiService.getImageData()
        imageCall = call
        call.enqueue(object : Callback<Helpers.ImageResponse> {
            override fun onResponse(
                call: Call<Helpers.ImageResponse>,
                response: Response<Helpers.ImageResponse>
            ) {
                imageCall = null
                if (response.isSuccessful) {
                    val imageResponse = response.body()
                    if (imageResponse != null) {
                        setOfflineIndicator(false)
                        previousImage = currentImage
                        currentImage = imageResponse
                        showImage(imageResponse)
                    }
                } else {
                    setOfflineIndicator(true)
                    Toast.makeText(
                        applicationContext,
                        "Failed to load image (HTTP ${response.code()})",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            }

            override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                imageCall = null
                if (!call.isCanceled) setOfflineIndicator(true)
            }
        })
    }

    private fun startImageTimer() {
        if (!isImageTimerRunning) {
            isImageTimerRunning = true
            handler.postDelayed(imageRunnable, (serverSettings.interval * 1000).toLong())
        }
    }

    private fun setOfflineIndicator(offline: Boolean) {
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

    private fun stopImageTimer() {
        isImageTimerRunning = false
        if (::framePresenter.isInitialized) framePresenter.pauseZoom()
        handler.removeCallbacks(imageRunnable)
        imageCall?.cancel()
        imageCall = null
        imageJob?.cancel()
        imageJob = null
    }

    private fun getWeather() {
        apiService.getWeather().enqueue(object : Callback<Helpers.Weather> {
            override fun onResponse(
                call: Call<Helpers.Weather>,
                response: Response<Helpers.Weather>
            ) {
                if (response.isSuccessful) {
                    val weatherResponse = response.body()
                    if (weatherResponse != null) {
                        currentWeather =
                            "\n ${weatherResponse.location}, ${weatherResponse.temperatureUnit} \n ${weatherResponse.description}"
                    }
                }
            }

            override fun onFailure(call: Call<Helpers.Weather>, t: Throwable) {
                Log.e("Weather", "Failed to fetch weather: ${t.message}")
            }
        })
    }

    private fun getServerSettings(
        onSuccess: (Helpers.ServerSettings) -> Unit,
        onFailure: (Throwable) -> Unit,
        maxRetries: Int = Int.MAX_VALUE,
        retryDelayMillis: Long = 5000
    ) {
        var retryCount = 0

        fun attemptFetch() {
            apiService.getServerSettings().enqueue(object : Callback<Helpers.ServerSettings> {
                override fun onResponse(
                    call: Call<Helpers.ServerSettings>,
                    response: Response<Helpers.ServerSettings>
                ) {
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
                    handleFailure(t)
                }

                private fun handleFailure(t: Throwable) {
                    if (retryCount < maxRetries) {
                        retryCount++
                        handler.postDelayed({
                            attemptFetch()
                        }, retryDelayMillis)
                    } else {
                        onFailure(t)
                    }
                }
            })
        }

        attemptFetch()
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
        var savedUrl = prefs.getString("webview_url", "") ?: ""
        useWebView = prefs.getBoolean("useWebView", false)
        val authSecret = prefs.getString("authSecret", "") ?: ""

        webViewContainer.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        photoInfoContainer.visibility = View.GONE
        txtDateTime.visibility = View.GONE //enabled in onSettingsLoaded based on server settings

        if (useWebView) {
            val activeWebView = ensureWebView()
            handler.removeCallbacks(imageRunnable)
            handler.removeCallbacks(weatherRunnable)
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
                    Handler(Looper.getMainLooper()).postDelayed({
                        activeWebView.loadUrl(savedUrl, if (authSecret.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $authSecret"))
                    }, 5000)
                }
            }
            activeWebView.settings.javaScriptEnabled = true
            activeWebView.settings.cacheMode = WebSettings.LOAD_DEFAULT
            activeWebView.settings.domStorageEnabled = true
            loadWebViewWithRetry(savedUrl, headers = if (authSecret.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $authSecret"))
        } else {
            destroyWebView()
            retrofit = Helpers.createRetrofit(savedUrl, authSecret)
            apiService = retrofit!!.create(Helpers.ApiService::class.java)
            getServerSettings(
                onSuccess = { settings ->
                    serverSettings = settings
                    onSettingsLoaded()
                },
                onFailure = { error ->
                    Toast.makeText(
                        this,
                        "Failed to load server settings: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            )
        }
    }

    private fun onSettingsLoaded() {
        if (serverSettings.imageFill){
            imageView1.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView2.scaleType = ImageView.ScaleType.CENTER_CROP
        }
        else{
            imageView1.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView2.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            photoInfoContainer.visibility = View.VISIBLE
            val size = metadataSizeSp.toFloat()
            txtPhotoInfoLeft.textSize = size
            txtPhotoInfoRight.textSize = size
            if (serverSettings.primaryColor != null) {
                val color = runCatching { serverSettings.primaryColor!!.toColorInt() }.getOrDefault(Color.WHITE)
                txtPhotoInfoLeft.setTextColor(color)
                txtPhotoInfoRight.setTextColor(color)
            } else {
                txtPhotoInfoLeft.setTextColor(Color.WHITE)
                txtPhotoInfoRight.setTextColor(Color.WHITE)
            }
            applyOverlayStyle()
        } else {
            photoInfoContainer.visibility = View.GONE
        }
        if (serverSettings.showClock) {
            txtDateTime.visibility = View.VISIBLE
            txtDateTime.textSize = clockSizeSp / 2f
            txtDateTime.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            if (serverSettings.primaryColor != null) {
                txtDateTime.setTextColor(
                    runCatching { serverSettings.primaryColor!!.toColorInt() }
                        .getOrDefault(Color.WHITE)
                )
            } else {
                txtDateTime.setTextColor(Color.WHITE)
            }
            applyOverlayStyle()
        } else {
            txtDateTime.visibility = View.GONE
        }

        val cachedImage = if (currentImage == null) CachedImageStore.read(applicationContext) else null
        if (cachedImage != null) {
            cachedImage.let {
                currentImage = it
                showImage(it, persist = false, afterPresented = ::getNextImage)
            }
        } else {
            getNextImage()
        }
        startImageTimer()

        if (serverSettings.showWeatherDescription) {
            getWeather()
            if (!isWeatherTimerRunning) {
                isWeatherTimerRunning = true
                handler.postDelayed(object : Runnable {
                    override fun run() {
                        getWeather()
                        handler.postDelayed(this, 600000)
                    }
                }, (300000))
            }

        }
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val powerManager = getSystemService(POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK, "ImmichFrame::ScreenSaverWakeLock"
            )
            wakeLock?.acquire(120 * 60 * 1000L)
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }

    private fun loadWebViewWithRetry(
        url: String,
        attempt: Int = 1,
        maxAttempts: Int = Int.MAX_VALUE,
        headers: Map<String, String> = emptyMap(),
    ) {
        webViewRetryScope?.launch {
            val reachable = withContext(Dispatchers.IO) {
                Helpers.isServerReachable(url)
            }

            if (reachable) {
                webView?.loadUrl(url, headers)
            } else if (attempt <= maxAttempts) {
                Toast.makeText(
                    this@ScreenSaverService,
                    "Connecting to server... Attempt $attempt of $maxAttempts",
                    Toast.LENGTH_SHORT
                ).show()

                delay(5_000)
                loadWebViewWithRetry(url, attempt + 1, maxAttempts, headers)
            } else {
                Toast.makeText(
                    this@ScreenSaverService,
                    "Could not connect to server after $maxAttempts attempts",
                    Toast.LENGTH_LONG
                ).show()

                webView?.loadUrl(url, headers)
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        return WebView(this).also { view ->
            view.setBackgroundColor(Color.BLACK)
            view.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            view.setOnTouchListener { _, _ ->
                finish()
                true
            }
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
}
