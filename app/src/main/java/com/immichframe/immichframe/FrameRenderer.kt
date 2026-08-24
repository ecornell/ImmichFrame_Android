package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.LinearGradient
import android.graphics.Shader
import kotlin.math.ceil

internal enum class FrameBackground { BLACK, AVERAGE, THUMBHASH }
internal enum class FrameScale { FIT, CROP }

internal data class FrameMeta(val date: String, val location: String)
internal data class IntBounds(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width get() = right - left
    val height get() = bottom - top
    fun toRect() = Rect(left, top, right, bottom)
}
internal data class FloatBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    fun toRectF() = RectF(left, top, right, bottom)
}
internal data class RenderedFrame(
    val bitmap: Bitmap,
    val left: FrameMeta,
    val right: FrameMeta?,
)

internal object FrameRenderer {
    fun slotBounds(width: Int, height: Int, split: Boolean, gap: Int): List<IntBounds> {
        require(width > 0 && height > 0)
        if (!split) return listOf(IntBounds(0, 0, width, height))
        val safeGap = gap.coerceIn(0, (width - 2).coerceAtLeast(0))
        val leftWidth = (width - safeGap) / 2
        return listOf(
            IntBounds(0, 0, leftWidth, height),
            IntBounds(leftWidth + safeGap, 0, width, height),
        )
    }

    fun destination(sourceWidth: Int, sourceHeight: Int, slot: Rect, scale: FrameScale): RectF {
        return destinationBounds(
            sourceWidth,
            sourceHeight,
            IntBounds(slot.left, slot.top, slot.right, slot.bottom),
            scale,
        ).toRectF()
    }

    fun destinationBounds(
        sourceWidth: Int,
        sourceHeight: Int,
        slot: IntBounds,
        scale: FrameScale,
    ): FloatBounds {
        require(sourceWidth > 0 && sourceHeight > 0)
        val sx = slot.width.toFloat() / sourceWidth
        val sy = slot.height.toFloat() / sourceHeight
        val factor = if (scale == FrameScale.CROP) maxOf(sx, sy) else minOf(sx, sy)
        val width = sourceWidth * factor
        val height = sourceHeight * factor
        val left = slot.left + (slot.width - width) / 2f
        val top = slot.top + (slot.height - height) / 2f
        return FloatBounds(left, top, left + width, top + height)
    }

    internal fun requiredDecodeDimension(
        sourceWidth: Int,
        sourceHeight: Int,
        slot: IntBounds,
        scale: FrameScale,
    ): Int {
        val destination = destinationBounds(sourceWidth, sourceHeight, slot, scale)
        return ceil(maxOf(destination.width, destination.height).toDouble()).toInt()
            .coerceAtMost(maxOf(sourceWidth, sourceHeight))
            .coerceAtLeast(1)
    }

    fun render(
        width: Int,
        height: Int,
        first: Helpers.PreparedImage,
        second: Helpers.PreparedImage?,
        scale: FrameScale,
        background: FrameBackground,
        gapPx: Int,
        gapColor: Int,
        featherPx: Int,
    ): RenderedFrame {
        val slots = slotBounds(width, height, second != null, gapPx).map(IntBounds::toRect)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        canvas.drawColor(gapColor)
        drawImage(canvas, slots[0], first, scale, background, featherPx)
        second?.let { drawImage(canvas, slots[1], it, scale, background, featherPx) }
        return RenderedFrame(
            output,
            FrameMeta(first.response.photoDate, first.response.imageLocation),
            second?.let { FrameMeta(it.response.photoDate, it.response.imageLocation) },
        )
    }

    private fun drawImage(
        canvas: Canvas,
        slot: Rect,
        prepared: Helpers.PreparedImage,
        scale: FrameScale,
        background: FrameBackground,
        featherPx: Int,
    ) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val thumb = prepared.response.thumbHashImageBase64.takeIf { it.isNotBlank() }?.let {
            runCatching { Helpers.decodeBitmapFromBytes(it, 96) }.getOrNull()
        }
        val averageColor = thumb?.let { averageColor(it) } ?: Color.BLACK
        when (background) {
            FrameBackground.BLACK -> canvas.drawRect(slot, Paint().apply { color = Color.BLACK })
            FrameBackground.AVERAGE -> {
                canvas.drawRect(slot, Paint().apply { color = averageColor })
            }
            FrameBackground.THUMBHASH -> if (thumb != null) {
                canvas.save()
                canvas.clipRect(slot)
                val stageWidth = slot.width().coerceAtMost(320).coerceAtLeast(1)
                val stageHeight = slot.height().coerceAtMost(180).coerceAtLeast(1)
                val stage = Bitmap.createBitmap(stageWidth, stageHeight, Bitmap.Config.RGB_565)
                Canvas(stage).drawBitmap(
                    thumb,
                    null,
                    destination(thumb.width, thumb.height, Rect(0, 0, stageWidth, stageHeight), FrameScale.CROP),
                    paint,
                )
                canvas.drawBitmap(stage, null, slot, paint)
                stage.recycle()
                canvas.drawRect(slot, Paint().apply { color = 0x33000000 })
                canvas.restore()
            } else canvas.drawRect(slot, Paint().apply { color = Color.BLACK })
        }
        thumb?.recycle()

        val targetDecode = requiredDecodeDimension(
            prepared.width,
            prepared.height,
            IntBounds(slot.left, slot.top, slot.right, slot.bottom),
            scale,
        )
        val image = Helpers.decodePreparedBitmap(prepared, targetDecode)
        canvas.save()
        canvas.clipRect(slot)
        val destination = destination(image.width, image.height, slot, scale)
        canvas.drawBitmap(image, null, destination, paint)
        if (scale == FrameScale.FIT && featherPx > 0) {
            drawFeather(canvas, slot, destination, featherPx.toFloat(), averageColor)
        }
        canvas.restore()
        image.recycle()
    }

    private fun averageColor(bitmap: Bitmap): Int {
        val pixel = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        return pixel.getPixel(0, 0).also { pixel.recycle() }
    }

    private fun drawFeather(canvas: Canvas, slot: Rect, image: RectF, feather: Float, color: Int) {
        val opaque = Color.argb(190, Color.red(color), Color.green(color), Color.blue(color))
        val transparent = Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))
        fun gradient(left: Float, top: Float, right: Float, bottom: Float, colors: IntArray, horizontal: Boolean) {
            if (right <= left || bottom <= top) return
            val shader = if (horizontal) LinearGradient(left, 0f, right, 0f, colors, null, Shader.TileMode.CLAMP)
            else LinearGradient(0f, top, 0f, bottom, colors, null, Shader.TileMode.CLAMP)
            canvas.drawRect(left, top, right, bottom, Paint().apply { this.shader = shader })
        }
        if (image.left > slot.left) {
            gradient(image.left, image.top, image.left + feather, image.bottom, intArrayOf(opaque, transparent), true)
            gradient(image.right - feather, image.top, image.right, image.bottom, intArrayOf(transparent, opaque), true)
        }
        if (image.top > slot.top) {
            gradient(image.left, image.top, image.right, image.top + feather, intArrayOf(opaque, transparent), false)
            gradient(image.left, image.bottom - feather, image.right, image.bottom, intArrayOf(transparent, opaque), false)
        }
    }
}

internal class OverlayBurnInController {
    private val offsets = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1, 0 to 0)
    fun offset(step: Long, distance: Float): Pair<Float, Float> {
        val value = offsets[(step % offsets.size).toInt()]
        return value.first * distance to value.second * distance
    }
}
