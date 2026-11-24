package com.kitzurim.app

import android.app.Notification
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.graphics.drawable.toBitmap
import java.io.File

/**
 * תמונה (ושם) מוואטסאפ למי שלא שמור אצלך: כשמישהו כותב לך בוואטסאפ, שומרים את תמונת הפרופיל שלו לפי המספר.
 * ככה כשהוא מתקשר (או מופיע ברשימת השיחות) רואים את התמונה שלו, גם בלי שהוא שמור באנשי הקשר.
 */
object WaPhotos {
    private val cache = HashMap<String, Bitmap?>()

    private fun dir(c: Context) = File(c.filesDir, "wa_photos").apply { mkdirs() }
    private fun file(c: Context, key: String) = File(dir(c), "$key.png")
    private fun sp(c: Context) = c.getSharedPreferences("wa_photos", Context.MODE_PRIVATE)

    /** הודעה מוואטסאפ: שומרים את התמונה והשם של מי שכתב (לא קבוצות) */
    fun record(c: Context, sbn: StatusBarNotification) {
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style?.isGroupConversation == true || n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return
        val last = style?.messages?.lastOrNull()
        // הודעה שאתה שלחת: התמונה שבה היא שלך
        if (style != null && last != null && last.person == null) return
        val key = CallerId.key(Driving.chatNumber(sbn, style)) ?: return
        // שם: מה שוואטסאפ מראה (אצל מי שלא שמור זה השם שהוא בחר לעצמו, לפעמים עם ~)
        val title = (last?.person?.name ?: n.extras.getCharSequence(Notification.EXTRA_TITLE))?.toString()
            ?.trim()?.removePrefix("~")?.trim()
        if (!title.isNullOrBlank() && title.any { it.isLetter() }) sp(c).edit().putString("n:$key", title).apply()
        // התמונה: לא יותר מפעם ביום לאותו אחד
        val f = file(c, key)
        if (f.exists() && System.currentTimeMillis() - f.lastModified() < 24 * 3600_000L) return
        val icon = last?.person?.icon?.loadDrawable(c)
            ?: n.getLargeIcon()?.loadDrawable(c)
            ?: return
        val bmp = runCatching { icon.toBitmap(192, 192) }.getOrNull() ?: return
        runCatching { f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 95, it) } }
        cache[key] = bmp
    }

    /** תמונת הפרופיל מוואטסאפ של המספר הזה (אם שמרנו) */
    fun photo(c: Context, number: String?): Bitmap? {
        val key = CallerId.key(number) ?: return null
        return cache.getOrPut(key) {
            file(c, key).takeIf { it.exists() }?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        }
    }

    /** השם שהוא מופיע איתו בוואטסאפ (אם שמרנו) */
    fun name(c: Context, number: String?): String? {
        val key = CallerId.key(number) ?: return null
        return sp(c).getString("n:$key", null)?.takeIf { it.isNotBlank() }
    }
}
