package com.immichframe.immichframe

/**
 * Main-thread owner of the next slide. A slide is built ahead of time after the previous one
 * is presented; when playback advances, a ready slide is presented immediately, an in-flight
 * build is presented as soon as it completes, and otherwise a build is requested.
 * Every slide removed without being presented is passed to [discard] so its bitmap is freed.
 */
internal class NextSlideQueue<T : Any>(private val discard: (T) -> Unit) {
    sealed interface Advance<out T> {
        data class Present<T>(val slide: T) : Advance<T>
        /** A build is in flight and will be presented when it completes. */
        data object Waiting : Advance<Nothing>
        /** Nothing is ready or in flight: the caller must start a build. */
        data object Build : Advance<Nothing>
    }

    private var ready: T? = null
    private var presentWhenBuilt = false
    var isBuilding = false
        private set

    /** Advances playback; a ready slide that no longer matches [isUsable] is discarded. */
    fun advance(isUsable: (T) -> Boolean): Advance<T> {
        ready?.let { slide ->
            ready = null
            if (isUsable(slide)) return Advance.Present(slide)
            discard(slide)
        }
        presentWhenBuilt = true
        if (isBuilding) return Advance.Waiting
        isBuilding = true
        return Advance.Build
    }

    /** Returns true when the caller should start building the next slide ahead of time. */
    fun beginPrefetch(): Boolean {
        if (isBuilding || ready != null) return false
        isBuilding = true
        presentWhenBuilt = false
        return true
    }

    /** Hands over a finished build; returns it if it should be presented now, else keeps it. */
    fun onBuilt(slide: T): T? {
        isBuilding = false
        if (presentWhenBuilt) {
            presentWhenBuilt = false
            return slide
        }
        ready?.let(discard)
        ready = slide
        return null
    }

    /** Returns true when playback was waiting on the failed build and must handle the failure. */
    fun onFailed(): Boolean {
        isBuilding = false
        return presentWhenBuilt.also { presentWhenBuilt = false }
    }

    /** Pausing keeps a ready or in-flight slide, but it must not be presented until resumed. */
    fun withdrawRequest() {
        presentWhenBuilt = false
    }

    /** Drops all state; the caller must also make any in-flight build's result stale. */
    fun clear() {
        ready?.let(discard)
        ready = null
        isBuilding = false
        presentWhenBuilt = false
    }
}
