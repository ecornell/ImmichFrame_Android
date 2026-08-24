package com.immichframe.immichframe

import android.os.Handler
import android.os.SystemClock
import android.view.View
import android.widget.ImageView

/**
 * Presents pre-composed native frames with a bounded-rate Ken Burns transform. The active
 * ImageView is promoted to a hardware layer so the old Android 8.1 HWUI stack can reuse the
 * static texture instead of rebuilding a full-screen display list for every zoom step.
 */
internal class KenBurnsZoomController(
    private val handler: Handler,
) {
    private var activeView: ImageView? = null
    private var runnable: Runnable? = null

    fun start(
        view: ImageView,
        durationMillis: Long,
        framesPerSecond: Int,
        endScale: Float = 1.2f,
    ) {
        cancel(releaseLayer = true)
        val duration = durationMillis.coerceAtLeast(1L)
        val frameDelay = (1_000L / framesPerSecond.coerceIn(10, 60)).coerceAtLeast(16L)
        val startedAt = SystemClock.uptimeMillis()
        activeView = view
        view.setLayerType(View.LAYER_TYPE_HARDWARE, null)
        view.buildLayer()
        val task = object : Runnable {
            override fun run() {
                if (activeView !== view || !view.isAttachedToWindow) return
                val elapsed = SystemClock.uptimeMillis() - startedAt
                val scale = scaleAt(elapsed, duration, endScale)
                view.scaleX = scale
                view.scaleY = scale
                if (elapsed < duration) handler.postDelayed(this, frameDelay)
            }
        }
        runnable = task
        handler.post(task)
    }

    fun cancel(releaseLayer: Boolean) {
        runnable?.let(handler::removeCallbacks)
        runnable = null
        activeView?.let { if (releaseLayer) it.setLayerType(View.LAYER_TYPE_NONE, null) }
        activeView = null
    }

    companion object {
        internal fun scaleAt(elapsedMillis: Long, durationMillis: Long, endScale: Float): Float {
            val fraction = (elapsedMillis.toFloat() / durationMillis.coerceAtLeast(1L))
                .coerceIn(0f, 1f)
            return 1f + ((endScale - 1f) * fraction)
        }
    }
}

internal class NativeFramePresenter(
    private val firstView: ImageView,
    private val secondView: ImageView,
    handler: Handler,
) {
    private val zoom = KenBurnsZoomController(handler)
    private var showingFirst = true
    private var generation = 0L

    fun present(
        frame: RenderedFrame,
        transitionMillis: Long,
        zoomEnabled: Boolean,
        zoomDurationMillis: Long,
        zoomFramesPerSecond: Int,
        onPresented: (RenderedFrame) -> Unit,
    ) {
        val token = ++generation
        val oldView = if (showingFirst) firstView else secondView
        val newView = if (showingFirst) secondView else firstView
        showingFirst = !showingFirst

        zoom.cancel(releaseLayer = false)
        oldView.animate().cancel()
        newView.animate().cancel()
        val outgoingDrawable = oldView.drawable

        newView.setLayerType(View.LAYER_TYPE_NONE, null)
        newView.setImageDrawable(null)
        newView.background = null
        newView.alpha = 0f
        newView.scaleX = 1f
        newView.scaleY = 1f
        newView.setImageBitmap(frame.bitmap)
        newView.visibility = View.VISIBLE

        newView.animate()
            .alpha(1f)
            .setDuration(transitionMillis.coerceAtLeast(0L))
            .withLayer()
            .withEndAction {
                if (token == generation && zoomEnabled) {
                    // withLayer() restores the previous layer type after this callback. Start
                    // the long-running cached zoom on the next loop turn so that restoration
                    // cannot immediately undo its explicit hardware-layer promotion.
                    newView.post {
                        if (token == generation) {
                            zoom.start(newView, zoomDurationMillis, zoomFramesPerSecond)
                        }
                    }
                }
            }
            .start()

        oldView.animate()
            .alpha(0f)
            .setDuration(transitionMillis.coerceAtLeast(0L))
            .withLayer()
            .withEndAction {
                if (token == generation) {
                    if (oldView.drawable === outgoingDrawable) oldView.setImageDrawable(null)
                    oldView.visibility = View.GONE
                    oldView.scaleX = 1f
                    oldView.scaleY = 1f
                    oldView.post {
                        if (token == generation) {
                            oldView.setLayerType(View.LAYER_TYPE_NONE, null)
                        }
                    }
                }
            }
            .start()

        onPresented(frame)
    }

    fun pauseZoom() = zoom.cancel(releaseLayer = true)

    fun clear() {
        generation++
        zoom.cancel(releaseLayer = true)
        listOf(firstView, secondView).forEach { view ->
            view.animate().cancel()
            view.setImageDrawable(null)
            view.setLayerType(View.LAYER_TYPE_NONE, null)
            view.scaleX = 1f
            view.scaleY = 1f
        }
    }
}
