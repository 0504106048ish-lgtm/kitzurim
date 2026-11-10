package com.kitzurim.app

import android.content.Context

/** ההגדרות נשמרות רק בטלפון */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("kitzurim", Context.MODE_PRIVATE)

    /** המספר שלך (לא חובה): בשביל "שלח לעצמי..." בפקודה קולית */
    fun phone(c: Context): String = sp(c).getString("phone", "") ?: ""
    fun home(c: Context): String = sp(c).getString("home", "") ?: ""

    fun save(c: Context, phone: String, home: String) {
        sp(c).edit()
            .putString("phone", phone.trim())
            .putString("home", home.trim())
            .apply()
    }
}
