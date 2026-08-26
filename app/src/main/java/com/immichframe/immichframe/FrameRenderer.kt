package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
    val zoomEndScale: Float,
)
private data class DrawResult(val averageColor: Int, val contentWidth: Int, val contentHeight: Int)

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
        backgroundBlurRadius: Int,
        backgroundDarkness: Int,
    ): RenderedFrame {
        val slots = slotBounds(width, height, second != null, gapPx).map(IntBounds::toRect)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        canvas.drawColor(gapColor)
        val firstResult = drawImage(
            canvas, slots[0], first, scale, background, featherPx,
            backgroundBlurRadius, backgroundDarkness,
        )
        val secondResult = second?.let {
            drawImage(
                canvas, slots[1], it, scale, background, featherPx,
                backgroundBlurRadius, backgroundDarkness,
            )
        }
        if (secondResult != null) {
            canvas.drawRect(
                slots[0].right.toFloat(),
                0f,
                slots[1].left.toFloat(),
                height.toFloat(),
                Paint().apply { color = dividerColor(firstResult.averageColor, secondResult.averageColor) },
            )
        }
        val zoomEndScale = recommendedZoomScale(
            firstResult.contentWidth,
            firstResult.contentHeight,
            IntBounds(slots[0].left, slots[0].top, slots[0].right, slots[0].bottom),
            scale,
            second != null,
        )
        return RenderedFrame(
            output,
            FrameMeta(first.response.photoDate, first.response.imageLocation),
            second?.let { FrameMeta(it.response.photoDate, it.response.imageLocation) },
            zoomEndScale,
        )
    }

    internal fun recommendedZoomScale(
        sourceWidth: Int,
        sourceHeight: Int,
        slot: IntBounds,
        scale: FrameScale,
        split: Boolean,
    ): Float {
        if (split) return 1.10f
        if (scale == FrameScale.CROP) return 1.16f
        val destination = destinationBounds(sourceWidth, sourceHeight, slot, FrameScale.FIT)
        val coverage = minOf(destination.width / slot.width, destination.height / slot.height)
        return when {
            coverage < 0.78f -> 1.06f
            coverage < 0.90f -> 1.10f
            else -> 1.16f
        }
    }

    private fun drawImage(
        canvas: Canvas,
        slot: Rect,
        prepared: Helpers.PreparedImage,
        scale: FrameScale,
        background: FrameBackground,
        featherPx: Int,
        backgroundBlurRadius: Int,
        backgroundDarkness: Int,
    ): DrawResult {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val targetDecode = requiredDecodeDimension(
            prepared.width,
            prepared.height,
            IntBounds(slot.left, slot.top, slot.right, slot.bottom),
            scale,
        )
        val image = Helpers.decodePreparedBitmap(prepared, targetDecode)
        val content = detectContentBounds(image)
        val thumb = prepared.response.thumbHashImageBase64.takeIf { it.isNotBlank() }?.let {
            runCatching { Helpers.decodeBitmapFromBytes(it, 96) }.getOrNull()
        }
        val averageColor = thumb?.let { averageColor(it) } ?: Color.BLACK
        when (background) {
            FrameBackground.BLACK -> canvas.drawRect(slot, Paint().apply { color = Color.BLACK })
            FrameBackground.AVERAGE -> {
                canvas.drawRect(slot, Paint().apply { color = averageColor })
            }
            FrameBackground.THUMBHASH -> {
                canvas.save()
                canvas.clipRect(slot)
                val stageWidth = slot.width().coerceAtMost(320).coerceAtLeast(1)
                val stageHeight = slot.height().coerceAtMost(180).coerceAtLeast(1)
                val stage = Bitmap.createBitmap(stageWidth, stageHeight, Bitmap.Config.ARGB_8888)
                Canvas(stage).drawBitmap(
                    image,
                    content,
                    destination(content.width(), content.height(), Rect(0, 0, stageWidth, stageHeight), FrameScale.CROP),
                    paint,
                )
                if (backgroundBlurRadius > 0) {
                    boxBlur(stage, radius = backgroundBlurRadius.coerceIn(1, 24), passes = 3)
                }
                canvas.drawBitmap(stage, null, slot, paint)
                stage.recycle()
                canvas.drawRect(slot, Paint().apply {
                    color = Color.argb(backgroundDarkness.coerceIn(0, 200), 0, 0, 0)
                })
                canvas.restore()
            }
        }
        thumb?.recycle()

        canvas.save()
        canvas.clipRect(slot)
        val destination = destination(content.width(), content.height(), slot, scale)
        if (scale == FrameScale.FIT && featherPx > 0) {
            // Keep the photo edge away from the offscreen layer boundary. A layer bounded
            // exactly to the fractional destination can leave a one-pixel opaque seam when
            // Android rounds and composites that boundary on RGB_565 canvases.
            val layer = canvas.saveLayer(RectF(slot), null)
            canvas.drawBitmap(image, content, destination, paint)
            drawFeatherMask(canvas, slot, destination, featherPx.toFloat())
            canvas.restoreToCount(layer)
        } else {
            canvas.drawBitmap(image, content, destination, paint)
        }
        canvas.restore()
        image.recycle()
        return DrawResult(averageColor, content.width(), content.height())
    }

    private fun dividerColor(first: Int, second: Int): Int {
        fun channel(a: Int, b: Int) = (((a + b) / 2) * 0.55f).toInt().coerceIn(0, 255)
        return Color.rgb(
            channel(Color.red(first), Color.red(second)),
            channel(Color.green(first), Color.green(second)),
            channel(Color.blue(first), Color.blue(second)),
        )
    }

    private fun detectContentBounds(bitmap: Bitmap): Rect {
        val maxTop = (bitmap.height * 0.35f).toInt()
        val maxSide = (bitmap.width * 0.35f).toInt()
        var top = 0
        while (top < maxTop && isUniformBlackRow(bitmap, top)) top++
        var bottom = bitmap.height
        while (bottom > bitmap.height - maxTop && isUniformBlackRow(bitmap, bottom - 1)) bottom--
        var left = 0
        while (left < maxSide && isUniformBlackColumn(bitmap, left, top, bottom)) left++
        var right = bitmap.width
        while (right > bitmap.width - maxSide && isUniformBlackColumn(bitmap, right - 1, top, bottom)) right--

        val minimumVerticalBar = (bitmap.height / 50).coerceAtLeast(2)
        val minimumHorizontalBar = (bitmap.width / 50).coerceAtLeast(2)
        if (top < minimumVerticalBar) top = 0
        if (bitmap.height - bottom < minimumVerticalBar) bottom = bitmap.height
        if (left < minimumHorizontalBar) left = 0
        if (bitmap.width - right < minimumHorizontalBar) right = bitmap.width
        if (right - left < bitmap.width * 0.4f || bottom - top < bitmap.height * 0.4f) {
            return Rect(0, 0, bitmap.width, bitmap.height)
        }
        return Rect(left, top, right, bottom)
    }

    private fun isUniformBlackRow(bitmap: Bitmap, y: Int): Boolean {
        val samples = 64.coerceAtMost(bitmap.width)
        var dark = 0
        repeat(samples) { index ->
            val x = if (samples == 1) 0 else index * (bitmap.width - 1) / (samples - 1)
            if (isNearlyBlack(bitmap.getPixel(x, y))) dark++
        }
        return dark >= samples * 0.98f
    }

    private fun isUniformBlackColumn(bitmap: Bitmap, x: Int, top: Int, bottom: Int): Boolean {
        val height = (bottom - top).coerceAtLeast(1)
        val samples = 64.coerceAtMost(height)
        var dark = 0
        repeat(samples) { index ->
            val y = top + if (samples == 1) 0 else index * (height - 1) / (samples - 1)
            if (isNearlyBlack(bitmap.getPixel(x, y))) dark++
        }
        return dark >= samples * 0.98f
    }

    private fun isNearlyBlack(color: Int): Boolean =
        isNearlyBlack(Color.red(color), Color.green(color), Color.blue(color))

    internal fun isNearlyBlack(red: Int, green: Int, blue: Int): Boolean =
        red <= 20 && green <= 20 && blue <= 20

    private fun averageColor(bitmap: Bitmap): Int {
        val pixel = Bitmap.createScaledBitmap(bitmap, 1, 1, true)
        return pixel.getPixel(0, 0).also { pixel.recycle() }
    }

    internal fun drawFeatherMask(canvas: Canvas, slot: Rect, image: RectF, feather: Float) {
        val opaque = Color.WHITE
        val transparent = Color.TRANSPARENT
        val fadeIn = intArrayOf(
            transparent,
            Color.argb(26, 255, 255, 255),
            Color.argb(128, 255, 255, 255),
            Color.argb(230, 255, 255, 255),
            opaque,
        )
        val fadeOut = fadeIn.reversedArray()
        val stops = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)
        fun gradient(
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            colors: IntArray,
            horizontal: Boolean,
            shaderStart: Float,
            shaderEnd: Float,
        ) {
            if (right <= left || bottom <= top) return
            val shader = if (horizontal) {
                LinearGradient(shaderStart, 0f, shaderEnd, 0f, colors, stops, Shader.TileMode.CLAMP)
            } else {
                LinearGradient(0f, shaderStart, 0f, shaderEnd, colors, stops, Shader.TileMode.CLAMP)
            }
            canvas.drawRect(left, top, right, bottom, Paint(Paint.DITHER_FLAG).apply {
                this.shader = shader
                xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
            })
        }
        if (image.left > slot.left) {
            val leftFeather = minOf(feather, (image.left - slot.left) * 0.45f).coerceAtLeast(1f)
            val rightFeather = minOf(feather, (slot.right - image.right) * 0.45f).coerceAtLeast(1f)
            // Draw through the letterbox area so a fractional photo boundary cannot leave
            // a partially unmasked opaque pixel. CLAMP keeps that outer area transparent.
            gradient(
                slot.left.toFloat(), image.top, image.left + leftFeather, image.bottom,
                fadeIn, true, image.left, image.left + leftFeather,
            )
            gradient(
                image.right - rightFeather, image.top, slot.right.toFloat(), image.bottom,
                fadeOut, true, image.right - rightFeather, image.right,
            )
        }
        if (image.top > slot.top) {
            val topFeather = minOf(feather, (image.top - slot.top) * 0.45f).coerceAtLeast(1f)
            val bottomFeather = minOf(feather, (slot.bottom - image.bottom) * 0.45f).coerceAtLeast(1f)
            gradient(
                image.left, slot.top.toFloat(), image.right, image.top + topFeather,
                fadeIn, false, image.top, image.top + topFeather,
            )
            gradient(
                image.left, image.bottom - bottomFeather, image.right, slot.bottom.toFloat(),
                fadeOut, false, image.bottom - bottomFeather, image.bottom,
            )
        }
    }

    private fun boxBlur(bitmap: Bitmap, radius: Int, passes: Int) {
        val width = bitmap.width
        val height = bitmap.height
        var source = IntArray(width * height)
        var target = IntArray(source.size)
        bitmap.getPixels(source, 0, width, 0, 0, width, height)
        repeat(passes.coerceAtLeast(1)) {
            blurHorizontal(source, target, width, height, radius)
            blurVertical(target, source, width, height, radius)
        }
        bitmap.setPixels(source, 0, width, 0, 0, width, height)
    }

    private fun blurHorizontal(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
        val red = IntArray(width + 1)
        val green = IntArray(width + 1)
        val blue = IntArray(width + 1)
        for (y in 0 until height) {
            red.fill(0); green.fill(0); blue.fill(0)
            for (x in 0 until width) {
                val color = source[y * width + x]
                red[x + 1] = red[x] + Color.red(color)
                green[x + 1] = green[x] + Color.green(color)
                blue[x + 1] = blue[x] + Color.blue(color)
            }
            for (x in 0 until width) {
                val start = (x - radius).coerceAtLeast(0)
                val end = (x + radius + 1).coerceAtMost(width)
                val count = end - start
                target[y * width + x] = Color.rgb(
                    (red[end] - red[start]) / count,
                    (green[end] - green[start]) / count,
                    (blue[end] - blue[start]) / count,
                )
            }
        }
    }

    private fun blurVertical(source: IntArray, target: IntArray, width: Int, height: Int, radius: Int) {
        val red = IntArray(height + 1)
        val green = IntArray(height + 1)
        val blue = IntArray(height + 1)
        for (x in 0 until width) {
            red.fill(0); green.fill(0); blue.fill(0)
            for (y in 0 until height) {
                val color = source[y * width + x]
                red[y + 1] = red[y] + Color.red(color)
                green[y + 1] = green[y] + Color.green(color)
                blue[y + 1] = blue[y] + Color.blue(color)
            }
            for (y in 0 until height) {
                val start = (y - radius).coerceAtLeast(0)
                val end = (y + radius + 1).coerceAtMost(height)
                val count = end - start
                target[y * width + x] = Color.rgb(
                    (red[end] - red[start]) / count,
                    (green[end] - green[start]) / count,
                    (blue[end] - blue[start]) / count,
                )
            }
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
