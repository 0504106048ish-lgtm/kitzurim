package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification
import android.telecom.Call
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat

/**
 * זיהוי מתקשר בעזרת Truecaller: כש-Truecaller מראה את החלון שלה בזמן שיחה (או התראה על שיחה),
 * קיצורים קוראת משם את השם של מספר שלא שמור אצלך, מציגה אותו במסך השיחה ובגלולה,
 * וזוכרת אותו בשביל רשימת השיחות והחיפוש. שום דבר לא נשלח לשום מקום.
 * בנוסף: שיחת ספאם (לפי Truecaller) משתתקת לבד, ו"זהה את כולם" ממלא שמות למספרים ישנים.
 */
object CallerId {
    private const val PREFS = "caller_id"
    private const val TC = "com.truecaller"
    private const val ME = "com.nfo.me.android"

    /** אפליקציות זיהוי מתקשר שקיצורים יודעת לקרוא מהן: Truecaller ו-Me */
    private val APPS = listOf(TC to "Truecaller", ME to "Me")
    private fun srcOf(pkg: String?) = APPS.firstOrNull { pkg?.startsWith(it.first) == true }

    /** מאיזו אפליקציה הגיע השם שקוראים עכשיו */
    @Volatile private var curSrc = "Truecaller"

    /** מאיזו אפליקציה הגיע השם (או הספאם) של המספר */
    fun sourceFor(c: Context, number: String?): String {
        val k = key(number) ?: return "Truecaller"
        return runCatching { sp(c).getString("src:$k", null) }.getOrNull() ?: "Truecaller"
    }

    /** האפליקציות שמותקנות בטלפון (Truecaller קודם, כי רק בה אפשר לחפש מספר ישירות) */
    private fun installedApps(c: Context) = APPS.filter { (p, _) -> runCatching { c.packageManager.getPackageInfo(p, 0); true }.getOrDefault(false) }

    /** השם של אפליקציית הזיהוי שיש לך (בשביל הכפתורים) */
    fun appName(c: Context) = installedApps(c).joinToString(" / ") { it.second }.ifBlank { "Truecaller" }

    /** המספר האחרון שהיה בשיחה, ומתי (כדי לשייך שם גם קצת אחרי שהשיחה נגמרה) */
    @Volatile private var lastNumber: String? = null
    @Volatile private var lastAt = 0L
    private var lastScan = 0L

    /** "זהה את כולם": המספר שמחפשים עכשיו ב-Truecaller */
    @Volatile private var lookupNumber: String? = null
    @Volatile private var lookupAt = 0L
    @Volatile private var lookupFound = false

    /** "זהה את כולם" רץ עכשיו (הכפתור מראה התקדמות) */
    var batchText by mutableStateOf<String?>(null)

    /** מילים של כפתורים וכותרות ב-Truecaller, שהן לא שם */
    private val notNames = listOf(
        "truecaller", "spam", "ספאם", "call", "שיחה", "message", "הודעה", "save", "שמור", "block", "חסום",
        "close", "סגור", "mobile", "נייד", "israel", "ישראל", "unknown", "לא ידוע", "premium", "פרימיום",
        "identified", "זוהה", "calling", "מתקשר", "incoming", "נכנסת", "missed", "לא נענתה", "edit", "ערוך",
        "search", "חפש", "חיפוש", "whatsapp", "וואטסאפ", "sms", "view profile", "פרופיל", "suggest", "הצע", "ad", "מודעה",
        "cellcom", "סלקום", "pelephone", "פלאפון", "partner", "פרטנר", "hot mobile", "golan", "גולן",
        "rami levy", "רמי לוי", "we4g", "xphone", "בזק", "bezeq", "flash call", "now", "עכשיו",
        "contacts", "אנשי קשר", "recents", "אחרונות", "history", "היסטוריה", "details", "פרטים", "copy", "העתק",
        "share", "שתף", "invite", "הזמן", "back", "חזור", "more", "עוד", "settings", "הגדרות",
        // כפתורים בכרטיס של Truecaller ו-Me, שנקראו בטעות כשם
        "חייג", "חיוג", "dial", "התקשר", "התקשרות", "call back", "חזור אליו", "שלח הודעה", "send message",
        "chat", "צ'אט", "צאט", "voice", "קולי", "video", "וידאו", "שיחת וידאו", "answer", "ענה", "decline", "דחה",
        "reply", "השב", "תשובה", "report", "דווח", "not spam", "לא ספאם", "add contact", "הוסף", "הוסף איש קשר",
        "contact", "איש קשר", "remind", "תזכורת", "pay", "תשלום", "options", "אפשרויות", "open", "פתח", "notes", "הערות",
        "הוספה", "add to contacts", "שמירה", "חסימה", "דיווח", "שיתוף", "העתקה", "עריכה", "שליחת הודעה", "הצגת פרופיל",
        "שיחה חוזרת", "פרטי איש קשר", "צור איש קשר", "create contact", "new contact", "איש קשר חדש",
    )

    /** טקסטים שמסמנים ספאם, וכפתורים שמכילים את המילה אבל הם לא סימון */
    private val spamWords = listOf("spam", "ספאם", "הונאה", "fraud", "scam", "telemarketing", "טלמרקטינג")
    private val spamButtons = listOf(
        "report", "not spam", "דווח", "דיווח", "לא ספאם", "סמן", "mark", "block", "חסום", "protection", "הגנ", "no spam", "אין",
        "blocked", "נחסמ", "filter", "סינון", "settings", "הגדרות",
    )

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 9 הספרות האחרונות: אותו מספר גם עם 972+ וגם עם 0 בהתחלה */
    fun key(number: String?): String? {
        val raw = number?.trim() ?: return null
        val d = raw.filter { it.isDigit() }
        if (d.length >= 7) return d.takeLast(9)
        // מספרים מקוצרים (כמו *2108 או 1222): המספר כמו שהוא, עם הכוכבית
        if (d.length in 3..6) return (if (raw.startsWith("*")) "*" else "") + d
        return null
    }

    /** השם ש-Truecaller נתנה למספר הזה (אם היה) */
    fun nameFor(c: Context, number: String?): String? {
        val k = key(number) ?: return null
        // שם ישן שנשמר בטעות (כמו "חייג") לא מוצג, וכך המספר יזוהה מחדש
        return runCatching { sp(c).getString(k, null) }.getOrNull()
            ?.takeIf { looksLikeName(it) && !sp(c).getBoolean("bad:$it", false) }
    }

    /** Truecaller סימנה את המספר כספאם */
    fun isSpam(c: Context, number: String?): Boolean {
        val k = key(number) ?: return false
        return runCatching { sp(c).getBoolean("spam2:$k", false) }.getOrDefault(false)
    }

    fun installed(c: Context) = installedApps(c).isNotEmpty()

    /** שיחה התחילה: זוכרים את המספר שלה */
    fun callSeen(number: String?) {
        if (key(number) == null) return
        lastNumber = number
        lastAt = System.currentTimeMillis()
    }

    /** המספר שאנחנו מחפשים לו שם: שיחה פעילה עכשיו, או עד 2 דקות אחרי */
    private fun target(): String? {
        Calls.main()?.let { Calls.number(it) }?.let { if (key(it) != null) { callSeen(it); return it } }
        return lastNumber?.takeIf { System.currentTimeMillis() - lastAt < 30_000 }
    }

    private fun save(c: Context, number: String, name: String) {
        val k = key(number) ?: return
        // מי ששמור אצלך באנשי הקשר: השם שלך קובע
        if (Calls.lookup(c, number)?.first != null) return
        if (nameFor(c, number) == name) return
        // לומד לבד: אותו "שם" בדיוק לשני מספרים אחרים כבר = כנראה כפתור או כותרת, לא שם.
        // מסמנים אותו ככזה, ומוחקים אותו מכל המספרים (והם יזוהו מחדש)
        val p = sp(c)
        if (p.getBoolean("bad:$name", false)) return
        val same = runCatching { p.all.filter { (kk, vv) -> !kk.contains(':') && kk != k && vv == name }.keys }.getOrDefault(emptySet())
        if (same.size >= 2) {
            runCatching { p.edit().apply { putBoolean("bad:$name", true); same.forEach { remove(it) } }.apply() }
            diag(c, number, "🚫 \"$name\" מופיע לכמה מספרים, כנראה כפתור ולא שם")
            Calls.changed()
            return
        }
        runCatching { p.edit().putString(k, name).putString("src:$k", curSrc).apply() }
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
        // טקסטים של כפתורים שמופיעים בכל מקום במשפט ("הוספה לאנשי קשר", "Add to contacts")
        if (listOf("אנשי קשר", "לאנשי", "contacts", "truecaller", "פרימיום", "premium").any { low.contains(it) }) return false
        if (spamWords.any { low.contains(it) }) return false
        return true
    }

    // ── מהחלון של Truecaller (בזמן צלצול ושיחה, ובחיפוש של "זהה את כולם") ──

    fun onEvent(svc: AccessibilityService, e: AccessibilityEvent) {
        val pkg = e.packageName?.toString() ?: return
        curSrc = srcOf(pkg)?.second ?: return
        val now = System.currentTimeMillis()
        val lookup = lookupNumber?.takeIf { now - lookupAt < 8000 }
        lookup ?: target() ?: return
        lastSource = e.source
        // החלון מתעדכן כמה פעמים ברצף (קודם "מחפש…" ורק אחר כך השם): סורקים רגע אחרי העדכון האחרון,
        // ועוד פעם אחרי שנייה, כדי לא לפספס שם שמגיע מאוחר
        scanHandler.removeCallbacksAndMessages(null)
        scanHandler.postDelayed({ scan(svc) }, 250)
        scanHandler.postDelayed({ scan(svc) }, 1200)
    }

    private val scanHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastSource: AccessibilityNodeInfo? = null

    /** מה כבר נרשם ב"מה קרה היום" על השיחה הזו (כדי לא לרשום שוב ושוב) */
    private var diagFor = ""

    private fun scan(svc: AccessibilityService) {
        val now = System.currentTimeMillis()
        val lookup = lookupNumber?.takeIf { now - lookupAt < 8000 }
        val number = lookup ?: target() ?: return
        lastScan = now
        val roots = ArrayList<AccessibilityNodeInfo>()
        val inCall = Calls.main() != null
        // בזמן שיחה קוראים רק את החלון הקטן שקופץ (לא את האפליקציה עצמה, שיש בה רשימות שמות ולשונית "ספאם").
        // ב"זהה את כולם" קוראים את דף החיפוש המלא
        if (lookup != null || inCall) lastSource?.let { runCatching { it.refresh() }; roots += it }
        runCatching {
            val screenH = svc.resources.displayMetrics.heightPixels
            val b = android.graphics.Rect()
            svc.windows.forEach { w ->
                val r = w.root ?: return@forEach
                if (srcOf(r.packageName?.toString())?.second != curSrc) return@forEach
                w.getBoundsInScreen(b)
                val popup = w.type != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || b.height() < screenH * 0.7f
                // מסך מלא של זיהוי מתקשר בזמן צלצול גם נחשב. אחרי השיחה: רק חלון קטן
                if (lookup != null || popup || Calls.list.any { it.state == Call.STATE_RINGING }) roots += r
            }
        }
        val seen = ArrayList<String>()
        for (r in roots) {
            val texts = texts(r)
            if (texts.isEmpty()) continue
            seen += texts.take(10).map { it.second.replace('\n', ' ').take(30) }
            if (lookup == null) checkSpam(svc, number, texts)
            val name = readName(texts, number, strict = lookup != null) ?: continue
            if (lookup != null) lookupFound = true
            save(svc, number, name)
            if (lookup == null) diag(svc, number, "✓ $curSrc זיהתה: $name")
            return
        }
        // לא נמצא שם בשיחה: רושמים ב"מה קרה היום" מה קיצורים ראתה בחלון, כדי שאפשר יהיה לשפר
        if (lookup == null && inCall) diag(svc, number, "$curSrc: לא נמצא שם. בחלון: " + seen.distinct().take(10).joinToString(" | ").ifBlank { "(ריק)" })
    }

    private fun diag(c: Context, number: String, text: String) {
        val k = (key(number) ?: number) + "|" + curSrc + "|" + text.startsWith("✓")
        if (diagFor == k) return
        diagFor = k
        runCatching { ActivityLog.add(c, "🔎", text.take(300)) }
    }

    /** כל הטקסטים בחלון, לפי הסדר מלמעלה למטה (עם המזהה של כל אחד) */
    private fun texts(root: AccessibilityNodeInfo): List<Pair<String, String>> {
        // לפעמים האירוע מגיע מתוך החלון: עולים לראש שלו
        var top = root
        repeat(30) { top = top.parent ?: return@repeat }
        val out = ArrayList<Pair<String, String>>()
        collect(top, out)
        return out
    }

    private fun collect(n: AccessibilityNodeInfo?, out: MutableList<Pair<String, String>>, depth: Int = 0) {
        if (n == null || depth > 25 || out.size > 80) return
        val t = n.text?.toString()?.takeIf { it.isNotBlank() } ?: n.contentDescription?.toString()?.takeIf { it.isNotBlank() && n.childCount == 0 }
        if (t != null) out += (n.viewIdResourceName ?: "") to t
        for (i in 0 until n.childCount) collect(n.getChild(i), out, depth + 1)
    }

    /**
     * השם מתוך החלון. קודם שדה שהמזהה שלו "שם"/"כותרת".
     * אחרת הטקסט שנראה כמו שם, רק אם המספר שלנו מופיע בחלון. בחיפוש (strict) רק טקסט צמוד למספר,
     * כדי לא לקחת בטעות שם של מישהו אחר מהרשימה של Truecaller
     */
    private fun readName(all: List<Pair<String, String>>, number: String, strict: Boolean): String? {
        val k = key(number) ?: return null
        // כפתורים (חייג, הודעה, צ'אט...) הם אף פעם לא השם
        val texts = all.filter { (id, _) ->
            val i = id.lowercase()
            listOf("button", "btn", "action", "cta", "chip", "fab").none { i.contains(it) }
        }
        val at = texts.indexOfFirst { key(it.second) == k }
        texts.firstOrNull { (id, t) ->
            val i = id.lowercase()
            (i.contains("name") || i.contains("title")) && !i.contains("number") && !i.contains("toolbar") && looksLikeName(t)
        }?.let { if (!strict || at >= 0) return it.second.trim() }
        if (at < 0) return null
        if (!strict) return texts.firstOrNull { looksLikeName(it.second) }?.second?.trim()
        // צמוד למספר: עד 3 טקסטים לפניו או אחריו (קודם לפני, כי בדרך כלל השם מעל המספר)
        for (d in 1..3) {
            texts.getOrNull(at - d)?.second?.takeIf { looksLikeName(it) }?.let { return it.trim() }
            texts.getOrNull(at + d)?.second?.takeIf { looksLikeName(it) }?.let { return it.trim() }
        }
        return null
    }

    /** Truecaller אומרת "ספאם": משתיקים את הצלצול מיד ומסמנים את המספר */
    private fun checkSpam(svc: AccessibilityService, number: String, texts: List<Pair<String, String>>) {
        val spam = texts.any { (_, t) ->
            val low = t.lowercase()
            spamWords.any { low.contains(it) } && spamButtons.none { low.contains(it) }
        }
        if (!spam) return
        val k = key(number) ?: return
        // מי ששמור אצלך לא ספאם
        if (Calls.lookup(svc, number)?.first != null) return
        val first = !isSpam(svc, number)
        // ספאם נקבע רק בשיחה נכנסת שמצלצלת עכשיו (לא בשיחה שאתה מתקשר, ולא אחרי השיחה)
        val ringing = Calls.list.any { it.state == Call.STATE_RINGING && key(Calls.number(it)) == k }
        if (!ringing) return
        runCatching { sp(svc).edit().putBoolean("spam2:$k", true).putString("src:$k", curSrc).apply() }
        if (!Calls.silenced && NotchPrefs.spamSilence(svc)) {
            Calls.silence(svc)
            Actions.toast(svc, "🚫 ספאם לפי $curSrc, הצלצול הושתק")
        }
        if (first) Calls.changed()
    }

    // ── "זהה את כולם": מחפשים כל מספר לא מזוהה ב-Truecaller, אחד אחרי השני ──

    /** פותח את Truecaller על המספר: קודם דף החיפוש, ואם אין אז המקלדת שלה עם המספר */
    private fun open(c: Context, number: String): Boolean {
        val d = number.filter { it.isDigit() }
        val short = d.length < 7 && number.trim().startsWith("*")
        val local = if (short) Uri.encode("*$d") else if (d.startsWith("972")) "0" + d.drop(3) else d
        // Truecaller: דף החיפוש או המקלדת שלה. Me: פותחים אותה על המספר (אם היא מקבלת)
        val tries = listOf(
            Intent(Intent.ACTION_VIEW, Uri.parse("https://www.truecaller.com/search/il/$local")),
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$local")),
        ).map { it.setPackage(TC) } + listOf(
            Intent(Intent.ACTION_DIAL, Uri.parse("tel:$local")),
            Intent(Intent.ACTION_VIEW, Uri.parse("tel:$local")),
        ).map { it.setPackage(ME) }
        tries.forEach { it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
        for (i in tries) {
            if (i.resolveActivity(c.packageManager) == null) continue
            if (runCatching { c.startActivity(i) }.isSuccess) return true
        }
        return false
    }

    fun identifyAll(c: Context, numbers: List<String>) {
        if (batchText != null) return
        if (!installed(c)) { Actions.toast(c, "צריך Truecaller או Me"); return }
        if (!AutoSend.allowed(c)) { Actions.toast(c, "צריך להדליק את שירות הנגישות של קיצורים"); return }
        val list = numbers.filter { key(it) != null }.distinctBy { key(it) }.take(30)
        if (list.isEmpty()) return
        val app = c.applicationContext
        val h = Handler(Looper.getMainLooper())
        var i = 0
        var found = 0
        Actions.toast(app, "🔎 מזהה ${list.size} מספרים ב-${appName(app)}. לא לגעת רגע…")
        fun finish() {
            lookupNumber = null
            batchText = null
            runCatching {
                app.startActivity(
                    Intent(app, DialActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
                )
            }
            Actions.toast(app, if (found > 0) "✓ זוהו $found מתוך ${list.size}" else "${appName(app)} לא מצאה שמות")
        }
        fun next() {
            if (i > 0 && lookupFound) found++
            if (i >= list.size) { finish(); return }
            val n = list[i++]
            batchText = "מזהה $i/${list.size}…"
            lookupNumber = n
            lookupFound = false
            lookupAt = System.currentTimeMillis()
            if (!open(app, n)) { found = 0; i = list.size; finish(); Actions.toast(app, "לא הצלחתי לפתוח את ${appName(app)}"); return }
            var waited = 0
            h.postDelayed(object : Runnable {
                override fun run() {
                    waited += 300
                    // מצאנו: עוד רגע קטן ועוברים הלאה. לא מצאנו אחרי 5 שניות: מוותרים על המספר הזה
                    if (lookupFound || waited >= 5000) h.postDelayed({ next() }, if (lookupFound) 250L else 0L)
                    else h.postDelayed(this, 300)
                }
            }, 300)
        }
        next()
    }

    // ── מההתראות של Truecaller (שיחה שלא נענתה, שיחה נכנסת) ──

    fun onNotification(c: Context, sbn: StatusBarNotification) {
        curSrc = srcOf(sbn.packageName)?.second ?: return
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

/**
 * "על מה דיברנו": ההודעה האחרונה שכל אחד שלח לך בוואטסאפ (מההתראות),
 * כדי להראות אותה מתחת לשם כשהוא מתקשר. נשמר רק בטלפון.
 */
object LastChat {
    private const val PREFS = "last_chat"
    private const val WEEK = 7L * 24 * 60 * 60 * 1000

    private fun sp(c: Context) = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(c: Context, sbn: StatusBarNotification) {
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style?.isGroupConversation == true || n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return
        val last = style?.messages?.lastOrNull()
        // הודעה שאתה שלחת: לא
        if (style != null && last != null && last.person == null) return
        val text = (last?.text ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString()?.trim() ?: return
        if (text.isBlank() || Regex("^\\d+ (הודעות|new messages)").containsMatchIn(text)) return
        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim()
        val v = "${System.currentTimeMillis()}|$text"
        val e = sp(c).edit()
        CallerId.key(Driving.chatNumber(sbn, style))?.let { e.putString(it, v) }
        title?.takeIf { it.isNotBlank() }?.let { e.putString("n:$it", v) }
        e.apply()
    }

    /** "💬 ההודעה · לפני שעתיים", אם הייתה הודעה בשבוע האחרון */
    fun forCall(c: Context, number: String?, name: String?): String? {
        val p = sp(c)
        val raw = CallerId.key(number)?.let { p.getString(it, null) }
            ?: name?.let { p.getString("n:${it.trim()}", null) }
            ?: return null
        val at = raw.substringBefore('|').toLongOrNull() ?: return null
        val text = raw.substringAfter('|')
        val ago = System.currentTimeMillis() - at
        if (ago > WEEK || ago < 0) return null
        val min = ago / 60_000
        val whenText = when {
            min < 1 -> "עכשיו"
            min < 60 -> "לפני $min דק׳"
            min < 120 -> "לפני שעה"
            min < 24 * 60 -> "לפני ${min / 60} שעות"
            min < 48 * 60 -> "אתמול"
            else -> "לפני ${min / (24 * 60)} ימים"
        }
        return "💬 $text · $whenText"
    }
}
