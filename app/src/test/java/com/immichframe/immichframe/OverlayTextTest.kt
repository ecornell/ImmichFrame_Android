package com.immichframe.immichframe

import org.junit.Assert.assertEquals
import org.junit.Test

class OverlayTextTest {
    @Test fun scalesWithTheShorterSide() {
        assertEquals(50f, OverlayText.sizePx(1280, 800), 0.01f)
        assertEquals(67.5f, OverlayText.sizePx(1920, 1080), 0.01f)
        assertEquals(67.5f, OverlayText.sizePx(1080, 1920), 0.01f)
    }
}
