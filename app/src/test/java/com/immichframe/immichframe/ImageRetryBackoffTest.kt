package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageRetryBackoffTest {
    @Test fun doublesFromFiveSecondsToFiveMinutes() {
        val backoff = ImageRetryBackoff()
        val delays = List(9) { backoff.next() }
        assertEquals(
            listOf(5_000L, 10_000L, 20_000L, 40_000L, 80_000L, 160_000L, 300_000L, 300_000L, 300_000L),
            delays,
        )
    }

    @Test fun aPresentedImageStartsOver() {
        val backoff = ImageRetryBackoff()
        repeat(4) { backoff.next() }
        backoff.reset()
        assertEquals(5_000L, backoff.next())
        assertEquals(10_000L, backoff.next())
    }
}
