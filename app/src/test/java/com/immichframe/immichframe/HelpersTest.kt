package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class HelpersTest {
    @Test fun dimensionsNeverUpscale() {
        assertEquals(800 to 600, Helpers.scaledDimensions(800, 600, 1000))
        assertEquals(1000 to 500, Helpers.scaledDimensions(4000, 2000, 1000))
    }

    @Test fun decodeSamplingStaysNearTheRenderTarget() {
        assertEquals(2, Helpers.decodeSampleSize(6000, 4000, 1920))
        assertEquals(2, Helpers.decodeSampleSize(4032, 3024, 1920))
        assertEquals(1, Helpers.decodeSampleSize(1920, 1080, 1920))
    }

    @Test fun overnightScheduleIsAnchoredToStartingDay() {
        val schedule = Helpers.ActiveSchedule(listOf(
            Helpers.ActiveRule(setOf(Calendar.MONDAY), listOf(Helpers.ActiveRange("22:00", "06:00")))
        ))
        val monday = Calendar.getInstance().apply {
            set(2026, Calendar.AUGUST, 24, 23, 0, 0)
        }
        assertTrue(Helpers.isActiveNow(schedule, monday))
        val tuesday = monday.clone() as Calendar
        tuesday.add(Calendar.HOUR, 2)
        assertTrue(Helpers.isActiveNow(schedule, tuesday))
        tuesday.set(Calendar.HOUR_OF_DAY, 7)
        assertFalse(Helpers.isActiveNow(schedule, tuesday))
    }
}
