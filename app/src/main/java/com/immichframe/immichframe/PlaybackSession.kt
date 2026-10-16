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

    /** Applies a changed interval from now without accidentally resuming paused playback. */
    fun reschedule() {
        if (!isRunning) return
        removeCallbacks(callback)
        postDelayed(callback, intervalMillis())
    }
}

/** Runs one asynchronous refresh at a time and schedules the next after that refresh completes. */
internal class AsyncRefreshTimer(
    private val postDelayed: (Runnable, Long) -> Unit,
    private val removeCallbacks: (Runnable) -> Unit,
    private val intervalMillis: Long,
    private val refresh: (onComplete: () -> Unit) -> Unit,
) {
    private var generation = 0L
    private var isRunning = false
    private var pending: Runnable? = null

    fun start() {
        if (isRunning) return
        isRunning = true
        val token = ++generation
        schedule(token)
    }

    fun stop() {
        isRunning = false
        generation++
        pending?.let(removeCallbacks)
        pending = null
    }

    private fun schedule(token: Long) {
        if (!isRunning || token != generation) return
        val task = Runnable {
            if (!isRunning || token != generation) return@Runnable
            pending = null
            var completed = false
            refresh {
                if (!completed) {
                    completed = true
                    schedule(token)
                }
            }
        }
        pending = task
        postDelayed(task, intervalMillis)
    }
}

internal const val SERVER_SETTINGS_REFRESH_MILLIS = 30_000L

internal object PlaybackPolicies {
    fun shouldWaitForPortraitPair(
        isPortrait: Boolean,
        layout: String,
        allowPortraitPair: Boolean,
    ): Boolean = allowPortraitPair && isPortrait && layout == "splitview"

    data class Pairing<T>(val first: T, val second: T?, val held: T?)

    /**
     * Decides a split-view slide when a waiting portrait [first] has been followed by [next].
     * Neither is skipped, since the server already counted both as shown: an unpaired portrait is
     * held for one more slide to find a partner, and shown alone if it already waited once.
     */
    fun <T> pairPortrait(first: T, firstWasHeld: Boolean, next: T, nextWaitsForPair: Boolean): Pairing<T> =
        when {
            nextWaitsForPair -> Pairing(first, next, held = null)
            firstWasHeld -> Pairing(first, second = null, held = next)
            else -> Pairing(next, second = null, held = first)
        }
}
