package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Test

class FramePreferencesTest {
    @Test fun missingPreferencesUseTheDefaults() {
        val loaded = FramePreferences.load(FakeSharedPreferences(), density = 1f)
        assertEquals(FramePreferences(), loaded)
    }

    @Test fun dpPreferencesAreConvertedWithTheDensity() {
        val loaded = FramePreferences.load(
            FakeSharedPreferences("splitGapDp" to 10, "edgeFeatherDp" to 20),
            density = 1.5f,
        )
        assertEquals(15, loaded.splitGapPx)
        assertEquals(30, loaded.edgeFeatherPx)
        // safeMarginDp stays in dp: the overlay converts it when it lays out.
        assertEquals(24, FramePreferences.load(FakeSharedPreferences(), 2f).safeMarginDp)
    }

    @Test fun rangesAreClamped() {
        val low = FramePreferences.load(
            FakeSharedPreferences(
                "backgroundBlurRadius" to -3, "backgroundDarkness" to -1,
                "zoomFrameRate" to 1, "transitionFrameRate" to 0,
            ),
            1f,
        )
        assertEquals(listOf(0, 0, 10, 10), listOf(low.backgroundBlurRadius, low.backgroundDarkness, low.zoomFrameRate, low.transitionFrameRate))
        val high = FramePreferences.load(
            FakeSharedPreferences(
                "backgroundBlurRadius" to 99, "backgroundDarkness" to 999,
                "zoomFrameRate" to 240, "transitionFrameRate" to 120,
            ),
            1f,
        )
        assertEquals(listOf(24, 200, 60, 60), listOf(high.backgroundBlurRadius, high.backgroundDarkness, high.zoomFrameRate, high.transitionFrameRate))
    }

    @Test fun unknownEnumNamesFallBack() {
        val loaded = FramePreferences.load(
            FakeSharedPreferences("frameBackground" to "PLAID", "frameScale" to "stretch"),
            1f,
        )
        assertEquals(FrameBackground.THUMBHASH, loaded.frameBackground)
        assertEquals(FrameScale.FIT, loaded.frameScale)
        val chosen = FramePreferences.load(
            FakeSharedPreferences("frameBackground" to FrameBackground.values().last().name, "frameScale" to "CROP"),
            1f,
        )
        assertEquals(FrameBackground.values().last(), chosen.frameBackground)
        assertEquals(FrameScale.CROP, chosen.frameScale)
    }

    @Test fun flagsAndSizesAreRead() {
        val loaded = FramePreferences.load(
            FakeSharedPreferences(
                "burnInEnabled" to false, "showCurrentDate" to false, "overlayFadeSeconds" to 8,
                "clockSizeSp" to 52, "metadataSizeSp" to 18, "overlayScrimAlpha" to 0, "safeMarginDp" to 40,
            ),
            1f,
        )
        assertEquals(false, loaded.burnInEnabled)
        assertEquals(false, loaded.showCurrentDate)
        assertEquals(listOf(8, 52, 18, 0, 40), listOf(loaded.overlayFadeSeconds, loaded.clockSizeSp, loaded.metadataSizeSp, loaded.overlayScrimAlpha, loaded.safeMarginDp))
    }
}
