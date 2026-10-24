package com.immichframe.immichframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.preference.PreferenceManager
import java.util.Calendar

internal object FrameScheduleManager {
    fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context,
        ScheduleWakeReceiver.REQUEST_CODE,
        Intent(context, ScheduleWakeReceiver::class.java).setAction(ScheduleWakeReceiver.ACTION_WAKE),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun cancel(context: Context) {
        (context.getSystemService(Context.ALARM_SERVICE) as AlarmManager)
            .cancel(pendingIntent(context))
    }

    fun schedule(context: Context) {
        val prefs = PreferenceManager.getDefaultSharedPreferences(context)
        cancel(context)
        if (!prefs.getBoolean("activeTimes", false)) return
        val schedule = Helpers.parseActiveSchedule(prefs.getString("activeSchedule", null))
        val next = Helpers.nextActiveStart(schedule, Calendar.getInstance()) ?: return
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val exact = exactAlarmPermitted(Build.VERSION.SDK_INT) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) manager.canScheduleExactAlarms() else true
        }
        try {
            if (exact) {
                manager.setExactAndAllowWhileIdle(
                    AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent(context),
                )
            } else {
                manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent(context))
            }
        } catch (_: SecurityException) {
            // The exact-alarm grant can be revoked between the check and the call.
            manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent(context))
        }
    }

    /**
     * Whether Active Times has just entered an active period, such as the morning wake. Not at the
     * first check after start ([wasActive] null), when there is no pause to keep anyway.
     */
    internal fun activePeriodBegan(wasActive: Boolean?, isActive: Boolean): Boolean =
        wasActive == false && isActive

    /**
     * Exact alarms need no grant before Android 12 (API 31). From then on they need
     * SCHEDULE_EXACT_ALARM (API 31-32, revocable) or USE_EXACT_ALARM (API 33+), which
     * [canScheduleExactAlarms] reports; without it the wake alarm is inexact but still fires in Doze.
     */
    internal fun exactAlarmPermitted(sdkInt: Int, canScheduleExactAlarms: () -> Boolean): Boolean =
        sdkInt < 31 || canScheduleExactAlarms()
}

class ScheduleStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = FrameScheduleManager.schedule(context)
}
