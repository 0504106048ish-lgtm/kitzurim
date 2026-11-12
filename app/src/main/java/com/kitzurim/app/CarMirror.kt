package com.kitzurim.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.UiModeManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.Uri
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput

/**
 * אנדרואיד אוטו: כשהטלפון מחובר לרכב ומגיעה הודעה (וואטסאפ וכו'), קיצורים מעבירה אותה למסך הרכב
 * כהתראה של שיחה. ככה ברכב ההודעה קופצת, נקראת בקול, ואפשר לענות בקול, גם כשהחלון הקופץ של וואטסאפ כבוי.
 * התשובה נשלחת דרך הכפתור "השב" של ההתראה המקורית (כמו המענה האוטומטי).
 * בנוי כמו ההתראות של צ'טי, שעובדות באנדרואיד אוטו.
 */
object CarMirror {
    private const val CH = "car_messages"
    const val KEY_REPLY = "car_reply"
    const val ACTION_REPLY = "com.kitzurim.app.CAR_REPLY"
    const val ACTION_READ = "com.kitzurim.app.CAR_READ"
    const val EXTRA_KEY = "orig_key"

    /** מה צריך כדי לענות או לסמן כנקרא בהתראה המקורית */
    private class Orig(val reply: Notification.Action?, val read: Notification.Action?, val id: Int)

    private val origs = HashMap<String, Orig>()

    /** האם הטלפון מחובר עכשיו לאנדרואיד אוטו */
    fun connected(c: Context): Boolean {
        // מצב רכב של הטלפון (אנדרואיד אוטו מדליק אותו)
        val car = c.getSystemService(UiModeManager::class.java)?.currentModeType == Configuration.UI_MODE_TYPE_CAR
        if (car) return true
        // הדרך הרשמית של אנדרואיד אוטו לספר שהוא מחובר (2 = מחובר דרך הטלפון)
        return runCatching {
            c.contentResolver.query(Uri.parse("content://androidx.car.app.connection"), arrayOf("CarConnectionState"), null, null, null)
                ?.use { cur -> cur.moveToFirst() && cur.getInt(0) != 0 }
        }.getOrNull() == true
    }

    /** הודעה חדשה: אם מחוברים לרכב, מעבירים אותה למסך הרכב */
    fun mirror(
        c: Context,
        key: String,
        sender: String,
        text: String,
        groupTitle: String?,
        reply: Notification.Action?,
        read: Notification.Action?,
    ) {
        if (!NotchPrefs.carMirror(c) || !connected(c)) return
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        runCatching {
            nm.createNotificationChannel(NotificationChannel(CH, "הודעות ברכב", NotificationManager.IMPORTANCE_HIGH))
        }
        val id = 7000 + (key.hashCode() and 0xffff)
        origs[key] = Orig(reply, read, id)

        // ההודעות הקודמות באותה שיחה נשארות (כמו בצ'טי), כדי שברכב יראו את השיחה
        val old = nm.activeNotifications.firstOrNull { it.id == id }?.notification
            ?.let { NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(it) }
        val me = Person.Builder().setName("אני").build()
        val style = NotificationCompat.MessagingStyle(me)
        if (groupTitle != null) style.setConversationTitle(groupTitle).setGroupConversation(true)
        old?.messages?.takeLast(5)?.forEach { style.addMessage(it) }
        style.addMessage(NotificationCompat.MessagingStyle.Message(text, System.currentTimeMillis(), Person.Builder().setName(sender).build()))

        fun pi(action: String, code: Int, mutable: Boolean) = PendingIntent.getBroadcast(
            c, code,
            Intent(c, CarReplyReceiver::class.java).setAction(action).putExtra(EXTRA_KEY, key),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE),
        )
        val b = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(groupTitle ?: sender)
            .setContentText(text)
            .setStyle(style)
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setOnlyAlertOnce(false)
        if (reply != null) {
            // "השב": בלי לפתוח אפליקציה, עם הקלטת תשובה (חובה בשביל אנדרואיד אוטו)
            b.addAction(
                NotificationCompat.Action.Builder(R.drawable.ic_stat, "השב", pi(ACTION_REPLY, id * 2, true))
                    .addRemoteInput(RemoteInput.Builder(KEY_REPLY).setLabel("תשובה…").build())
                    .setAllowGeneratedReplies(true)
                    .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_REPLY)
                    .setShowsUserInterface(false)
                    .build(),
            )
        }
        b.addAction(
            NotificationCompat.Action.Builder(R.drawable.ic_stat, "סמן כנקרא", pi(ACTION_READ, id * 2 + 1, false))
                .setSemanticAction(NotificationCompat.Action.SEMANTIC_ACTION_MARK_AS_READ)
                .setShowsUserInterface(false)
                .build(),
        )
        runCatching { nm.notify(id, b.build()) }
    }

    /** ההודעה המקורית נעלמה (קראו אותה בטלפון): מורידים גם מהרכב */
    fun removed(c: Context, key: String) {
        val o = origs.remove(key) ?: return
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(o.id) }
    }

    /** תשובה בקול או "סמן כנקרא" מהרכב: שולחים דרך ההתראה המקורית של וואטסאפ */
    fun handle(c: Context, i: Intent) {
        val key = i.getStringExtra(EXTRA_KEY) ?: return
        val o = origs[key] ?: return
        if (i.action == ACTION_REPLY) {
            val text = RemoteInput.getResultsFromIntent(i)?.getCharSequence(KEY_REPLY)?.toString()?.trim()
            val a = o.reply
            if (!text.isNullOrEmpty() && a != null) {
                val inputs = a.remoteInputs ?: return
                val results = Bundle()
                inputs.forEach { results.putCharSequence(it.resultKey, text) }
                val fill = Intent()
                android.app.RemoteInput.addResultsToIntent(inputs, fill, results)
                runCatching { a.actionIntent.send(c, 0, fill) }
                ActivityLog.add(c, "🚗", "ענית ברכב: $text")
            }
        } else {
            o.read?.let { runCatching { it.actionIntent.send() } }
        }
        origs.remove(key)
        runCatching { c.getSystemService(NotificationManager::class.java)?.cancel(o.id) }
    }
}

/** הכפתורים של ההודעה ברכב: "השב" ו"סמן כנקרא" */
class CarReplyReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        CarMirror.handle(c, i)
    }
}
