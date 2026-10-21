package com.immichframe.immichframe

import android.content.SharedPreferences

/** Native presentation preferences, read the same way by the activity and the screensaver. */
internal data class FramePreferences(
    val frameBackground: FrameBackground = FrameBackground.THUMBHASH,
    val frameScale: FrameScale = FrameScale.FIT,
    val splitGapPx: Int = 6,
    val overlayFadeSeconds: Int = 0,
    val burnInEnabled: Boolean = true,
    val clockSizeSp: Int = 40,
    val metadataSizeSp: Int = 24,
    val overlayScrimAlpha: Int = 96,
    val safeMarginDp: Int = 24,
    val edgeFeatherPx: Int = 32,
    val backgroundBlurRadius: Int = 16,
    val backgroundDarkness: Int = 84,
    val zoomFrameRate: Int = 15,
    val transitionFrameRate: Int = 30,
    val showCurrentDate: Boolean = true,
) {
    companion object {
        /** [density] converts the dp preferences to pixels. */
        fun load(prefs: SharedPreferences, density: Float): FramePreferences = FramePreferences(
            frameBackground = runCatching {
                FrameBackground.valueOf(prefs.getString("frameBackground", "THUMBHASH")!!)
            }.getOrDefault(FrameBackground.THUMBHASH),
            frameScale = runCatching {
                FrameScale.valueOf(prefs.getString("frameScale", "FIT")!!)
            }.getOrDefault(FrameScale.FIT),
            splitGapPx = (prefs.getInt("splitGapDp", 6) * density).toInt(),
            overlayFadeSeconds = prefs.getInt("overlayFadeSeconds", 0),
            burnInEnabled = prefs.getBoolean("burnInEnabled", true),
            clockSizeSp = prefs.getInt("clockSizeSp", 40),
            metadataSizeSp = prefs.getInt("metadataSizeSp", 24),
            overlayScrimAlpha = prefs.getInt("overlayScrimAlpha", 96),
            safeMarginDp = prefs.getInt("safeMarginDp", 24),
            edgeFeatherPx = (prefs.getInt("edgeFeatherDp", 32) * density).toInt(),
            backgroundBlurRadius = prefs.getInt("backgroundBlurRadius", 16).coerceIn(0, 24),
            backgroundDarkness = prefs.getInt("backgroundDarkness", 84).coerceIn(0, 200),
            zoomFrameRate = prefs.getInt("zoomFrameRate", 15).coerceIn(10, 60),
            transitionFrameRate = prefs.getInt("transitionFrameRate", 30).coerceIn(10, 60),
            showCurrentDate = prefs.getBoolean("showCurrentDate", true),
        )
    }
}
