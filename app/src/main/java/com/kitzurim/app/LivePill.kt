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
    private var bg: View? = null
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
        show("msg", 300, color, 3500, onTap = { runCatching { open?.send() } }) { ctx, left, right ->
            // בעברית קוראים מימין: מי ששלח בצד ימין, ההודעה בצד שמאל
            right.addView(label(ctx, sender, 14.5f, Color.WHITE, bold = true))
            right.addView(letter(ctx, sender, color))
            left.addView(label(ctx, text, 13.5f, Color.rgb(210, 216, 228)))
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
            left.addView(label(ctx, name, 14.5f, Color.WHITE, bold = true))
            right.addView(label(ctx, "שיחה נכנסת", 12.5f, blue))
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
            val t = label(ctx, text, 14f, Color.WHITE, bold = true)
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
            right.addView(label(ctx, title, 13.5f, Color.WHITE))
        }
    }

    fun showCharging(percent: Int, minutesToFull: Long?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCharging(s)) return
        if (kind == "call" || kind == "incall") return
        val green = Color.rgb(62, 209, 140)
        show("charge", 240, green, 3000, onTap = null) { ctx, left, right ->
            left.addView(label(ctx, "⚡ $percent%", 14.5f, green, bold = true))
            right.addView(
                label(
                    ctx,
                    minutesToFull?.let { "מלאה בעוד " + Battery.minutesText(it) } ?: "בטעינה",
                    13.5f,
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
        // באפליקציה שבה קיצורים כבוי: רק שיחה עוברת
        if (newKind != "call" && newKind != "incall" && NotchOverlay.offHere()) return
        main.removeCallbacks(autoClose)
        removeNow()
        timerText = null

        val d = s.resources.displayMetrics.density
        val screenW = s.resources.displayMetrics.widthPixels
        val c = NotchOverlay.cutout(s)
        val cx = c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = c.exactCenterY() + NotchPrefs.offsetY(s) * d
        val hole = max(c.width(), c.height())
        val h = (max(hole.toFloat(), 22 * d) + 18 * d).toInt()
        // רוחב לפי התוכן, עד הרווח שבין השעה לסוללה (שלא נכסה אותן)
        val maxW = (screenW - 2 * 58 * d).toInt()
        val gapW = hole + (14 * d).toInt()
        val padW = (12 * d).toInt()
        collapsedW = h

        val root = FrameLayout(s)
        // רק הרקע נמתח; התוכן כבר בגודל הסופי ורק מופיע, כדי שהטקסט לא יקפוץ בזמן המתיחה
        val b = View(s).apply {
            background = GradientDrawable().apply {
                cornerRadius = h / 2f
                setColor(Color.BLACK)
                setStroke((1.5f * d).toInt(), color)
            }
        }
        val p = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(padW, 0, padW, 0)
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
        fill(s, left, right)
        if (onTap != null) {
            p.setOnClickListener {
                onTap()
                hide()
            }
        }

        // רוחב לפי התוכן: כמה שצריך, עם מרווח נוח (לא צמוד), ולא יותר מהמקסימום.
        // שני הצדדים באותו רוחב, כדי שהחור יישאר בדיוק באמצע
        val maxSide = ((maxW - gapW - 2 * padW) / 2).coerceAtLeast(0)
        fit(left, maxSide)
        fit(right, maxSide)
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxSide, View.MeasureSpec.AT_MOST)
        val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        left.measure(wSpec, hSpec)
        right.measure(wSpec, hSpec)
        val side = min(max(left.measuredWidth, right.measuredWidth) + (6 * d).toInt(), maxSide)
        val targetW = max(2 * side + gapW + 2 * padW, collapsedW)
        p.addView(left, LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))
        p.addView(View(s), LinearLayout.LayoutParams(gapW, 1))
        p.addView(right, LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))

        // החלון רק קצת רחב מהגלולה (מקום לקפיצה של המתיחה), ושקוף: רואים מה שמתחת.
        // לא לחרוג מלמעלה: אם החור קרוב לקצה, הגלולה יורדת קצת
        val pillTop = max(0, (cy - h / 2f).toInt())
        val winW = min((targetW * 1.12f).toInt(), screenW)
        root.addView(
            b,
            FrameLayout.LayoutParams(collapsedW, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pillTop },
        )
        root.addView(
            p,
            FrameLayout.LayoutParams(targetW, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pillTop },
        )

        runCatching {
            wm.addView(
                root,
                NotchOverlay.params(winW, pillTop + h + (4 * d).toInt(), (cx - winW / 2f).toInt(), 0, touchable = newKind != "incall"),
            )
        }.onFailure { return }
        window = root
        pill = p
        bg = b
        kind = newKind

        // מתיחה אלסטית: נמתחת לצדדים ועוצרת עם קפיצה קטנה ועדינה
        ValueAnimator.ofInt(collapsedW, targetW).apply {
            duration = 420
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val lp = b.layoutParams
                lp.width = it.animatedValue as Int
                b.layoutParams = lp
            }
            start()
        }
        left.animate().alpha(1f).setStartDelay(220).setDuration(180).start()
        right.animate().alpha(1f).setStartDelay(220).setDuration(180).start()

        if (autoMs != null) main.postDelayed(autoClose, autoMs)
    }

    /** מתכווצת חזרה לעיגול ונעלמת */
    fun hide() {
        main.removeCallbacks(autoClose)
        val p = pill ?: return removeNow()
        val b = bg ?: return removeNow()
        kind = null
        p.animate().alpha(0f).setDuration(120).start()
        ValueAnimator.ofInt(b.width.coerceAtLeast(collapsedW), collapsedW).apply {
            duration = 240
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val lp = b.layoutParams
                lp.width = it.animatedValue as Int
                b.layoutParams = lp
            }
            start()
        }
        main.postDelayed({ if (pill === p) removeNow() }, 260)
    }

    private fun removeNow() {
        window?.let { w -> runCatching { NotchOverlay.wm?.removeView(w) } }
        window = null
        pill = null
        bg = null
        kind = null
    }

    /**
     * צד שלא נכנס: קודם מקטינים קצת את הכתב, ואם עדיין ארוך מקצרים רק את הטקסט,
     * כך שהעיגולים והכפתורים תמיד נשארים שלמים
     */
    private fun fit(side: LinearLayout, maxSide: Int) {
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        side.measure(free, free)
        val need = side.measuredWidth
        if (need <= maxSide || need == 0) return
        val texts = (0 until side.childCount).map { side.getChildAt(it) }
            .filter { it is TextView && (it.layoutParams == null || it.layoutParams.width == LinearLayout.LayoutParams.WRAP_CONTENT) }
            .map { it as TextView }
        if (texts.isEmpty()) return
        val scale = max(0.8f, maxSide.toFloat() / need)
        texts.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize * scale) }
        side.measure(free, free)
        if (side.measuredWidth <= maxSide) return
        var fixed = 0
        for (i in 0 until side.childCount) {
            val v = side.getChildAt(i)
            if (v !in texts) {
                val lp = v.layoutParams as? LinearLayout.LayoutParams
                fixed += v.measuredWidth + (lp?.leftMargin ?: 0) + (lp?.rightMargin ?: 0)
            }
        }
        val each = ((maxSide - fixed) / texts.size).coerceAtLeast(0)
        texts.forEach { it.maxWidth = each }
    }

    /** כפתור "דוגמא" במסך הבית: מראה איך תיראה הגלולה, גם כשהמתגים כבויים */
    fun demo(type: String): Boolean {
        val s = NotchOverlay.service ?: return false
        if (kind == "call" || kind == "incall") return true
        when (type) {
            "msg" -> {
                val green = Color.rgb(37, 211, 102)
                show("msg", 300, green, 3500, onTap = null) { ctx, left, right ->
                    right.addView(label(ctx, "דני", 14.5f, Color.WHITE, bold = true))
                    right.addView(letter(ctx, "דני", green))
                    left.addView(label(ctx, "מה קורה? מגיע היום?", 13.5f, Color.rgb(210, 216, 228)))
                }
            }
            "charge" -> {
                val green = Color.rgb(62, 209, 140)
                val b = Battery.read(s, null)
                show("charge", 240, green, 3000, onTap = null) { ctx, left, right ->
                    left.addView(label(ctx, "⚡ ${b.percent}%", 14.5f, green, bold = true))
                    right.addView(label(ctx, "מלאה בעוד 1:10 שע׳", 13.5f, Color.WHITE))
                }
            }
            "music" -> {
                val purple = Color.rgb(179, 136, 255)
                show("music", 240, purple, 3500, onTap = null) { ctx, left, right ->
                    left.addView(label(ctx, "♪", 14f, purple, bold = true))
                    right.addView(label(ctx, "שיר לדוגמא", 13.5f, Color.WHITE))
                }
            }
        }
        return true
    }

    /** נכנסו לאפליקציה שבה קיצורים כבוי: סוגרים, חוץ משיחה */
    fun hideUnlessCall() {
        if (kind != null && kind != "call" && kind != "incall") hide()
    }

    // ---------- רכיבים קטנים ----------

    private fun label(ctx: Context, text: String, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
        this.text = text
        setTextColor(color)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        // טקסט בעברית נכתב מימין לשמאל: סימן שאלה ושלוש נקודות בסוף המשפט, לא בתחילתו
        textDirection = View.TEXT_DIRECTION_ANY_RTL
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
