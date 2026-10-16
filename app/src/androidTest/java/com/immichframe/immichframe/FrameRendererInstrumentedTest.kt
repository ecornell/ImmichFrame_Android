package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
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
        canvas.drawRect(image, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        FrameRenderer.drawFeatherOverlay(canvas, slot, image, 24f, null, background)

        fun red(x: Int) = Color.red(output.getPixel(x, 50))
        val leftResidual = abs(red(20) - ((red(19) + red(21)) / 2))
        val rightResidual = abs(red(179) - ((red(178) + red(180)) / 2))
        assertTrue("left fractional edge residual was $leftResidual", leftResidual <= 12)
        assertTrue("right fractional edge residual was $rightResidual", rightResidual <= 12)
        assertTrue(red(30) > red(22))
        assertTrue(red(169) > red(177))
        output.recycle()
    }

    @Test
    fun shaderBackdropFeatherBlendsIntoTheSameBackdrop() {
        val output = Bitmap.createBitmap(200, 100, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        val stage = Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(0, 0, 160)) }
        val shader = BitmapShader(stage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val slot = Rect(0, 0, output.width, output.height)
        val image = RectF(40.5f, 0f, 159.5f, 100f)
        canvas.drawRect(RectF(slot), Paint().apply { this.shader = shader })
        canvas.drawRect(image, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE })
        FrameRenderer.drawFeatherOverlay(canvas, slot, image, 24f, shader, Color.BLACK)

        fun pixel(x: Int) = output.getPixel(x, 50)
        // At the photo edge the backdrop fully covers the photo; inward the photo shows through.
        assertTrue(Color.red(pixel(40)) <= 12 && Color.blue(pixel(40)) >= 148)
        assertTrue(Color.red(pixel(50)) > Color.red(pixel(42)))
        assertTrue(Color.red(pixel(100)) >= 240)
        output.recycle()
        stage.recycle()
    }
}
