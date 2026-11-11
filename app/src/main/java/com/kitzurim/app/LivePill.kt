package com.kitzurim.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.max
import kotlin.math.min

/**
 * הגלולה החיה: הטבעת סביב המצלמה נמתחת לצדדים (עם קפיצה קטנה בסוף, כמו גומי)
 * ומראה פרטים: הודעה, שיחה, זמן שיחה, שיר, טעינה.
 * התוכן מחולק לשני צדדים, ובאמצע רווח בדיוק ברוחב החור, כדי ששום דבר לא יוסתר מאחורי המצלמה.
 */
@SuppressLint("StaticFieldLeak")
object LivePill {
    private var window: FrameLayout? = null
    private var pill: LinearLayout? = null
    private var kind: String? = null
    private var timerText: TextView? = null
    private var collapsedW = 0
    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { hide() }

    /** מפתח ההתראה של השיחה הנכנסת שמוצגת עכשיו */
    var callKey: String? = null
        private set

    // ---------- מה מציגים ----------

    fun showMessage(sender: String, text: String, color: Int, open: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillMessages(s)) return
        if (kind == "call" || kind == "incall") return // שיחה חשובה יותר
        show("msg", 310, color, 4500, onTap = { runCatching { open?.send() } }) { ctx, left, right ->
            left.addView(letter(ctx, sender, color))
            left.addView(label(ctx, sender, 13f, Color.WHITE, bold = true))
            right.addView(label(ctx, text, 12.5f, Color.rgb(200, 208, 220)))
        }
    }

    fun showCall(key: String, name: String, decline: PendingIntent?, answer: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s)) return
        callKey = key
        val blue = Color.rgb(79, 179, 255)
        show("call", 320, blue, null, onTap = null) { ctx, left, right ->
            left.addView(button(ctx, "✕", Color.rgb(229, 72, 77)) {
                runCatching { decline?.send() }
                hide()
            })
            left.addView(label(ctx, name, 13f, Color.WHITE, bold = true))
            right.addView(label(ctx, "שיחה נכנסת", 11.5f, blue))
            right.addView(button(ctx, "✓", Color.rgb(42, 212, 154)) {
                runCatching { answer?.send() }
                hide()
            })
        }
    }

    fun callNotificationRemoved(key: String) {
        if (key == callKey) {
            callKey = null
            if (kind == "call") hide()
        }
    }

    /** בזמן שיחה: גלולה קטנה עם טיימר. נקראת כל שנייה */
    fun inCall(seconds: Int) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s)) return
        val text = "%02d:%02d".format(seconds / 60, seconds % 60)
        if (kind == "incall") {
            timerText?.text = text
            return
        }
        val green = Color.rgb(42, 212, 154)
        show("incall", 150, green, null, onTap = null) { ctx, left, right ->
            left.addView(label(ctx, "●", 12f, green))
            val t = label(ctx, text, 12.5f, Color.WHITE, bold = true)
            timerText = t
            right.addView(t)
        }
    }

    fun callEnded() {
        if (kind == "incall" || kind == "call") hide()
    }

    fun showMusic(title: String) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillMusic(s)) return
        if (kind == "call" || kind == "incall") return
        val purple = Color.rgb(179, 136, 255)
        show("music", 240, purple, 3500, onTap = null) { ctx, left, right ->
            val disc = label(ctx, "♪", 13f, Color.WHITE, bold = true)
            disc.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(purple)
            }
            disc.gravity = Gravity.CENTER
            val dp = ctx.resources.displayMetrics.density
            disc.layoutParams = LinearLayout.LayoutParams((22 * dp).toInt(), (22 * dp).toInt())
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 3000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { disc.rotation = it.animatedValue as Float }
                start()
            }
            left.addView(disc)
            right.addView(label(ctx, title, 12.5f, Color.WHITE))
        }
    }

    fun showCharging(percent: Int, minutesToFull: Long?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCharging(s)) return
        if (kind == "call" || kind == "incall") return
        val green = Color.rgb(62, 209, 140)
        show("charge", 240, green, 3000, onTap = null) { ctx, left, right ->
            left.addView(label(ctx, "⚡ $percent%", 13f, green, bold = true))
            right.addView(
                label(
                    ctx,
                    minutesToFull?.let { "מלאה בעוד " + Battery.minutesText(it) } ?: "בטעינה",
                    12f,
                    Color.WHITE,
                )
            )
        }
    }

    // ---------- הגלולה עצמה ----------

    private fun show(
        newKind: String,
        widthDp: Int,
        color: Int,
        autoMs: Long?,
        onTap: (() -> Unit)?,
        fill: (Context, LinearLayout, LinearLayout) -> Unit,
    ) {
        val s = NotchOverlay.service ?: return
        val wm = NotchOverlay.wm ?: return
        main.removeCallbacks(autoClose)
        removeNow()
        timerText = null

        val d = s.resources.displayMetrics.density
        val screenW = s.resources.displayMetrics.widthPixels
        val c = NotchOverlay.cutout(s)
        val cx = c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = c.exactCenterY() + NotchPrefs.offsetY(s) * d
        val hole = max(c.width(), c.height())
        val h = (max(hole.toFloat(), 22 * d) + 16 * d).toInt()
        val targetW = min(widthDp * d, screenW - 16 * d).toInt()
        collapsedW = h
        // החלון רחב קצת יותר מהגלולה, כדי שיהיה מקום לקפיצה של המתיחה
        val winW = min((targetW * 1.15f).toInt(), screenW)
        val winH = h + (8 * d).toInt()

        val root = FrameLayout(s)
        val p = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            background = GradientDrawable().apply {
                cornerRadius = h / 2f
                setColor(Color.BLACK)
                setStroke((1.5f * d).toInt(), color)
            }
            setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
        }
        // שני צדדים, ורווח באמצע ברוחב החור (ועוד קצת), כדי שהטקסט לא יוסתר
        val left = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            alpha = 0f
        }
        val right = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            alpha = 0f
        }
        p.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        p.addView(View(s), LinearLayout.LayoutParams(hole + (14 * d).toInt(), 1))
        p.addView(right, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
        fill(s, left, right)
        if (onTap != null) {
            p.setOnClickListener {
                onTap()
                hide()
            }
        }
        root.addView(p, FrameLayout.LayoutParams(collapsedW, h, Gravity.CENTER))

        runCatching {
            wm.addView(
                root,
                NotchOverlay.params(winW, winH, (cx - winW / 2f).toInt(), (cy - winH / 2f).toInt(), touchable = true),
            )
        }.onFailure { return }
        window = root
        pill = p
        kind = newKind

        // מתיחה אלסטית: נמתחת לצדדים ועוצרת עם קפיצה קטנה
        ValueAnimator.ofInt(collapsedW, targetW).apply {
            duration = 520
            interpolator = OvershootInterpolator(1.6f)
            addUpdateListener {
                val lp = p.layoutParams
                lp.width = it.animatedValue as Int
                p.layoutParams = lp
            }
            start()
        }
        left.animate().alpha(1f).setStartDelay(180).setDuration(200).start()
        right.animate().alpha(1f).setStartDelay(180).setDuration(200).start()

        if (autoMs != null) main.postDelayed(autoClose, autoMs)
    }

    /** מתכווצת חזרה לעיגול ונעלמת */
    fun hide() {
        main.removeCallbacks(autoClose)
        val p = pill ?: return removeNow()
        kind = null
        for (i in 0 until p.childCount) p.getChildAt(i).animate().alpha(0f).setDuration(120).start()
        ValueAnimator.ofInt(p.width.coerceAtLeast(collapsedW), collapsedW).apply {
            duration = 240
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val lp = p.layoutParams
                lp.width = it.animatedValue as Int
                p.layoutParams = lp
            }
            start()
        }
        main.postDelayed({ if (pill === p) removeNow() }, 260)
    }

    private fun removeNow() {
        window?.let { w -> runCatching { NotchOverlay.wm?.removeView(w) } }
        window = null
        pill = null
        kind = null
    }

    // ---------- רכיבים קטנים ----------

    private fun label(ctx: Context, text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        if (bold) typeface = Typeface.DEFAULT_BOLD
        val d = ctx.resources.displayMetrics.density
        setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
    }

    /** עיגול עם האות הראשונה של השם */
    private fun letter(ctx: Context, name: String, color: Int): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = name.trim().take(1)
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt())
        }
    }

    private fun button(ctx: Context, symbol: String, color: Int, onClick: () -> Unit): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = symbol
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams((28 * d).toInt(), (28 * d).toInt()).apply {
                marginStart = (2 * d).toInt()
                marginEnd = (2 * d).toInt()
            }
            setOnClickListener { onClick() }
        }
    }
}
