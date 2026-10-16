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

    @Test fun letterboxCoversEverythingOutsideTheFittedPhoto() {
        val slot = IntBounds(0, 0, 1920, 1080)
        val photo = FrameRenderer.destinationBounds(1470, 1188, slot, FrameScale.FIT)
        assertTrue(!FrameRenderer.coversSlot(photo, slot))
        val bands = FrameRenderer.letterboxBounds(slot, photo, 2f)
        assertEquals(2, bands.size)
        // Left and right bands span the full height and reach 2 px under the photo.
        assertEquals(FloatBounds(0f, 0f, photo.left + 2f, 1080f), bands[0])
        assertEquals(FloatBounds(photo.right - 2f, 0f, 1920f, 1080f), bands[1])
    }

    @Test fun letterboxAddsTopAndBottomBandsForWidePhotos() {
        val slot = IntBounds(0, 0, 960, 1080)
        val photo = FrameRenderer.destinationBounds(1920, 1080, slot, FrameScale.FIT)
        val bands = FrameRenderer.letterboxBounds(slot, photo, 2f)
        assertEquals(
            listOf(
                FloatBounds(0f, 0f, 960f, photo.top + 2f),
                FloatBounds(0f, photo.bottom - 2f, 960f, 1080f),
            ),
            bands,
        )
    }

    @Test fun croppedPhotoNeedsNoBackground() {
        val slot = IntBounds(0, 0, 1920, 1080)
        val crop = FrameRenderer.destinationBounds(1470, 1188, slot, FrameScale.CROP)
        assertTrue(FrameRenderer.coversSlot(crop, slot))
        assertTrue(FrameRenderer.letterboxBounds(slot, crop, 2f).isEmpty())
    }

    @Test fun featherCoverIsTheComplementOfTheFormerPhotoMask() {
        val formerMask = intArrayOf(0, 26, 128, 230, 255)
        assertEquals(
            formerMask.map { 255 - it },
            FrameRenderer.FEATHER_COVER_ALPHAS.toList(),
        )
    }

    @Test fun halfSizeStageHalvesTheBlurRadius() {
        assertEquals(8, FrameRenderer.stageBlurRadius(16))
        assertEquals(1, FrameRenderer.stageBlurRadius(1))
        assertEquals(12, FrameRenderer.stageBlurRadius(99))
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

    @Test fun fadeAlphaEasesBetweenEndpointsAndStopsAtTarget() {
        assertEquals(0f, FadeController.alphaAt(0, 1_000, 0f, 1f), 0.0001f)
        assertEquals(0.5f, FadeController.alphaAt(500, 1_000, 0f, 1f), 0.0001f)
        assertTrue(FadeController.alphaAt(100, 1_000, 0f, 1f) < 0.1f)
        assertEquals(0f, FadeController.alphaAt(2_000, 1_000, 1f, 0f), 0.0001f)
        assertEquals(1f, FadeController.alphaAt(0, 0, 0f, 1f))
    }

    @Test fun cropDecodeTargetPreservesExtremeAspectRatios() {
        val slot = IntBounds(0, 0, 1920, 1080)
        assertEquals(8000, FrameRenderer.requiredDecodeDimension(8000, 1000, slot, FrameScale.CROP))
        assertEquals(1920, FrameRenderer.requiredDecodeDimension(8000, 1000, slot, FrameScale.FIT))
    }

    @Test fun letterboxedAndSplitPhotosUseGentlerZoom() {
        val screen = IntBounds(0, 0, 1920, 1080)
        assertEquals(1.06f, FrameRenderer.recommendedZoomScale(1600, 1200, screen, FrameScale.FIT, false))
        assertEquals(1.16f, FrameRenderer.recommendedZoomScale(1920, 1080, screen, FrameScale.FIT, false))
        assertEquals(1.10f, FrameRenderer.recommendedZoomScale(900, 1200, screen, FrameScale.FIT, true))
    }

    @Test fun blackBarDetectionUsesAConservativeColorThreshold() {
        assertTrue(FrameRenderer.isNearlyBlack(12, 18, 20))
        assertTrue(!FrameRenderer.isNearlyBlack(21, 18, 18))
        assertTrue(!FrameRenderer.isNearlyBlack(8, 8, 30))
    }
}
