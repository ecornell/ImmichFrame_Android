package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackTimerTest {
    private val pending = linkedMapOf<Runnable, Long>()
    private var ticks = 0
    private var interval = 30_000L
    private val timer = PlaybackTimer(
        { task, delay -> pending[task] = delay }, { pending.remove(it) },
        { interval }, { ticks++ },
    )

    @Test fun nativeWebViewNativeRestartsTheTimerExactlyOnce() {
        timer.start()
        timer.start()
        assertEquals(1, pending.size)
        timer.stop()
        assertFalse(timer.isRunning)
        assertTrue(pending.isEmpty())
        timer.start()
        assertTrue(timer.isRunning)
        assertEquals(1, pending.size)
        fire()
        assertEquals(1, ticks)
        assertEquals(listOf(30_000L), pending.values.toList())
    }

    @Test fun malformedImageRetryReplacesNormalTickButCannotResumePausedPlayback() {
        timer.start()
        timer.retryAfter(5_000L)
        assertEquals(listOf(5_000L), pending.values.toList())
        fire()
        assertEquals(1, ticks)
        assertEquals(listOf(30_000L), pending.values.toList())
        timer.stop()
        timer.retryAfter(5_000L)
        assertTrue(pending.isEmpty())
    }

    @Test fun stoppedCallbackDoesNotFetchOrReschedule() {
        timer.start()
        val staleCallback = pending.keys.single()
        timer.stop()
        staleCallback.run()
        assertEquals(0, ticks)
        assertTrue(pending.isEmpty())
    }

    @Test fun weatherStartsImmediatelyAndUsesUpdatedIntervalOnNextTick() {
        timer.start(0L)
        assertEquals(listOf(0L), pending.values.toList())
        interval = 600_000L
        fire()
        assertEquals(listOf(600_000L), pending.values.toList())
        assertEquals(1, ticks)
    }

    private fun fire() {
        val task = pending.keys.single()
        pending.remove(task)
        task.run()
    }
}
