package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

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
        val monday = calendar(2026, Calendar.AUGUST, 24, 23, 0)
        assertTrue(Helpers.isActiveNow(schedule, monday))
        val tuesday = monday.clone() as Calendar
        tuesday.add(Calendar.HOUR, 2)
        assertTrue(Helpers.isActiveNow(schedule, tuesday))
        tuesday.set(Calendar.HOUR_OF_DAY, 7)
        assertFalse(Helpers.isActiveNow(schedule, tuesday))
    }

    @Test fun nextActiveStartFindsSameDayTransitionWhenInactive() {
        val schedule = schedule(Calendar.MONDAY, "10:00", "11:00")
        val next = Helpers.nextActiveStart(schedule, calendar(2026, Calendar.AUGUST, 24, 9, 15))!!
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(10, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
    }

    @Test fun nextActiveStartSkipsRemainderOfCurrentActivePeriod() {
        val schedule = schedule(Calendar.MONDAY, "10:00", "11:00")
        val next = Helpers.nextActiveStart(schedule, calendar(2026, Calendar.AUGUST, 24, 10, 15))!!
        assertEquals(Calendar.MONDAY, next.get(Calendar.DAY_OF_WEEK))
        assertEquals(31, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(10, next.get(Calendar.HOUR_OF_DAY))
    }

    @Test fun nextActiveStartFindsNextOvernightOpening() {
        val schedule = schedule(Calendar.MONDAY, "22:00", "06:00")
        val next = Helpers.nextActiveStart(schedule, calendar(2026, Calendar.AUGUST, 25, 2, 0))!!
        assertEquals(31, next.get(Calendar.DAY_OF_MONTH))
        assertEquals(22, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
    }

    @Test fun nextActiveStartFollowsDaylightSavingTimeJump() {
        val zone = TimeZone.getTimeZone("America/New_York")
        val schedule = schedule(Calendar.SUNDAY, "03:00", "04:00")
        val next = Helpers.nextActiveStart(
            schedule,
            calendar(2026, Calendar.MARCH, 8, 1, 55, zone),
        )!!
        assertEquals(3, next.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, next.get(Calendar.MINUTE))
        assertEquals(zone.id, next.timeZone.id)
    }

    @Test fun nextActiveStartFindsRepeatedHourTransitionInAutumn() {
        val zone = TimeZone.getTimeZone("America/New_York")
        // 05:45 UTC is the first 01:45 (EDT); the clock then rolls back to 01:00 EST.
        val now = Calendar.getInstance(zone).apply {
            timeInMillis = calendar(2026, Calendar.NOVEMBER, 1, 5, 45).timeInMillis
        }
        val next = Helpers.nextActiveStart(schedule(Calendar.SUNDAY, "01:30", "01:40"), now)!!
        assertEquals(calendar(2026, Calendar.NOVEMBER, 1, 6, 30).timeInMillis, next.timeInMillis)
    }

    @Test fun alwaysActiveSchedulesHaveNoWakeTransition() {
        val now = calendar(2026, Calendar.AUGUST, 24, 10, 0)
        assertNull(Helpers.nextActiveStart(Helpers.ActiveSchedule(emptyList()), now))
        val allDay = Helpers.ActiveSchedule(listOf(
            Helpers.ActiveRule((Calendar.SUNDAY..Calendar.SATURDAY).toSet(), listOf(Helpers.ActiveRange("00:00", "00:00")))
        ))
        assertNull(Helpers.nextActiveStart(allDay, now))
    }

    @Test fun adjacentActiveRangesDoNotCreateExtraWakeTransitions() {
        val schedule = Helpers.ActiveSchedule(listOf(
            Helpers.ActiveRule(setOf(Calendar.MONDAY), listOf(
                Helpers.ActiveRange("10:00", "11:00"), Helpers.ActiveRange("11:00", "12:00"),
            ))
        ))
        val next = Helpers.nextActiveStart(schedule, calendar(2026, Calendar.AUGUST, 24, 10, 59))!!
        assertEquals(calendar(2026, Calendar.AUGUST, 31, 10, 0).timeInMillis, next.timeInMillis)
    }

    private fun schedule(day: Int, start: String, end: String) = Helpers.ActiveSchedule(
        listOf(Helpers.ActiveRule(setOf(day), listOf(Helpers.ActiveRange(start, end))))
    )

    private fun calendar(
        year: Int,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int,
        zone: TimeZone = TimeZone.getTimeZone("UTC"),
    ): Calendar = Calendar.getInstance(zone).apply {
        clear()
        set(year, month, day, hour, minute)
    }
}
