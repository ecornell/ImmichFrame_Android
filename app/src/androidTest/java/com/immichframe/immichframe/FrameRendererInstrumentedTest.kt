package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlin.math.abs
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FrameRendererInstrumentedTest {
    @Test
    fun fractionalFitEdgesDoNotLeaveOpaqueRgb565Seams() {
        val output = Bitmap.createBitmap(200, 100, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        val background = Color.rgb(48, 48, 48)
        canvas.drawColor(background)
        val slot = Rect(0, 0, output.width, output.height)
        val image = RectF(20.3f, 0f, 179.7f, 100f)
        val layer = canvas.saveLayer(RectF(slot), null)
        canvas.drawRect(image, Paint().apply { color = Color.WHITE })
        FrameRenderer.drawFeatherMask(canvas, slot, image, 24f)
        canvas.restoreToCount(layer)

        fun red(x: Int) = Color.red(output.getPixel(x, 50))
        val leftResidual = abs(red(20) - ((red(19) + red(21)) / 2))
        val rightResidual = abs(red(179) - ((red(178) + red(180)) / 2))
        assertTrue("left fractional edge residual was $leftResidual", leftResidual <= 12)
        assertTrue("right fractional edge residual was $rightResidual", rightResidual <= 12)
        assertTrue(red(30) > red(22))
        assertTrue(red(169) > red(177))
        output.recycle()
    }
}
