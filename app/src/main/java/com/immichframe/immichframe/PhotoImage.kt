package com.immichframe.immichframe

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.core.graphics.scale
import java.io.ByteArrayOutputStream

/** What the Home Assistant image entity shows of the photo on screen. */
enum class PhotoImageMode {
    OFF,
    /** The photo's thumbhash: a few hundred bytes of blurred colour, nothing recognisable. */
    BLURRED,
    /** A small copy of the rendered frame, split slides and background included. */
    THUMBNAIL;

    companion object {
        fun parse(value: String?): PhotoImageMode = values().firstOrNull { it.name == value } ?: OFF
    }
}

internal object PhotoImage {
    const val CONTENT_TYPE = "image/jpeg"
    // About 30-50 KB per slide: enough for a dashboard card, small for a 1 GB frame and the broker.
    const val MAX_EDGE = 480
    private const val JPEG_QUALITY = 70

    /**
     * A new bitmap for [mode], never [frame] itself: the presenter recycles the frame after the
     * next crossfade, so only the copy may leave the main thread.
     */
    fun source(mode: PhotoImageMode, frame: Bitmap?, newest: Helpers.ImageResponse): Bitmap? = when (mode) {
        PhotoImageMode.OFF -> null
        PhotoImageMode.BLURRED -> runCatching {
            val bytes = Base64.decode(newest.thumbHashImageBase64, Base64.DEFAULT)
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull()
        PhotoImageMode.THUMBNAIL -> frame?.takeUnless { it.isRecycled }?.let { scaledCopy(it) }
    }

    // Null when a frame already small enough cannot be copied; the photo then goes without an image.
    private fun scaledCopy(frame: Bitmap): Bitmap? {
        val (width, height) = Helpers.scaledDimensions(frame.width, frame.height, MAX_EDGE)
        val scaled = frame.scale(width, height)
        return if (scaled !== frame) scaled else frame.copy(frame.config ?: Bitmap.Config.RGB_565, false)
    }

    fun encode(bitmap: Bitmap): ByteArray = ByteArrayOutputStream().use { output ->
        check(bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) { "JPEG encoding failed" }
        output.toByteArray()
    }
}
