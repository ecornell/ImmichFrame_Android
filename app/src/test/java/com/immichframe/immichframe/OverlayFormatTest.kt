package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

class OverlayFormatTest {
    private val meta = FrameMeta("07/04/2019", "Lyon, France")

    @Test fun photoInfoShowsTheEnabledPartsOnSeparateLines() {
        assertEquals("07/04/2019\nLyon, France", OverlayFormat.photoInfo(meta, showDate = true, showLocation = true))
        assertEquals("Lyon, France", OverlayFormat.photoInfo(meta, showDate = false, showLocation = true))
        assertEquals("07/04/2019", OverlayFormat.photoInfo(meta, showDate = true, showLocation = false))
        assertEquals("Lyon, France", OverlayFormat.photoInfo(FrameMeta("", "Lyon, France"), true, true))
        assertEquals("", OverlayFormat.photoInfo(FrameMeta("", ""), true, true))
    }

    @Test fun aSinglePhotoIsCaptionedOnTheRightOnly() {
        assertEquals("" to "07/04/2019", OverlayFormat.photoInfo(meta, null, true, false))
        val right = FrameMeta("01/01/2020", "")
        assertEquals("07/04/2019" to "01/01/2020", OverlayFormat.photoInfo(meta, right, true, false))
    }

    @Test fun dateTimePutsTheDateAboveTheEnlargedTime() {
        val now = Calendar.getInstance(TimeZone.getDefault()).apply { set(2024, Calendar.MARCH, 9, 7, 5, 0) }.time
        val withDate = OverlayFormat.dateTime(now, "dd.MM.yyyy", "HH:mm", showDate = true, Locale.US)
        assertEquals("09.03.2024\n07:05", withDate.text)
        assertEquals(11, withDate.timeStart)
        val timeOnly = OverlayFormat.dateTime(now, "dd.MM.yyyy", "HH:mm", showDate = false, Locale.US)
        assertEquals(OverlayFormat.DateTime("07:05", 0), timeOnly)
    }

    @Test fun aMalformedFormatShowsNothingForThatPart() {
        val now = Calendar.getInstance().time
        val badDate = OverlayFormat.dateTime(now, "'unterminated", "HH", showDate = true, Locale.US)
        assertEquals(0, badDate.timeStart)
        assertEquals(2, badDate.text.length)
        assertEquals("", OverlayFormat.dateTime(now, "yyyy", "'bad", showDate = false, Locale.US).text)
    }

    @Test fun weatherShowsOneDecimalAndTheUnit() {
        val weather = Helpers.Weather("Lyon", 21.26, "°C", "21.3 °C", "Clear sky", "01d")
        assertEquals("\n Lyon, 21.3°C \n Clear sky", OverlayFormat.weather(weather, Locale.US))
        assertEquals("\n Lyon, 21,3°C \n Clear sky", OverlayFormat.weather(weather, Locale.FRANCE))
    }

    @Test fun scrimContrastsWithTheTextColour() {
        assertTrue(OverlayFormat.scrimIsBlack(255, 255, 255))
        assertFalse(OverlayFormat.scrimIsBlack(0, 0, 0))
        assertTrue(OverlayFormat.scrimIsBlack(255, 200, 0)) // yellow reads as light
        assertFalse(OverlayFormat.scrimIsBlack(0, 0, 255)) // blue reads as dark
    }
}
