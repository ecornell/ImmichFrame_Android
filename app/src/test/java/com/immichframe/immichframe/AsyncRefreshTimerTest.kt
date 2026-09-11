package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AsyncRefreshTimerTest {
    private val pending = linkedMapOf<Runnable, Long>()
    private val completions = mutableListOf<() -> Unit>()
    private var refreshes = 0
    private val timer = AsyncRefreshTimer(
        { task, delay -> pending[task] = delay },
        { pending.remove(it) },
        SERVER_SETTINGS_REFRESH_MILLIS,
        { complete ->
            refreshes++
            completions += complete
        },
    )

    @Test fun refreshesAtCadenceWithoutOverlappingRequests() {
        timer.start()
        timer.start()
        assertEquals(listOf(SERVER_SETTINGS_REFRESH_MILLIS), pending.values.toList())

        fire()
        assertEquals(1, refreshes)
        assertTrue(pending.isEmpty())

        completions.single().invoke()
        completions.single().invoke()
        assertEquals(listOf(SERVER_SETTINGS_REFRESH_MILLIS), pending.values.toList())
        fire()
        assertEquals(2, refreshes)
    }

    @Test fun stopCancelsPendingRefresh() {
        timer.start()
        val staleScheduledRefresh = pending.keys.single()

        timer.stop()
        staleScheduledRefresh.run()

        assertEquals(0, refreshes)
        assertTrue(pending.isEmpty())
    }

    @Test fun stopRejectsInFlightRefreshCompletion() {
        timer.start()
        fire()
        val staleCompletion = completions.single()

        timer.stop()
        staleCompletion()

        assertEquals(1, refreshes)
        assertTrue(pending.isEmpty())
    }

    @Test fun completionFromPreviousRunCannotDuplicateRestartedTimer() {
        timer.start()
        fire()
        val staleCompletion = completions.single()
        timer.stop()
        timer.start()

        staleCompletion()
        assertEquals(1, pending.size)
    }

    private fun fire() {
        val task = pending.keys.single()
        pending.remove(task)
        task.run()
    }
}
