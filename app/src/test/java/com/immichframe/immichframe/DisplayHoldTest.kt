package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DisplayHoldTest {
    private val endOfDay = 23_000L

    @Test fun aCommandThatMatchesTheScheduleNeedsNoHold() {
        assertNull(DisplayHold.forCommand(active = true, scheduledActive = true, nextChangeMillis = endOfDay, nowMillis = 0L))
        assertNull(DisplayHold.forCommand(active = false, scheduledActive = false, nextChangeMillis = endOfDay, nowMillis = 0L))
    }

    @Test fun displayOffDuringActiveHoursHoldsUntilTheNextScheduledChange() {
        val hold = DisplayHold.forCommand(active = false, scheduledActive = true, nextChangeMillis = endOfDay, nowMillis = 15_000L)
        assertEquals(DisplayHold(false, endOfDay), hold)
        // The 30-second schedule check sees "should be active" but the hold keeps the frame off.
        assertEquals(false to hold, DisplayHold.resolve(scheduledActive = true, hold = hold, nowMillis = 15_000L))
        // At the scheduled change the hold ends and the schedule decides again.
        assertEquals(false to null, DisplayHold.resolve(scheduledActive = false, hold = hold, nowMillis = endOfDay))
    }

    @Test fun displayOnDuringInactiveHoursHoldsAtMostTwoHours() {
        val hour = 60 * 60 * 1_000L
        val night = 23 * hour + 30 * 60 * 1_000L
        val morning = 31 * hour
        // On at 23:30 with the next change at 07:00: held until 01:30, not all night.
        val hold = DisplayHold.forCommand(active = true, scheduledActive = false, nextChangeMillis = morning, nowMillis = night)
        assertEquals(DisplayHold(true, night + 2 * hour), hold)
        assertEquals(true to hold, DisplayHold.resolve(scheduledActive = false, hold = hold, nowMillis = night + hour))
        assertEquals(false to null, DisplayHold.resolve(scheduledActive = false, hold = hold, nowMillis = night + 2 * hour))
        // A scheduled change sooner than two hours ends it first.
        val soon = DisplayHold.forCommand(active = true, scheduledActive = false, nextChangeMillis = night + hour, nowMillis = night)
        assertEquals(DisplayHold(true, night + hour), soon)
    }

    @Test fun withoutAScheduledChangeTheHoldLastsUntilTheNextCommand() {
        val hold = DisplayHold.forCommand(active = false, scheduledActive = true, nextChangeMillis = null, nowMillis = 0L)
        assertEquals(false to hold, DisplayHold.resolve(scheduledActive = true, hold = hold, nowMillis = Long.MAX_VALUE))
    }

    @Test fun noHoldFollowsTheSchedule() {
        assertEquals(true to null, DisplayHold.resolve(scheduledActive = true, hold = null, nowMillis = 0L))
        assertEquals(false to null, DisplayHold.resolve(scheduledActive = false, hold = null, nowMillis = 0L))
    }
}
