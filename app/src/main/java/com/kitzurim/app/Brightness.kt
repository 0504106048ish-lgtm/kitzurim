package com.kitzurim.app

import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.net.Uri
import android.provider.Settings
import android.view.View
import kotlin.math.pow

/**
 * בהירות בהחלקה על המצלמה: מתחילים להחליק מהחור, והגלולה נמתחת לפס צהוב.
 * כל עוד האצבע על המסך הבהירות זזה אחריה: ימינה בהיר יותר, שמאלה כהה יותר. עוזבים, והיא נשארת.
 */
object Brightness {
    private var bar: BarView? = null
    private var start = 0.5f
    private var level = 0.5f
    private var lastWrite = 0L

    // הבהירות שהעין רואה לא קווית: הפס זז "שווה", והערך בהגדרות לפי עקומה
    private fun toSetting(l: Float) = (l.coerceIn(0f, 1f).pow(2.2f) * 254 + 1).toInt()
    private fun fromSetting(v: Int) = ((v - 1).coerceAtLeast(0) / 254f).pow(1f / 2.2f)

    /** התחלת גרירה. false אם אין הרשאה (ואז פותחים את ההגדרה המתאימה) */
    fun begin(c: Context): Boolean {
        if (!Settings.System.canWrite(c)) {
            Actions.toast(c, "☀️ כדי לשנות בהירות: להדליק את קיצורים כאן (פעם אחת)")
            runCatching {
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.packageName))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
            return false
        }
        val cr = c.contentResolver
        // בהירות אוטומטית מחזירה את הבהירות לבד, אז מכבים אותה כשמכוונים ביד
        runCatching {
            Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        }
        val cur = runCatching { Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS) }.getOrDefault(128)
        start = fromSetting(cur.coerceIn(1, 255))
        level = start
        showBar(c)
        return true
    }

    /** האצבע זזה dx פיקסלים מהנקודה שבה התחילה */
    fun drag(c: Context, dx: Float) {
        val w = c.resources.displayMetrics.widthPixels
        level = (start + dx / (w * 0.6f)).coerceIn(0f, 1f)
        bar?.let { it.level = level; it.invalidate() }
        // לא כותבים בכל תזוזה קטנה (חוסך עומס), אבל מספיק מהר כדי שירגיש חי
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastWrite > 30) {
            lastWrite = now
            write(c)
        }
    }

    fun end(c: Context) {
        write(c)
        val v = bar ?: return
        bar = null
        v.animate().alpha(0f).setStartDelay(500).setDuration(250).withEndAction {
            runCatching { NotchOverlay.wm?.removeView(v) }
        }.start()
    }

    private fun write(c: Context) {
        runCatching { Settings.System.putInt(c.contentResolver, Settings.System.SCREEN_BRIGHTNESS, toSetting(level)) }
    }

    private fun showBar(c: Context) {
        val s = NotchOverlay.service ?: return
        val wm = NotchOverlay.wm ?: return
        bar?.let { runCatching { wm.removeView(it) } }
        val d = s.resources.displayMetrics.density
        val cut = NotchOverlay.cutout(s)
        val hole = maxOf(cut.width(), cut.height())
        val h = (hole * 0.7f + 2 * d).coerceIn(22 * d, 26 * d).toInt()
        val w = (240 * d).toInt()
        val cx = cut.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = cut.exactCenterY() + NotchPrefs.offsetY(s) * d
        val v = BarView(s, hole * 0.9f + 6 * d)
        v.level = level
        val lp = NotchOverlay.params(w, h, (cx - w / 2f).toInt(), (cy - h / 2f).toInt().coerceAtLeast(0), touchable = false)
        runCatching { wm.addView(v, lp) }.onFailure { return }
        bar = v
        // נמתחת מהחור לרוחב
        v.scaleX = 0.15f
        v.animate().scaleX(1f).setDuration(260).setInterpolator(android.view.animation.OvershootInterpolator(0.8f)).start()
    }

    /** הפס: גלולה שחורה עם מילוי צהוב, ☀️ בצד, ורווח באמצע בשביל המצלמה */
    private class BarView(ctx: Context, val gap: Float) : View(ctx) {
        val d = ctx.resources.displayMetrics.density
        var level = 0.5f
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK }
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.5f * d
            color = Color.rgb(255, 213, 74)
        }
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val icon = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textSize = 13 * ctx.resources.displayMetrics.scaledDensity
            textAlign = Paint.Align.CENTER
        }
        val r = RectF()

        override fun onDraw(c: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            val rad = h / 2f
            r.set(0.75f * d, 0.75f * d, w - 0.75f * d, h - 0.75f * d)
            c.drawRoundRect(r, rad, rad, bg)
            // המילוי: משמאל (כהה) לימין (בהיר), בתוך הגלולה
            val inset = 3 * d
            val fw = (w - 2 * inset) * level
            if (fw > 0f) {
                fill.shader = LinearGradient(inset, 0f, inset + fw, 0f,
                    Color.argb(200, 255, 179, 0), Color.argb(235, 255, 213, 74), Shader.TileMode.CLAMP)
                r.set(inset, inset, inset + fw, h - inset)
                c.drawRoundRect(r, rad - inset, rad - inset, fill)
            }
            // החור של המצלמה: שחור באמצע, כדי שהמילוי לא ייראה "נחתך" סביבו
            c.drawCircle(w / 2f, h / 2f, gap / 2f, bg)
            r.set(0.75f * d, 0.75f * d, w - 0.75f * d, h - 0.75f * d)
            c.drawRoundRect(r, rad, rad, rim)
            c.drawText("☀️", w - rad - 2 * d, h / 2f - (icon.descent() + icon.ascent()) / 2f, icon)
        }
    }
}
