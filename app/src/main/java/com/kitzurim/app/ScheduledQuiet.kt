package com.kitzurim.app

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Build
import java.util.Calendar

/** שקט לפי שעות קבועות: בתחילת כל טווח הטלפון עובר לשקט, ובסופו הצלצול חוזר */
object ScheduledQuiet {
    val DAY_LETTERS = listOf("א", "ב", "ג", "ד", "ה", "ו", "ש")

    fun format(minutes: Int) = "%02d:%02d".format(minutes / 60, minutes % 60)

    fun describe(r: QuietRule): String {
        val days = when (r.days) {
            QuietRule.ALL_DAYS -> "כל יום"
            0 -> "אף יום"
            else -> DAY_LETTERS.filterIndexed { i, _ -> r.hasDay(i) }.joinToString(" ")
        }
        return "${format(r.start)}–${format(r.end)} · $days"
    }

    /** האם הכלל פעיל ברגע הזה */
    private fun isActive(r: QuietRule, cal: Calendar): Boolean {
        if (r.start == r.end) return false
        val day = cal.get(Calendar.DAY_OF_WEEK) - 1 // ראשון = 0
        val m = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        if (r.start < r.end) return r.hasDay(day) && m >= r.start && m < r.end
        // טווח שעובר את חצות: מהתחלה עד סוף היום, ומתחילת היום הבא עד הסוף
        if (m >= r.start && r.hasDay(day)) return true
        return m < r.end && r.hasDay((day + 6) % 7)
    }

    private fun shouldBeQuiet(ctx: Context): Boolean {
        if (!AutoPrefs.scheduleEnabled(ctx)) return false
        val now = Calendar.getInstance()
        return AutoPrefs.rules(ctx).any { isActive(it, now) }
    }

    /** בודק אם עכשיו זמן שקט, מחליף מצב צלצול אם צריך, וקובע את הבדיקה הבאה */
    fun apply(ctx: Context) {
        val am = ctx.getSystemService(AudioManager::class.java)
        val want = shouldBeQuiet(ctx)
        if (am != null) {
            if (want && !AutoPrefs.inScheduledQuiet(ctx)) {
                AutoPrefs.enterScheduledQuiet(ctx, am.ringerMode)
                QuietPlaces.setRinger(ctx, AudioManager.RINGER_MODE_SILENT)
                Actions.toast(ctx, "🤫 שעת שקט: הטלפון בשקט")
            } else if (!want && AutoPrefs.inScheduledQuiet(ctx)) {
                QuietPlaces.setRinger(ctx, AutoPrefs.scheduledSavedRinger(ctx))
                AutoPrefs.leaveScheduledQuiet(ctx)
                Actions.toast(ctx, "🔔 שעת השקט נגמרה: הצלצול חזר")
            }
        }
        scheduleNext(ctx)
    }

    private fun pendingIntent(ctx: Context) = PendingIntent.getBroadcast(
        ctx, 1, Intent(ctx, ScheduleReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /** מכוון "שעון מעורר" שקט לרגע הבא שבו מתחיל או נגמר טווח */
    private fun scheduleNext(ctx: Context) {
        val alarms = ctx.getSystemService(AlarmManager::class.java) ?: return
        val pi = pendingIntent(ctx)
        alarms.cancel(pi)
        val rules = AutoPrefs.rules(ctx)
        if (!AutoPrefs.scheduleEnabled(ctx) || rules.isEmpty()) return

        val now = System.currentTimeMillis()
        var next: Long? = null
        for (d in 0..7) {
            for (r in rules) {
                for (minutes in listOf(r.start, r.end)) {
                    val cal = Calendar.getInstance().apply {
                        add(Calendar.DAY_OF_YEAR, d)
                        set(Calendar.HOUR_OF_DAY, minutes / 60)
                        set(Calendar.MINUTE, minutes % 60)
                        set(Calendar.SECOND, 1)
                        set(Calendar.MILLISECOND, 0)
                    }
                    val t = cal.timeInMillis
                    if (t > now + 1000 && (next == null || t < next)) next = t
                }
            }
        }
        val at = next ?: return
        try {
            if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) {
                alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            } else {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            }
        } catch (_: SecurityException) {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

/** הגיע הזמן שבו מתחיל או נגמר טווח שקט */
class ScheduleReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ScheduledQuiet.apply(ctx)
    }
}
