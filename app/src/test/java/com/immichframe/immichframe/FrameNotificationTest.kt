package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameNotificationTest {
    @Test fun plainPayloadIsTheMessage() {
        assertEquals(
            FrameNotification("Washer done", null, FrameNotification.DEFAULT_SECONDS),
            FrameNotification.parse("  Washer done \n"),
        )
    }

    @Test fun jsonPayloadAddsTitleAndDuration() {
        assertEquals(
            FrameNotification("Front door open", "Security", 30),
            FrameNotification.parse("""{"message":"Front door open","title":"Security","duration":30}"""),
        )
    }

    @Test fun onlyAJsonTrueWakesTheScreen() {
        assertTrue(FrameNotification.parse("""{"message":"Leak in the basement","wake":true}""")!!.wake)
        assertFalse(FrameNotification.parse("""{"message":"a","wake":"true"}""")!!.wake)
        assertFalse(FrameNotification.parse("""{"message":"a"}""")!!.wake)
        assertFalse(FrameNotification.parse("wake")!!.wake)
    }

    @Test fun invalidJsonIsShownAsWritten() {
        assertEquals("{not json", FrameNotification.parse("{not json")?.message)
    }

    @Test fun blankOrMissingMessagesAreRejected() {
        assertNull(FrameNotification.parse("   "))
        assertNull(FrameNotification.parse("""{"title":"No message"}"""))
        assertNull(FrameNotification.parse("""{"message":"  "}"""))
        assertNull(FrameNotification.parse("""{"message":42}"""))
    }

    @Test fun durationIsBoundedAndTextIsTruncated() {
        assertEquals(FrameNotification.MIN_SECONDS, FrameNotification.parse("""{"message":"a","duration":0}""")!!.durationSeconds)
        assertEquals(FrameNotification.MAX_SECONDS, FrameNotification.parse("""{"message":"a","duration":99999}""")!!.durationSeconds)
        assertEquals(500, FrameNotification.parse("x".repeat(2_000))!!.message.length)
        assertNull(FrameNotification.parse("""{"message":"a","title":"  "}""")!!.title)
    }

    @Test fun queueKeepsOnlyTheNewestNotifications() {
        val queue = NotificationQueue(capacity = 2)
        val first = FrameNotification("1", null, 10)
        assertNull(queue.offer(first))
        assertNull(queue.offer(FrameNotification("2", null, 10)))
        assertEquals(first, queue.offer(FrameNotification("3", null, 10)))
        assertEquals("2", queue.poll()?.message)
        assertEquals("3", queue.poll()?.message)
        assertNull(queue.poll())
    }
}
