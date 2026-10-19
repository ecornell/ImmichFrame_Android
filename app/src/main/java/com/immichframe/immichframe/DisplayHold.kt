package com.immichframe.immichframe

/**
 * A remote display command (RPC or MQTT) that differs from Active Times, held so the periodic
 * schedule check does not undo it. "Off" holds until the schedule next changes; "on" also ends
 * after [MAX_ON_HOLD_MILLIS], so a forgotten "on" cannot keep the frame lit all night. A null
 * [untilMillis] means no end (an "off" with no change ahead: Active Times off, or an always-active
 * schedule); the hold then lasts until the next remote command.
 */
data class DisplayHold(val active: Boolean, val untilMillis: Long?) {
    companion object {
        const val MAX_ON_HOLD_MILLIS = 2 * 60 * 60 * 1_000L

        /** The hold for a remote command; none when the command matches the schedule anyway. */
        fun forCommand(
            active: Boolean,
            scheduledActive: Boolean,
            nextChangeMillis: Long?,
            nowMillis: Long,
        ): DisplayHold? {
            if (active == scheduledActive) return null
            if (!active) return DisplayHold(false, nextChangeMillis)
            val cap = nowMillis + MAX_ON_HOLD_MILLIS
            return DisplayHold(true, nextChangeMillis?.let { minOf(it, cap) } ?: cap)
        }

        /** Whether the frame should be active, and the hold still in force (null once expired). */
        fun resolve(scheduledActive: Boolean, hold: DisplayHold?, nowMillis: Long): Pair<Boolean, DisplayHold?> {
            if (hold == null) return scheduledActive to null
            val until = hold.untilMillis
            if (until != null && nowMillis >= until) return scheduledActive to null
            return hold.active to hold
        }
    }
}
