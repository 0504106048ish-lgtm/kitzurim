package com.kitzurim.app

import android.app.NotificationManager
import android.content.Context
import android.media.AudioManager
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * חסימת שיחות וואטסאפ בשקט: שיחה ממי שחסמת לא מצלצלת אצלך (הצלצול והרטט מושתקים עד שהשיחה נגמרת),
 * והוא רואה שהשיחה ממשיכה לצלצל עד שנגמרת לבד, כאילו לא ענית. לא יודע שנחסם.
 * כבוי כברירת מחדל: כשכבוי, כל השיחות מגיעות כרגיל.
 */
object WaBlock {
    private fun sp(c: Context) = c.getSharedPreferences("wa_block", Context.MODE_PRIVATE)

    fun on(c: Context) = sp(c).getBoolean("on", false)
    fun setOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("on", v).apply()
    /** מספרים שלא שמורים אצלך */
    fun unknown(c: Context) = sp(c).getBoolean("unknown", true)
    fun setUnknown(c: Context, v: Boolean) = sp(c).edit().putBoolean("unknown", v).apply()
    /** מספרים ש-Truecaller סימנה כספאם */
    fun spam(c: Context) = sp(c).getBoolean("spam", true)
    fun setSpam(c: Context, v: Boolean) = sp(c).edit().putBoolean("spam", v).apply()
    /** אנשי קשר שמורים (כבוי כברירת מחדל: מי ששמור אצלך מגיע כרגיל) */
    fun contacts(c: Context) = sp(c).getBoolean("contacts", false)
    fun setContacts(c: Context, v: Boolean) = sp(c).edit().putBoolean("contacts", v).apply()

    /** אנשים שחסמת (השם כמו שוואטסאפ מראה אותו, או המספר) */
    fun list(c: Context): Set<String> = sp(c).getStringSet("list", emptySet()) ?: emptySet()
    fun add(c: Context, who: String) = sp(c).edit().putStringSet("list", list(c) + who.trim()).apply()
    fun remove(c: Context, who: String) = sp(c).edit().putStringSet("list", list(c) - who).apply()

    /** כמה שיחות נחסמו (בשביל ההגדרות: "נחסמו 3 שיחות") */
    fun count(c: Context) = sp(c).getInt("count", 0)

    fun isWhatsApp(pkg: String) = pkg == "com.whatsapp" || pkg == "com.whatsapp.w4b"

    /** השם שבהתראה הוא בעצם מספר (כך וואטסאפ מראה מי שלא שמור אצלך) */
    private fun looksLikeNumber(s: String) = s.count { it.isDigit() } >= 7 && s.none { it.isLetter() }

    /** מי שאישרת ("לאפשר"): לא נחסם גם אם הוא לא שמור אצלך */
    fun allowed(c: Context): Set<String> = sp(c).getStringSet("allow", emptySet()) ?: emptySet()
    fun allow(c: Context, who: String) {
        sp(c).edit().putStringSet("allow", allowed(c) + who.trim()).putStringSet("list", list(c) - who.trim()).apply()
    }

    /** כל השיחות שנחסמו (הכי חדשה ראשונה): זמן ומי */
    fun log(c: Context): List<Pair<Long, String>> =
        (sp(c).getString("log", "") ?: "").split("\n").mapNotNull { line ->
            val t = line.substringBefore('|').toLongOrNull() ?: return@mapNotNull null
            t to line.substringAfter('|')
        }

    private fun addLog(c: Context, who: String) {
        val lines = listOf("${System.currentTimeMillis()}|$who") + log(c).map { "${it.first}|${it.second}" }
        sp(c).edit().putString("log", lines.take(50).joinToString("\n")).apply()
    }

    fun clearLog(c: Context) = sp(c).edit().remove("log").putInt("count", 0).apply()

    fun shouldBlock(c: Context, name: String): Boolean {
        if (!on(c)) return false
        val who = name.trim()
        if (who in allowed(c)) return false
        if (who in list(c)) return true
        val digits = who.filter { it.isDigit() }
        if (digits.length >= 7 && list(c).any { it.filter { ch -> ch.isDigit() }.takeLast(9) == digits.takeLast(9) }) return true
        if (looksLikeNumber(who)) {
            if (spam(c) && CallerId.isSpam(c, who)) return true
            if (unknown(c)) return true
        } else if (who.isNotEmpty() && contacts(c)) return true
        return false
    }

    // ---------- השתקה בזמן השיחה החסומה ----------
    private var silencedKey: String? = null
    private var oldMode: Int? = null
    private var oldRing: Int? = null
    private var silencedAt = 0L
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** שיחה חסומה הגיעה: משתיקים צלצול ורטט, ומסתירים את החלון הקופץ של וואטסאפ */
    fun silence(l: NotificationListenerService, sbn: StatusBarNotification, who: String = "") {
        if (silencedKey == sbn.key) return
        val am = l.getSystemService(AudioManager::class.java) ?: return
        val nm = l.getSystemService(NotificationManager::class.java)
        silencedKey = sbn.key
        silencedAt = android.os.SystemClock.elapsedRealtime()
        oldMode = am.ringerMode
        oldRing = am.getStreamVolume(AudioManager.STREAM_RING)
        runCatching {
            if (nm?.isNotificationPolicyAccessGranted == true) am.ringerMode = AudioManager.RINGER_MODE_SILENT
            else am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_MUTE, 0)
        }
        // החלון הקופץ של השיחה: נעלם (חוזר לבד אם השיחה עוד נמשכת אחרי דקה)
        runCatching { l.snoozeNotification(sbn.key, 60_000L) }
        sp(l).edit().putInt("count", count(l) + 1).apply()
        if (who.isNotBlank()) addLog(l, who)
        ActivityLog.add(l, "🚫", "שיחת וואטסאפ חסומה (בשקט)")
        // ליתר ביטחון: שיחת וואטסאפ לא מצלצלת יותר מדקה, אז אחרי 70 שניות הצלצול חוזר בכל מקרה
        val k = sbn.key
        main.postDelayed({ restore(l.applicationContext, k) }, 70_000L)
    }

    /** ההתראה של השיחה החסומה ירדה: אם זו ההסתרה שלנו (מיד אחרי ההשתקה) לא עושים כלום, אחרת השיחה נגמרה */
    fun ended(c: Context, key: String) {
        if (key != silencedKey) return
        if (android.os.SystemClock.elapsedRealtime() - silencedAt < 2000) return
        restore(c, key)
    }

    /** מחזירים את הצלצול כמו שהיה */
    private fun restore(c: Context, key: String) {
        if (key != silencedKey) return
        silencedKey = null
        val am = c.getSystemService(AudioManager::class.java) ?: return
        runCatching {
            oldMode?.let { if (am.ringerMode != it) am.ringerMode = it }
            am.adjustStreamVolume(AudioManager.STREAM_RING, AudioManager.ADJUST_UNMUTE, 0)
            oldRing?.let { am.setStreamVolume(AudioManager.STREAM_RING, it, 0) }
        }
        oldMode = null
        oldRing = null
    }
}
