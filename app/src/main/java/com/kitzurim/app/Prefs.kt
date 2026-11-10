package com.kitzurim.app

import android.content.Context
import org.json.JSONArray

/** ההגדרות נשמרות רק בטלפון */
object Prefs {
    const val DEFAULT_MESSAGE = "שלום לך!"

    private fun sp(c: Context) = c.getSharedPreferences("kitzurim", Context.MODE_PRIVATE)

    fun phone(c: Context): String = sp(c).getString("phone", "") ?: ""
    fun message(c: Context): String = sp(c).getString("message", DEFAULT_MESSAGE) ?: DEFAULT_MESSAGE

    fun home(c: Context): String = sp(c).getString("home", "") ?: ""

    fun save(c: Context, phone: String, message: String, home: String) {
        sp(c).edit()
            .putString("phone", phone.trim())
            .putString("message", message)
            .putString("home", home.trim())
            .apply()
    }

    fun notes(c: Context): List<String> {
        val raw = sp(c).getString("notes", "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            List(a.length()) { a.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun setNotes(c: Context, notes: List<String>) {
        sp(c).edit().putString("notes", JSONArray(notes).toString()).apply()
    }
}
