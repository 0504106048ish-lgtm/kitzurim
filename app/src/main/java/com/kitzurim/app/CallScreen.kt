package com.kitzurim.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max

/**
 * מסך שיחה נכנסת בסגנון ניאון, מעל מסך השיחה של סמסונג.
 * הטלפון של סמסונג ממשיך לעבוד כרגיל: "ענה" ו"דחה" כאן לוחצים על הכפתורים שבהתראה של השיחה.
 * נסגר כשעונים, דוחים, או כשהשיחה הפסיקה לצלצל.
 */
@SuppressLint("StaticFieldLeak")
object CallScreen {
    private var window: FrameLayout? = null
    private var key: String? = null
    private val anims = mutableListOf<ValueAnimator>()

    private val GREEN = Color.rgb(42, 212, 154)
    private val RED = Color.rgb(255, 77, 94)

    fun isShowing() = window != null

    /** מראה את המסך. מחזיר false אם לא הצליח (ואז מראים את הגלולה הרגילה) */
    fun show(
        callKey: String,
        name: String,
        sub: String?,
        photo: Drawable?,
        decline: PendingIntent?,
        answer: PendingIntent?,
    ): Boolean {
        val s = NotchOverlay.service ?: return false
        val wm = NotchOverlay.wm ?: return false
        if (!NotchPrefs.callScreen(s)) return false
        if (window != null && key == callKey) return true // אותה שיחה: כבר מוצג
        hide()
        key = callKey
        val d = s.resources.displayMetrics.density
        fun dp(v: Float) = (v * d).toInt()

        val root = FrameLayout(s).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.rgb(20, 32, 42), Color.rgb(7, 9, 13), Color.rgb(7, 9, 13)),
            ).apply {
                gradientType = GradientDrawable.RADIAL_GRADIENT
                gradientRadius = s.resources.displayMetrics.heightPixels * 0.7f
                setGradientCenter(0.5f, 0.25f)
            }
            alpha = 0f
            // נגיעה ברקע לא עוברת לסמסונג שמתחת
            isClickable = true
        }

        // ---------- למעלה: "שיחה נכנסת" ----------
        val top = text(s, "שיחה נכנסת", 14f, GREEN, bold = false)
        root.addView(top, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(90f) })

        // ---------- תמונה עם שתי טבעות נושמות ----------
        val avatarSize = dp(116f)
        val ringSize = dp(146f)
        val avatarTop = dp(150f)
        fun ring(delay: Long): View {
            val v = View(s).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setStroke(dp(2f), GREEN)
                    setColor(Color.TRANSPARENT)
                }
            }
            root.addView(v, FrameLayout.LayoutParams(ringSize, ringSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply {
                topMargin = avatarTop - (ringSize - avatarSize) / 2
            })
            ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                addUpdateListener {
                    val f = it.animatedValue as Float
                    // נושמת: גדלה קצת ונעלמת, וחוזרת
                    val wave = if (f < 0.5f) f * 2 else (1 - f) * 2
                    v.scaleX = 1f + 0.14f * wave
                    v.scaleY = 1f + 0.14f * wave
                    v.alpha = 0.9f - 0.7f * wave
                }
                start()
                anims += this
            }
            return v
        }
        ring(0)
        ring(800)
        val avatar: View = if (photo != null) {
            ImageView(s).apply {
                setImageDrawable(photo)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.DKGRAY) }
                clipToOutline = true
            }
        } else {
            text(s, name.trim().take(1), 44f, Color.WHITE, bold = true).apply {
                gravity = Gravity.CENTER
                background = GradientDrawable(
                    GradientDrawable.Orientation.TL_BR,
                    intArrayOf(Color.rgb(58, 123, 213), Color.rgb(106, 76, 255)),
                ).apply { shape = GradientDrawable.OVAL }
            }
        }
        root.addView(avatar, FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = avatarTop })

        // ---------- שם ומספר ----------
        val names = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        names.addView(text(s, name, 30f, Color.WHITE, bold = true).apply {
            gravity = Gravity.CENTER
            maxLines = 2
        })
        if (!sub.isNullOrBlank() && sub != name) {
            names.addView(text(s, sub, 14f, Color.rgb(168, 176, 192), bold = false).apply { gravity = Gravity.CENTER })
        }
        root.addView(names, FrameLayout.LayoutParams(-1, -2, Gravity.TOP).apply {
            topMargin = avatarTop + avatarSize + dp(34f)
            leftMargin = dp(24f)
            rightMargin = dp(24f)
        })

        // ---------- למטה: מחליקים ימינה כדי לענות ----------
        val trackH = dp(64f)
        val knob = dp(54f)
        val pad = dp(5f)
        val track = FrameLayout(s).apply {
            background = GradientDrawable().apply {
                cornerRadius = trackH / 2f
                setColor(Color.argb(20, 255, 255, 255))
                setStroke(dp(1f), Color.argb(34, 255, 255, 255))
            }
            layoutDirection = View.LAYOUT_DIRECTION_LTR
        }
        val hint = text(s, "החלק ימינה כדי לענות", 14f, Color.rgb(184, 194, 212), bold = false)
        track.addView(hint, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        val k = text(s, "✆︎", 24f, Color.BLACK, bold = true).apply {
            gravity = Gravity.CENTER
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(GREEN) }
            elevation = dp(8f).toFloat()
        }
        track.addView(k, FrameLayout.LayoutParams(knob, knob, Gravity.LEFT or Gravity.CENTER_VERTICAL).apply { leftMargin = pad })
        root.addView(track, FrameLayout.LayoutParams(-1, trackH, Gravity.BOTTOM).apply {
            leftMargin = dp(26f)
            rightMargin = dp(26f)
            bottomMargin = dp(118f)
        })

        // רמז: הכפתור זז קצת ימינה כל כמה שניות
        val nudge = ValueAnimator.ofFloat(0f, 1f, 0f, 0f).apply {
            duration = 1800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { if (!dragging) k.translationX = dp(16f) * (it.animatedValue as Float) }
            start()
        }
        anims += nudge

        var downX = 0f
        k.setOnTouchListener { _, e ->
            val maxX = (track.width - knob - 2 * pad).toFloat()
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    dragging = true
                    downX = e.rawX - k.translationX
                }
                MotionEvent.ACTION_MOVE -> {
                    val x = (e.rawX - downX).coerceIn(0f, maxX)
                    k.translationX = x
                    hint.alpha = 1f - x / max(1f, maxX)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (k.translationX > maxX * 0.7f) {
                        // ענית
                        k.animate().translationX(maxX).setDuration(120).start()
                        runCatching { answer?.send() }
                        main.postDelayed({ hide() }, 180)
                    } else {
                        k.animate().translationX(0f).setInterpolator(OvershootInterpolator()).setDuration(260)
                            .withEndAction { dragging = false }.start()
                        hint.animate().alpha(1f).setDuration(200).start()
                    }
                }
            }
            true
        }

        // ---------- דחייה ----------
        val dec = text(s, "✕  דחה", 16f, RED, bold = true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(26f), dp(12f), dp(26f), dp(12f))
            background = GradientDrawable().apply {
                cornerRadius = dp(24f).toFloat()
                setColor(Color.argb(38, 255, 77, 94))
                setStroke(dp(1.5f), RED)
            }
            setOnClickListener {
                runCatching { decline?.send() }
                hide()
            }
        }
        root.addView(dec, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply { bottomMargin = dp(46f) })

        val lp = NotchOverlay.params(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            0,
            0,
            touchable = true,
        )
        runCatching { wm.addView(root, lp) }.onFailure {
            stopAnims()
            key = null
            return false
        }
        window = root
        root.animate().alpha(1f).setDuration(220).start()
        return true
    }

    private var dragging = false
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** ההתראה של השיחה נעלמה (ענו, דחו, או שהמתקשר ניתק) */
    fun removed(callKey: String) {
        if (callKey == key) hide()
    }

    fun hide() {
        val w = window ?: return
        window = null
        key = null
        dragging = false
        stopAnims()
        w.animate().alpha(0f).setDuration(160).withEndAction {
            runCatching { NotchOverlay.wm?.removeView(w) }
        }.start()
    }

    private fun stopAnims() {
        anims.forEach { it.cancel() }
        anims.clear()
    }

    private fun text(ctx: Context, t: String, sp: Float, color: Int, bold: Boolean) = TextView(ctx).apply {
        text = t
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        if (bold) typeface = Typeface.DEFAULT_BOLD
        textDirection = View.TEXT_DIRECTION_ANY_RTL
    }
}
