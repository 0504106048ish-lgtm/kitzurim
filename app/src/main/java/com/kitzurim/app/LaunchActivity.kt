package com.kitzurim.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity

/**
 * מה שנפתח מהאייקון ומהלחצן הצדדי: מסך שקוף שמעביר מיד הלאה.
 * בנוי בדיוק כמו הכניסה של הגרסה הראשונה (שקוף, בלי מקום ברשימת האחרונים),
 * כי אז קיצורים הופיעה רגיל ברשימה של הלחצן הצדדי של סמסונג.
 */
class LaunchActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val next = if (Prefs.voiceFirst(this)) {
            // "פתיחה ישר לפקודה קולית": "חזרה" בחלון הדיבור פותח את האפליקציה
            Intent(this, VoiceActivity::class.java).putExtra(VoiceActivity.BACK_TO_HOME, true)
        } else {
            Intent(this, HomeActivity::class.java)
        }
        startActivity(next.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }
}
