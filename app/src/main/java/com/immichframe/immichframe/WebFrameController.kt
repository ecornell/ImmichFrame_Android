package com.immichframe.immichframe

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The WebView presentation shared by the activity and the screensaver. The WebView is created
 * lazily, so native mode never pays for it, and destroyed when native mode takes over.
 */
internal class WebFrameController(
    private val context: Context,
    private val container: FrameLayout,
    /** Null once the host has ended (the screensaver's scope after the dream stops). */
    private val scope: () -> CoroutineScope?,
    /** False once the host has left WebView mode; a pending connection retry then gives up. */
    private val isActive: () -> Boolean,
    /** A service has no task of its own to open a link in. */
    private val openLinksInNewTask: Boolean,
    private val configureView: (WebView) -> Unit = {},
) {
    var view: WebView? = null
        private set
    private val handler = Handler(Looper.getMainLooper())
    private val retryToken = Any()
    private var connectJob: Job? = null

    /** Shows [url] once the server answers; a failed page load shows the error page and retries. */
    @SuppressLint("SetJavaScriptEnabled")
    fun load(prefs: SharedPreferences, url: String, authSecret: String) {
        val activeWebView = ensureView()
        cancelRetries()
        val headers = if (authSecret.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $authSecret")
        activeWebView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val link = request?.url ?: return false
                // Open the URL in the default browser; frames often have none.
                val intent = Intent(Intent.ACTION_VIEW, link)
                if (openLinksInNewTask) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(intent)
                } catch (error: ActivityNotFoundException) {
                    Log.w(TAG, "No app can open $link")
                }
                return true
            }

            override fun onReceivedError(view: WebView?, request: WebResourceRequest?, error: WebResourceError?) {
                super.onReceivedError(view, request, error)
                if (request?.isForMainFrame != true || error == null) return
                view?.loadUrl("file:///android_asset/error_page.html")
                postRetry(500) {
                    val errorCode = error.errorCode
                    val errorDescription = error.description.toString().replace("'", "\\'")
                    view?.evaluateJavascript("showError('$errorCode', '$errorDescription')", null)
                }
                // Only a failed page load is retried; a failed image or favicon would
                // otherwise reload the whole page every 5 seconds.
                postRetry(5000) {
                    // Check the URL again in case the user has changed it.
                    val currentUrl = prefs.getString("webview_url", "")?.trim() ?: ""
                    if (currentUrl.isNotEmpty() && this@WebFrameController.view === activeWebView) {
                        activeWebView.loadUrl(currentUrl, headers)
                    }
                }
            }
        }
        activeWebView.settings.javaScriptEnabled = true
        activeWebView.settings.cacheMode = WebSettings.LOAD_DEFAULT
        activeWebView.settings.domStorageEnabled = true
        connectJob?.cancel()
        connectJob = scope()?.launch { connectWithRetry(url, headers) }
    }

    /** Empties the page while the display is off, so nothing reloads it on the dark screen. */
    fun blank() {
        cancelRetries()
        view?.loadUrl("about:blank")
    }

    fun destroy() {
        cancelRetries()
        view?.let { view ->
            view.stopLoading()
            view.loadUrl("about:blank")
            view.clearHistory()
            container.removeView(view)
            view.destroy()
        }
        view = null
    }

    private suspend fun connectWithRetry(url: String, headers: Map<String, String>) {
        var attempt = 1
        while (true) {
            if (!isActive()) return
            val reachable = withContext(Dispatchers.IO) { Helpers.isServerReachable(url) }
            if (!isActive()) return
            if (reachable) {
                view?.loadUrl(url, headers)
                return
            }
            Toast.makeText(context, "Connecting to server... Attempt $attempt", Toast.LENGTH_SHORT).show()
            delay(5_000)
            attempt++
        }
    }

    private fun ensureView(): WebView {
        view?.let { return it }
        return WebView(context).also { created ->
            created.setBackgroundColor(Color.BLACK)
            created.layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
            configureView(created)
            container.addView(created)
            view = created
        }
    }

    /** Error-page script and reload callbacks, posted with [retryToken] so they can be removed. */
    private fun postRetry(delayMillis: Long, action: () -> Unit) {
        handler.postAtTime(action, retryToken, SystemClock.uptimeMillis() + delayMillis)
    }

    /** Also stops a connection retry still waiting for the server. */
    private fun cancelRetries() {
        handler.removeCallbacksAndMessages(retryToken)
        connectJob?.cancel()
        connectJob = null
    }

    private companion object {
        const val TAG = "WebFrame"
    }
}
