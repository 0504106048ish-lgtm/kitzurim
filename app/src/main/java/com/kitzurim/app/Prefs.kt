package com.kitzurim.app

import android.content.Context

/** ההגדרות נשמרות רק בטלפון */
object Prefs {
    private fun sp(c: Context) = c.getSharedPreferences("kitzurim", Context.MODE_PRIVATE)

    /** המספר שלך (לא חובה): בשביל "שלח לעצמי..." בפקודה קולית */
    fun phone(c: Context): String = sp(c).getString("phone", "") ?: ""
    fun home(c: Context): String = sp(c).getString("home", "") ?: ""

    /** האייקון "קיצורים" פותח ישר את הפקודה הקולית (בשביל הלחצן הצדדי). לאפליקציה: לחיצה ארוכה ← "פתיחת קיצורים" */
    fun voiceFirst(c: Context) = sp(c).getBoolean("voice_first", false)
    fun setVoiceFirst(c: Context, on: Boolean) = sp(c).edit().putBoolean("voice_first", on).apply()

    fun save(c: Context, phone: String, home: String) {
        sp(c).edit()
            .putString("phone", phone.trim())
            .putString("home", home.trim())
            .apply()
    }
}
