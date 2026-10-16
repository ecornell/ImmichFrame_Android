package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
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
        val split = second != null
        val slots = slotBounds(width, height, split, gapPx).map(IntBounds::toRect)
        val output = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(output)
        // Each slot paints every one of its pixels, so only the split gap needs a fill.
        if (split) canvas.drawColor(gapColor)
        // The average colour is only visible as an AVERAGE background or as the split divider.
        val needsAverage = split || background == FrameBackground.AVERAGE
        val firstResult = drawImage(
            canvas, slots[0], first, scale, background, featherPx,
            backgroundBlurRadius, backgroundDarkness, needsAverage,
        )
        val secondResult = second?.let {
            drawImage(
                canvas, slots[1], it, scale, background, featherPx,
                backgroundBlurRadius, backgroundDarkness, needsAverage,
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
        needsAverage: Boolean,
    ): DrawResult {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val slotBounds = IntBounds(slot.left, slot.top, slot.right, slot.bottom)
        val targetDecode = requiredDecodeDimension(prepared.width, prepared.height, slotBounds, scale)
        val image = Helpers.decodePreparedBitmap(prepared, targetDecode)
        val content = detectContentBounds(image)
        val averageColor = if (needsAverage) thumbHashAverage(prepared) else Color.BLACK
        val destination = destination(content.width(), content.height(), slot, scale)
        val photoBounds = FloatBounds(destination.left, destination.top, destination.right, destination.bottom)

        canvas.save()
        canvas.clipRect(slot)
        // Paint the background only where the photo leaves it visible, and never build the
        // blurred stage for a photo that covers its slot.
        val backdrop = if (coversSlot(photoBounds, slotBounds)) null else {
            createBackdrop(background, image, content, slot, averageColor, backgroundBlurRadius, backgroundDarkness)
        }
        if (backdrop != null) {
            letterboxBounds(slotBounds, photoBounds, EDGE_OVERLAP_PX).forEach {
                canvas.drawRect(it.left, it.top, it.right, it.bottom, backdrop.paint)
            }
        }
        canvas.drawBitmap(image, content, destination, paint)
        if (backdrop != null && scale == FrameScale.FIT && featherPx > 0) {
            // Feather by painting the background back over the photo edges. This avoids a
            // full-slot offscreen layer, whose fractional boundary could also leave a
            // one-pixel opaque seam on RGB_565 canvases.
            drawFeatherOverlay(canvas, slot, destination, featherPx.toFloat(), backdrop.shader, backdrop.color)
        }
        canvas.restore()
        backdrop?.recycle()
        image.recycle()
        return DrawResult(averageColor, content.width(), content.height())
    }

    /** Background paint for one slot: a solid colour, or a shader over a small blurred stage. */
    private class Backdrop(val paint: Paint, val shader: Shader?, val color: Int, private val stage: Bitmap?) {
        fun recycle() {
            stage?.recycle()
        }
    }

    private fun createBackdrop(
        background: FrameBackground,
        image: Bitmap,
        content: Rect,
        slot: Rect,
        averageColor: Int,
        blurRadius: Int,
        darkness: Int,
    ): Backdrop = when (background) {
        FrameBackground.BLACK -> Backdrop(Paint().apply { color = Color.BLACK }, null, Color.BLACK, null)
        FrameBackground.AVERAGE -> Backdrop(Paint().apply { color = averageColor }, null, averageColor, null)
        FrameBackground.THUMBHASH -> {
            val stageWidth = slot.width().coerceIn(1, STAGE_MAX_WIDTH)
            val stageHeight = slot.height().coerceIn(1, STAGE_MAX_HEIGHT)
            val stage = Bitmap.createBitmap(stageWidth, stageHeight, Bitmap.Config.ARGB_8888)
            val stageCanvas = Canvas(stage)
            stageCanvas.drawBitmap(
                image,
                content,
                destination(content.width(), content.height(), Rect(0, 0, stageWidth, stageHeight), FrameScale.CROP),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            if (blurRadius > 0) boxBlur(stage, radius = stageBlurRadius(blurRadius), passes = 3)
            // Darken the small stage once instead of a full-slot pass.
            stageCanvas.drawColor(Color.argb(darkness.coerceIn(0, 200), 0, 0, 0))
            // One shader serves both the letterbox fill and the feather overlay, so both sample
            // the upscaled stage identically and no band shows where they meet.
            val shader = BitmapShader(stage, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                setLocalMatrix(Matrix().apply {
                    setRectToRect(
                        RectF(0f, 0f, stageWidth.toFloat(), stageHeight.toFloat()),
                        RectF(slot),
                        Matrix.ScaleToFit.FILL,
                    )
                })
            }
            Backdrop(Paint(Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }, shader, Color.BLACK, stage)
        }
    }

    private fun thumbHashAverage(prepared: Helpers.PreparedImage): Int {
        val thumb = prepared.response.thumbHashImageBase64.takeIf { it.isNotBlank() }?.let {
            runCatching { Helpers.decodeBitmapFromBytes(it, 96) }.getOrNull()
        } ?: return Color.BLACK
        return averageColor(thumb).also { thumb.recycle() }
    }

    /** The stage is half the former 320×180 size, so the blur radius halves with it. */
    internal fun stageBlurRadius(radius: Int): Int = (radius.coerceIn(1, 24) + 1) / 2

    internal fun coversSlot(photo: FloatBounds, slot: IntBounds): Boolean =
        photo.left <= slot.left && photo.top <= slot.top &&
            photo.right >= slot.right && photo.bottom >= slot.bottom

    /**
     * Background regions around a photo, each extended [overlap] pixels under the photo so its
     * anti-aliased fractional edge blends over background rather than an unpainted pixel.
     */
    internal fun letterboxBounds(slot: IntBounds, photo: FloatBounds, overlap: Float): List<FloatBounds> {
        val bounds = mutableListOf<FloatBounds>()
        val top = slot.top.toFloat()
        val bottom = slot.bottom.toFloat()
        if (photo.left > slot.left) {
            bounds += FloatBounds(slot.left.toFloat(), top, minOf(photo.left + overlap, slot.right.toFloat()), bottom)
        }
        if (photo.right < slot.right) {
            bounds += FloatBounds(maxOf(photo.right - overlap, slot.left.toFloat()), top, slot.right.toFloat(), bottom)
        }
        val left = maxOf(photo.left, slot.left.toFloat())
        val right = minOf(photo.right, slot.right.toFloat())
        if (photo.top > slot.top) {
            bounds += FloatBounds(left, top, right, minOf(photo.top + overlap, bottom))
        }
        if (photo.bottom < slot.bottom) {
            bounds += FloatBounds(left, maxOf(photo.bottom - overlap, top), right, bottom)
        }
        return bounds
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

    /**
     * Fades the photo edges into the background by painting the background over them with a
     * falling alpha. [FEATHER_COVER_ALPHAS] is the complement of the former photo mask, so the
     * result is the same blend without an offscreen layer. Each band starts [EDGE_OVERLAP_PX]
     * outside the photo at full cover, hiding its anti-aliased fractional edge pixel.
     */
    internal fun drawFeatherOverlay(
        canvas: Canvas,
        slot: Rect,
        image: RectF,
        feather: Float,
        backdropShader: Shader?,
        backdropColor: Int,
    ) {
        val stops = floatArrayOf(0f, 0.2f, 0.5f, 0.8f, 1f)
        val tint = if (backdropShader == null) backdropColor else Color.WHITE
        val colors = FEATHER_COVER_ALPHAS.map {
            Color.argb(it, Color.red(tint), Color.green(tint), Color.blue(tint))
        }.toIntArray()
        // Gradients run from the photo edge (full cover) inward to the feather width (none).
        fun band(left: Float, top: Float, right: Float, bottom: Float, x0: Float, y0: Float, x1: Float, y1: Float) {
            if (right <= left || bottom <= top) return
            val gradient = LinearGradient(x0, y0, x1, y1, colors, stops, Shader.TileMode.CLAMP)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
            // DST_IN keeps the background colour and takes the gradient's alpha.
            paint.shader = if (backdropShader == null) gradient else {
                ComposeShader(backdropShader, gradient, PorterDuff.Mode.DST_IN)
            }
            canvas.drawRect(left, top, right, bottom, paint)
        }
        val slotLeft = slot.left.toFloat()
        val slotTop = slot.top.toFloat()
        val slotRight = slot.right.toFloat()
        val slotBottom = slot.bottom.toFloat()
        if (image.left > slot.left) {
            val leftFeather = minOf(feather, (image.left - slot.left) * 0.45f).coerceAtLeast(1f)
            val rightFeather = minOf(feather, (slot.right - image.right) * 0.45f).coerceAtLeast(1f)
            band(
                maxOf(image.left - EDGE_OVERLAP_PX, slotLeft), image.top, image.left + leftFeather, image.bottom,
                image.left, 0f, image.left + leftFeather, 0f,
            )
            band(
                image.right - rightFeather, image.top, minOf(image.right + EDGE_OVERLAP_PX, slotRight), image.bottom,
                image.right, 0f, image.right - rightFeather, 0f,
            )
        }
        if (image.top > slot.top) {
            val topFeather = minOf(feather, (image.top - slot.top) * 0.45f).coerceAtLeast(1f)
            val bottomFeather = minOf(feather, (slot.bottom - image.bottom) * 0.45f).coerceAtLeast(1f)
            band(
                image.left, maxOf(image.top - EDGE_OVERLAP_PX, slotTop), image.right, image.top + topFeather,
                0f, image.top, 0f, image.top + topFeather,
            )
            band(
                image.left, image.bottom - bottomFeather, image.right, minOf(image.bottom + EDGE_OVERLAP_PX, slotBottom),
                0f, image.bottom, 0f, image.bottom - bottomFeather,
            )
        }
    }

    /** Background cover at stops 0, 0.2, 0.5, 0.8 and 1 of the feather band, from the edge in. */
    internal val FEATHER_COVER_ALPHAS = intArrayOf(255, 229, 127, 25, 0)
    internal const val EDGE_OVERLAP_PX = 2f
    private const val STAGE_MAX_WIDTH = 160
    private const val STAGE_MAX_HEIGHT = 90

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
