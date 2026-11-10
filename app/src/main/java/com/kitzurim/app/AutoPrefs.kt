package com.kitzurim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** מקום שבו הטלפון עובר לשקט */
data class Place(val id: String, val name: String, val lat: Double, val lng: Double)

/** תשובה מיוחדת בנהיגה לאדם מסוים (מזוהה לפי מספר, ואם אין מספר אז לפי שם) */
data class CustomReply(val name: String, val number: String, val text: String) {
    fun entry() = Excluded.encode(name, number)
}

/** הודעה שהגיעה בזמן נסיעה */
data class DriveMessage(val chat: String, val text: String, val time: String, val number: String)

/** "תזכיר לי X כשאני ב-Y" */
data class PlaceReminder(val id: String, val text: String, val place: String, val lat: Double, val lng: Double)

/** ההגדרות של האוטומציות. נשמרות רק בטלפון */
object AutoPrefs {
    const val DEFAULT_REPLY = "אני נוהג, אחזור אליך בהקדם 🚗"
    const val PLACE_RADIUS_METERS = 150f

    private fun sp(c: Context) = c.getSharedPreferences("kitzurim_auto", Context.MODE_PRIVATE)

    private fun list(c: Context, key: String): List<String> = try {
        val a = JSONArray(sp(c).getString(key, "[]"))
        List(a.length()) { a.getString(it) }
    } catch (_: Exception) {
        emptyList()
    }

    private fun setList(c: Context, key: String, items: List<String>) {
        sp(c).edit().putString(key, JSONArray(items).toString()).apply()
    }

    // ---------- מצב נהיגה ----------
    fun drivingEnabled(c: Context) = sp(c).getBoolean("driving_enabled", false)
    fun setDrivingEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("driving_enabled", on).apply()

    fun carAddress(c: Context) = sp(c).getString("car_address", "") ?: ""
    fun carName(c: Context) = sp(c).getString("car_name", "") ?: ""
    fun setCar(c: Context, name: String, address: String) =
        sp(c).edit().putString("car_name", name).putString("car_address", address).apply()

    /** האם אתה נוהג עכשיו (נדלק כשהטלפון מתחבר לרכב, או ידנית) */
    fun drivingActive(c: Context) = sp(c).getBoolean("driving_active", false)
    fun setDrivingActive(c: Context, on: Boolean) {
        sp(c).edit().putBoolean("driving_active", on).apply()
        // נסיעה חדשה: כל אחד יקבל שוב תשובה אחת, והסיכום מתחיל מחדש
        if (on) {
            setList(c, "replied", emptyList())
            setList(c, "drive_log", emptyList())
        }
    }

    fun replyText(c: Context) = sp(c).getString("reply_text", DEFAULT_REPLY) ?: DEFAULT_REPLY
    fun setReplyText(c: Context, text: String) = sp(c).edit().putString("reply_text", text).apply()

    /** כל צ'אט מקבל תשובה אחת בלבד בכל נסיעה */
    fun alreadyReplied(c: Context, chat: String) = chat in list(c, "replied")
    fun markReplied(c: Context, chat: String) {
        setList(c, "replied", list(c, "replied") + chat)
        // הרשימה שמוצגת במסך: מי קיבל תשובה ומתי (30 האחרונים)
        setList(c, "reply_log", (listOf("${now()} · $chat") + replyLog(c)).take(30))
    }

    fun replyLog(c: Context) = list(c, "reply_log")
    fun clearReplyLog(c: Context) = setList(c, "reply_log", emptyList())

    /** אנשים שלא מקבלים מענה אוטומטי (לפי השם כמו שהוא מופיע בוואטסאפ) */
    fun excluded(c: Context) = list(c, "excluded")
    fun addExcluded(c: Context, name: String, number: String?) {
        if (name.isBlank()) return
        val entry = Excluded.encode(name, number)
        if (entry !in excluded(c)) setList(c, "excluded", excluded(c) + entry)
    }
    fun removeExcluded(c: Context, entry: String) = setList(c, "excluded", excluded(c) - entry)

    // ---------- תשובה מיוחדת לאנשים מסוימים ----------
    fun customReplies(c: Context): List<CustomReply> = list(c, "custom_replies").mapNotNull {
        try {
            val o = JSONObject(it)
            CustomReply(o.getString("name"), o.optString("num"), o.getString("text"))
        } catch (_: Exception) {
            null
        }
    }

    fun addCustomReply(c: Context, r: CustomReply) {
        // אותו אדם פעם אחת: תשובה חדשה מחליפה את הקודמת
        val others = customReplies(c).filter { it.entry() != r.entry() }
        saveCustomReplies(c, others + r)
    }

    fun removeCustomReply(c: Context, r: CustomReply) =
        saveCustomReplies(c, customReplies(c).filter { it.entry() != r.entry() })

    private fun saveCustomReplies(c: Context, all: List<CustomReply>) = setList(c, "custom_replies", all.map {
        JSONObject().put("name", it.name).put("num", it.number).put("text", it.text).toString()
    })

    /** התשובה לצ'אט הזה: מיוחדת אם הגדרת לו, ואחרת הרגילה */
    fun replyFor(c: Context, chat: String, number: String?): String =
        customReplies(c).firstOrNull { Excluded.matches(it.entry(), chat, number) }?.text ?: replyText(c)

    // ---------- סיכום הנסיעה ----------
    fun summaryEnabled(c: Context) = sp(c).getBoolean("summary_enabled", true)
    fun setSummaryEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("summary_enabled", on).apply()

    /** ההודעות שהגיעו בנסיעה האחרונה */
    fun driveMessages(c: Context): List<DriveMessage> = list(c, "drive_log").mapNotNull {
        try {
            val o = JSONObject(it)
            DriveMessage(o.getString("chat"), o.getString("text"), o.getString("time"), o.optString("num"))
        } catch (_: Exception) {
            null
        }
    }

    fun addDriveMessage(c: Context, chat: String, text: String, number: String?) {
        val all = driveMessages(c)
        // וואטסאפ מעדכן את אותה התראה כמה פעמים: לא שומרים את אותה הודעה פעמיים
        if (all.any { it.chat == chat && it.text == text }) return
        val o = JSONObject().put("chat", chat).put("text", text).put("time", nowTime()).put("num", number ?: "")
        setList(c, "drive_log", (list(c, "drive_log") + o.toString()).takeLast(200))
    }

    fun clearDriveMessages(c: Context) = setList(c, "drive_log", emptyList())

    private fun nowTime(): String = SimpleDateFormat("HH:mm", Locale("he")).format(Date())

    // ---------- תזכורות לפי מקום ----------
    fun reminders(c: Context): List<PlaceReminder> = list(c, "reminders").mapNotNull {
        try {
            val o = JSONObject(it)
            PlaceReminder(o.getString("id"), o.getString("text"), o.getString("place"), o.getDouble("lat"), o.getDouble("lng"))
        } catch (_: Exception) {
            null
        }
    }

    fun addReminder(c: Context, r: PlaceReminder) {
        val o = JSONObject().put("id", r.id).put("text", r.text).put("place", r.place).put("lat", r.lat).put("lng", r.lng)
        setList(c, "reminders", list(c, "reminders") + o.toString())
    }

    fun removeReminder(c: Context, id: String) =
        setList(c, "reminders", list(c, "reminders").filter { !it.contains("\"id\":\"$id\"") })

    private fun now(): String = SimpleDateFormat("dd/MM HH:mm", Locale("he")).format(Date())

    // ---------- שיחות לא מוכרות בלילה ----------
    fun nightCallsEnabled(c: Context) = sp(c).getBoolean("night_calls", false)
    fun setNightCallsEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("night_calls", on).apply()

    fun rejectedCalls(c: Context) = list(c, "rejected")
    fun addRejected(c: Context, number: String) {
        setList(c, "rejected", (listOf("${now()} · $number") + rejectedCalls(c)).take(30))
    }
    fun clearRejected(c: Context) = setList(c, "rejected", emptyList())

    // ---------- שקט לפי מקום ----------
    fun quietEnabled(c: Context) = sp(c).getBoolean("quiet_enabled", false)
    fun setQuietEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("quiet_enabled", on).apply()

    fun places(c: Context): List<Place> = list(c, "places").mapNotNull {
        try {
            val o = JSONObject(it)
            Place(o.getString("id"), o.getString("name"), o.getDouble("lat"), o.getDouble("lng"))
        } catch (_: Exception) {
            null
        }
    }

    fun addPlace(c: Context, p: Place) {
        val o = JSONObject().put("id", p.id).put("name", p.name).put("lat", p.lat).put("lng", p.lng)
        setList(c, "places", list(c, "places") + o.toString())
    }

    fun removePlace(c: Context, id: String) =
        setList(c, "places", list(c, "places").filter { !it.contains("\"id\":\"$id\"") })

    /** מצב הצלצול שהיה לפני שנכנסת למקום שקט, כדי להחזיר אותו ביציאה */
    fun inQuietPlace(c: Context) = sp(c).getBoolean("in_quiet", false)
    fun savedRinger(c: Context) = sp(c).getInt("saved_ringer", 2)
    fun enterQuiet(c: Context, previousRinger: Int) =
        sp(c).edit().putBoolean("in_quiet", true).putInt("saved_ringer", previousRinger).apply()
    fun leaveQuiet(c: Context) = sp(c).edit().putBoolean("in_quiet", false).apply()

    // ---------- שקט לפי שעות קבועות ----------
    fun scheduleEnabled(c: Context) = sp(c).getBoolean("schedule_enabled", false)
    fun setScheduleEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("schedule_enabled", on).apply()

    fun rules(c: Context): List<QuietRule> = list(c, "rules").mapNotNull {
        try {
            val o = JSONObject(it)
            QuietRule(o.getString("id"), o.getInt("start"), o.getInt("end"), o.getInt("days"))
        } catch (_: Exception) {
            null
        }
    }

    private fun saveRules(c: Context, rules: List<QuietRule>) = setList(c, "rules", rules.map {
        JSONObject().put("id", it.id).put("start", it.start).put("end", it.end).put("days", it.days).toString()
    })

    fun addRule(c: Context, r: QuietRule) = saveRules(c, rules(c) + r)
    fun updateRule(c: Context, r: QuietRule) = saveRules(c, rules(c).map { if (it.id == r.id) r else it })
    fun removeRule(c: Context, id: String) = saveRules(c, rules(c).filter { it.id != id })

    fun inScheduledQuiet(c: Context) = sp(c).getBoolean("in_sched_quiet", false)
    fun scheduledSavedRinger(c: Context) = sp(c).getInt("sched_saved_ringer", 2)
    fun enterScheduledQuiet(c: Context, previousRinger: Int) =
        sp(c).edit().putBoolean("in_sched_quiet", true).putInt("sched_saved_ringer", previousRinger).apply()
    fun leaveScheduledQuiet(c: Context) = sp(c).edit().putBoolean("in_sched_quiet", false).apply()
}

/**
 * שעות שקט קבועות. start ו-end בדקות מתחילת היום (08:30 = 510).
 * days: ביט לכל יום, ראשון = ביט 0 ... שבת = ביט 6. אם end קטן מ-start, השקט נמשך אל תוך הלילה.
 */
data class QuietRule(val id: String, val start: Int, val end: Int, val days: Int) {
    fun hasDay(d: Int) = days and (1 shl d) != 0
    fun toggleDay(d: Int) = copy(days = days xor (1 shl d))

    companion object {
        const val ALL_DAYS = 0b1111111
    }
}
