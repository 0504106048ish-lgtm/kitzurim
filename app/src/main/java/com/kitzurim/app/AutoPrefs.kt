package com.kitzurim.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** מקום שבו הטלפון עובר לשקט */
data class Place(val id: String, val name: String, val lat: Double, val lng: Double)

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
        // נסיעה חדשה: כל אחד יקבל שוב תשובה אחת
        if (on) setList(c, "replied", emptyList())
    }

    fun replyText(c: Context) = sp(c).getString("reply_text", DEFAULT_REPLY) ?: DEFAULT_REPLY
    fun setReplyText(c: Context, text: String) = sp(c).edit().putString("reply_text", text).apply()

    /** כל צ'אט מקבל תשובה אחת בלבד בכל נסיעה */
    fun alreadyReplied(c: Context, chat: String) = chat in list(c, "replied")
    fun markReplied(c: Context, chat: String) = setList(c, "replied", list(c, "replied") + chat)

    // ---------- שיחות לא מוכרות בלילה ----------
    fun nightCallsEnabled(c: Context) = sp(c).getBoolean("night_calls", false)
    fun setNightCallsEnabled(c: Context, on: Boolean) = sp(c).edit().putBoolean("night_calls", on).apply()

    fun rejectedCalls(c: Context) = list(c, "rejected")
    fun addRejected(c: Context, number: String) {
        val time = SimpleDateFormat("dd/MM HH:mm", Locale("he")).format(Date())
        setList(c, "rejected", (listOf("$time · $number") + rejectedCalls(c)).take(30))
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
}
