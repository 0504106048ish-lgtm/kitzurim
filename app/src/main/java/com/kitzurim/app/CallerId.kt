package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.service.notification.StatusBarNotification
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * זיהוי מתקשר בעזרת Truecaller: כש-Truecaller מראה את החלון שלה בזמן שיחה (או התראה על שיחה),
 * קיצורים קוראת משם את השם של מספר שלא שמור אצלך, מציגה אותו במסך השיחה ובגלולה,
 * וזוכרת אותו בשביל רשימת השיחות והחיפוש. שום דבר לא נשלח לשום מקום.
 */
object CallerId {
    private const val PREFS = "caller_id"
    private const val TC = "com.truecaller"

    /** המספר האחרון שהיה בשיחה, ומתי (כדי לשייך שם גם קצת אחרי שהשיחה נגמרה) */
    @Volatile private var lastNumber: String? = null
    @Volatile private var lastAt = 0L
    private var lastScan = 0L

    /** מילים של כפתורים וכותרות ב-Truecaller, שהן לא שם */
    private val notNames = listOf(
        "truecaller", "spam", "ספאם", "call", "שיחה", "message", "הודעה", "save", "שמור", "block", "חסום",
        "close", "סגור", "mobile", "נייד", "israel", "ישראל", "unknown", "לא ידוע", "premium", "פרימיום",
        "identified", "זוהה", "calling", "מתקשר", "incoming", "נכנסת", "missed", "לא נענתה", "edit", "ערוך",
        "search", "חפש", "whatsapp", "וואטסאפ", "sms", "view profile", "פרופיל", "suggest", "הצע", "ad", "מודעה",
        "partner", "cellcom", "סלקום", "pelephone", "פלאפון", "partner", "פרטנר", "hot mobile", "golan", "גולן",
        "rami levy", "רמי לוי", "012", "019", "we4g", "xphone", "בזק", "bezeq", "flash call", "now", "עכשיו",
    )

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 9 הספרות האחרונות: אותו מספר גם עם 972+ וגם עם 0 בהתחלה */
    fun key(number: String?): String? {
        val d = number?.filter { it.isDigit() } ?: return null
        return if (d.length < 7) null else d.takeLast(9)
    }

    /** השם ש-Truecaller נתנה למספר הזה (אם היה) */
    fun nameFor(c: Context, number: String?): String? {
        val k = key(number) ?: return null
        return runCatching { sp(c).getString(k, null) }.getOrNull()
    }

    /** שיחה התחילה: זוכרים את המספר שלה */
    fun callSeen(number: String?) {
        if (key(number) == null) return
        lastNumber = number
        lastAt = System.currentTimeMillis()
    }

    /** המספר שאנחנו מחפשים לו שם: שיחה פעילה עכשיו, או עד 2 דקות אחרי */
    private fun target(): String? {
        Calls.main()?.let { Calls.number(it) }?.let { if (key(it) != null) { callSeen(it); return it } }
        return lastNumber?.takeIf { System.currentTimeMillis() - lastAt < 120_000 }
    }

    private fun save(c: Context, number: String, name: String) {
        val k = key(number) ?: return
        // מי ששמור אצלך באנשי הקשר: השם שלך קובע
        if (Calls.lookup(c, number)?.first != null) return
        if (nameFor(c, number) == name) return
        runCatching { sp(c).edit().putString(k, name).apply() }
        Calls.changed()
        runCatching { CallNotif.update(c) }
    }

    /** האם הטקסט נראה כמו שם של אדם או עסק */
    private fun looksLikeName(t: String): Boolean {
        val s = t.trim()
        if (s.length !in 2..40) return false
        if (s.count { it.isDigit() } > 2) return false
        if (!s.any { it.isLetter() }) return false
        if (s.contains("%") || s.contains("•") && s.length < 4) return false
        val low = s.lowercase()
        if (notNames.any { low == it || low.startsWith("$it ") || low.endsWith(" $it") }) return false
        return true
    }

    // ── מהחלון של Truecaller (בזמן צלצול ושיחה) ──

    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        if (!pkg.startsWith(TC)) return
        val number = target() ?: return
        val now = System.currentTimeMillis()
        if (now - lastScan < 400) return
        lastScan = now
        val roots = ArrayList<AccessibilityNodeInfo>()
        e.source?.let { roots += it }
        runCatching {
            svc.windows.forEach { w -> w.root?.takeIf { it.packageName?.toString()?.startsWith(TC) == true }?.let { roots += it } }
        }
        for (r in roots) {
            val name = readName(r, number) ?: continue
            save(svc, number, name)
            return
        }
    }

    /** טקסטים מהחלון, לפי הסדר מלמעלה למטה (עם המזהה של כל אחד) */
    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Pair<String, String>>, depth: Int = 0) {
        if (n == null || depth > 25 || out.size > 60) return
        val t = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() && n.childCount == 0 }
        if (t != null) out += (n.viewIdResourceName ?: "") to t
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    private fun readName(root: AccessibilityNodeInfo, number: String): String? {
        // לפעמים האירוע מגיע מתוך החלון: עולים לראש שלו
        var top = root
        repeat(30) { top = top.parent ?: return@repeat }
        val texts = ArrayList<Pair<String, String>>()
        collect(top, texts)
        if (texts.isEmpty()) return null
        val k = key(number) ?: return null
        val hasNumber = texts.any { key(it.second) == k }
        // קודם: שדה שהמזהה שלו אומר "שם"/"כותרת"
        texts.firstOrNull { (id, t) ->
            val i = id.lowercase()
            (i.contains("name") || i.contains("title")) && !i.contains("number") && looksLikeName(t)
        }?.let { return it.second.trim() }
        // אחרת: הטקסט הראשון שנראה כמו שם, רק אם המספר של השיחה מופיע בחלון
        if (!hasNumber) return null
        return texts.firstOrNull { looksLikeName(it.second) }?.second?.trim()
    }

    // ── מההתראות של Truecaller (שיחה שלא נענתה, שיחה נכנסת) ──

    fun onNotification(c: Context, sbn: StatusBarNotification) {
        if (!sbn.packageName.startsWith(TC)) return
        val x = sbn.notification?.extras ?: return
        val title = x.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: return
        val text = listOfNotNull(
            x.getCharSequence(Notification.EXTRA_TEXT), x.getCharSequence(Notification.EXTRA_SUB_TEXT),
            x.getCharSequence(Notification.EXTRA_BIG_TEXT),
        ).joinToString(" ")
        // המספר מופיע בהתראה: זה הכי בטוח
        val num = Regex("[+\\d][\\d\\- ]{6,}\\d").find(text)?.value
            ?: target()?.takeIf { text.contains("שיחה") || text.lowercase().contains("call") }
            ?: return
        val clean = title
            .removePrefix("שיחה שלא נענתה מ").removePrefix("שיחה שלא נענתה מאת ")
            .removePrefix("Missed call from ").removePrefix("Incoming call from ").trim()
        if (looksLikeName(clean)) save(c, num, clean)
    }
}
