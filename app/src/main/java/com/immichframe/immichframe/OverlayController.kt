package com.immichframe.immichframe

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.SpannableString
import android.text.Spanned
import android.text.style.RelativeSizeSpan
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.graphics.toColorInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The text the native overlays show, kept free of views so it can be unit tested. */
internal object OverlayFormat {
    fun photoInfo(meta: FrameMeta, showDate: Boolean, showLocation: Boolean): String = buildString {
        if (showDate && meta.date.isNotEmpty()) append(meta.date)
        if (showLocation && meta.location.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append(meta.location)
        }
    }

    /** Left and right captions: a single photo is captioned on the right only. */
    fun photoInfo(left: FrameMeta, right: FrameMeta?, showDate: Boolean, showLocation: Boolean): Pair<String, String> =
        (if (right == null) "" else photoInfo(left, showDate, showLocation)) to
            photoInfo(right ?: left, showDate, showLocation)

    /** The clock text; [timeStart] is where the (enlarged) time begins after an optional date line. */
    data class DateTime(val text: String, val timeStart: Int)

    fun dateTime(now: Date, dateFormat: String, clockFormat: String, showDate: Boolean, locale: Locale = Locale.getDefault()): DateTime {
        // A malformed server format shows nothing rather than failing the whole overlay.
        val date = runCatching { SimpleDateFormat(dateFormat, locale).format(now) }.getOrDefault("")
        val time = runCatching { SimpleDateFormat(clockFormat, locale).format(now) }.getOrDefault("")
        return if (showDate && date.isNotEmpty()) DateTime("$date\n$time", date.length + 1) else DateTime(time, 0)
    }

    /** Appended below the clock. */
    fun weather(weather: Helpers.Weather, locale: Locale = Locale.getDefault()): String =
        "\n ${weather.location}, ${String.format(locale, "%.1f", weather.temperature)}${weather.unit} \n ${weather.description}"

    /** Black behind light text and white behind dark text, by perceived luminance. */
    fun scrimIsBlack(red: Int, green: Int, blue: Int): Boolean = (red * 299 + green * 587 + blue * 114) / 1000 >= 128
}

/**
 * The clock, weather, photo captions and offline indicator over the native slideshow, shared by
 * the activity and the screensaver. The clock ticks every minute, moving the overlays slightly
 * against panel burn-in, whenever [shouldTick] and server settings have been applied.
 */
internal class OverlayController(
    private val dateTime: TextView,
    private val photoInfoContainer: View,
    private val photoInfoLeft: TextView,
    private val photoInfoRight: TextView,
    private val offlineIndicator: TextView,
    private val shouldTick: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private val burnIn = OverlayBurnInController()
    private var prefs = FramePreferences()
    private var settings: Helpers.ServerSettings? = null
    private var weather = ""
    private var lastLeft: FrameMeta? = null
    private var lastRight: FrameMeta? = null

    private val clockRunnable = object : Runnable {
        override fun run() {
            if (settings != null && shouldTick()) {
                updateDateTimeWeather()
                val distance = if (prefs.burnInEnabled) 12f else 0f
                val (x, y) = burnIn.offset(System.currentTimeMillis() / 300_000L, distance)
                dateTime.translationX = x
                dateTime.translationY = y
                photoInfoContainer.translationX = -x
                photoInfoContainer.translationY = -y
            }
            handler.postDelayed(this, 60_000L - (System.currentTimeMillis() % 60_000L))
        }
    }

    fun setPreferences(preferences: FramePreferences) {
        prefs = preferences
    }

    /** Hidden until server settings are applied. */
    fun hide() {
        photoInfoContainer.visibility = View.GONE
        dateTime.visibility = View.GONE
    }

    fun applyPresentation(serverSettings: Helpers.ServerSettings) {
        settings = serverSettings
        val color = serverSettings.primaryColor?.let {
            runCatching { it.toColorInt() }.getOrDefault(Color.WHITE)
        } ?: Color.WHITE
        if (serverSettings.showPhotoDate || serverSettings.showImageLocation) {
            photoInfoContainer.visibility = View.VISIBLE
            val infoSize = prefs.metadataSizeSp.toFloat()
            photoInfoLeft.textSize = infoSize
            photoInfoRight.textSize = infoSize
            photoInfoLeft.setTextColor(color)
            photoInfoRight.setTextColor(color)
            applyStyle()
        } else {
            photoInfoContainer.visibility = View.GONE
        }
        if (serverSettings.showClock) {
            dateTime.visibility = View.VISIBLE
            dateTime.textSize = prefs.clockSizeSp / 2f
            dateTime.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            dateTime.setTextColor(color)
            applyStyle()
        } else {
            dateTime.visibility = View.GONE
        }
    }

    /** Captions the slide just presented; [right] is null for a single photo. */
    fun showPhotoInfo(left: FrameMeta, right: FrameMeta?) {
        lastLeft = left
        lastRight = right
        val serverSettings = settings ?: return
        val (leftText, rightText) = OverlayFormat.photoInfo(
            left, right, serverSettings.showPhotoDate, serverSettings.showImageLocation,
        )
        photoInfoLeft.text = leftText
        photoInfoRight.text = rightText
        photoInfoLeft.visibility = if (leftText.isBlank()) View.GONE else View.VISIBLE
        photoInfoRight.visibility = if (rightText.isBlank()) View.GONE else View.VISIBLE
        photoInfoContainer.animate().cancel()
        photoInfoContainer.alpha = 1f
        if (prefs.overlayFadeSeconds > 0) {
            photoInfoContainer.animate().alpha(0f).setStartDelay(prefs.overlayFadeSeconds * 1000L).start()
        }
    }

    /** Re-captions the slide on screen, e.g. after the server settings changed. */
    fun refreshPhotoInfo() {
        lastLeft?.let { showPhotoInfo(it, lastRight) }
    }

    fun updateDateTimeWeather() {
        val serverSettings = settings ?: return
        if (serverSettings.showClock) {
            val (text, timeStart) = OverlayFormat.dateTime(
                Date(), serverSettings.photoDateFormat, serverSettings.clockFormat, prefs.showCurrentDate,
            )
            dateTime.text = SpannableString(text).apply {
                setSpan(RelativeSizeSpan(2f), timeStart, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        if (serverSettings.showWeatherDescription) dateTime.append(weather)
    }

    fun setWeather(current: Helpers.Weather) {
        weather = OverlayFormat.weather(current)
        updateDateTimeWeather()
    }

    /** Shows the offline indicator for eight seconds, or hides it. */
    fun setOffline(offline: Boolean) {
        offlineIndicator.animate().cancel()
        if (!offline) {
            offlineIndicator.visibility = View.GONE
            return
        }
        offlineIndicator.alpha = 1f
        offlineIndicator.visibility = View.VISIBLE
        offlineIndicator.animate()
            .alpha(0f)
            .setStartDelay(8_000L)
            .setDuration(500L)
            .withEndAction { offlineIndicator.visibility = View.GONE }
            .start()
    }

    /** (Re)starts the minute tick now. */
    fun startClock() {
        handler.removeCallbacks(clockRunnable)
        handler.post(clockRunnable)
    }

    fun stopClock() = handler.removeCallbacks(clockRunnable)

    private fun applyStyle() {
        val density = dateTime.resources.displayMetrics.density
        val horizontal = (12 * density).toInt()
        val vertical = (6 * density).toInt()
        val radius = 10 * density
        fun style(view: TextView, alpha: Int = prefs.overlayScrimAlpha) {
            val color = view.currentTextColor
            val scrim = if (OverlayFormat.scrimIsBlack(Color.red(color), Color.green(color), Color.blue(color))) {
                Color.BLACK
            } else {
                Color.WHITE
            }
            view.background = GradientDrawable().apply {
                setColor(Color.argb(alpha.coerceIn(0, 255), Color.red(scrim), Color.green(scrim), Color.blue(scrim)))
                cornerRadius = radius
            }
            view.setPadding(horizontal, vertical, horizontal, vertical)
        }
        style(dateTime)
        style(offlineIndicator)
        val metadataAlpha = (prefs.overlayScrimAlpha * 0.8f).toInt()
        style(photoInfoLeft, metadataAlpha)
        style(photoInfoRight, metadataAlpha)
        val margin = (prefs.safeMarginDp * density).toInt()
        (dateTime.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.setMargins(margin, margin, margin, margin)
            dateTime.layoutParams = it
        }
        (photoInfoContainer.layoutParams as? FrameLayout.LayoutParams)?.let {
            it.setMargins(margin, margin, margin, margin)
            photoInfoContainer.layoutParams = it
        }
    }
}
