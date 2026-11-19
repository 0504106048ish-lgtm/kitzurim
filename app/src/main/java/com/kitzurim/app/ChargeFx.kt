package com.kitzurim.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.graphics.Typeface
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.min
import kotlin.math.sin

/**
 * טעינה נוזלית: מחברים מטען, טיפה נוטפת מהמצלמה ונופלת למטה,
 * ומשם נוזל עדין עולה עד גובה הסוללה, עם גל נמוך, קו אור דק, בועות והאחוז. אחרי כשלוש שניות הכול נעלם.
 */
object ChargeFx {
    /** הסגנונות (לפי הסדר של הדוגמאות). ברירת מחדל: ערפל */
    val STYLES = listOf("זכוכית רכה", "ערפל", "רק קו")

    private var view: View? = null

    /** מראה את האנימציה. מחזיר false אם לא הוצגה (ואז מראים את הגלולה הרגילה) */
    fun play(c: Context, percent: Int, minutesToFull: Long?, force: Boolean = false): Boolean {
        val s = NotchOverlay.service ?: return false
        val wm = NotchOverlay.wm ?: return false
        if (!force && !NotchPrefs.chargeFx(s)) return false
        if (!force && (NotchOverlay.offHere() || NotchOverlay.pillBlocked("charge"))) return false
        // מסך כבוי: אין למי להראות
        val on = runCatching { s.getSystemService(android.os.PowerManager::class.java)?.isInteractive == true }.getOrDefault(true)
        if (!on) return false
        view?.let { runCatching { wm.removeView(it) } }
        val bounds = if (android.os.Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds
        else android.graphics.Rect(0, 0, s.resources.displayMetrics.widthPixels, s.resources.displayMetrics.heightPixels)
        val sub = minutesToFull?.let { "מלאה בעוד " + Battery.minutesText(it) } ?: "בטעינה"
        val style = NotchPrefs.chargeStyle(s).coerceIn(0, STYLES.size - 1)
        val v = FxView(s, percent.coerceIn(1, 100), sub, style)
        val lp = NotchOverlay.params(bounds.width(), bounds.height(), 0, 0, touchable = false)
        runCatching { wm.addView(v, lp) }.onFailure { return false }
        view = v
        v.go {
            if (view === v) view = null
            runCatching { wm.removeView(v) }
        }
        return true
    }

    private class FxView(ctx: Context, val pct: Int, val sub: String, val kind: Int) : View(ctx) {
        val d = ctx.resources.displayMetrics.density
        val sp = ctx.resources.displayMetrics.scaledDensity
        var t = 0f
        val total = 3300f

        // גוון רך לפי הסוללה: אדום, צהוב או ירוק עדינים
        val c = if (pct < 20) Color.rgb(255, 138, 138) else if (pct < 50) Color.rgb(255, 210, 122) else Color.rgb(127, 240, 192)

        fun withA(a: Float) = Color.argb((a * 255).toInt().coerceIn(0, 255), Color.red(c), Color.green(c), Color.blue(c))

        // הצבע של הנוזל מלמעלה (פני הנוזל) עד למטה. בערפל: שקוף למעלה, והולך ומתמלא למטה (בלי קו חד)
        val stops: IntArray
        val pos: FloatArray
        val lineA: Float // קו האור הלבן הדק
        val glowA: Float // זוהר בצבע סביב הקו

        init {
            when (kind) {
                0 -> { stops = intArrayOf(withA(0.42f), withA(0.22f)); pos = floatArrayOf(0f, 1f); lineA = 0.75f; glowA = 0f }
                2 -> { stops = intArrayOf(withA(0.12f), withA(0.04f)); pos = floatArrayOf(0f, 1f); lineA = 1f; glowA = 0.95f }
                else -> { stops = intArrayOf(withA(0f), withA(0.18f), withA(0.38f)); pos = floatArrayOf(0f, 0.35f, 1f); lineA = 0.9f; glowA = 0.75f }
            }
        }

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textSize = 40 * sp
            letterSpacing = 0.02f
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 12.5f * sp
        }
        val path = Path()
        val surf = Path()
        val plate = android.graphics.RectF()
        val plateFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(12, 14, 20) }
        val plateRim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1f * d
            color = Color.WHITE
        }
        // בועות קטנות: מיקום לרוחב, עיכוב וגודל (קבועים, כדי שלא יקפצו בין פריימים)
        val bubbles = List(6) { i -> Triple(((i * 37 + 11) % 90) / 100f + 0.05f, i * 130f, (1.5f + i % 3) * d) }

        fun go(done: () -> Unit) {
            ValueAnimator.ofFloat(0f, total).apply {
                duration = total.toLong()
                interpolator = LinearInterpolator()
                addUpdateListener { t = it.animatedValue as Float; invalidate() }
                addListener(object : android.animation.AnimatorListenerAdapter() {
                    override fun onAnimationEnd(animation: android.animation.Animator) = done()
                })
                start()
            }
        }

        override fun onDraw(cv: Canvas) {
            val s = NotchOverlay.service ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val cut = NotchOverlay.cutout(s)
            val hx = cut.exactCenterX() + NotchPrefs.offsetX(s) * d
            val hy = cut.exactCenterY() + NotchPrefs.offsetY(s) * d
            // הכול נמוג בסוף
            val fade = if (t > 2700f) (1f - (t - 2700f) / 600f).coerceAtLeast(0f) else 1f

            // 1. הטיפה: נוטפת מהמצלמה, נמתחת קצת ונופלת עד למטה
            if (t < 900f) {
                val f = t / 900f
                val y = hy + f * f * (h - hy)
                val r = 5 * d * (0.4f + 0.6f * min(1f, f * 4f))
                val stretch = 1f + 0.45f * f
                dot.color = c
                dot.alpha = 240
                cv.drawOval(hx - r, y - r * stretch, hx + r, y + r * stretch, dot)
            }
            if (t < 880f) return

            // 2. הנוזל עולה עד גובה הסוללה, עם גל נמוך ורחב
            val g = min(1f, (t - 880f) / 1600f)
            val ease = 1f - (1f - g) * (1f - g) * (1f - g)
            val sy = h - h * pct / 100f * ease
            val amp = 3.5f * d * (0.4f + 0.6f * ease)
            val phase = t / 1600f * 2f * PI.toFloat()
            val lambda = w / 1.2f
            path.reset(); surf.reset()
            path.moveTo(0f, h)
            var x = 0f
            val step = 6 * d
            while (x <= w + step) {
                val y = sy + amp * sin(2f * PI.toFloat() * x / lambda + phase)
                path.lineTo(x, y)
                if (x == 0f) surf.moveTo(x, y) else surf.lineTo(x, y)
                x += step
            }
            path.lineTo(w, h)
            path.close()
            fill.shader = LinearGradient(0f, sy - amp, 0f, h, stops, pos, Shader.TileMode.CLAMP)
            fill.alpha = (255 * fade).toInt()
            cv.drawPath(path, fill)

            // 3. קו האור: זוהר רך בצבע, ועליו קו לבן דק
            if (glowA > 0f) {
                for (i in 3 downTo 1) {
                    stroke.strokeWidth = (1.6f + i * 2.4f) * d
                    stroke.color = c
                    stroke.alpha = (glowA * 255 / (i + 1.5f) * fade).toInt()
                    cv.drawPath(surf, stroke)
                }
            }
            stroke.strokeWidth = 1.6f * d
            stroke.color = Color.WHITE
            stroke.alpha = (lineA * 255 * fade).toInt()
            cv.drawPath(surf, stroke)

            // 4. בועות קטנות ושקופות שעולות בתוך הנוזל
            dot.color = Color.WHITE
            for ((bx, delay, r) in bubbles) {
                val bt = t - 900f - delay
                if (bt < 0f || bt > 2200f) continue
                val by = h - 10 * d - bt / 2200f * 150 * d
                if (by < sy + 4 * d) continue
                dot.alpha = ((1f - bt / 2200f) * 100 * fade).toInt()
                cv.drawCircle(bx * w, by, r, dot)
            }

            // 5. האחוז, ומתחתיו מתי תהיה מלאה
            val ta = min(1f, (t - 900f) / 400f) * fade
            if (ta > 0f) {
                big.color = Color.WHITE
                big.alpha = (255 * ta).toInt()
                big.setShadowLayer(14 * d, 0f, 2 * d, Color.argb(90, 0, 0, 0))
                small.color = Color.WHITE
                small.alpha = (215 * ta).toInt()
                small.setShadowLayer(6 * d, 0f, 1 * d, Color.argb(110, 0, 0, 0))
                val cy = h * 0.42f
                // לוח זכוכית כהה מאחורי הטקסט: רואים את האחוזים גם על רקע לבן
                val tw = maxOf(big.measureText("⚡ $pct%"), small.measureText(sub)) + 36 * d
                plate.set(w / 2f - tw / 2f, cy + big.ascent() - 12 * d, w / 2f + tw / 2f, cy + 26 * d + small.descent() + 12 * d)
                plateFill.alpha = (150 * ta).toInt()
                plateRim.alpha = (45 * ta).toInt()
                val pr = 22 * d
                cv.drawRoundRect(plate, pr, pr, plateFill)
                cv.drawRoundRect(plate, pr, pr, plateRim)
                cv.drawText("⚡ $pct%", w / 2f, cy, big)
                cv.drawText(sub, w / 2f, cy + 26 * d, small)
            }
        }
    }
}
