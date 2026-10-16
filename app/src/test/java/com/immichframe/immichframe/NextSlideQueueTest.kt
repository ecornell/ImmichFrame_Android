package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NextSlideQueueTest {
    private val discarded = mutableListOf<String>()
    private val queue = NextSlideQueue<String> { discarded += it }
    private val usable: (String) -> Boolean = { true }

    @Test fun firstAdvanceBuildsAndPresentsWhenBuilt() {
        assertEquals(NextSlideQueue.Advance.Build, queue.advance(usable))
        assertTrue(queue.isBuilding)
        assertEquals("a", queue.onBuilt("a"))
        assertFalse(queue.isBuilding)
    }

    @Test fun prefetchedSlideIsPresentedImmediatelyOnAdvance() {
        assertTrue(queue.beginPrefetch())
        assertNull(queue.onBuilt("next"))
        assertEquals(NextSlideQueue.Advance.Present("next"), queue.advance(usable))
        // Taking it leaves room for the following prefetch.
        assertTrue(queue.beginPrefetch())
    }

    @Test fun advanceDuringAPrefetchWaitsForIt() {
        assertTrue(queue.beginPrefetch())
        assertEquals(NextSlideQueue.Advance.Waiting, queue.advance(usable))
        assertEquals(NextSlideQueue.Advance.Waiting, queue.advance(usable))
        assertEquals("late", queue.onBuilt("late"))
    }

    @Test fun onlyOneBuildRunsAtATime() {
        assertTrue(queue.beginPrefetch())
        assertFalse(queue.beginPrefetch())
        assertNull(queue.onBuilt("ready"))
        assertFalse(queue.beginPrefetch())
    }

    @Test fun staleReadySlideIsDiscardedAndRebuilt() {
        queue.beginPrefetch()
        queue.onBuilt("old-layout")
        assertEquals(NextSlideQueue.Advance.Build, queue.advance { false })
        assertEquals(listOf("old-layout"), discarded)
    }

    @Test fun failedPrefetchIsSilentButAFailedRequestIsReported() {
        queue.beginPrefetch()
        assertFalse(queue.onFailed())
        assertEquals(NextSlideQueue.Advance.Build, queue.advance(usable))
        assertTrue(queue.onFailed())
        assertFalse(queue.isBuilding)
    }

    @Test fun pausingKeepsTheSlideButDoesNotPresentIt() {
        assertEquals(NextSlideQueue.Advance.Build, queue.advance(usable))
        queue.withdrawRequest()
        assertNull(queue.onBuilt("built-while-paused"))
        assertEquals(NextSlideQueue.Advance.Present("built-while-paused"), queue.advance(usable))
        assertTrue(discarded.isEmpty())
    }

    @Test fun clearDiscardsTheReadySlideAndResets() {
        queue.beginPrefetch()
        queue.onBuilt("ready")
        queue.clear()
        assertEquals(listOf("ready"), discarded)
        assertFalse(queue.isBuilding)
        assertEquals(NextSlideQueue.Advance.Build, queue.advance(usable))
    }

    @Test fun clearDuringABuildDropsThePendingRequest() {
        queue.advance(usable)
        queue.clear()
        // The caller treats the in-flight result as stale; a new request builds afresh.
        assertTrue(queue.beginPrefetch())
    }
}
