package com.immichframe.immichframe

/** Main-thread owner of asynchronous native work across mode/lifecycle changes. */
internal class PlaybackSession {
    private var generation = 0L
    private var active = false

    fun activate(): Long {
        active = true
        return ++generation
    }

    fun invalidate() {
        active = false
        generation++
    }

    fun isCurrent(token: Long): Boolean = active && token == generation
}

/** Keeps the running flag and scheduled callback together so a stopped timer can always restart. */
internal class PlaybackTimer(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val intervalMillis: () -> Long,
    private val tick: () -> Unit,
) {
    var isRunning = false
        private set

    private val callback = object : Runnable {
        override fun run() {
            if (!isRunning) return
            postDelayed(this, intervalMillis())
            tick()
        }
    }

    fun start(delayMillis: Long = intervalMillis()) {
        if (isRunning) return
        isRunning = true
        postDelayed(callback, delayMillis)
    }

    fun stop() {
        isRunning = false
        removeCallbacks(callback)
    }

    fun retryAfter(delayMillis: Long) {
        if (!isRunning) return
        removeCallbacks(callback)
        postDelayed(callback, delayMillis)
    }
}

internal object PlaybackPolicies {
    fun shouldWaitForPortraitPair(
        isPortrait: Boolean,
        layout: String,
        allowPortraitPair: Boolean,
    ): Boolean = allowPortraitPair && isPortrait && layout == "splitview"
}
