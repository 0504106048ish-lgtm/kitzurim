package com.kitzurim.app

import android.app.AppOpsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.os.Process
import java.util.Calendar

/**
 * "מי אכל את הסוללה": כשהסוללה יורדת ל-15% (בלי מטען), הגלולה מראה אילו אפליקציות היו הכי הרבה בשימוש היום,
 * עם פס לכל אחת, כפתור לעצור את הראשונה ו"חיסכון".
 * (אנדרואיד לא נותן לאפליקציות לראות צריכת סוללה של אחרות, אז מודדים לפי זמן השימוש של היום,
 *  שזה כמעט תמיד מה שאוכל את הסוללה.)
 */
object LowBattery {

    fun on(c: Context) = c.getSharedPreferences("kitzurim_notch", Context.MODE_PRIVATE).getBoolean("low_bat", true)
    fun setOn(c: Context, v: Boolean) = c.getSharedPreferences("kitzurim_notch", Context.MODE_PRIVATE).edit().putBoolean("low_bat", v).apply()

    /** האם אישרת לקיצורים לראות זמני שימוש */
    fun hasAccess(c: Context): Boolean = runCatching {
        val ops = c.getSystemService(AppOpsManager::class.java)
        @Suppress("DEPRECATION")
        ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName) == AppOpsManager.MODE_ALLOWED
    }.getOrDefault(false)

    fun openAccess(c: Context) {
        runCatching {
            c.startActivity(Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** האפליקציות שהיו הכי הרבה בשימוש היום: (חבילה, שם, אחוז מכל הזמן) */
    fun top(c: Context, n: Int = 3): List<Triple<String, String, Int>> {
        if (!hasAccess(c)) return emptyList()
        val usm = c.getSystemService(UsageStatsManager::class.java) ?: return emptyList()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0)
        }.timeInMillis
        val stats = runCatching { usm.queryAndAggregateUsageStats(start, System.currentTimeMillis()) }.getOrNull() ?: return emptyList()
        // לא סופרים את מסך הבית, את קיצורים ואת ממשק המערכת
        val home = runCatching {
            c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)?.activityInfo?.packageName
        }.getOrNull()
        val skip = setOf(c.packageName, home, "com.android.systemui", "android")
        val used = stats.values
            .filter { it.packageName !in skip && it.totalTimeInForeground > 60_000 }
            .sortedByDescending { it.totalTimeInForeground }
        val total = used.sumOf { it.totalTimeInForeground }.coerceAtLeast(1)
        val pm = c.packageManager
        return used.take(n).map { u ->
            val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(u.packageName, 0)).toString() }.getOrDefault(u.packageName)
            Triple(u.packageName, label, (u.totalTimeInForeground * 100 / total).toInt())
        }
    }

    /** כל שינוי בסוללה: ב-15% (פעם אחת בכל פעם שהיא יורדת) מראים את הגלולה */
    fun check(c: Context, level: Int, charging: Boolean) {
        val sp = c.getSharedPreferences("kitzurim_notch", Context.MODE_PRIVATE)
        // מתאפס רק כשהסוללה באמת עלתה מעל 20% (לא כשרק חיברו ונתקו מטען), ונשמר גם אחרי הפעלה מחדש
        if (level > 20) { if (sp.getBoolean("low_bat_shown", false)) sp.edit().putBoolean("low_bat_shown", false).apply(); return }
        if (charging || level > 15 || level < 0 || !on(c) || sp.getBoolean("low_bat_shown", false)) return
        sp.edit().putBoolean("low_bat_shown", true).apply()
        LivePill.showLowBattery(level, top(c))
    }
}
