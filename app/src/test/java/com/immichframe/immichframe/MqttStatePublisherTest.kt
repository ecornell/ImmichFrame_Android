package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MqttStatePublisherTest {
    private val settings = MqttSettings("tcp://127.0.0.1:1", "", "", "test")
    private val display = "immichframe/test/display/state"
    private val brightness = "immichframe/test/brightness/state"

    @Test fun aLateListenerCallPublishesTheNewestStateNotItsOwn() {
        val store = RemoteStateStore()
        val retained = HashMap<String, String>()
        val publisher = MqttStatePublisher(settings, store::snapshot) { topic, payload ->
            synchronized(retained) { retained[topic] = payload }
            true
        }
        val newerPublished = CountDownLatch(1)
        val olderUpdated = CountDownLatch(1)
        // The older update's listener runs only after the newer update has been published,
        // which is the order that left a stale retained state on the broker.
        store.addListener { state ->
            if (!state.displayActive) {
                olderUpdated.countDown()
                assertTrue(newerPublished.await(5, TimeUnit.SECONDS))
            }
            publisher.publishLatest()
            if (state.displayActive && state.paused) newerPublished.countDown()
        }
        val older = Thread { store.update { it.copy(displayActive = false) } }.apply { start() }
        assertTrue(olderUpdated.await(5, TimeUnit.SECONDS))
        store.update { it.copy(displayActive = true, paused = true) }
        older.join(5_000)

        assertEquals("ON", retained[display])
        assertEquals("OFF", retained["immichframe/test/playback/state"])
    }

    @Test fun concurrentUpdatesLeaveTheFinalStateRetained() {
        val store = RemoteStateStore()
        val retained = HashMap<String, String>()
        val publisher = MqttStatePublisher(settings, store::snapshot) { topic, payload ->
            synchronized(retained) { retained[topic] = payload }
            true
        }
        store.addListener { publisher.publishLatest() }
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) { thread ->
            pool.execute {
                repeat(200) { step ->
                    store.update { it.copy(brightness = ((thread * 200 + step) % 101) / 100f, displayActive = step % 2 == 0) }
                }
            }
        }
        pool.shutdown()
        assertTrue(pool.awaitTermination(20, TimeUnit.SECONDS))

        val expected = MqttPayloads.state(settings, store.snapshot())
        assertEquals(expected, expected.keys.associateWith { retained[it] })
    }

    @Test fun onlyChangedTopicsAreSentUntilReset() {
        val store = RemoteStateStore()
        val sent = mutableListOf<String>()
        val publisher = MqttStatePublisher(settings, store::snapshot) { topic, _ -> sent += topic; true }
        publisher.publishLatest()
        val full = sent.size
        sent.clear()
        store.update { it.copy(brightness = 0.5f) }
        publisher.publishLatest()
        assertEquals(listOf(brightness), sent)
        sent.clear()
        publisher.reset()
        publisher.publishLatest()
        assertEquals(full, sent.size)
    }

    @Test fun aFailedPublishIsRetriedWithTheNextChange() {
        val store = RemoteStateStore()
        var connected = false
        val sent = mutableListOf<String>()
        val publisher = MqttStatePublisher(settings, store::snapshot) { topic, _ ->
            if (connected) sent += topic
            connected
        }
        publisher.publishLatest()
        connected = true
        store.update { it.copy(brightness = 0.5f) }
        publisher.publishLatest()
        assertTrue(sent.contains(display))
    }
}
