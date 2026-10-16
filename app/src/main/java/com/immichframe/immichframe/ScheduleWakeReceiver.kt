package com.immichframe.immichframe

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * Fired by AlarmManager (RTC_WAKEUP) at the next scheduled active-start time. Wakes the device by
 * bringing MainActivity to the foreground; MainActivity then re-evaluates the schedule and powers
 * the screen back on. Needed because the device may be in deep sleep, where the in-app Handler loop
 * is suspended.
 */
class ScheduleWakeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // MainActivity is singleTask, so starting it would close Settings or the schedule editor.
        // Someone is using the screen, and MainActivity re-checks the schedule when it resumes.
        if (ConfigurationScreens.isInFront) {
            Log.i("ScheduleWake", "A configuration screen is open; not bringing the frame to the front")
            return
        }
        val launch = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
        }
        context.startActivity(launch)
    }

    companion object {
        const val ACTION_WAKE = "com.immichframe.immichframe.ACTION_SCHEDULE_WAKE"
        const val REQUEST_CODE = 4711
    }
}

/**
 * Counts resumed configuration screens, so the schedule wake alarm does not close them. Activity
 * callbacks and the receiver both run on the main thread.
 */
internal object ConfigurationScreens {
    private var resumed = 0

    val isInFront: Boolean get() = resumed > 0

    fun onResumed() {
        resumed++
    }

    fun onPaused() {
        resumed = (resumed - 1).coerceAtLeast(0)
    }
}
