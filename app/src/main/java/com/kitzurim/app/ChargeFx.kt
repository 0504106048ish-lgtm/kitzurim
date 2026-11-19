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
 * טעינה נוזלית: מחברים מטען, טיפה נוטפת מהמצלמה, נופלת ומתיזה למטה,
 * ומשם הנוזל עולה עד גובה הסוללה, עם גלים, בועות והאחוז בגדול. אחרי כשלוש שניות הכול נעלם.
 */
object ChargeFx {
    /** שמות הסגנונות (לפי הסדר של הדוגמאות) */
    val STYLES = listOf("צבע מלא", "ניאון", "זכוכית", "הצבע שבחרת", "שקיעה", "ים")

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
        val v = FxView(s, percent.coerceIn(1, 100), sub, NotchPrefs.chargeStyle(s))
        val lp = NotchOverlay.params(bounds.width(), bounds.height(), 0, 0, touchable = false)
        runCatching { wm.addView(v, lp) }.onFailure { return false }
        view = v
        v.go {
            if (view === v) view = null
            runCatching { wm.removeView(v) }
        }
        return true
    }

    private class FxView(ctx: Context, val pct: Int, val sub: String, val style: Int) : View(ctx) {
        val d = ctx.resources.displayMetrics.density
        val sp = ctx.resources.displayMetrics.scaledDensity
        var t = 0f
        val total = 3300f

        // הצבעים: למעלה ולמטה בנוזל, צבע הטיפה, וקו פני הנוזל
        val top: Int
        val bottom: Int
        val dropC: Int
        val lineC: Int
        val textC: Int

        init {
            fun byPct(red: Int, yellow: Int, green: Int) = if (pct < 20) red else if (pct < 50) yellow else green
            when (style) {
                0 -> { // צבע מלא
                    dropC = byPct(Color.rgb(255, 107, 107), Color.rgb(255, 213, 74), Color.rgb(62, 209, 140))
                    top = dropC
                    bottom = byPct(Color.rgb(192, 57, 43), Color.rgb(224, 168, 0), Color.rgb(19, 138, 87))
                    lineC = Color.TRANSPARENT
                    textC = Color.WHITE
                }
                1 -> { // ניאון
                    dropC = byPct(Color.rgb(255, 77, 141), Color.rgb(255, 184, 77), Color.rgb(34, 240, 200))
                    top = withA(dropC, 0x40)
                    bottom = withA(dropC, 0x10)
                    lineC = dropC
                    textC = dropC
                }
                3 -> { // הצבע שבחרת: אותו צבע, בהיר יותר כשיש יותר סוללה
                    val base = NotchPrefs.tint(ctx, "charge", Color.rgb(62, 209, 140))
                    val hsv = FloatArray(3)
                    Color.colorToHSV(base, hsv)
                    hsv[2] = (0.55f + pct / 100f * 0.45f).coerceAtMost(1f)
                    dropC = Color.HSVToColor(hsv)
                    top = dropC
                    hsv[2] *= 0.6f
                    bottom = Color.HSVToColor(hsv)
                    lineC = Color.TRANSPARENT
                    textC = Color.WHITE
                }
                4 -> { // שקיעה
                    dropC = Color.rgb(255, 154, 90)
                    top = dropC
                    bottom = Color.rgb(123, 63, 228)
                    lineC = Color.argb(120, 255, 255, 255)
                    textC = Color.WHITE
                }
                5 -> { // ים
                    dropC = Color.rgb(79, 195, 255)
                    top = Color.argb(0xCC, 79, 195, 255)
                    bottom = Color.rgb(11, 61, 145)
                    lineC = Color.argb(150, 255, 255, 255)
                    textC = Color.WHITE
                }
                else -> { // זכוכית
                    dropC = byPct(Color.rgb(255, 138, 138), Color.rgb(255, 217, 138), Color.rgb(143, 240, 196))
                    top = withA(dropC, 0x80)
                    bottom = withA(byPct(Color.rgb(212, 90, 90), Color.rgb(217, 168, 74), Color.rgb(63, 185, 138)), 0x55)
                    lineC = Color.argb(190, 255, 255, 255)
                    textC = Color.WHITE
                }
            }
        }

        fun withA(c: Int, a: Int) = Color.argb(a, Color.red(c), Color.green(c), Color.blue(c))

        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
        val dot = Paint(Paint.ANTI_ALIAS_FLAG)
        val big = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.DEFAULT_BOLD
            textSize = 44 * sp
        }
        val small = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 13 * sp
        }
        val path = Path()
        val surf = Path()
        // בועות: מיקום לרוחב, עיכוב וגודל (קבועים, כדי שלא יקפצו בין פריימים)
        val bubbles = List(if (style == 5) 14 else 7) { i ->
            Triple(((i * 37 + 11) % 90 + 5) / 100f, (i * 113 % 800).toFloat(), (4 + i * 7 % 6) * d / 2f)
        }

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

        override fun onDraw(c: Canvas) {
            val s = NotchOverlay.service ?: return
            val w = width.toFloat()
            val h = height.toFloat()
            val cut = NotchOverlay.cutout(s)
            val hx = cut.exactCenterX() + NotchPrefs.offsetX(s) * d
            val hy = cut.exactCenterY() + NotchPrefs.offsetY(s) * d
            // הכול נמוג בסוף
            val fadeOut = if (t > 2700f) 1f - (t - 2700f) / 600f else 1f

            // 1. הטיפה: נוטפת מהמצלמה, נמתחת ונופלת עד למטה
            if (t < 900f) {
                val f = t / 900f
                val y = hy + f * f * (h - hy)
                val r = 6 * d * (0.4f + 0.6f * min(1f, f * 4f))
                val stretch = 1f + 0.45f * f
                dot.color = dropC
                if (style == 1) dot.setShadowLayer(10 * d, 0f, 0f, dropC)
                c.drawOval(hx - r, y - r * stretch, hx + r, y + r * stretch, dot)
                dot.clearShadowLayer()
            }
            // 2. התזה למטה
            if (t in 880f..1500f) {
                val g = (t - 880f) / 620f
                dot.color = dropC
                dot.alpha = ((1f - g) * 255).toInt()
                for (k in 0 until 6) {
                    val dx = (k - 2.5f) * 30 * d * g
                    val dy = -(28 + (k * 7 % 20)) * d * sin(PI.toFloat() * g)
                    c.drawCircle(hx + dx, h - 4 * d + dy, 3 * d, dot)
                }
                dot.alpha = 255
            }
            if (t < 880f) return

            // 3. הנוזל עולה עד גובה הסוללה
            val g = min(1f, (t - 880f) / 1600f)
            val ease = 1f - (1f - g) * (1f - g) * (1f - g)
            val level = h * pct / 100f * ease
            val sy = h - level
            val amp = 6 * d * (0.4f + 0.6f * ease)
            val phase = t / 1600f * 2f * PI.toFloat()
            val lambda = w / 1.5f
            fun waveY(x: Float, ph: Float, a: Float) = sy + a * sin(2f * PI.toFloat() * x / lambda + ph)
            path.reset(); surf.reset()
            path.moveTo(0f, h)
            var x = 0f
            val step = 6 * d
            while (x <= w + step) {
                val y = waveY(x, phase, amp)
                path.lineTo(x, y)
                if (x == 0f) surf.moveTo(x, y) else surf.lineTo(x, y)
                x += step
            }
            path.lineTo(w, h)
            path.close()
            val alpha = (255 * fadeOut).toInt().coerceIn(0, 255)
            fill.shader = LinearGradient(0f, sy - amp, 0f, h, top, bottom, Shader.TileMode.CLAMP)
            fill.alpha = alpha
            c.drawPath(path, fill)
            // ים: גל שני מאחור, שזז לכיוון השני
            if (style == 5) {
                path.reset(); path.moveTo(0f, h)
                x = 0f
                while (x <= w + step) { path.lineTo(x, waveY(x, -phase * 1.4f, amp * 0.7f) + 5 * d); x += step }
                path.lineTo(w, h); path.close()
                fill.alpha = alpha / 2
                c.drawPath(path, fill)
            }
            // קו פני הנוזל: ברק לבן (זכוכית) או זוהר ניאון
            if (Color.alpha(lineC) > 0) {
                if (style == 1) {
                    for (i in 4 downTo 0) {
                        stroke.strokeWidth = (2f + i * 3f) * d
                        stroke.color = lineC
                        stroke.alpha = ((if (i == 0) 255 else 110 / (i + 1)) * fadeOut).toInt()
                        c.drawPath(surf, stroke)
                    }
                } else {
                    stroke.strokeWidth = 2 * d
                    stroke.color = lineC
                    stroke.alpha = (Color.alpha(lineC) * fadeOut).toInt()
                    c.drawPath(surf, stroke)
                }
            }
            // בועות שעולות בתוך הנוזל
            dot.color = Color.WHITE
            for ((bx, delay, r) in bubbles) {
                val bt = t - 900f - delay
                if (bt < 0f || bt > 2200f) continue
                val by = h - 10 * d - bt / 2200f * 170 * d
                if (by < sy + 4 * d) continue
                dot.alpha = ((1f - bt / 2200f) * 120 * fadeOut).toInt()
                c.drawCircle(bx * w, by, r, dot)
            }
            dot.alpha = 255

            // 4. האחוז בגדול, ומתחתיו מתי תהיה מלאה
            val ta = min(1f, (t - 900f) / 400f) * fadeOut
            if (ta > 0f) {
                big.color = textC
                big.alpha = (255 * ta).toInt()
                big.setShadowLayer(12 * d, 0f, 2 * d, if (style == 1) textC else Color.argb(110, 0, 0, 0))
                small.color = Color.WHITE
                small.alpha = (230 * ta).toInt()
                small.setShadowLayer(6 * d, 0f, 1 * d, Color.argb(120, 0, 0, 0))
                val cy = h * 0.42f
                c.drawText("⚡ $pct%", w / 2f, cy, big)
                c.drawText(sub, w / 2f, cy + 26 * d, small)
            }
        }
    }
}
