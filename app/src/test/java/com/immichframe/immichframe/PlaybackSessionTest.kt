package com.immichframe.immichframe

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackSessionTest {
    @Test fun obsoleteCallbacksAreRejectedAcrossModeChanges() {
        val session = PlaybackSession()
        val firstNative = session.activate()
        assertTrue(session.isCurrent(firstNative))

        session.invalidate()
        assertFalse(session.isCurrent(firstNative))

        val secondNative = session.activate()
        assertFalse(session.isCurrent(firstNative))
        assertTrue(session.isCurrent(secondNative))
    }

    @Test fun cachedPortraitCanBypassPairingWhileLivePortraitWaits() {
        assertTrue(PlaybackPolicies.shouldWaitForPortraitPair(true, "splitview", true))
        assertFalse(PlaybackPolicies.shouldWaitForPortraitPair(true, "splitview", false))
        assertFalse(PlaybackPolicies.shouldWaitForPortraitPair(false, "splitview", true))
    }
}
