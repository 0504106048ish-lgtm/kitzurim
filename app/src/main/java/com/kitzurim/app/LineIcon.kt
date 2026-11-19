package com.kitzurim.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PathMeasure
import android.graphics.RectF
import android.view.View
import android.widget.LinearLayout

/**
 * סמל בקו דק בצבע של הגלולה, שמצייר את עצמו כשהגלולה מופיעה (עם זוהר קטן).
 * במקום האימוג'י, כדי שהגלולה תיראה כמו חתיכה אחת.
 */
class LineIcon(ctx: Context, kind: String, private val color: Int, sizeDp: Float = 16f) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val px = sizeDp * d
    // הסמלים מצוירים על רשת של 20×20
    private val u = px / 20f
    private val path = Path()
    private val part = Path()
    private var p = 0f

    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        strokeWidth = 1.8f * u
        this.color = this@LineIcon.color
    }
    private val glow = Paint(line).apply { strokeWidth = 4.5f * u }

    init {
        build(kind)
        layoutParams = LinearLayout.LayoutParams(px.toInt(), px.toInt()).apply {
            marginStart = (2 * d).toInt()
            marginEnd = (2 * d).toInt()
        }
    }

    private fun r(l: Float, t: Float, rr: Float, b: Float) = RectF(l * u, t * u, rr * u, b * u)
    private fun m(x: Float, y: Float) = path.moveTo(x * u, y * u)
    private fun l(x: Float, y: Float) = path.lineTo(x * u, y * u)
    private fun seg(x1: Float, y1: Float, x2: Float, y2: Float) { m(x1, y1); l(x2, y2) }

    private fun build(kind: String) {
        when (kind) {
            "clip" -> {
                path.addRoundRect(r(5f, 4f, 15f, 17f), 2 * u, 2 * u, Path.Direction.CW)
                path.addRoundRect(r(8f, 2f, 12f, 4.6f), 1.2f * u, 1.2f * u, Path.Direction.CW)
                seg(8f, 9f, 12f, 9f)
                seg(8f, 12f, 11f, 12f)
            }
            "down" -> {
                seg(10f, 3f, 10f, 13f)
                m(6f, 9f); l(10f, 13f); l(14f, 9f)
                seg(4f, 16.5f, 16f, 16.5f)
            }
            "buds" -> {
                m(4f, 12f); l(4f, 10f)
                path.arcTo(r(4f, 4f, 16f, 16f), 180f, 180f)
                l(16f, 12f)
                path.addRoundRect(r(3f, 11.5f, 6.4f, 16.5f), 1.4f * u, 1.4f * u, Path.Direction.CW)
                path.addRoundRect(r(13.6f, 11.5f, 17f, 16.5f), 1.4f * u, 1.4f * u, Path.Direction.CW)
            }
            "bolt" -> {
                m(11f, 2f); l(5f, 11f); l(9.5f, 11f); l(8.5f, 18f); l(15f, 8.5f); l(10.5f, 8.5f); path.close()
            }
            "torch" -> {
                m(7f, 2f); l(13f, 2f); l(12f, 7f); l(8f, 7f); path.close()
                path.addRoundRect(r(8f, 7f, 12f, 17.5f), 1.2f * u, 1.2f * u, Path.Direction.CW)
                seg(10f, 10.5f, 10f, 12.5f)
            }
            "key" -> {
                path.addCircle(7f * u, 10f * u, 3.4f * u, Path.Direction.CW)
                seg(10.4f, 10f, 17f, 10f)
                seg(14.5f, 10f, 14.5f, 12.6f)
                seg(16.8f, 10f, 16.8f, 12.2f)
            }
            "bell" -> {
                m(6f, 12.5f); l(6f, 9f)
                path.arcTo(r(6f, 5f, 14f, 13f), 180f, 180f)
                l(14f, 12.5f); l(15f, 14f); l(5f, 14f); path.close()
                path.addArc(r(8.5f, 14.5f, 11.5f, 17.5f), 0f, 180f)
            }
            "phone" -> {
                path.addRoundRect(r(6f, 2.5f, 14f, 17.5f), 2 * u, 2 * u, Path.Direction.CW)
                seg(9f, 15f, 11f, 15f)
            }
            else -> path.addCircle(10f * u, 10f * u, 6f * u, Path.Direction.CW)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        // מצייר את עצמו רגע אחרי שהגלולה נפתחת
        ValueAnimator.ofFloat(0f, 1f).apply {
            startDelay = 280
            duration = 750
            interpolator = android.view.animation.DecelerateInterpolator(1.4f)
            addUpdateListener { p = it.animatedValue as Float; invalidate() }
            start()
        }
    }

    override fun onDraw(c: Canvas) {
        // רק החלק שכבר "צויר" מכל קו
        part.reset()
        val pm = PathMeasure(path, false)
        do {
            val len = pm.length
            if (len > 0f) pm.getSegment(0f, len * p, part, true)
        } while (pm.nextContour())
        // זוהר רך מתחת לקו (חזק יותר בזמן הציור, ונרגע בסוף)
        glow.alpha = (60 + 50 * (1f - p)).toInt()
        c.drawPath(part, glow)
        c.drawPath(part, line)
    }
}
