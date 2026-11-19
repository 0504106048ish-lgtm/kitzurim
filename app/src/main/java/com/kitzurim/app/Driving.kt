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
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat
import androidx.core.graphics.drawable.toBitmap

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

    /** אפליקציות שההודעות שלהן עוברות לרכב דרך קיצורים */
    private val carApps = setOf("com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "com.instagram.android", "com.facebook.orca")
    private val smsApps = setOf("com.samsung.android.messaging", "com.google.android.apps.messaging")

    private fun lightFor(sbn: StatusBarNotification) {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return

        // קוד אימות: מועתק לבד ומוצג בגלולה (במקום ההודעה הרגילה)
        if (Codes.handle(this, sbn)) return

        // שיחה נכנסת (רגילה או וואטסאפ): הגלולה עם "ענה" ו"דחה", דרך הכפתורים שכבר בהתראה
        if (n.category == Notification.CATEGORY_CALL && n.fullScreenIntent != null) {
            // שיחה של קיצורים עצמה (כשהיא אפליקציית הטלפון): מסך השיחה בניאון כבר פתוח
            if (sbn.packageName == packageName && CallActivity.visible) return
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

        // שיחה פעילה (יוצאת, או אחרי שענית): השם, והאם כבר ענו (כשההתראה מתחילה לספור זמן)
        if (n.category == Notification.CATEGORY_CALL && n.flags and Notification.FLAG_ONGOING_EVENT != 0) {
            val name = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: "שיחה"
            val counting = n.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER, false)
            // לא כל טלפון מסמן "סופר זמן": אם כבר לא כתוב "מחייג" / "מתקשר", סימן שענו
            val status = n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.lowercase() ?: ""
            val dialing = listOf("מחייג", "מתקשר", "מחבר", "חיוג", "dialing", "calling", "connecting", "outgoing").any { status.contains(it) }
            val answered = counting || (status.isNotBlank() && !dialing)
            val at = if (n.`when` in 1..System.currentTimeMillis()) n.`when` else System.currentTimeMillis()
            LivePill.ongoingCall(name, if (answered) at else null, n.actions?.toList() ?: emptyList(), n.contentIntent)
            return
        }

        // ניווט (וויז / גוגל מפות): הפנייה הבאה והמרחק בגלולה
        if (sbn.packageName in navApps) {
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) {
                val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
                val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
                    ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString() ?: ""
                val arrow = runCatching { n.getLargeIcon()?.loadDrawable(this) }.getOrNull()
                run {
                    LivePill.showNav(sbn.key, sbn.packageName, title, text, arrow, n.contentIntent)
                }
            }
            return
        }

        // הורדה: הטבעת מתמלאת לפי ההתקדמות, ובסוף "הורד ✓"
        // גם הורדה של סרטון או תמונה בוואטסאפ (או כל אפליקציה): ההתראה מתעדכנת כל רגע עם האחוזים,
        // אז במקום מלא גלולות של הודעה, הטבעת מתמלאת בשקט ובסוף "הורד ✓" פעם אחת
        val progress = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0) > 0 ||
            n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false) ||
            n.category == Notification.CATEGORY_PROGRESS
        if (sbn.packageName in downloadApps || progress || sbn.key in downloadNames) {
            val max = n.extras.getInt(Notification.EXTRA_PROGRESS_MAX, 0)
            val prog = n.extras.getInt(Notification.EXTRA_PROGRESS, 0)
            val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: ""
            if (max > 0 && !n.extras.getBoolean(Notification.EXTRA_PROGRESS_INDETERMINATE, false)) {
                NotchOverlay.downloadProgress(sbn.key, prog.toFloat() / max)
                downloadNames[sbn.key] = title
            } else {
                // אותה התראה עברה ל"הושלם"
                val p = NotchOverlay.downloadEnded(sbn.key)
                if (p != null) LivePill.showDownloaded(downloadNames.remove(sbn.key) ?: title, n.contentIntent)
            }
            return
        }

        // נגן מוזיקה: זוכרים את ערוץ ההתראה שלו, בשביל הכפתור "להסתיר את סמל המוזיקה משורת המצב"
        if (n.extras.containsKey(Notification.EXTRA_MEDIA_SESSION) && n.channelId != null) {
            NotchPrefs.setMediaChannel(this, sbn.packageName, n.channelId)
        }
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return
        val appColor = lightColors[sbn.packageName]
            ?: if (n.category == Notification.CATEGORY_MESSAGE && n.color != 0) n.color else return
        val color = NotchPrefs.tint(this, "msg", appColor)

        // הגלולה החיה: מי שלח ומה כתב (לא מה ששלחת אתה)
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        val last = style?.messages?.lastOrNull()
        // ענית בעצמך: סימן שקראת, העיגול נכבה
        if (style != null && last != null && last.person == null) {
            NotchOverlay.notificationRemoved(sbn.key)
            return
        }
        val sender = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val text = last?.text?.toString() ?: n.extras.getCharSequence(Notification.EXTRA_TEXT)?.toString() ?: return
        if (text.isBlank()) return
        // רק הודעה חדשה באמת: וואטסאפ מפרסם שוב את אותה התראה (למשל כשמחברים מטען),
        // ואז העיגול לא נדלק שוב על הודעה שכבר ראית
        // גם אותה הודעה פעמיים ("אוקי", "אוקי") היא הודעה חדשה: מבדילים לפי הזמן שלה
        // אותה הודעה שוואטסאפ פרסם שוב: לא מראים שוב. הזמן של ההודעה עצמה (אם יש), לא של ההתראה (שמשתנה בכל פרסום)
        val sig = "$sender|$text|${last?.timestamp ?: 0L}"
        if (shown[sbn.key] == sig) return
        shown[sbn.key] = sig
        val sent = last?.timestamp?.takeIf { it > 0 } ?: n.`when`
        if (sent > 0 && System.currentTimeMillis() - sent > 60_000) return
        // הכפתורים של ההתראה: השב (עם שורת כתיבה) וסמן כנקרא
        val acts = n.actions?.toList() ?: emptyList()
        val reply = acts.firstOrNull { !it.remoteInputs.isNullOrEmpty() }
        val read = acts.firstOrNull { a ->
            (Build.VERSION.SDK_INT >= 28 && a.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ) ||
                listOf("נקרא", "read").any { a.title?.toString()?.contains(it, ignoreCase = true) == true }
        }
        // התמונה של מי ששלח (בקבוצה: של הקבוצה), ותמונה שנשלחה בהודעה, בשביל הגלולה
        val isGroup = style?.isGroupConversation == true
        val photo = if (NotchPrefs.pillPhoto(this)) runCatching {
            val ic = (if (isGroup) null else last?.person?.icon)
                ?: n.getLargeIcon()?.let { androidx.core.graphics.drawable.IconCompat.createFromIcon(this, it) }
                ?: last?.person?.icon
            ic?.loadDrawable(this)?.let { dr -> dr.toBitmap(96, 96) }
        }.getOrNull() else null
        val thumb = if (NotchPrefs.pillPhoto(this)) runCatching { msgPicture(n, last) }.getOrNull() else null
        // הרבה הודעות ברצף באותה שיחה: גלולה אחת עם מונה, ולא גלולה חדשה שקופצת כל פעם
        val nowMs = System.currentTimeMillis()
        val prev = burst[sbn.key]
        val count = if (prev != null && nowMs - prev.second < 25_000) prev.first + 1 else 1
        burst[sbn.key] = count to nowMs
        val who = last?.person?.name?.toString()?.takeIf { it.isNotBlank() && isGroup }
        val pillText = when {
            count > 1 && who != null -> "$count הודעות · $who: $text"
            count > 1 -> "$count הודעות · $text"
            who != null && !sender.contains(who) -> "$who: $text"
            else -> text
        }
        val pillTitle = if (isGroup) style?.conversationTitle?.toString()?.takeIf { it.isNotBlank() } ?: sender else sender
        val m = LastMsg(sender, text, color, n.contentIntent, reply, read, sbn.key, photo, thumb)
        // אנדרואיד אוטו: מחוברים לרכב? ההודעה עוברת גם למסך הרכב (הקראה ותשובה בקול)
        // (הודעות SMS כבר מגיעות לרכב לבד, אז רק אפליקציות צ'אט, כדי שלא יהיו פעמיים)
        // אפליקציה שכבר מגיעה לרכב לבד (כמו וואטסאפ: הודעה בסגנון צ'אט + "השב" + "סמן כנקרא"):
        // לא מעבירים עותק, אחרת ההודעה מופיעה ברכב פעמיים
        // אבל אנדרואיד אוטו מראה אותה לבד רק אם ההתראה "קופצת" (חשיבות גבוהה). כשהחלון הקופץ של וואטסאפ כבוי
        // היא לא מגיעה לרכב בכלל, ואז כן מעבירים עותק (אחרת לא מופיע כלום)
        val pops = runCatching {
            val r = NotificationListenerService.Ranking()
            currentRanking.getRanking(sbn.key, r) && r.importance >= android.app.NotificationManager.IMPORTANCE_HIGH
        }.getOrDefault(true)
        val native = pops && style != null && reply != null && Build.VERSION.SDK_INT >= 28 &&
            acts.any { it.semanticAction == Notification.Action.SEMANTIC_ACTION_MARK_AS_READ }
        if (native) {
            CarMirror.removed(this, sbn.key)
        } else if ((sbn.packageName in carApps || sbn.packageName in smsApps) && NotchPrefs.carApp(this, sbn.packageName)) {
            val group = style?.isGroupConversation == true
            val who = last?.person?.name?.toString()?.takeIf { it.isNotBlank() } ?: sender
            // תמונת הפרופיל של מי ששלח (כמו שוואטסאפ מראה), שתופיע גם ברכב
            val icon = last?.person?.icon
                ?: n.getLargeIcon()?.let { runCatching { androidx.core.graphics.drawable.IconCompat.createFromIcon(this, it) }.getOrNull() }
            CarMirror.mirror(this, sbn.key, who, text, if (group) (style?.conversationTitle?.toString() ?: sender) else null, reply, read, icon, n.contentIntent)
        }
        NotchOverlay.notificationPosted(sbn.key, color, m)
        LivePill.showMessage(pillTitle, pillText, color, n.contentIntent, msg = m, burst = count > 1)
    }

    private val shown = HashMap<String, String>()

    /** כמה הודעות הגיעו ברצף בכל שיחה, ומתי האחרונה */
    private val burst = HashMap<String, Pair<Int, Long>>()

    /** תמונה שנשלחה בהודעה (מוואטסאפ וכו'), מוקטנת */
    private fun msgPicture(n: Notification, last: NotificationCompat.MessagingStyle.Message?): android.graphics.Bitmap? {
        val uri = last?.dataUri?.takeIf { last?.dataMimeType?.startsWith("image/") == true }
        if (uri != null) {
            val o = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri)?.use { android.graphics.BitmapFactory.decodeStream(it, null, o) }
            var sample = 1
            while (o.outWidth / (sample * 2) >= 96 && o.outHeight / (sample * 2) >= 96) sample *= 2
            val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
            contentResolver.openInputStream(uri)?.use { s -> android.graphics.BitmapFactory.decodeStream(s, null, opts) }?.let { return it }
        }
        @Suppress("DEPRECATION")
        val pic = n.extras.getParcelable<android.graphics.Bitmap>(Notification.EXTRA_PICTURE) ?: return null
        val sc = 96f / kotlin.math.max(pic.width, pic.height)
        return if (sc < 1f) android.graphics.Bitmap.createScaledBitmap(pic, (pic.width * sc).toInt().coerceAtLeast(1), (pic.height * sc).toInt().coerceAtLeast(1), true) else pic
    }

    private val navApps = setOf("com.waze", "com.google.android.apps.maps")
    private val downloadApps = setOf(
        "com.android.providers.downloads",
        "com.android.chrome",
        "com.sec.android.app.sbrowser",
        "org.mozilla.firefox",
        "com.android.vending",
    )
    private val downloadNames = HashMap<String, String>()

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        CarMirror.removed(this, sbn.key)
        NotchOverlay.notificationRemoved(sbn.key)
        LivePill.callNotificationRemoved(sbn.key)
        LivePill.notificationGone(sbn.key)
        LivePill.navEnded(sbn.key)
        // הורדה שנעלמה כמעט בסוף: כנראה הסתיימה
        val p = NotchOverlay.downloadEnded(sbn.key)
        val name = downloadNames.remove(sbn.key)
        if (p != null && p >= 0.9f) LivePill.showDownloaded(name ?: "", null)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        lightFor(sbn)
        runCatching { CallerId.onNotification(this, sbn) }
        runCatching { LastChat.record(this, sbn) }
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
