package com.kitzurim.app

import java.util.Calendar

/**
 * חיפוש חכם בחייגן:
 * - שם: באמצע מילה ובכל חלק של השם ("יהו" מוצא את אליהו), בלי הבדל בין אותיות סופיות (ך/כ, ם/מ...)
 * - טעות הקלדה: מילה של 5 אותיות ומעלה נמצאת גם עם טעות אחת ("מיכאך" = מיכאל), ומ-9 אותיות גם עם שתיים
 * - תאריך: "היום", "אתמול", "שלשום", "יום שלישי", "17/11", "17.11.26", "נובמבר"
 */
object SmartName {
    private val finals = mapOf('ך' to 'כ', 'ם' to 'מ', 'ן' to 'נ', 'ף' to 'פ', 'ץ' to 'צ')

    private fun norm(s: String): String = buildString {
        for (ch in s.lowercase()) {
            if (ch in '֑'..'ׇ') continue // ניקוד וטעמים
            if (ch == '\'' || ch == '"' || ch == '׳' || ch == '״' || ch == '-' || ch == '.') continue
            append(finals[ch] ?: ch)
        }
    }.replace(Regex("\\s+"), " ").trim()

    /** השם מכיל את מה שכתבת (בכל מקום בשם) */
    fun exact(name: String, q: String): Boolean {
        val n = norm(q)
        return n.isNotEmpty() && norm(name).contains(n)
    }

    /** השם מכיל את מה שכתבת עם טעות הקלדה אחת (או שתיים במילה ארוכה) */
    fun fuzzy(name: String, q: String): Boolean {
        val p = norm(q)
        if (p.length < 5) return false
        val max = if (p.length >= 9) 2 else 1
        return approx(p, norm(name)) <= max
    }

    /** כמה שינויים צריך כדי שהטקסט יכיל את המילה איפשהו (מחיקה, הוספה או החלפה של אות) */
    private fun approx(p: String, t: String): Int {
        if (t.isEmpty()) return p.length
        var prev = IntArray(t.length + 1) // שורה 0: אפשר להתחיל בכל מקום בטקסט
        for (i in 1..p.length) {
            val cur = IntArray(t.length + 1)
            cur[0] = i
            for (j in 1..t.length) {
                val cost = if (p[i - 1] == t[j - 1]) 0 else 1
                cur[j] = minOf(prev[j - 1] + cost, prev[j] + 1, cur[j - 1] + 1)
            }
            prev = cur
        }
        return prev.min()
    }

    private val weekdays = listOf("ראשון", "שני", "שלישי", "רביעי", "חמישי", "שישי", "שבת")
    private val months = listOf("ינואר", "פברואר", "מרץ", "אפריל", "מאי", "יוני", "יולי", "אוגוסט", "ספטמבר", "אוקטובר", "נובמבר", "דצמבר")

    private fun dayStart(c: Calendar) = c.apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun oneDay(c: Calendar): Pair<Long, Long> {
        val from = dayStart(c).timeInMillis
        return from to (c.apply { add(Calendar.DAY_OF_MONTH, 1) }.timeInMillis)
    }

    /** טווח הזמן (מ-עד) שמתאים למה שכתבת, או null אם זה לא תאריך */
    fun dateRange(raw: String): Pair<Long, Long>? {
        val t = raw.trim()
        // גם "ביום שלישי", "בנובמבר", "אתמול"
        return listOfNotNull(t, t.takeIf { it.startsWith("ב") }?.drop(1))
            .firstNotNullOfOrNull { parse(it.removePrefix("יום ").trim()) }
    }

    private fun parse(q: String): Pair<Long, Long>? {
        val now = Calendar.getInstance()
        when (q) {
            "היום" -> return oneDay(now)
            "אתמול" -> return oneDay(now.apply { add(Calendar.DAY_OF_MONTH, -1) })
            "שלשום" -> return oneDay(now.apply { add(Calendar.DAY_OF_MONTH, -2) })
        }
        // יום בשבוע: הפעם האחרונה שהיה (היום או לפני)
        val wd = weekdays.indexOf(q)
        if (wd >= 0) {
            val c = Calendar.getInstance()
            while (c.get(Calendar.DAY_OF_WEEK) != wd + 1) c.add(Calendar.DAY_OF_MONTH, -1)
            return oneDay(c)
        }
        // 17/11, 17.11, 17-11-2026
        Regex("""^(\d{1,2})[/.\-](\d{1,2})(?:[/.\-](\d{2,4}))?$""").find(q)?.let { m ->
            val d = m.groupValues[1].toInt()
            val mo = m.groupValues[2].toInt()
            if (d !in 1..31 || mo !in 1..12) return null
            val c = Calendar.getInstance()
            val y = m.groupValues[3].takeIf { it.isNotEmpty() }?.toInt()?.let { if (it < 100) 2000 + it else it }
            c.set(y ?: c.get(Calendar.YEAR), mo - 1, d)
            // בלי שנה ובעתיד: כנראה התכוונת לשנה שעברה
            if (y == null && c.after(now)) c.add(Calendar.YEAR, -1)
            return oneDay(c)
        }
        // חודש: כל החודש (האחרון שהיה)
        val mi = months.indexOf(q)
        if (mi >= 0) {
            val c = Calendar.getInstance()
            if (mi > c.get(Calendar.MONTH)) c.add(Calendar.YEAR, -1)
            c.set(Calendar.MONTH, mi)
            c.set(Calendar.DAY_OF_MONTH, 1)
            val from = dayStart(c).timeInMillis
            return from to c.apply { add(Calendar.MONTH, 1) }.timeInMillis
        }
        return null
    }
}

/** שני זמנים באותו יום */
internal fun sameDay(a: Long, b: Long): Boolean {
    val x = Calendar.getInstance().apply { timeInMillis = a }
    val y = Calendar.getInstance().apply { timeInMillis = b }
    return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR)
}

private val hebMonths = listOf("בינואר", "בפברואר", "במרץ", "באפריל", "במאי", "ביוני", "ביולי", "באוגוסט", "בספטמבר", "באוקטובר", "בנובמבר", "בדצמבר")
private val hebDays = listOf("יום ראשון", "יום שני", "יום שלישי", "יום רביעי", "יום חמישי", "יום שישי", "שבת")

/** השם של היום בתגית: היום, אתמול, יום שלישי (השבוע האחרון), 9 בנובמבר, ובשנה אחרת גם השנה */
internal fun dayLabel(t: Long): String {
    val now = Calendar.getInstance()
    val c = Calendar.getInstance().apply { timeInMillis = t }
    if (sameDay(t, now.timeInMillis)) return "היום"
    val y = Calendar.getInstance().apply { add(Calendar.DAY_OF_MONTH, -1) }
    if (sameDay(t, y.timeInMillis)) return "אתמול"
    val weekAgo = Calendar.getInstance().apply {
        add(Calendar.DAY_OF_MONTH, -6)
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    if (t >= weekAgo.timeInMillis) return hebDays[c.get(Calendar.DAY_OF_WEEK) - 1]
    val base = "${c.get(Calendar.DAY_OF_MONTH)} ${hebMonths[c.get(Calendar.MONTH)]}"
    return if (c.get(Calendar.YEAR) == now.get(Calendar.YEAR)) base else "$base ${c.get(Calendar.YEAR)}"
}

/** "אליהו אתמול", "אתמול אליהו", "דני 17/11", "אמא ביום שלישי": התאריך (מ-עד) ומה שנשאר בשביל השם */
internal fun splitDate(q: String): Pair<Pair<Long, Long>, String>? {
    val w = q.trim().split(Regex("\s+"))
    if (w.size < 2) return null
    for (k in w.size - 1 downTo 1) {
        SmartName.dateRange(w.takeLast(k).joinToString(" "))?.let { return it to w.dropLast(k).joinToString(" ") }
        SmartName.dateRange(w.take(k).joinToString(" "))?.let { return it to w.drop(k).joinToString(" ") }
    }
    return null
}
