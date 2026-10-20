package com.immichframe.immichframe

/** Sizing for text shown over everything: notifications and camera captions. */
internal object OverlayText {
    // The text is this fraction of the screen's shorter side, so it reads from across a room on any
    // frame: 50 px on the W10J (800 high), 68 px on the WF1561 (1080 high).
    private const val SHARE = 16f

    /** The text size in pixels for a [width] by [height] screen. */
    fun sizePx(width: Int, height: Int): Float = minOf(width, height) / SHARE
}
