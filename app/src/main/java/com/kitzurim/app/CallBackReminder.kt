package com.kitzurim.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/**
 * "תזכיר לי" בשיחה נכנסת: דוחים את השיחה, ואחרי זמן מה קופצת תזכורת להתקשר בחזרה.
 * הקשה על התזכורת (או על "חייג") מחייגת מיד.
 */
object CallBackReminder {
    private const val CH = "call_back"
    private const val EXTRA_NAME = "name"
    private const val EXTRA_NUMBER = "number"

    fun schedule(c: Context, name: String, number: String, minutes: Int) {
        if (number.isBlank()) return
        val am = c.getSystemService(AlarmManager::class.java) ?: return
        val i = Intent(c, CallBackReceiver::class.java)
            .putExtra(EXTRA_NAME, name)
            .putExtra(EXTRA_NUMBER, number)
        val pi = PendingIntent.getBroadcast(c, number.hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val at = System.currentTimeMillis() + minutes * 60_000L
        runCatching { am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi) }
    }

    /** הגיע הזמן: מראים תזכורת עם "חייג" */
    fun show(c: Context, i: Intent) {
        val name = i.getStringExtra(EXTRA_NAME) ?: return
        val number = i.getStringExtra(EXTRA_NUMBER) ?: return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.createNotificationChannel(NotificationChannel(CH, "תזכורות להתקשר", NotificationManager.IMPORTANCE_HIGH)) }
        val call = PendingIntent.getActivity(
            c, number.hashCode(),
            Intent(c, DialActivity::class.java).putExtra(DialActivity.EXTRA_CALL, number).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("⏰ להתקשר ל$name")
            .setContentText("ביקשת שאזכיר לך לחזור אליו")
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(call)
            .addAction(0, "📞 חייג", call)
            .build()
        runCatching { nm.notify(8000 + (number.hashCode() and 0xfff), n) }
    }
}

class CallBackReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        CallBackReminder.show(c, i)
    }
}
