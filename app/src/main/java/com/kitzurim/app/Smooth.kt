package com.kitzurim.app

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min

/**
 * ערך שזז בתנועה רכה במקום לקפוץ (הטבעת של השיר והווליום, המסגרת של הגלולה).
 * הווליום: מחליק לערך החדש. השיר: מתקדם ברצף בין העדכונים (שמגיעים פעם בשנייה),
 * לפי הקצב שבו הוא התקדם, כך שהקו נע כל הזמן ולא קופץ כל שנייה.
 */
class Smooth(private val follow: Boolean) {
    /** מה שמצויר עכשיו (מינוס = עוד אין ערך) */
    var shown = -1f
        private set
    private var target = 0f
    private var at = 0L
    private var rate = 0f
    private var lastStep = 0L

    fun set(v: Float) {
        val now = SystemClock.uptimeMillis()
        val value = v.coerceIn(0f, 1f)
        if (shown < 0f) {
            shown = value; target = value; at = now; rate = 0f; lastStep = now
            return
        }
        if (follow) {
            val dt = (now - at) / 1000f
            val d = value - target
            // התקדמות רגילה של שיר: לומדים את הקצב. קפיצה (שיר חדש, דילוג) או עצירה: בלי קצב
            rate = if (dt in 0.3f..5f && d > 0f && d < 0.05f) d / dt else 0f
        }
        target = value
        at = now
    }

    fun clear() { shown = -1f; rate = 0f }

    /** צעד אחד של התנועה. מחזיר אחרי כמה מילישניות לצייר שוב (או null = הגענו, אין צורך) */
    fun step(): Long? {
        if (shown < 0f) return null
        val now = SystemClock.uptimeMillis()
        val frame = ((now - lastStep).coerceIn(0L, 100L)) / 1000f
        lastStep = now
        // ממשיכים עם הקצב עד שתי שניות אחרי העדכון האחרון (אם העדכונים נעצרו, גם הקו נעצר)
        val ahead = rate * (min(now - at, 2000L) / 1000f)
        val goal = (target + ahead).coerceIn(0f, 1f)
        val gap = goal - shown
        shown += gap * min(1f, frame / 0.12f)
        return when {
            abs(gap) > 0.002f -> 16L // באמצע תנועה: כל פריים
            rate > 0f -> 80L // רק מתקדם לאט עם השיר: מספיק כמה פעמים בשנייה
            else -> null
        }
    }
}
