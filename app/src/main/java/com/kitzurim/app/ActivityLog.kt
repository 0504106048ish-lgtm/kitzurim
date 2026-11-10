package com.kitzurim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** משהו שקיצורים עשתה לבד, בשביל "מה קרה היום" במסך הפתיחה */
data class LogEvent(val time: Long, val emoji: String, val text: String) {
    fun clock(): String = SimpleDateFormat("HH:mm", Locale("he")).format(Date(time))
}

object ActivityLog {
    private fun sp(c: Context) = c.getSharedPreferences("kitzurim_log", Context.MODE_PRIVATE)

    fun add(c: Context, emoji: String, text: String) {
        val all = all(c)
        val o = JSONArray()
        (listOf(LogEvent(System.currentTimeMillis(), emoji, text)) + all).take(100).forEach {
            o.put(JSONObject().put("t", it.time).put("e", it.emoji).put("x", it.text))
        }
        sp(c).edit().putString("events", o.toString()).apply()
    }

    fun all(c: Context): List<LogEvent> = try {
        val a = JSONArray(sp(c).getString("events", "[]"))
        List(a.length()) {
            val o = a.getJSONObject(it)
            LogEvent(o.getLong("t"), o.optString("e"), o.optString("x"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    /** מה קרה היום (מחצות) */
    fun today(c: Context): List<LogEvent> {
        val midnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return all(c).filter { it.time >= midnight }
    }
}
