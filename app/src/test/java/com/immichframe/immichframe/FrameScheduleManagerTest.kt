package com.immichframe.immichframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameScheduleManagerTest {
    @Test fun exactAlarmsNeedNoGrantBeforeAndroid12() {
        assertTrue(FrameScheduleManager.exactAlarmPermitted(25) { error("not asked before API 31") })
        assertTrue(FrameScheduleManager.exactAlarmPermitted(30) { false })
    }

    @Test fun fromAndroid12TheGrantDecides() {
        assertTrue(FrameScheduleManager.exactAlarmPermitted(31) { true })
        assertFalse(FrameScheduleManager.exactAlarmPermitted(31) { false })
        assertFalse(FrameScheduleManager.exactAlarmPermitted(34) { false })
    }
}
