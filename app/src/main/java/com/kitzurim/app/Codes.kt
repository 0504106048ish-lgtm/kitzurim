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
        // רק מילים שבאמת מופיעות בהודעת קוד (לא "כניסה" או "pin" שמופיעים גם בהודעות רגילות)
        """(קוד|אימות|\bcode\b|verification|\botp\b|passcode|one.time)""",
        RegexOption.IGNORE_CASE,
    )
    // 4 עד 8 ספרות (גם עם רווח או מקף באמצע, כמו 123-456), לא חלק ממספר ארוך יותר
    // ולא חלק ממספר טלפון כמו 052-7592920 (ספרות לפני או אחרי, גם עם מקף או רווח באמצע)
    private val number = Regex("""(?<![\d+])(?<!\d[ -])(\d{3}[ -]\d{3}|\d{4,8})(?![ -]?\d)""")
    private val shown = HashMap<String, String>()

    /** מחזיר את הקוד אם ההתראה נראית כמו קוד אימות */
    fun find(sbn: StatusBarNotification): String? {
        val n = sbn.notification
        if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return null
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return null
        val e = n.extras
        // הודעה בקבוצה (וואטסאפ וכו'): אנשים כותבים שם "קוד" ומספרים, זה לא קוד אימות
        if (e.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return null
        val text = listOfNotNull(
            e.getCharSequence(Notification.EXTRA_TITLE),
            e.getCharSequence(Notification.EXTRA_TEXT),
            e.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        val keys = words.findAll(text).map { it.range }.toList()
        if (keys.isEmpty()) return null
        // רק מספר שקרוב למילה "קוד"/"code" (עד 40 תווים ממנה), ולא סתם מספר במקום אחר בהודעה
        fun near(r: IntRange) = keys.any { k ->
            val gap = if (r.first > k.last) r.first - k.last else if (k.first > r.last) k.first - r.last else 0
            gap <= 40
        }
        val all = number.findAll(text).filter { near(it.range) }.map { it.value.replace(" ", "").replace("-", "") }.toList()
        if (all.isEmpty()) return null
        // מספר שנראה כמו שנה (2024) לא נחשב, אלא אם אין אחר. קודם קוד באורך הרגיל (4 עד 6)
        val ok = all.filter { !(it.length == 4 && it.startsWith("20")) }
        return ok.firstOrNull { it.length in 4..6 } ?: ok.firstOrNull() ?: all.first()
    }

    /** קוד חדש: מעתיקים ומראים. אותו קוד מאותה התראה לא מראים פעמיים */
    fun handle(c: Context, sbn: StatusBarNotification): Boolean {
        if (!NotchPrefs.pillCodes(c)) return false
        val code = find(sbn) ?: return false
        if (shown[sbn.key] == code) return true
        shown[sbn.key] = code
        // רק הודעה שהגיעה עכשיו. התראה ישנה שמתפרסמת שוב (מטען, הפעלה מחדש, עדכון של האפליקציה)
        // לא מעתיקה שוב את הקוד שלה, וכך לא מועתקים "סתם" מספרים בלי שהגיעה הודעה
        val n = sbn.notification
        val sent = if (n.`when` > 0) n.`when` else sbn.postTime
        if (System.currentTimeMillis() - sent > 90_000) return true
        // אותו קוד כבר הועתק (גם לפני שהטלפון/קיצורים הופעלו מחדש): לא שוב
        val sp = c.getSharedPreferences("codes_seen", Context.MODE_PRIVATE)
        val seen = "${sbn.packageName}|$code"
        if (System.currentTimeMillis() - sp.getLong(seen, 0L) < 30 * 60_000L) return true
        val ed = sp.edit()
        // ניקוי: שומרים רק את האחרונים
        sp.all.entries.sortedByDescending { it.value as? Long ?: 0L }.drop(30).forEach { ed.remove(it.key) }
        ed.putLong(seen, System.currentTimeMillis()).apply()
        copy(c, code)
        ClipWatch.ownCopy()
        LivePill.showCode(code)
        return true
    }

    fun copy(c: Context, code: String) {
        runCatching {
            c.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("קוד", code))
        }
    }
}
