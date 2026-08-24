package com.immichframe.immichframe

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
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
        try {
            manager.setExactAndAllowWhileIdle(
                AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent(context),
            )
        } catch (_: SecurityException) {
            manager.set(AlarmManager.RTC_WAKEUP, next.timeInMillis, pendingIntent(context))
        }
    }
}

class ScheduleStateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) = FrameScheduleManager.schedule(context)
}
