package com.immichframe.immichframe

import android.annotation.SuppressLint
import android.service.dreams.DreamService
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import androidx.preference.PreferenceManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive

/**
 * The screensaver: the same native slideshow, overlays and WebView as the activity, without
 * remote control, photo reporting or videos.
 */
class ScreenSaverService : DreamService() {
    private var dreamScope: CoroutineScope? = null
    private lateinit var web: WebFrameController
    private lateinit var webViewContainer: FrameLayout
    private lateinit var imageView1: ImageView
    private lateinit var imageView2: ImageView
    private lateinit var overlay: OverlayController
    private lateinit var slideshow: NativeSlideshowController
    private var useWebView = true

    @SuppressLint("ClickableViewAccessibility")
    override fun onDreamingStarted() {
        super.onDreamingStarted()
        val dream = CoroutineScope(Dispatchers.Main + Job())
        dreamScope = dream
        isFullscreen = true
        isInteractive = true
        setContentView(R.layout.screen_saver_view)
        webViewContainer = findViewById(R.id.webViewContainer)
        web = WebFrameController(
            this, webViewContainer, scope = { dreamScope }, isActive = { useWebView },
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
        overlay = OverlayController(
            findViewById(R.id.txtDateTime),
            findViewById(R.id.photoInfoContainer),
            findViewById(R.id.txtPhotoInfoLeft),
            findViewById(R.id.txtPhotoInfoRight),
            findViewById(R.id.txtOfflineIndicator),
            shouldTick = { !useWebView },
        )
        slideshow = NativeSlideshowController(
            applicationContext,
            object : NativeSlideshowController.Host {
                override val scope: CoroutineScope = dream
                override fun isAlive() = dream.isActive

                override fun onSettingsLoadFailed(error: Throwable) {
                    Toast.makeText(
                        this@ScreenSaverService,
                        "Failed to load server settings: ${error.localizedMessage}",
                        Toast.LENGTH_SHORT,
                    ).show()
                }
            },
            window.decorView, imageView1, imageView2, overlay, videoPlayer = null,
        )

        loadSettings()
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        if (::overlay.isInitialized) overlay.stopClock()
        if (::slideshow.isInitialized) slideshow.destroy()
        dreamScope?.cancel()
        dreamScope = null
        if (::web.isInitialized) web.destroy()
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
            slideshow.previous()
        }
    }

    private fun nextAction() {
        if (useWebView) {
            // Simulate a key press
            web.view?.requestFocus()
            val event = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT)
            web.view?.dispatchKeyEvent(event)
        } else {
            slideshow.next()
        }
    }

    private fun loadSettings() {
        val prefs = PreferenceManager.getDefaultSharedPreferences(applicationContext)
        val framePrefs = FramePreferences.load(prefs, resources.displayMetrics.density)
        overlay.setPreferences(framePrefs)
        slideshow.preferences = framePrefs
        slideshow.stop()
        val savedUrl = prefs.getString("webview_url", "") ?: ""
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
            web.destroy()
            slideshow.start(
                savedUrl, authSecret, Helpers.clientIdentifier(prefs, "screensaver"), includeVideos = false,
            )
        }
    }
}
