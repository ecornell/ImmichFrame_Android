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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.util.Log
import android.view.Gravity
import android.view.KeyEvent
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
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import kotlinx.coroutines.*
import androidx.lifecycle.lifecycleScope
import androidx.core.graphics.toColorInt
import androidx.core.view.isVisible

class MainActivity : AppCompatActivity() {
    private var webView: WebView? = null
    private lateinit var webViewContainer: FrameLayout
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var photoInfoContainer: LinearLayout
    private lateinit var txtPhotoInfoLeft: TextView
    private lateinit var txtPhotoInfoRight: TextView
    private lateinit var txtDateTime: TextView
    private lateinit var btnPrevious: View
    private lateinit var btnPause: View
    private lateinit var btnNext: View
    private lateinit var dimOverlay: View
    private lateinit var swipeRefreshLayout: View
    private lateinit var serverSettings: Helpers.ServerSettings
    private var retrofit: Retrofit? = null
    private lateinit var apiService: Helpers.ApiService
    private var rpcServer: RpcHttpServer? = null
    private var imageCall: Call<Helpers.ImageResponse>? = null
    private var imageJob: Job? = null
    @Volatile private var imageGeneration = 0L
    private var imageRetryMillis = 5_000L
    private var isWeatherTimerRunning = false
    private var useWebView = true
    private var frameBackground = FrameBackground.THUMBHASH
    private var frameScale = FrameScale.FIT
    private var splitGapPx = 10
    private var overlayFadeSeconds = 0
    private var burnInEnabled = true
    private var clockSizeSp = 40
    private var metadataSizeSp = 20
    private var overlayScrimAlpha = 96
    private var safeMarginDp = 24
    private var edgeFeatherPx = 16
    private var zoomFrameRate = 30
    private val burnInController = OverlayBurnInController()
    private var showCurrentDate = true
    private var currentWeather = ""
    private var isImageTimerRunning = false
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
        photoInfoContainer = findViewById(R.id.photoInfoContainer)
        txtPhotoInfoLeft = findViewById(R.id.txtPhotoInfoLeft)
        txtPhotoInfoRight = findViewById(R.id.txtPhotoInfoRight)
        txtDateTime = findViewById(R.id.txtDateTime)
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

        configureRpcServer()

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

    private fun showImage(
        imageResponse: Helpers.ImageResponse,
        persist: Boolean = true,
        afterPresented: (() -> Unit)? = null,
    ) {
        val generation = ++imageGeneration
        imageJob?.cancel()
        imageJob = lifecycleScope.launch(Dispatchers.IO) {
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
                    edgeFeatherPx,
                )
                withContext(Dispatchers.Main) {
                    if (generation == imageGeneration && !isFinishing && !isDestroyed) {
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
        if (imageCall?.isExecuted == true || imageJob?.isActive == true ||
            !::apiService.isInitialized || isFrameInactive == true
        ) return
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
                        previousImage = currentImage
                        currentImage = imageResponse
                        imageRetryMillis = 5_000L
                        showImage(imageResponse)
                    }
                } else {
                    scheduleImageRetry()
                }
            }

            override fun onFailure(call: Call<Helpers.ImageResponse>, t: Throwable) {
                imageCall = null
                if (!call.isCanceled) scheduleImageRetry()
            }
        })
    }

    private fun scheduleImageRetry() {
        if (!isImageTimerRunning || isFrameInactive == true) return
        handler.removeCallbacks(imageRunnable)
        handler.postDelayed(imageRunnable, imageRetryMillis)
        imageRetryMillis = (imageRetryMillis * 2).coerceAtMost(5 * 60_000L)
    }

    private fun startImageTimer() {
        if (!isImageTimerRunning) {
            isImageTimerRunning = true
            handler.postDelayed(imageRunnable, (serverSettings.interval * 1000).toLong())
        }
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

    private fun startWeatherTimer() {
        if (!isWeatherTimerRunning) {
            isWeatherTimerRunning = true
            handler.post(weatherRunnable)
        }
    }

    private fun stopWeatherTimer() {
        isWeatherTimerRunning = false
        handler.removeCallbacks(weatherRunnable)
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
                            "\n ${weatherResponse.location}, ${"%.1f".format(weatherResponse.temperature)}${weatherResponse.unit} \n ${weatherResponse.description}"
                        updateDateTimeWeather()
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
            if (useWebView) {
                return
            }
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
                    if (useWebView) {
                        return
                    }
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
        splitGapPx = (prefs.getInt("splitGapDp", 8) * resources.displayMetrics.density).toInt()
        overlayFadeSeconds = prefs.getInt("overlayFadeSeconds", 0)
        burnInEnabled = prefs.getBoolean("burnInEnabled", true)
        clockSizeSp = prefs.getInt("clockSizeSp", 40)
        metadataSizeSp = prefs.getInt("metadataSizeSp", 20)
        overlayScrimAlpha = prefs.getInt("overlayScrimAlpha", 96)
        safeMarginDp = prefs.getInt("safeMarginDp", 24)
        edgeFeatherPx = (prefs.getInt("edgeFeatherDp", 16) * resources.displayMetrics.density).toInt()
        zoomFrameRate = prefs.getInt("zoomFrameRate", 30).coerceIn(10, 60)
        showCurrentDate = prefs.getBoolean("showCurrentDate", true)
        var savedUrl = prefs.getString("webview_url", "") ?: ""
        useWebView = prefs.getBoolean("useWebView", false)
        val authSecret = prefs.getString("authSecret", "") ?: ""
        val settingsLock = prefs.getBoolean("settingsLock", false)
        val activeTimes = prefs.getBoolean("activeTimes", false)
        configureRpcServer()

        webViewContainer.visibility = if (useWebView) View.VISIBLE else View.GONE
        imageView1.visibility = if (useWebView) View.GONE else View.VISIBLE
        imageView2.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnPrevious.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnPause.visibility = if (useWebView) View.GONE else View.VISIBLE
        btnNext.visibility = if (useWebView) View.GONE else View.VISIBLE
        swipeRefreshLayout.isEnabled = !settingsLock
        photoInfoContainer.visibility = View.GONE //enabled in onSettingsLoaded based on server settings
        txtDateTime.visibility = View.GONE //enabled in onSettingsLoaded based on server settings

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
        if (serverSettings.imageFill) {
            imageView1.scaleType = ImageView.ScaleType.CENTER_CROP
            imageView2.scaleType = ImageView.ScaleType.CENTER_CROP
        } else {
            imageView1.scaleType = ImageView.ScaleType.FIT_CENTER
            imageView2.scaleType = ImageView.ScaleType.FIT_CENTER
        }
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            photoInfoContainer.visibility = View.VISIBLE
            val infoSize = metadataSizeSp.toFloat()
            txtPhotoInfoLeft.textSize = infoSize
            txtPhotoInfoRight.textSize = infoSize
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
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)

        if (serverSettings.showWeatherDescription) {
            startWeatherTimer()
        }
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
            getNextImage()
            startImageTimer()
        }
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
                getNextImage()
                startImageTimer()
            }
        }
    }

    private fun settingsAction(authorizedOverride: Boolean = false) {
        val locked = PreferenceManager.getDefaultSharedPreferences(applicationContext)
            .getBoolean("settingsLock", false)
        if (locked && !authorizedOverride) return
        val intent = Intent(this, SettingsActivity::class.java)
        stopImageTimer()
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
            // Stop refreshing content while inactive
            stopImageTimer()
            stopWeatherTimer()
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
        configureRpcServer()
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
        super.onPause()
    }

    override fun onDestroy() {
        if (::framePresenter.isInitialized) framePresenter.clear()
        destroyWebView()
        super.onDestroy()
        rpcServer?.stop()
        rpcServer = null
        imageCall?.cancel()
        imageJob?.cancel()
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
            val reachable = withContext(Dispatchers.IO) {
                Helpers.isServerReachable(url)
            }

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

    private fun configureRpcServer() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val enabled = prefs.getBoolean("rpcEnabled", false)
        val token = prefs.getString("rpcToken", "").orEmpty()
        if (!enabled || token.isBlank()) {
            rpcServer?.stop()
            rpcServer = null
            return
        }
        if (rpcServer != null) return
        val server = RpcHttpServer(
            expectedToken = {
                PreferenceManager.getDefaultSharedPreferences(applicationContext)
                    .getString("rpcToken", "").orEmpty()
            },
            onFrameActiveCommand = { active -> runOnUiThread { setFrameActive(active) } },
            onNextCommand = { runOnUiThread { nextAction() } },
            onPreviousCommand = { runOnUiThread { previousAction() } },
            onPauseCommand = { runOnUiThread { pauseAction() } },
            onSettingsCommand = { runOnUiThread { settingsAction(authorizedOverride = true) } },
            onBrightnessCommand = { brightness -> runOnUiThread { screenBrightnessAction(brightness) } },
        )
        try {
            server.start()
            rpcServer = server
        } catch (e: Exception) {
            Log.e("MainActivity", "RPC server unavailable", e)
            server.stop()
        }
    }

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
