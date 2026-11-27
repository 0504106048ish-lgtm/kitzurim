package com.kitzurim.app

import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.min

/**
 * ערך שזז בתנועה רכה במקום לקפוץ (הטבעת של השיר והווליום, המסגרת של הגלולה).
 * הווליום: מחליק לערך החדש. השיר: נע ברצף ובמהירות קבועה לפי הקצב של השיר,
 * בלי לעצור, בלי לחזור אחורה ובלי לקפוץ כשמגיע עדכון (פעם בשנייה). רק מאיץ או מאט קצת כדי להישאר צמוד.
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
        // אותו ערך שוב (הטבעת מתעדכנת גם בגלל דברים אחרים): לא נוגעים, אחרת הקצב מתאפס והקו נעצר
        if (value == target) return
        if (follow) {
            val dt = (now - at) / 1000f
            val d = value - target
            if (dt in 0.3f..5f && d > 0f && d < 0.05f) {
                // התקדמות רגילה: הקצב נלמד בהדרגה (ממוצע), כדי שעדכון אחד לא ישנה את המהירות בבת אחת
                val r = d / dt
                rate = if (rate > 0f) rate * 0.7f + r * 0.3f else r
            } else if (d < 0f || d >= 0.05f) rate = 0f // שיר חדש, דילוג או חזרה אחורה
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
        if (frame <= 0f) return if (rate > 0f || abs(target - shown) > 0.001f) 16L else null
        // איפה השיר באמת עכשיו: העדכון האחרון + הקצב מאז (עד שתי שניות, אם העדכונים נעצרו גם הקו נעצר)
        val ahead = rate * (min(now - at, 2000L) / 1000f)
        val goal = (target + ahead).coerceIn(0f, 1f)
        val gap = goal - shown
        if (follow && rate > 0f && abs(gap) < 0.05f && now - at < 2000L) {
            // שיר מתנגן: מהירות קבועה, ומתקנים בעדינות (עד חצי מהירות לכאן או לכאן). אף פעם לא אחורה ולא עוצר
            val fix = (gap / (rate * 1.5f)).coerceIn(-0.5f, 0.5f)
            shown = (shown + rate * (1f + fix) * frame).coerceIn(0f, 1f)
            return 16L
        }
        // ווליום, שיר חדש או דילוג: מחליק לערך החדש
        shown += gap * min(1f, frame / 0.15f)
        return if (abs(gap) > 0.001f) 16L else null
    }
}
