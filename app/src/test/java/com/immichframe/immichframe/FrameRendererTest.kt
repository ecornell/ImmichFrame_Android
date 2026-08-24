package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameRendererTest {
    @Test fun splitSlotsAreBalancedAndRespectGap() {
        assertEquals(
            listOf(IntBounds(0, 0, 955, 1080), IntBounds(965, 0, 1920, 1080)),
            FrameRenderer.slotBounds(1920, 1080, true, 10),
        )
    }

    @Test fun fitCentersAndCropFills() {
        val slot = IntBounds(0, 0, 960, 1080)
        val fit = FrameRenderer.destinationBounds(600, 1200, slot, FrameScale.FIT)
        assertEquals(1080f, fit.height)
        assertEquals(210f, fit.left)
        val crop = FrameRenderer.destinationBounds(600, 1200, slot, FrameScale.CROP)
        assertTrue(crop.width >= slot.width)
        assertTrue(crop.height >= slot.height)
    }

    @Test fun burnInOffsetsStayBounded() {
        val controller = OverlayBurnInController()
        repeat(20) {
            val (x, y) = controller.offset(it.toLong(), 12f)
            assertTrue(x in -12f..12f && y in -12f..12f)
        }
    }

    @Test fun kenBurnsScaleUsesElapsedTimeAndStopsAtTarget() {
        assertEquals(1f, KenBurnsZoomController.scaleAt(0, 20_000, 1.2f))
        assertEquals(1.1f, KenBurnsZoomController.scaleAt(10_000, 20_000, 1.2f), 0.0001f)
        assertEquals(1.2f, KenBurnsZoomController.scaleAt(30_000, 20_000, 1.2f))
    }

    @Test fun cropDecodeTargetPreservesExtremeAspectRatios() {
        val slot = IntBounds(0, 0, 1920, 1080)
        assertEquals(8000, FrameRenderer.requiredDecodeDimension(8000, 1000, slot, FrameScale.CROP))
        assertEquals(1920, FrameRenderer.requiredDecodeDimension(8000, 1000, slot, FrameScale.FIT))
    }
}
