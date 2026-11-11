package com.kitzurim.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat

/** הטלפון התחבר לרכב או התנתק ממנו: מדליק ומכבה את מצב הנהיגה */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return
        if (device.address != AutoPrefs.carAddress(ctx)) return
        val enabled = AutoPrefs.drivingEnabled(ctx)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                AutoPrefs.setDrivingActive(ctx, true)
                if (enabled) Actions.toast(ctx, "🚗 מצב נהיגה: מענה אוטומטי פעיל")
                ActivityLog.add(ctx, "🚗", "התחלת נסיעה")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                AutoPrefs.setDrivingActive(ctx, false)
                if (enabled) Actions.toast(ctx, "מצב נהיגה כבה")
                val got = AutoPrefs.driveMessages(ctx).size
                ActivityLog.add(ctx, "🏁", if (got > 0) "סוף נסיעה · הגיעו $got הודעות" else "סוף נסיעה")
                DriveSummary.show(ctx)
            }
        }
    }
}

/**
 * קורא את ההתראות של וואטסאפ בזמן נהיגה:
 * שומר כל הודעה לסיכום שמופיע בסוף הנסיעה, ועונה פעם אחת לכל צ'אט פרטי.
 */
class ReplyListener : NotificationListenerService() {
    // נורת התראות בטבעת: הודעה חדשה מאפליקציית הודעות ← הטבעת מהבהבת בצבע שלה עד שקוראים
    private val lightColors = mapOf(
        "com.whatsapp" to 0xFF25D366.toInt(),
        "com.whatsapp.w4b" to 0xFF25D366.toInt(),
        "org.telegram.messenger" to 0xFF2AABEE.toInt(),
        "com.google.android.apps.messaging" to 0xFF4C8DF6.toInt(),
        "com.samsung.android.messaging" to 0xFF4C8DF6.toInt(),
        "com.google.android.gm" to 0xFFEA4335.toInt(),
        "com.facebook.orca" to 0xFF0A7CFF.toInt(),
        "com.instagram.android" to 0xFFE1306C.toInt(),
    )

    private fun lightFor(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // שיחה נכנסת (רגילה או וואטסאפ): הגלולה עם "ענה" ו"דחה", דרך הכפתורים שכבר בהתראה
        if (n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null) {
            val name = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "שיחה"
            val actions = n.actions?.toList() ?: emptyList()
            fun find(words: List<String>) = actions.firstOrNull { a ->
                val t = a.title?.toString()?.lowercase() ?: ""
                words.any { t.contains(it) }
            }
            val answer = find(listOf("ענה", "מענה", "קבל", "answer", "accept")) ?: actions.lastOrNull()
            val decline = find(listOf("דחה", "דחיי", "נתק", "decline", "reject", "dismiss")) ?: actions.firstOrNull()
            LivePill.showCall(sbn.key, name, decline?.actionIntent, answer?.actionIntent)
            return
        }

        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val color = lightColors[sbn.packageName]
            ?: if (n.category == Notification.CATEGORY_MESSAGE && n.color != 0) n.color else return
        NotchOverlay.notificationPosted(sbn.key, color)

        // הגלולה החיה: מי שלח ומה כתב (לא מה ששלחת אתה)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        if (style != null && last != null && last.person == null) return
        val sender = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = last?.text?.toString() ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (text.isBlank()) return
        LivePill.showMessage(sender, text, color, n.contentIntent)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        NotchOverlay.notificationRemoved(sbn.key)
        LivePill.callNotificationRemoved(sbn.key)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        lightFor(sbn)
        if (!AutoPrefs.drivingActive(this)) return
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification
        // התראת "סיכום" של כמה צ'אטים: מדלגים עליה
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val isGroup = style?.isGroupConversation == true ||
            n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)
        val chat = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val number = Driving.chatNumber(sbn, style)

        // סיכום הנסיעה: שומרים את ההודעה האחרונה (לא את מה ששלחת אתה)
        if (AutoPrefs.summaryEnabled(this)) {
            val last = style?.messages?.lastOrNull()
            val fromMe = style != null && last != null && last.person == null
            val text = last?.text?.toString() ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            if (!fromMe && !text.isNullOrBlank()) AutoPrefs.addDriveMessage(this, chat, text, number)
        }

        if (!AutoPrefs.drivingEnabled(this) || isGroup) return
        if (AutoPrefs.alreadyReplied(this, chat)) return
        // אנשים שבחרת שלא יקבלו מענה אוטומטי: לפי מספר הטלפון, ואם אין מספר אז לפי השם
        if (AutoPrefs.excluded(this).any { Excluded.matches(it, chat, number) }) return

        // הכפתור "השב" שבהתראה: ממלאים בו את הטקסט ושולחים
        val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val inputs = action.remoteInputs
        val results = Bundle()
        val reply = AutoPrefs.replyFor(this, chat, number)
        inputs.forEach { results.putCharSequence(it.resultKey, reply) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        try {
            action.actionIntent.send(this, 0, fill)
            AutoPrefs.markReplied(this, chat)
            ActivityLog.add(this, "💬", "עניתי אוטומטית ל$chat")
        } catch (_: PendingIntent.CanceledException) {
        }
    }
}

/** איש קשר שלא מקבל מענה. נשמר כ"שם|מספר", כדי לזהות אותו גם כשהשם בוואטסאפ שונה מאנשי הקשר */
object Excluded {
    fun encode(name: String, number: String?) = name.trim() + "|" + (number?.filter { it.isDigit() } ?: "")
    fun name(entry: String) = entry.substringBefore("|")
    private fun number(entry: String) = entry.substringAfter("|", "")

    /** משווים את 9 הספרות האחרונות, כדי ש-050... ו-97250... ייחשבו אותו מספר */
    fun samePhone(a: String?, b: String?): Boolean {
        val x = a?.filter { it.isDigit() }?.takeLast(9) ?: return false
        val y = b?.filter { it.isDigit() }?.takeLast(9) ?: return false
        return x.length == 9 && x == y
    }

    fun matches(entry: String, chat: String, chatNumber: String?): Boolean {
        val num = number(entry)
        if (num.isNotEmpty() && chatNumber != null) return samePhone(num, chatNumber)
        return name(entry).equals(chat.trim(), ignoreCase = true)
    }
}

object Driving {
    private val JID = Regex("(\\d{8,15})@s\\.whatsapp\\.net")

    /** מספר הטלפון של הצ'אט: וואטסאפ שם אותו בהתראה (972501234567@s.whatsapp.net), גם כשהשם לא מסונכרן */
    fun chatNumber(sbn: StatusBarNotification, style: NotificationCompat.MessagingStyle?): String? {
        val n = sbn.notification
        val candidates = listOfNotNull(
            n.shortcutId,
            sbn.tag,
            style?.messages?.lastOrNull()?.person?.key,
            style?.messages?.lastOrNull()?.person?.uri,
        )
        return candidates.firstNotNullOfOrNull { JID.find(it)?.groupValues?.get(1) }
    }

    /** האם אישרת לקיצורים לקרוא התראות */
    fun listenerAllowed(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, ReplyListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    /** המכשירים שמחוברים לטלפון בבלוטות' (שם, כתובת) */
    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Pair<String, String>> = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.map { (it.name ?: it.address) to it.address }
            ?.sortedBy { it.first }
            ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}
