package com.kitzurim.app

import android.app.Notification
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.service.notification.StatusBarNotification

/**
 * קוד אימות: כשמגיעה הודעה עם קוד (SMS, מייל, וואטסאפ), מעתיקים אותו לבד ומראים אותו בגלולה,
 * כך שאפשר ישר להדביק בלי לפתוח את ההודעה.
 */
object Codes {
    private val words = Regex(
        "(קוד|אימות|סיסמ|code|verif|otp|passcode|password|pin|token|כניסה|login)",
        RegexOption.IGNORE_CASE,
    )
    // 4 עד 8 ספרות (גם עם רווח או מקף באמצע, כמו 123-456), לא חלק ממספר ארוך יותר
    private val number = Regex("""(?<![\d+])(\d{3}[ -]\d{3}|\d{4,8})(?!\d)""")
    private val shown = HashMap<String, String>()

    /** מחזיר את הקוד אם ההתראה נראית כמו קוד אימות */
    fun find(sbn: StatusBarNotification): String? {
        if (sbn.notification.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        val e = sbn.notification.extras
        val text = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        if (!words.containsMatchIn(text)) return null
        // מספר שנראה כמו שנה (2024) או שעה לא נחשב, אלא אם אין אחר
        val all = number.findAll(text).map { it.value.replace(" ", "").replace("-", "") }.toList()
        if (all.isEmpty()) return null
        return all.firstOrNull { !(it.length == 4 && it.startsWith("20")) } ?: all.first()
    }

    /** קוד חדש: מעתיקים ומראים. אותו קוד מאותה התראה לא מראים פעמיים */
    fun handle(c: Context, sbn: StatusBarNotification): Boolean {
        if (!NotchPrefs.pillCodes(c)) return false
        val code = find(sbn) ?: return false
        if (shown[sbn.key] == code) return true
        shown[sbn.key] = code
        copy(c, code)
        LivePill.showCode(code)
        return true
    }

    fun copy(c: Context, code: String) {
        runCatching {
            c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("קוד", code))
        }
    }
}
