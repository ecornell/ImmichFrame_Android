package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class RemoteStateStoreTest {
    private fun RemoteFrameState.count() = lastImageSuccessMillis ?: 0L

    @Test fun concurrentUpdatesAreNeitherLostNorDuplicated() {
        val store = RemoteStateStore()
        val returned = Collections.synchronizedList(mutableListOf<Long>())
        val notified = Collections.synchronizedList(mutableListOf<Long>())
        store.addListener { notified += it.count() }
        val threads = 8
        val perThread = 500
        val pool = Executors.newFixedThreadPool(threads)
        repeat(threads) {
            pool.execute {
                repeat(perThread) {
                    returned += store.update { it.copy(lastImageSuccessMillis = it.count() + 1) }.count()
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))

        val total = (threads * perThread).toLong()
        assertEquals(total, store.snapshot().count())
        // Every update saw the one before it: each count from 1 to the total was produced once,
        // and every listener was told about exactly that update.
        assertEquals((1..total).toList(), returned.sorted())
        assertEquals((1..total).toList(), notified.sorted())
    }

    @Test fun aListenerIsNotifiedAfterTheValueIsVisible() {
        val store = RemoteStateStore()
        var seen: RemoteFrameState? = null
        store.addListener { seen = store.snapshot() }
        val updated = store.update { it.copy(paused = true) }
        assertEquals(updated, seen)
    }

    @Test fun aRemovedListenerIsNotCalled() {
        val store = RemoteStateStore()
        var calls = 0
        val listener: (RemoteFrameState) -> Unit = { calls++ }
        store.addListener(listener)
        store.update { it.copy(paused = true) }
        store.removeListener(listener)
        store.update { it.copy(paused = false) }
        assertEquals(1, calls)
    }
}
