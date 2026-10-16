package com.immichframe.immichframe

import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import kotlin.math.PI
import kotlin.math.cos

/**
 * Runs a step on display vsync at a bounded rate. Steps land on vsync boundaries, which keeps
 * their size even at rates below the refresh rate.
 */
internal class VsyncStepper(
    private val choreographer: Choreographer = Choreographer.getInstance(),
) {
    private var callback: Choreographer.FrameCallback? = null

    /** Calls [step] with the elapsed time at most [framesPerSecond] times a second until it returns false. */
    fun start(framesPerSecond: Int, step: (elapsedMillis: Long) -> Boolean) {
        cancel()
        val frameIntervalNanos = 1_000_000_000L / framesPerSecond.coerceIn(10, 60)
        var startNanos = -1L
        var lastStepNanos = -1L
        val task = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                if (callback !== this) return
                if (startNanos < 0L) startNanos = frameTimeNanos
                if (lastStepNanos < 0L ||
                    frameTimeNanos - lastStepNanos >= frameIntervalNanos - VSYNC_TOLERANCE_NANOS
                ) {
                    lastStepNanos = frameTimeNanos
                    if (!step((frameTimeNanos - startNanos) / 1_000_000L)) {
                        if (callback === this) callback = null
                        return
                    }
                }
                choreographer.postFrameCallback(this)
            }
        }
        callback = task
        choreographer.postFrameCallback(task)
    }

    fun cancel() {
        callback?.let(choreographer::removeFrameCallback)
        callback = null
    }

    private companion object {
        /** Lets a 15 or 30 fps target step on every 4th or 2nd 60 Hz vsync despite jitter. */
        const val VSYNC_TOLERANCE_NANOS = 4_000_000L
    }
}

/**
 * Bounded-rate Ken Burns transform. Scale and translation are render properties, so each step
 * only re-composites the existing bitmap; no offscreen layer is needed.
 */
internal class KenBurnsZoomController(
    private val stepper: VsyncStepper = VsyncStepper(),
) {
    private var directionIndex = 0
    private val directions = listOf(-1f to -0.5f, 0.6f to -1f, 1f to 0.5f, -0.6f to 1f)

    fun start(
        view: ImageView,
        durationMillis: Long,
        framesPerSecond: Int,
        endScale: Float = 1.2f,
    ) {
        val duration = durationMillis.coerceAtLeast(1L)
        val direction = directions[directionIndex++ % directions.size]
        val maxTranslationX = view.width * (endScale - 1f) * 0.22f
        val maxTranslationY = view.height * (endScale - 1f) * 0.22f
        stepper.start(framesPerSecond) { elapsed ->
            if (!view.isAttachedToWindow) return@start false
            val fraction = fractionAt(elapsed, duration)
            val scale = 1f + ((endScale - 1f) * fraction)
            view.scaleX = scale
            view.scaleY = scale
            view.translationX = maxTranslationX * direction.first * fraction
            view.translationY = maxTranslationY * direction.second * fraction
            elapsed < duration
        }
    }

    fun cancel() = stepper.cancel()

    companion object {
        internal fun scaleAt(elapsedMillis: Long, durationMillis: Long, endScale: Float): Float {
            val fraction = fractionAt(elapsedMillis, durationMillis)
            return 1f + ((endScale - 1f) * fraction)
        }
    }
}

/**
 * Bounded-rate alpha fade. Each crossfade frame blends two full-screen bitmaps and takes about
 * 30 ms on the WF1561, so an unbounded 60 fps fade only produced uneven, janky frames.
 */
internal class FadeController(
    private val stepper: VsyncStepper = VsyncStepper(),
) {
    fun start(view: View, toAlpha: Float, durationMillis: Long, framesPerSecond: Int, endAction: Runnable) {
        val fromAlpha = view.alpha
        stepper.start(framesPerSecond) { elapsed ->
            view.alpha = alphaAt(elapsed, durationMillis, fromAlpha, toAlpha)
            val running = elapsed < durationMillis
            if (!running) endAction.run()
            running
        }
    }

    fun cancel() = stepper.cancel()

    companion object {
        /** Accelerate-decelerate easing, matching the ViewPropertyAnimator default it replaces. */
        internal fun alphaAt(elapsedMillis: Long, durationMillis: Long, from: Float, to: Float): Float {
            val fraction = if (durationMillis <= 0L) 1f else fractionAt(elapsedMillis, durationMillis)
            val eased = (cos((fraction + 1f) * PI) / 2.0 + 0.5).toFloat()
            return from + (to - from) * eased
        }
    }
}

private fun fractionAt(elapsedMillis: Long, durationMillis: Long): Float =
    (elapsedMillis.toFloat() / durationMillis.coerceAtLeast(1L)).coerceIn(0f, 1f)

/**
 * Crossfades pre-composed native frames between two stacked ImageViews. Only the upper view's
 * alpha animates while the lower one stays opaque, so the blend never shows the black behind
 * them. An ImageView without a background has no overlapping rendering, so the fade needs no
 * offscreen layer either.
 */
internal class NativeFramePresenter(
    private val firstView: ImageView,
    private val secondView: ImageView,
) {
    private val zoom = KenBurnsZoomController()
    private val fade = FadeController()
    private var showingFirst = true
    private var generation = 0L

    private val topView: ImageView
        get() {
            val parent = firstView.parent as? ViewGroup ?: return secondView
            return if (parent.indexOfChild(firstView) > parent.indexOfChild(secondView)) firstView else secondView
        }

    fun present(
        frame: RenderedFrame,
        transitionMillis: Long,
        zoomEnabled: Boolean,
        zoomDurationMillis: Long,
        zoomFramesPerSecond: Int,
        transitionFramesPerSecond: Int,
        onPresented: (RenderedFrame) -> Unit,
    ) {
        val token = ++generation
        val oldView = if (showingFirst) firstView else secondView
        val newView = if (showingFirst) secondView else firstView
        showingFirst = !showingFirst

        zoom.cancel()
        fade.cancel()
        oldView.animate().cancel()
        newView.animate().cancel()
        val outgoingDrawable = oldView.drawable

        newView.setImageDrawable(null)
        newView.background = null
        resetTransform(newView)
        newView.setImageBitmap(frame.bitmap)
        newView.visibility = View.VISIBLE

        val endAction = Runnable {
            if (token != generation) return@Runnable
            if (oldView.drawable === outgoingDrawable) oldView.setImageDrawable(null)
            oldView.visibility = View.GONE
            oldView.alpha = 1f
            resetTransform(oldView)
            if (zoomEnabled) {
                zoom.start(newView, zoomDurationMillis, zoomFramesPerSecond, frame.zoomEndScale)
            }
        }
        val duration = transitionMillis.coerceAtLeast(0L)
        if (newView === topView) {
            // Fade the new frame in over the opaque old one.
            oldView.alpha = 1f
            newView.alpha = 0f
            fade.start(newView, 1f, duration, transitionFramesPerSecond, endAction)
        } else {
            // The new frame is underneath: show it opaque and fade the old one out above it.
            newView.alpha = 1f
            fade.start(oldView, 0f, duration, transitionFramesPerSecond, endAction)
        }

        onPresented(frame)
    }

    fun pauseZoom() = zoom.cancel()

    fun clear() {
        generation++
        zoom.cancel()
        fade.cancel()
        listOf(firstView, secondView).forEach { view ->
            view.animate().cancel()
            view.setImageDrawable(null)
            view.alpha = 1f
            resetTransform(view)
        }
    }

    private fun resetTransform(view: ImageView) {
        view.scaleX = 1f
        view.scaleY = 1f
        view.translationX = 0f
        view.translationY = 0f
    }
}
