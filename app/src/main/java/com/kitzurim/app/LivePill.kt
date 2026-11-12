package com.kitzurim.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateInterpolator
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
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
    private var body: TextView? = null
    private var extra: View? = null
    private var kind: String? = null
    private var timerText: TextView? = null
    private var collapsedW = 0
    private val main = Handler(Looper.getMainLooper())
    private val autoClose = Runnable { hide() }

    /** מפתח ההתראה של השיחה הנכנסת שמוצגת עכשיו */
    var callKey: String? = null
        private set

    // ---------- מה מציגים ----------

    fun showMessage(sender: String, text: String, color: Int, open: PendingIntent?, fromRing: Boolean = false, msg: LastMsg? = null) {
        val s = NotchOverlay.service ?: return
        // הקשה על העיגול הדלוק: מראים גם אם הגלולה של הודעות כבויה
        if (!fromRing && (!NotchPrefs.pill(s) || !NotchPrefs.pillMessages(s))) return
        if (inCallPill()) return // שיחה חשובה יותר
        // שורה אחת בשורת המצב (לא יורדת): מימין לחור השם, ובקצה העיגול עם האות.
        // משמאל לחור ההודעה, שזזה פעם אחת לאט עד הסוף. הקשה פותחת
        val marquee = msgRow(sender, text, color, open)
        // זוכרים איזו התראה מוצגת: אם היא נמחקת (קראת, ענית), הגלולה נסגרת מיד
        if (kind == "msg") shownKey = msg?.key
        marquee?.let { closeAfterMarquee(it) }
    }

    /** גלולת ההודעה בשורה אחת. מחזירה את הטקסט שזז (כדי לדעת מתי לסגור) */
    private fun msgRow(sender: String, text: String, color: Int, open: PendingIntent?): MarqueeText? {
        // הודעה קצרה שנכנסת בצד אחד: מחולקת לשניים, השם (והעיגול עם האות) מימין לחור וההודעה משמאל
        val s = NotchOverlay.service ?: return null
        val d = s.resources.displayMetrics.density
        val msgText = text.replace('\n', ' ').trim()
        val c = NotchOverlay.cutout(s)
        val hole = max(c.width(), c.height())
        val maxSide = (s.resources.displayMetrics.widthPixels - 2 * NotchPrefs.pillMargin(s) * d - (hole + 14 * d) - 2 * 12 * d) / 2 - 10 * d
        val paint = android.text.TextPaint().apply { textSize = 12.5f * s.resources.displayMetrics.scaledDensity }
        if (paint.measureText(msgText) <= maxSide) {
            val grey = Color.rgb(210, 216, 228)
            show("msg", 300, color, 3000, onTap = { launch(open) }) { ctx, left, right ->
                left.addView(label(ctx, msgText, 12.5f, grey))
                right.addView(label(ctx, sender, 12.5f, Color.WHITE, bold = true))
                // רווח גמיש: השם צמוד לחור, והעיגול עם האות בקצה
                right.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
                right.addView(letter(ctx, sender, color))
            }
            return null
        }
        // שורה אחת לכל רוחב הגלולה: "דני:  ההודעה" זז פעם אחת עד הסוף, והעיגול עם האות בקצה הימני
        val line = android.text.SpannableStringBuilder().apply {
            append(sender.trim())
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(":  ")
            append(text.replace('\n', ' ').trim())
        }
        show("msg", 300, color, 3000, onTap = { launch(open) }, long = line, longColor = Color.rgb(210, 216, 228)) { ctx, _, right ->
            right.addView(letter(ctx, sender, color))
        }
        return null
    }

    /** הגלולה נסגרת רק אחרי שהטקסט סיים לזוז, ועוד רגע לקרוא את הסוף */
    private fun closeAfterMarquee(m: MarqueeText) {
        m.post {
            if (kind != "msg") return@post
            main.removeCallbacks(autoClose)
            main.postDelayed(autoClose, max(3000L, m.durationMs(m.width) + 1200L))
        }
    }

    private var shownKey: String? = null

    /** התראה נמחקה: אם הגלולה מראה אותה עכשיו, נסגרת מיד ולא מחכה עד הסוף */
    fun notificationGone(key: String) {
        if (key == shownKey && kind == "msg") hide()
    }

    /** אותו צבע, קצת יותר בהיר (בשביל ברק בכפתור זכוכית) */
    private fun lighter(c: Int) = Color.rgb(
        Color.red(c) + (255 - Color.red(c)) / 4,
        Color.green(c) + (255 - Color.green(c)) / 4,
        Color.blue(c) + (255 - Color.blue(c)) / 4,
    )

    /** כפתור דלוק (השתקה, רמקול): לבן מלא וסמל כהה. כבוי: זכוכית שקופה וסמל לבן */
    private fun setBtnOn(v: ImageView, on: Boolean) {
        val d = v.resources.displayMetrics.density
        v.background = GradientDrawable().apply {
            cornerRadius = 14 * d
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
            if (on) {
                colors = intArrayOf(Color.WHITE, Color.rgb(225, 230, 238))
            } else {
                colors = intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(30, 255, 255, 255))
                setStroke((0.8f * d).toInt().coerceAtLeast(1), Color.argb(90, 255, 255, 255))
            }
        }
        v.imageTintList = ColorStateList.valueOf(if (on) Color.rgb(10, 12, 16) else Color.WHITE)
    }

    /** כפתור עגול קטן לשורה הדקה */
    private fun thinBtn(ctx: Context, icon: Int, bg: Int, wide: Boolean = false, onClick: () -> Unit): ImageView {
        val d = ctx.resources.displayMetrics.density
        // סמל בקו דק (לא אימוג'י), בעיגול קטן; קטנים מספיק כדי ששלושה ייכנסו בצד אחד של הגלולה
        return ImageView(ctx).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val pad = (6 * d).toInt()
            setPadding(pad, pad, pad, pad)
            // כפתור זכוכית: אפור כהה הופך ללבן שקוף עם מסגרת בהירה; כפתור צבעוני (נתק, ענה) נשאר בצבע שלו עם ברק
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                if (bg == Color.rgb(42, 47, 58)) {
                    colors = intArrayOf(Color.argb(70, 255, 255, 255), Color.argb(30, 255, 255, 255))
                    setStroke((0.8f * d).toInt().coerceAtLeast(1), Color.argb(90, 255, 255, 255))
                } else {
                    colors = intArrayOf(lighter(bg), bg)
                    setStroke((0.8f * d).toInt().coerceAtLeast(1), Color.argb(70, 255, 255, 255))
                }
            }
            layoutParams = LinearLayout.LayoutParams(((if (wide) 32 else 26) * d).toInt(), (26 * d).toInt()).apply {
                marginStart = (2 * d).toInt()
                marginEnd = (2 * d).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    /** שם מודגש, ומתחתיו שורה קטנה (סטטוס או ההודעה) */
    private fun twoLines(ctx: Context, top: String, bottom: String, bottomColor: Int) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT
        // הרוחב נקבע ב-fit לפי המקום שבאמת יש בצד (מקצר עם "…")
        addView(label(ctx, top, 12.5f, Color.WHITE, bold = true).apply { gravity = Gravity.RIGHT },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.RIGHT })
        addView(label(ctx, bottom.replace('\n', ' '), 11.5f, bottomColor).apply { gravity = Gravity.RIGHT },
            LinearLayout.LayoutParams(-2, -2).apply { gravity = Gravity.RIGHT })
    }

    fun showCall(key: String, name: String, decline: PendingIntent?, answer: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s))) return
        callKey = key
        val blue = NotchPrefs.tint(NotchOverlay.service, "call", Color.rgb(79, 179, 255))
        // שורה אחת דקה: מימין מי מתקשר, משמאל דחה וענה
        show("call", 320, blue, null, onTap = null) { ctx, left, right ->
            left.addView(thinBtn(ctx, R.drawable.ic_c_close, Color.rgb(229, 72, 77), true) {
                runCatching { decline?.send() }
                hide()
            })
            left.addView(thinBtn(ctx, R.drawable.ic_c_phone, Color.rgb(42, 212, 154), true) {
                runCatching { answer?.send() }
                hide()
            })
            right.addView(twoLines(ctx, name, "שיחה נכנסת", blue))
        }
    }

    fun callNotificationRemoved(key: String) {
        if (key == callKey) {
            callKey = null
            if (kind == "call") hide()
        }
    }

    // ---------- שיחה פעילה (יוצאת או אחרי שענית) ----------
    // מה שידוע מההתראה של השיחה: שם, מתי ענו, והכפתורים (ניתוק, השתקה, רמקול)
    private var callName: String? = null
    private var callActions: List<Notification.Action> = emptyList()
    private var callInfoKnown = false
    private var answeredAt = 0L // שעון רגיל (מילישניות). 0 = עוד לא ענו
    private var dialSince = 0L // מתי התחיל החיוג (כדי להקטין אחרי 2 שניות)
    private var bigUntil = 0L
    private var menuOpen = false

    /** ההתראה של שיחה פעילה התעדכנה (נקרא מ-ReplyListener) */
    fun ongoingCall(name: String, answeredWall: Long?, actions: List<Notification.Action>) {
        callInfoKnown = true
        callName = name
        callActions = actions
        if (answeredWall != null && answeredAt == 0L) {
            answeredAt = answeredWall
            // ענו: קודם גדול עם השם לכמה שניות, ואז קטן
            bigUntil = SystemClock.elapsedRealtime() + 2000
        }
    }

    private fun callAction(words: List<String>) = callActions.firstOrNull { a ->
        val t = a.title?.toString()?.lowercase() ?: ""
        words.any { t.contains(it) }
    }

    /** בזמן שיחה. נקראת כל שנייה. secondsFromAudio = מתי הטלפון נכנס למצב שיחה */
    fun inCall(secondsFromAudio: Int) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s))) return
        // מסך השיחה של קיצורים פתוח: אין צורך בגלולה
        if (CallActivity.visible) {
            if (inCallPill()) hide()
            return
        }
        // ההתראה של השיחה עוד לא הגיעה: מחכים רגע, כדי לא להראות שניות לפני שענו
        if (!callInfoKnown && secondsFromAudio < 3) return
        val answered = !callInfoKnown || answeredAt > 0
        val secs = if (answeredAt > 0) ((System.currentTimeMillis() - answeredAt) / 1000).toInt().coerceAtLeast(0) else secondsFromAudio
        val t = clock(secs * 1000L)
        val name = callName ?: "שיחה"
        val green = NotchPrefs.tint(NotchOverlay.service, "call", Color.rgb(42, 212, 154))
        when {
            // מחייג: גדול עם השם, בלי שניות
            // התפריט פתוח (גם בזמן חיוג): לא מחליפים אותו, רק מעדכנים את הזמן
            menuOpen -> if (answered && kind == "callmenu") timerText?.text = t
            // מחייג: 2 שניות גדול עם השם, ואז קטן עם "…" (בלי שניות) עד שעונים
            !answered -> {
                val now = SystemClock.elapsedRealtime()
                if (dialSince == 0L) dialSince = now
                if (now - dialSince < 2000) {
                    if (kind != "dial") {
                        show("dial", 300, green, null, onTap = null, long = nameLine(name, "מחייג…")) { ctx, _, right ->
                            right.addView(phoneIcon(ctx, green))
                        }
                    }
                } else if (kind != "incall") {
                    callMark(green)
                }
            }
            // ענו עכשיו: גדול עם השם והזמן
            SystemClock.elapsedRealtime() < bigUntil -> if (kind != "callbig") {
                show("callbig", 300, green, null, onTap = { openCallMenu() }, long = nameLine(name, "בשיחה · $t")) { ctx, _, right ->
                    right.addView(phoneIcon(ctx, green))
                }
            } else {
                body?.text = nameLine(name, "בשיחה · $t")
            }
            // אחר כך: רק סימן קטן שיש שיחה (הזמן כבר בהתראה). הקשה פותחת את השיחה
            else -> if (kind != "incall") callMark(green)
        }
    }

    /** סימן קטן ליד המצלמה: נקודה ירוקה וטלפון, בלי זמן */
    private fun callMark(green: Int) {
        show("incall", 150, green, null, onTap = { openCallMenu() }) { ctx, left, right ->
            val d = ctx.resources.displayMetrics.density
            left.addView(View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(green)
                }
                layoutParams = LinearLayout.LayoutParams((7 * d).toInt(), (7 * d).toInt()).apply {
                    marginStart = (5 * d).toInt()
                    marginEnd = (5 * d).toInt()
                }
            })
            right.addView(phoneIcon(ctx, green))
        }
    }

    /** תפריט השיחה: שם, זמן, וכפתורים מההתראה של השיחה (השתקה, ניתוק, רמקול) */
    private fun openCallMenu() {
        // קיצורים היא אפליקציית הטלפון: הקשה על הגלולה מחזירה למסך השיחה המלא
        val s = NotchOverlay.service
        if (s != null && Calls.main() != null) {
            // הגלולה מתרחבת לכל המסך (ההפך ממה שקורה כשיוצאים), ואז נפתח מסך השיחה
            val open = {
                runCatching {
                    s.startActivity(
                        android.content.Intent(s, CallActivity::class.java)
                            .addFlags(
                                android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP or
                                    android.content.Intent.FLAG_ACTIVITY_NO_ANIMATION,
                            )
                    )
                }
                Unit
            }
            if (!morphScreen(grow = true, onEnd = open)) open()
            return
        }
        menuOpen = true
        main.postDelayed({ showCallMenu() }, 280)
    }

    /**
     * יוצאים ממסך השיחה (בית או חזור): כל המסך מתכווץ לגלולה הקטנה סביב המצלמה,
     * ואז הגלולה של השיחה נשארת שם.
     */
    fun shrinkFromScreen() {
        morphScreen(grow = false) {}
    }

    /**
     * מעבר בין מסך השיחה לגלולה: grow=false המסך מתכווץ לחור, grow=true הגלולה מתרחבת לכל המסך.
     * מחזיר false אם לא הצליח להתחיל (ואז פותחים בלי אנימציה)
     */
    private fun morphScreen(grow: Boolean, onEnd: () -> Unit): Boolean {
        val s = NotchOverlay.service ?: return false
        val wm = NotchOverlay.wm ?: return false
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s)) return false
        val d = s.resources.displayMetrics.density
        val dm = s.resources.displayMetrics
        val screenW = dm.widthPixels
        val screenH = dm.heightPixels + (80 * d).toInt()
        val c = NotchOverlay.cutout(s)
        val cx = c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = c.exactCenterY() + NotchPrefs.offsetY(s) * d
        val hole = max(c.width(), c.height())
        val endH = (max(hole.toFloat(), 22 * d) + 18 * d)
        val endW = endH + 90 * d
        val green = NotchPrefs.tint(NotchOverlay.service, "call", Color.rgb(42, 212, 154))

        // משמאל לימין: אחרת בעברית המיקום נמדד מצד ימין והמסגרת עפה לפינה במקום לחור
        val root = FrameLayout(s).apply { layoutDirection = View.LAYOUT_DIRECTION_LTR }
        val bgDrawable = GradientDrawable().apply {
            cornerRadius = 40 * d
            // כמו הרקע של מסך השיחה (ניאון ירקרק)
            colors = intArrayOf(Color.rgb(15, 42, 36), Color.rgb(6, 9, 12))
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = screenH * 0.6f
            setStroke((1.5f * d).toInt(), Color.TRANSPARENT)
        }
        val box = View(s).apply { background = bgDrawable }
        root.addView(box, FrameLayout.LayoutParams(screenW, screenH, Gravity.TOP or Gravity.LEFT))
        val lp = NotchOverlay.params(screenW, screenH, 0, 0, touchable = false)
        runCatching { wm.addView(root, lp) }.onFailure { return false }
        // מתרחבת: הגלולה הקטנה נעלמת ברגע שהמסגרת מתחילה לגדול ממנה
        if (grow) removeNow()

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (grow) 360 else 420
            interpolator = if (grow) android.view.animation.AccelerateDecelerateInterpolator() else android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                // f=1 גלולה, f=0 כל המסך
                val f = if (grow) 1f - (it.animatedValue as Float) else it.animatedValue as Float
                val w = screenW + (endW - screenW) * f
                val h = screenH + (endH - screenH) * f
                val left = (screenW / 2f) + (cx - screenW / 2f) * f - w / 2f
                val top = (screenH / 2f) + (cy - screenH / 2f) * f - h / 2f
                val p = box.layoutParams as FrameLayout.LayoutParams
                p.width = w.toInt()
                p.height = h.toInt()
                p.leftMargin = left.toInt()
                p.topMargin = top.toInt()
                box.layoutParams = p
                bgDrawable.cornerRadius = 40 * d + (endH / 2f - 40 * d) * f
                // ליד הגלולה: שחור עם המסגרת הירוקה של הגלולה
                if (f > 0.7f) {
                    bgDrawable.colors = intArrayOf(GlassDrawable.TOP, GlassDrawable.TOP)
                    bgDrawable.setStroke((1.5f * d).toInt(), green)
                } else {
                    bgDrawable.colors = intArrayOf(Color.rgb(15, 42, 36), Color.rgb(6, 9, 12))
                    bgDrawable.setStroke((1.5f * d).toInt(), Color.TRANSPARENT)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    if (grow) {
                        // מסך השיחה נפתח מתחת, ורק אז המסגרת שהתרחבה נעלמת
                        onEnd()
                        main.postDelayed({ runCatching { wm.removeView(root) } }, 350)
                    } else {
                        // הגלולה של השיחה נכנסת למקום, ורק אז המסגרת שהתכווצה נעלמת
                        inCall(99)
                        onEnd()
                        main.postDelayed({ runCatching { wm.removeView(root) } }, 120)
                    }
                }
            })
            start()
        }
        return true
    }

    private fun showCallMenu() {
        val s = NotchOverlay.service ?: return
        if (!menuOpen) return
        val d = s.resources.displayMetrics.density
        val green = NotchPrefs.tint(NotchOverlay.service, "call", Color.rgb(42, 212, 154))
        // שורה אחת: השם, ולידו (קרוב לחור) "מחייג…" או הזמן בירוק
        val who = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
        }
        val name = label(s, callName ?: "שיחה", 12.5f, Color.WHITE, bold = true)
        val secs = if (answeredAt > 0) ((System.currentTimeMillis() - answeredAt) / 1000).toInt() else 0
        val time = label(s, if (answeredAt > 0) clock(secs * 1000L) else "מחייג…", 12f, green)
        who.addView(time, LinearLayout.LayoutParams(-2, -2))
        who.addView(name, LinearLayout.LayoutParams(-2, -2))
        val row = mutableListOf<View>()
        // כפתור: קודם מה שיש בהתראה של השיחה, ואם אין, קיצורים עושה את זה בעצמה
        fun btn(icon: Int, bg: Int, wide: Boolean, a: Notification.Action?, after: ((ImageView) -> Unit)? = null, own: () -> Unit): ImageView {
            lateinit var v: ImageView
            v = thinBtn(s, icon, bg, wide) {
                val sent = a != null && runCatching { a.actionIntent.send() }.isSuccess
                if (!sent) runCatching { own() }
                after?.invoke(v)
                main.removeCallbacks(autoClose)
                main.postDelayed(autoClose, 6000)
            }
            return v
        }
        val dark = Color.rgb(42, 47, 58)
        val audio = s.getSystemService(android.media.AudioManager::class.java)
        // השתקה ורמקול: כפתור דלוק נהיה לבן עם סמל כהה, כדי שרואים שהוא פועל
        val own = Calls.main() != null
        var muted = if (own) Calls.muted else audio?.isMicrophoneMute == true
        var speaker = if (own) Calls.route == android.telecom.CallAudioState.ROUTE_SPEAKER else audio?.isSpeakerphoneOn == true
        @Suppress("DEPRECATION")
        val muteBtn = btn(R.drawable.ic_c_mute, dark, false, if (own) null else callAction(listOf("השתק", "mute")), after = { b -> muted = !muted; setBtnOn(b, muted) }) {
            if (own) Calls.toggleMute() else audio?.let { it.isMicrophoneMute = !it.isMicrophoneMute }
        }
        setBtnOn(muteBtn, muted)
        row.add(muteBtn)
        row.add(btn(R.drawable.ic_c_hang, Color.rgb(229, 72, 77), true, callAction(listOf("נתק", "סיים", "סיום", "hang", "end"))) {
            val call = Calls.main()
            if (call != null) Calls.hangup(call)
            else if (Build.VERSION.SDK_INT >= 28) {
                @Suppress("DEPRECATION", "MissingPermission")
                s.getSystemService(android.telecom.TelecomManager::class.java)?.endCall()
            }
            menuOpen = false
            hide()
        })
        @Suppress("DEPRECATION")
        val speakerBtn = btn(R.drawable.ic_c_speaker, dark, false, if (own) null else callAction(listOf("רמקול", "speaker")), after = { b -> speaker = !speaker; setBtnOn(b, speaker) }) {
            if (own) Calls.toggleSpeaker() else audio?.let { it.isSpeakerphoneOn = !it.isSpeakerphoneOn }
        }
        setBtnOn(speakerBtn, speaker)
        row.add(speakerBtn)
        // הקשה על השם: חוזרים לגלולה הקטנה
        show("callmenu", 300, green, 6000, onTap = { menuOpen = false }) { _, left, right ->
            row.forEach { left.addView(it) }
            right.addView(who)
        }
        timerText = time
    }

    fun callEnded() {
        callInfoKnown = false
        callName = null
        callActions = emptyList()
        answeredAt = 0L
        dialSince = 0L
        bigUntil = 0L
        menuOpen = false
        if (kind == "incall" || kind == "call" || kind == "dial" || kind == "callbig" || kind == "callmenu") hide()
    }

    /** השם בשורה אחת, ומתחת "מחייג…" או הזמן */
    private fun nameLine(name: String, status: String): CharSequence =
        android.text.SpannableStringBuilder().apply {
            append(name.trim())
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append("\n")
            append(status)
        }

    private fun phoneIcon(ctx: Context, color: Int): TextView {
        val d = ctx.resources.displayMetrics.density
        return TextView(ctx).apply {
            text = "✆︎"
            setTextColor(Color.BLACK)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams((22 * d).toInt(), (22 * d).toInt())
        }
    }

    /** פותח את מה שההתראה פותחת (למשל הצ'אט בוואטסאפ). מאנדרואיד 14 צריך לאשר במפורש פתיחה מהרקע */
    private fun launch(pi: PendingIntent?) {
        pi ?: return
        val s = NotchOverlay.service
        runCatching {
            if (Build.VERSION.SDK_INT >= 34 && s != null) {
                val o = ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                pi.send(s, 0, null, null, null, null, o.toBundle())
            } else {
                pi.send()
            }
        }
    }

    // ---------- נגן: נפתח בהקשה על העיגול כשמתנגן שיר ----------

    private var player: MediaController? = null
    private var pv: PlayerViews? = null
    private var dragging = false
    private var lastArt: Bitmap? = null
    private var glowArt: Bitmap? = null
    private var glowDone = false
    private const val PLAYER_MS = 7000L

    private class PlayerViews(
        val title: TextView,
        val artist: TextView,
        val cover: ImageView?,
        val seek: WaveBar?,
        val pos: TextView?,
        val dur: TextView?,
        val play: TextView,
    )

    private val playerTick = object : Runnable {
        override fun run() {
            updatePlayer()
            main.postDelayed(this, 500)
        }
    }

    fun isPlayerOpen() = kind == "player"

    private val CALL_KINDS = setOf("call", "incall", "dial", "callbig", "callmenu")
    private fun inCallPill() = kind in CALL_KINDS

    /**
     * הקשה על העיגול בזמן שגלולת שיחה פתוחה: הולכת לגלולה (פותחת את השיחה),
     * ולא לקיצור שבחרת להקשה. מחזיר true אם ההקשה טופלה כאן
     */
    fun tapPill(): Boolean {
        if (!inCallPill()) return false
        pill?.takeIf { it.hasOnClickListeners() }?.performClick()
        return true
    }

    /** מתי הנגן נסגר בנגיעה מחוץ לו: אם הנגיעה הייתה על העיגול, לא פותחים אותו מיד שוב */
    private var closedByOutside = 0L
    fun closedJustNow() = SystemClock.elapsedRealtime() - closedByOutside < 500

    /** כל נגיעה בנגן מאריכה את הזמן שהוא פתוח */
    private fun keepOpen() {
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, PLAYER_MS)
    }

    /** demo: בהדמיה מראים רק חלק אחד של הנגן (פס, קודם/הבא, תמונה), כדי שיראו מה כל מתג מוסיף */
    fun showPlayer(c: MediaController, demo: Triple<Boolean, Boolean, Boolean>? = null) {
        val optProgress = demo?.first ?: NotchPrefs.playerProgress(NotchOverlay.service ?: return)
        val optSkip = demo?.second ?: NotchPrefs.playerSkip(NotchOverlay.service ?: return)
        val optCover = demo?.third ?: NotchPrefs.playerCover(NotchOverlay.service ?: return)
        val s = NotchOverlay.service ?: return
        if (inCallPill()) return
        val d = s.resources.displayMetrics.density
        val purple = NotchPrefs.tint(NotchOverlay.service, "music", Color.rgb(179, 136, 255))
        val grey = Color.rgb(160, 168, 184)
        player = c
        lastArt = null

        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            setPadding((4 * d).toInt(), 0, (4 * d).toInt(), 0)
        }

        // שורה: תמונת האלבום מימין, ולידה שם השיר והזמר. לחיצה פותחת את אפליקציית המוזיקה
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setOnClickListener {
                launch(c.sessionActivity)
                hide()
            }
        }
        val cover = if (optCover) ImageView(s).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 14 * d
                setColor(Color.rgb(40, 44, 56))
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams((60 * d).toInt(), (60 * d).toInt()).apply { marginEnd = (12 * d).toInt() }
        } else null
        cover?.let { row.addView(it) }
        val texts = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }
        val title = label(s, "", 15f, Color.WHITE, bold = true)
        val artist = label(s, "", 12.5f, Color.rgb(199, 203, 214))
        texts.addView(title)
        texts.addView(artist)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        // פס התקדמות: אפשר לגרור כדי לקפוץ בשיר
        var seek: WaveBar? = null
        var pos: TextView? = null
        var dur: TextView? = null
        if (optProgress) {
            // גל קול: הלבן מתמלא עד איפה שהשיר. גוררים עליו כדי לקפוץ בשיר
            seek = WaveBar(s, onSeek = { f ->
                val length = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                if (length > 0) runCatching { c.transportControls.seekTo((length * f).toLong()) }
                keepOpen()
            }, onDrag = { on ->
                dragging = on
                if (on) main.removeCallbacks(autoClose)
            }).apply { seed = ((c.metadata?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: "").hashCode() % 100) / 10f }
            box.addView(seek, LinearLayout.LayoutParams(-1, (30 * d).toInt()).apply { topMargin = (12 * d).toInt() })
            val times = FrameLayout(s)
            pos = label(s, "0:00", 11f, grey)
            dur = label(s, "0:00", 11f, grey)
            times.addView(pos, FrameLayout.LayoutParams(-2, -2, Gravity.LEFT))
            times.addView(dur, FrameLayout.LayoutParams(-2, -2, Gravity.RIGHT))
            box.addView(times)
        }

        // כפתורים: הקודם, הפעלה / השהיה, הבא
        val controls = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER
        }
        fun ctl(symbol: String, size: Int, filled: Boolean, onClick: () -> Unit) = TextView(s).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextSize(TypedValue.COMPLEX_UNIT_SP, if (filled) 18f else 17f)
            setTextColor(if (filled) Color.BLACK else Color.WHITE)
            if (filled) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                // לבן עם ברק עדין (מלמעלה לבן, למטה אפרפר), כמו כפתור זכוכית
                orientation = GradientDrawable.Orientation.TOP_BOTTOM
                colors = intArrayOf(Color.WHITE, Color.rgb(227, 230, 238))
            }
            layoutParams = LinearLayout.LayoutParams((size * d).toInt(), (size * d).toInt()).apply {
                marginStart = (14 * d).toInt()
                marginEnd = (14 * d).toInt()
            }
            setOnClickListener {
                onClick()
                keepOpen()
                main.postDelayed({ updatePlayer() }, 250)
            }
        }
        val skip = optSkip
        if (skip) controls.addView(ctl("⏮︎", 40, false) { runCatching { c.transportControls.skipToPrevious() } })
        val play = ctl("❚❚", 46, true) {
            runCatching {
                if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
                else c.transportControls.play()
            }
        }
        controls.addView(play)
        if (skip) controls.addView(ctl("⏭︎", 40, false) { runCatching { c.transportControls.skipToNext() } })
        box.addView(controls, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (4 * d).toInt() })

        pv = PlayerViews(title, artist, cover, seek, pos, dur, play)
        updatePlayer()
        // הקשה על השורה של המצלמה סוגרת את הנגן
        show("player", 320, purple, PLAYER_MS, onTap = {}, extra = box) { ctx, _, right ->
            right.addView(label(ctx, "♪", 14f, purple, bold = true))
        }
        glowArt = null
        glowDone = false
        if (kind == "player") {
            applyGlow()
            main.post(playerTick)
        }
    }

    /** ההילה של הנגן בצבעים של תמונת השיר (מתחלפת כשהשיר מתחלף) */
    private fun applyGlow() {
        val c = player ?: return
        if (kind != "player") return
        val md = c.metadata
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
            ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        if (glowDone && art === glowArt) return
        glowArt = art
        glowDone = true
        val g = bg?.background as? GlassDrawable ?: return
        val d = (NotchOverlay.service ?: return).resources.displayMetrics.density
        val cols = artColors(art) ?: NotchPrefs.tint(NotchOverlay.service, "music", Color.rgb(179, 136, 255)).let { it to it }
        g.glowA = cols.first
        g.glowB = cols.second
        g.setStroke((1.5f * d).toInt(), cols.first)
        g.invalidateSelf()
    }

    /** מעדכן את הנגן: שם, תמונה, מיקום בשיר, והכפתור הפעלה / השהיה */
    private fun updatePlayer() {
        val c = player ?: return
        val v = pv ?: return
        applyGlow()
        val md = c.metadata
        val st = c.playbackState
        v.title.text = md?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""
        val who = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
            ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM) ?: ""
        v.artist.text = who
        v.artist.visibility = if (who.isBlank()) View.GONE else View.VISIBLE
        v.cover?.let { img ->
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (art !== lastArt) {
                lastArt = art
                img.setImageBitmap(art)
            }
        }
        val playing = st?.state == PlaybackState.STATE_PLAYING
        v.play.text = if (playing) "❚❚" else "▶︎"
        val length = md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
        if (st != null && length > 0) {
            val at = (st.position + if (playing) ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong() else 0L)
                .coerceIn(0L, length)
            if (!dragging) v.seek?.progress = (at * 1000 / length).toInt()
            v.pos?.text = clock(at)
            v.dur?.text = clock(length)
        }
    }

    private fun clock(ms: Long): String {
        val sec = ms / 1000
        return "%d:%02d".format(sec / 60, sec % 60)
    }

    // ---------- ניווט, אוזניות, הורדה, מוזיקה קבועה ----------

    private var navKey: String? = null
    private var navStep: String? = null
    private var navIcon: ImageView? = null
    private val navSmall = Runnable { navCompact() }
    private var navLabel = ""
    private var navOpen: PendingIntent? = null
    private var navImg: android.graphics.drawable.Drawable? = null
    private var navTint = false
    private val NAV_BLUE get() = NotchPrefs.tint(NotchOverlay.service, "nav", Color.rgb(51, 204, 255))

    /** אין כרגע גלולה, או שמוצגת רק המוזיקה הקבועה (שמפנה מקום לכל דבר אחר) */
    private fun free() = kind == null || kind == "musicLive"

    /**
     * ניווט (וויז / גוגל מפות): גלולה קטנה מאוד, עם הסמל של האפליקציה והשם שלה בכחול.
     * אם בהתראה יש מרחק לפנייה הבאה, רואים אותו במקום השם, ובפנייה חדשה ההוראה המלאה לכמה שניות.
     * הקשה פותחת את אפליקציית הניווט.
     */
    fun showNav(key: String, pkg: String, title: String, text: String, arrow: android.graphics.drawable.Drawable?, open: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillNav(s))) return
        if (inCallPill() || kind == "player" || kind == "msg") return
        val hasDist = title.any { it.isDigit() }
        val appName = runCatching {
            s.packageManager.getApplicationLabel(s.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull() ?: if (pkg == "com.waze") "Waze" else "מפות"
        val appIcon = runCatching { s.packageManager.getApplicationIcon(pkg) }.getOrNull()
        navKey = key
        navOpen = open
        navLabel = if (hasDist) title else appName
        navImg = if (hasDist && arrow != null) arrow else appIcon
        navTint = hasDist && arrow != null
        val step = if (hasDist) text else ""
        val newStep = step.isNotBlank() && step != navStep
        navStep = step
        when {
            newStep -> {
                main.removeCallbacks(navSmall)
                show("navbig", 300, NAV_BLUE, null, onTap = { launch(open) }, long = navLine(title, step)) { ctx, _, right ->
                    right.addView(navIconView(ctx))
                }
                main.postDelayed(navSmall, 4500)
            }
            kind == "navbig" -> body?.text = navLine(title, step)
            kind == "nav" -> {
                timerText?.text = navLabel
                navImg?.let { navIcon?.setImageDrawable(it) }
            }
            free() -> navCompact()
        }
    }

    private fun navCompact() {
        val s = NotchOverlay.service ?: return
        if (navKey == null || inCallPill() || kind == "player" || kind == "msg") return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillNav(s))) return
        val open = navOpen
        show("nav", 150, NAV_BLUE, null, onTap = { launch(open) }) { ctx, left, right ->
            val t = label(ctx, navLabel, 12.5f, NAV_BLUE, bold = true)
            timerText = t
            left.addView(t)
            right.addView(navIconView(ctx))
        }
    }

    /** הניווט הסתיים (ההתראה נעלמה) */
    fun navEnded(key: String) {
        if (key != navKey) return
        navKey = null
        navStep = null
        main.removeCallbacks(navSmall)
        if (kind == "nav" || kind == "navbig") hide()
    }

    private fun navLine(distance: String, step: String): CharSequence =
        android.text.SpannableStringBuilder().apply {
            append(distance.trim())
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(NAV_BLUE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (step.isNotBlank()) {
                append("\n")
                append(step.trim())
            }
        }

    /** הסמל של וויז / מפות (או חץ הפנייה) */
    private fun navIconView(ctx: Context): View {
        val d = ctx.resources.displayMetrics.density
        val img = navImg ?: return label(ctx, "➤", 13f, NAV_BLUE, bold = true)
        return ImageView(ctx).apply {
            setImageDrawable(img)
            if (navTint) setColorFilter(NAV_BLUE)
            layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt())
            navIcon = this
        }
    }

    // ---------- מוזיקה כל הזמן: תמונת האלבום מסתובבת וגלי צליל ----------
    private var liveArt: ImageView? = null

    /** נקרא כל שנייה כשמתנגן שיר. מראה את הגלולה הקבועה אם אין משהו אחר על המסך */
    fun musicLive(art: android.graphics.Bitmap?) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillMusicLive(s))) return
        if (navKey != null) return // בזמן ניווט הניווט חשוב יותר
        if (songWindow != null) return // שיר חדש מוצג עכשיו (הוא יצא מהגלולה הזו)
        if (kind == "musicLive") {
            if (art != null) liveArt?.setImageBitmap(art)
            return
        }
        if (kind != null) return
        val purple = NotchPrefs.tint(NotchOverlay.service, "music", Color.rgb(179, 136, 255))
        show("musicLive", 120, purple, null, onTap = {
            NotchOverlay.playingController(s)?.let { c -> main.postDelayed({ showPlayer(c) }, 280) }
            Unit
        }) { ctx, left, right ->
            left.addView(eqBars(ctx, purple))
            right.addView(artView(ctx, art, purple).also { liveArt = it })
        }
        // המסגרת: עמומה, ומעליה הקו הסגול שמתמלא עד סוף השיר
        val b = bg
        val r = window
        if (kind == "musicLive" && b != null && r != null) {
            val d = s.resources.displayMetrics.density
            (b.background as? GradientDrawable)?.setStroke((1.5f * d).toInt(), Color.argb(60, Color.red(purple), Color.green(purple), Color.blue(purple)))
            outline = PillOutline(s, b, 1.8f * d).also { r.addView(it, FrameLayout.LayoutParams(-1, -1)) }
            refreshOutline()
        }
    }

    // ---------- המסגרת של גלולת המוזיקה: מתמלאת לפי השיר, וכחולה לשנייה כשמשנים עוצמה ----------
    private var outline: PillOutline? = null
    private var songProgress = 1f
    private var volValue = 0f
    private var volUntil = 0L
    private val outlineTick = Runnable { refreshOutline() }

    /** איפה השיר (0 עד 1). אורך לא ידוע: מסגרת מלאה */
    fun musicProgress(p: Float?) {
        songProgress = p ?: 1f
        refreshOutline()
    }

    /** שינו עוצמה בזמן שהגלולה של המוזיקה פתוחה */
    fun musicVolume(v: Float) {
        if (kind != "musicLive") return
        volValue = v
        volUntil = SystemClock.uptimeMillis() + 1500
        refreshOutline()
        main.removeCallbacks(outlineTick)
        main.postDelayed(outlineTick, 1550)
    }

    private fun refreshOutline() {
        val o = outline ?: return
        val s = NotchOverlay.service
        val vol = SystemClock.uptimeMillis() < volUntil
        if (vol) o.set(volValue, NotchPrefs.tint(s, "volume", Color.rgb(79, 179, 255)))
        else o.set(songProgress, NotchPrefs.tint(s, "music", Color.rgb(179, 136, 255)))
    }

    /** מסגרת שמתמלאת סביב הגלולה, מהאמצע למעלה עם כיוון השעון */
    private class PillOutline(ctx: Context, private val target: View, private val sw: Float) : View(ctx) {
        private var progress = 0f
        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = sw
            strokeCap = android.graphics.Paint.Cap.ROUND
        }
        private val path = android.graphics.Path()
        private val seg = android.graphics.Path()
        private val pm = android.graphics.PathMeasure()

        init {
            target.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> invalidate() }
        }

        fun set(p: Float, color: Int) {
            progress = p.coerceIn(0f, 1f)
            paint.color = color
            invalidate()
        }

        override fun onDraw(c: android.graphics.Canvas) {
            if (progress <= 0f) return
            val l = target.left + target.translationX + sw / 2
            val t = target.top + sw / 2
            val r = target.right + target.translationX - sw / 2
            val b = target.bottom - sw / 2
            val rad = (b - t) / 2f
            if (r - l < 2 * rad) return
            val cx = (l + r) / 2
            path.reset()
            path.moveTo(cx, t)
            path.lineTo(r - rad, t)
            path.arcTo(android.graphics.RectF(r - 2 * rad, t, r, b), -90f, 180f)
            path.lineTo(l + rad, b)
            path.arcTo(android.graphics.RectF(l, t, l + 2 * rad, b), 90f, 180f)
            path.lineTo(cx, t)
            pm.setPath(path, false)
            seg.reset()
            pm.getSegment(0f, pm.length * progress, seg, true)
            c.drawPath(seg, paint)
        }
    }

    /** המוזיקה נעצרה */
    fun musicStopped() {
        if (kind == "musicLive") hide()
    }

    private fun artView(ctx: Context, art: android.graphics.Bitmap?, color: Int): ImageView {
        val d = ctx.resources.displayMetrics.density
        return ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            clipToOutline = true
            if (art != null) setImageBitmap(art)
            layoutParams = LinearLayout.LayoutParams((18 * d).toInt(), (18 * d).toInt())
            // מסתובבת לאט, כמו תקליט
            ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 6000
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { rotation = it.animatedValue as Float }
                start()
            }
        }
    }

    /** ארבעה פסים קטנים שקופצים בקצב */
    private fun eqBars(ctx: Context, color: Int): View {
        val d = ctx.resources.displayMetrics.density
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val maxH = 14 * d
        listOf(0L, 220L, 440L, 120L).forEach { delay ->
            val bar = View(ctx).apply {
                background = GradientDrawable().apply {
                    cornerRadius = 2 * d
                    setColor(color)
                }
            }
            row.addView(bar, LinearLayout.LayoutParams((2.5f * d).toInt(), maxH.toInt()).apply {
                marginStart = (1.2f * d).toInt()
                marginEnd = (1.2f * d).toInt()
            })
            ValueAnimator.ofFloat(0.25f, 1f).apply {
                duration = 450
                startDelay = delay
                repeatCount = ValueAnimator.INFINITE
                repeatMode = ValueAnimator.REVERSE
                addUpdateListener { bar.scaleY = it.animatedValue as Float }
                start()
            }
        }
        return row
    }

    /** אוזניות התחברו: השם והסוללה */
    fun showEarbuds(name: String, battery: Int?) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillEarbuds(s))) return
        if (inCallPill() || kind == "player") return
        val c = NotchPrefs.tint(NotchOverlay.service, "buds", Color.rgb(160, 168, 255))
        val text = if (battery != null) "$name · $battery%" else name
        if (kind == "buds") {
            body?.text = text
            return
        }
        show("buds", 260, c, 3000, onTap = null, long = text) { ctx, _, right ->
            right.addView(label(ctx, "🎧︎", 15f, c, bold = true))
        }
    }

    /** צילום מסך נשמר: תמונה קטנה של הצילום ו"נשמר". הקשה פותחת את הצילום */
    fun showScreenshot(thumb: Bitmap?, uri: android.net.Uri) {
        val s = NotchOverlay.service ?: return
        if (inCallPill() || kind == "player") return
        val blue = NotchPrefs.tint(s, "shot", Color.rgb(79, 179, 255))
        val d = s.resources.displayMetrics.density
        val open = {
            runCatching {
                s.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW)
                        .setDataAndType(uri, "image/*")
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                )
            }
            Unit
        }
        val line = android.text.SpannableStringBuilder().apply {
            append("צילום מסך נשמר ✓")
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        show("shot", 260, blue, 3000, onTap = open, long = line, longColor = Color.WHITE) { ctx, _, right ->
            right.addView(ImageView(ctx).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = GradientDrawable().apply {
                    cornerRadius = 4 * d
                    setColor(blue)
                }
                clipToOutline = true
                if (thumb != null) setImageBitmap(thumb)
            }, LinearLayout.LayoutParams((14 * d).toInt(), (22 * d).toInt()))
        }
    }

    /** הורדה הסתיימה */
    fun showDownloaded(name: String, open: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillDownloads(s))) return
        if (inCallPill() || kind == "player") return
        val blue = NotchPrefs.tint(NotchOverlay.service, "download", Color.rgb(79, 179, 255))
        val line = android.text.SpannableStringBuilder().apply {
            append("הורד ✓")
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (name.isNotBlank()) append("  ").append(name.trim())
        }
        show("download", 280, blue, 3000, onTap = { launch(open) }, long = line) { ctx, _, right ->
            right.addView(label(ctx, "⬇︎", 15f, blue, bold = true))
        }
    }

    /**
     * שיר חדש התחיל: שורה דקה אחת (תמונה קטנה, "שם השיר · הזמר") שיורדת מהעיגול שסביב המצלמה
     * אל מתחת לשורת המצב, ואחרי 3 שניות חוזרת לתוך העיגול. הקשה פותחת את הנגן המלא.
     */
    private var songWindow: FrameLayout? = null
    private var songClosing: FrameLayout? = null
    private var songImg: ImageView? = null

    /** הפעם הבאה שהגלולה מופיעה: בלי אנימציה (חוזרת למקום שהיא כבר הייתה בו) */
    private var instantNext = false

    fun showMusic(title: String) {
        val s = NotchOverlay.service ?: return
        val wm = NotchOverlay.wm ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillMusic(s))) return
        if (inCallPill() || kind == "player" || kind == "msg") return
        if (NotchOverlay.offHere() || NotchOverlay.pillBlocked("music")) return
        songWindow?.let { w -> runCatching { wm.removeView(w) } }
        songWindow = null

        val purple = NotchPrefs.tint(NotchOverlay.service, "music", Color.rgb(179, 136, 255))
        val grey = Color.rgb(160, 168, 184)
        val d = s.resources.displayMetrics.density
        val screenW = s.resources.displayMetrics.widthPixels
        val c = NotchOverlay.cutout(s)
        val cx = c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = c.exactCenterY() + NotchPrefs.offsetY(s) * d
        val hole = max(c.width(), c.height()).toFloat().coerceAtLeast(18 * d)
        val barId = s.resources.getIdentifier("status_bar_height", "dimen", "android")
        val barH = if (barId != 0) s.resources.getDimensionPixelSize(barId) else (28 * d).toInt()
        val pillH = 28 * d
        val endTop = barH + 4 * d

        // הגלולה הקטנה של המוזיקה כבר על המסך: השורה צומחת ממנה וחוזרת אליה (ולא שתיהן ביחד)
        val liveBg = bg
        val fromLive = kind == "musicLive" && liveBg != null && liveBg.width > 0
        val sW = if (fromLive) liveBg!!.width.toFloat() else hole
        val sH = if (fromLive) liveBg!!.height.toFloat() else hole
        val sTop = if (fromLive) liveBg!!.top.toFloat() else cy - hole / 2f
        if (fromLive) {
            main.removeCallbacks(autoClose)
            removeNow()
        }

        val ctl = NotchOverlay.playingController(s)
        val md = ctl?.metadata
        val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
        val who = md?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: ""

        // התוכן: תמונה עגולה קטנה, ובשורה אחת "שם השיר · הזמר"
        val content = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((5 * d).toInt(), 0, (10 * d).toInt(), 0)
            alpha = 0f
        }
        val img = ImageView(s).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(purple)
            }
            clipToOutline = true
            if (art != null) setImageBitmap(art)
        }
        content.addView(img, LinearLayout.LayoutParams((20 * d).toInt(), (20 * d).toInt()).apply { marginEnd = (7 * d).toInt() })
        val line = android.text.SpannableStringBuilder().apply {
            append(title.trim())
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (who.isNotBlank()) {
                val st = length
                append("  ·  ").append(who.trim())
                setSpan(android.text.style.ForegroundColorSpan(grey), st, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
        }
        // שיר חדש: שורה אחת בגובה החור, כמו הודעה (לא שורה שיורדת מתחת לשורת המצב).
        // השם זז פעם אחת עד הסוף; אחרי שהיא נסגרת הגלולה הקטנה של המוזיקה חוזרת לבד
        show("music", 240, purple, 3000, onTap = {
            ctl?.let { c -> main.postDelayed({ showPlayer(c) }, 280) }
            Unit
        }, long = line) { ctx, _, right ->
            right.addView(artView(ctx, art, purple).also { songImg = it })
        }
        // בספוטיפיי התמונה של השיר החדש מגיעה רק אחרי רגע: בודקים שוב, ומחליפים כשהיא מגיעה
        if (art == null) listOf(500L, 1200L, 2200L).forEach { t ->
            main.postDelayed({
                val img = songImg ?: return@postDelayed
                if (kind != "music") return@postDelayed
                val md2 = NotchOverlay.playingController(s)?.metadata
                val a2 = md2?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART) ?: md2?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                if (a2 != null) img.setImageBitmap(a2)
            }, t)
        }
        if (true) return
        val tv = label(s, line, 12.5f, Color.WHITE)
        content.addView(tv)

        // רוחב לפי התוכן, ולא יותר מהרווח שבין השעה לסוללה
        val maxW = (screenW - 2 * 68 * d).toInt()
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(free, free)
        val endW = min(content.measuredWidth.toFloat() + 6 * d, maxW.toFloat())
        // ארוך מדי: מקצרים רק את הטקסט, בדיוק כמה שחסר (התמונה והמרווחים נשארים)
        if (content.measuredWidth + 6 * d > maxW) tv.maxWidth = (maxW - 6 * d - (content.measuredWidth - tv.measuredWidth)).toInt().coerceAtLeast((40 * d).toInt())

        val winW = max(endW, sW).toInt() + (8 * d).toInt()
        val winX = (cx - winW / 2f).toInt()
        val winH = (endTop + pillH + 6 * d).toInt()
        val cxIn = cx - winX
        var onSwipe: () -> Unit = {}
        val root = SwipeUpFrame(s) { onSwipe() }
        // משמאל לימין במכוון: אחרת בעברית המיקום נמדד מצד ימין והשורה "בורחת" לימין
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val box = FrameLayout(s).apply {
            background = GlassDrawable(d).apply {
                cornerRadius = sH / 2f
                setStroke((1.5f * d).toInt(), purple)
            }
        }
        box.addView(content, FrameLayout.LayoutParams(-1, -1))
        root.addView(box, FrameLayout.LayoutParams(sW.toInt(), sH.toInt(), Gravity.TOP or Gravity.LEFT))
        val back = SongBack(cxIn, sW, sH, sTop, fromLive, art)
        val openPlayer = View.OnClickListener {
            hideSong(wm, root, box, content, back.copy(toLive = false))
            ctl?.let { cc -> main.postDelayed({ showPlayer(cc) }, 300) }
        }
        box.setOnClickListener(openPlayer)
        onSwipe = { hideSong(wm, root, box, content, back) }
        runCatching { wm.addView(root, NotchOverlay.params(winW, winH, winX, 0, touchable = true)) }.onFailure { return }
        songWindow = root

        // צומחת מהגלולה הקטנה (או מהעיגול), למטה ולרוחב, עד שורה דקה מתחת לשורת המצב
        fun place(f: Float) {
            val w = sW + (endW - sW) * f
            val h = sH + (pillH - sH) * f
            val top = sTop + (endTop - sTop) * f
            val lp = box.layoutParams as FrameLayout.LayoutParams
            lp.width = w.toInt()
            lp.height = h.toInt()
            lp.leftMargin = (cxIn - w / 2f).toInt()
            lp.topMargin = top.toInt()
            box.layoutParams = lp
            (box.background as GradientDrawable).cornerRadius = h / 2f
        }
        place(0f)
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 460
            interpolator = OvershootInterpolator(0.8f)
            addUpdateListener { place(it.animatedValue as Float) }
            start()
        }
        content.animate().alpha(1f).setStartDelay(300).setDuration(180).start()
        // שתי שניות על המסך, ואז חוזרת
        main.postDelayed({ if (songWindow === root) hideSong(wm, root, box, content, back) }, 2500)
    }

    /** לאן השורה חוזרת: לגלולה הקטנה של המוזיקה (ואז היא חוזרת בדיוק כמו שהייתה) או לעיגול */
    private data class SongBack(val cxIn: Float, val w: Float, val h: Float, val top: Float, val toLive: Boolean, val art: Bitmap?)

    /** מתכווצת חזרה למקום שממנו יצאה */
    private fun hideSong(wm: android.view.WindowManager, root: FrameLayout, box: FrameLayout, content: View, to: SongBack) {
        if (songWindow !== root || songClosing === root) return
        songClosing = root
        val lp0 = box.layoutParams as FrameLayout.LayoutParams
        val w0 = lp0.width.toFloat()
        val h0 = lp0.height.toFloat()
        val top0 = lp0.topMargin.toFloat()
        content.animate().alpha(0f).setDuration(100).start()
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 320
            interpolator = android.view.animation.DecelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val w = w0 + (to.w - w0) * f
                val h = h0 + (to.h - h0) * f
                val top = top0 + (to.top - top0) * f
                val lp = box.layoutParams as FrameLayout.LayoutParams
                lp.width = w.toInt()
                lp.height = h.toInt()
                lp.leftMargin = (to.cxIn - w / 2f).toInt()
                lp.topMargin = top.toInt()
                box.layoutParams = lp
                (box.background as GradientDrawable).cornerRadius = h / 2f
                // לעיגול: נעלמת בסוף. לגלולה הקטנה: נשארת, והגלולה חוזרת בדיוק במקומה
                if (!to.toLive) box.alpha = if (f > 0.8f) (1f - f) * 5f else 1f
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    songWindow = null
                    if (to.toLive) {
                        instantNext = true
                        musicLive(to.art)
                        instantNext = false
                    }
                    // רגע אחרי שהגלולה כבר מצוירת, כדי שלא יהיה הבהוב
                    main.postDelayed({ runCatching { wm.removeView(root) } }, if (to.toLive) 60 else 0)
                }
            })
            start()
        }
    }

    fun showCharging(percent: Int, minutesToFull: Long?) {
        val s = NotchOverlay.service ?: return
        if (!demoMode && (!NotchPrefs.pill(s) || !NotchPrefs.pillCharging(s))) return
        if (inCallPill()) return
        val green = NotchPrefs.tint(NotchOverlay.service, "charge", Color.rgb(62, 209, 140))
        val text = minutesToFull?.let { "מלאה בעוד " + Battery.minutesText(it) } ?: "בטעינה"
        show("charge", 240, green, 2500, onTap = null, long = text) { ctx, left, right ->
            // מימין האחוזים, משמאל כמה זמן עד מלאה (ואם לא נכנס: בשורה מתחת)
            right.addView(label(ctx, "⚡ $percent%", 14.5f, green, bold = true))
            left.addView(label(ctx, text, 13.5f, Color.WHITE))
        }
    }

    // ---------- הגלולה עצמה ----------

    private fun show(
        newKind: String,
        widthDp: Int,
        color: Int,
        autoMs: Long?,
        onTap: (() -> Unit)?,
        long: CharSequence? = null,
        longColor: Int = Color.WHITE,
        extra: View? = null,
        fill: (Context, LinearLayout, LinearLayout) -> Unit,
    ) {
        val s = NotchOverlay.service ?: return
        val wm = NotchOverlay.wm ?: return
        // באפליקציה שבה קיצורים כבוי: רק שיחה עוברת
        if (newKind !in CALL_KINDS && (NotchOverlay.offHere() || NotchOverlay.pillBlocked(newKind))) return
        if (kind == "callmenu" && newKind != "callmenu") menuOpen = false
        val instant = instantNext
        main.removeCallbacks(autoClose)
        removeNow()
        timerText = null

        val d = s.resources.displayMetrics.density
        val screenW = s.resources.displayMetrics.widthPixels
        // מסך מסובב: המצלמה בצד, אז הגלולה למעלה באמצע המסך, בלי רווח לחור
        val landscape = s.resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
        val c = NotchOverlay.cutout(s)
        val cx = if (landscape) screenW / 2f else c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val hole = if (landscape) 0 else max(c.width(), c.height())
        val cy = if (landscape) 6 * d + (max(hole.toFloat(), 22 * d) + 18 * d) / 2f else c.exactCenterY() + NotchPrefs.offsetY(s) * d
        // גלולות קטנות (ניווט, מוזיקה, זמן שיחה): דקות כמו הסימן של ספוטיפיי בשורת המצב, צמודות לחור
        val tight = newKind == "incall" || newKind == "nav" || newKind == "musicLive"
        // כל השאר: שורה אחת, קצת יותר גבוהה מהחור (לא כרטיס גבוה), כדי שהגלולה תהיה צמודה אליו
        val h = if (tight) max(hole * 0.85f + 2 * d, 22 * d).toInt() else max(hole + 12 * d, 30 * d).toInt()
        // רוחב לפי התוכן, עד הרווח שבין השעה לסוללה (שלא נכסה אותן)
        // רחבה עד השעה ועד הסוללה (אפשר לכוון ב"גודל ומיקום"), כדי שלא יראו חתיכות של סמלים בצדדים
        val margin = NotchPrefs.pillMargin(s) * d
        val maxW = if (landscape) min(screenW - 2 * margin, 380 * d).toInt() else (screenW - 2 * margin).toInt()
        val gapW = hole + ((if (tight) 4 else 14) * d).toInt()
        // גלולת השיחה הקטנה: צמודה ככל האפשר (רק שניות וסמל)
        val compact = newKind == "incall" || newKind == "nav" || newKind == "musicLive"
        val padW = ((if (compact) 5 else 12) * d).toInt()
        collapsedW = h

        // החלקה למעלה סוגרת (חוץ משיחה, שלא תיסגר בטעות)
        val root: FrameLayout = if (newKind in CALL_KINDS) FrameLayout(s) else SwipeUpFrame(s) {
            if (kind == newKind) { menuOpen = false; hide() }
        }
        // רק הרקע נמתח; התוכן כבר בגודל הסופי ורק מופיע, כדי שהטקסט לא יקפוץ בזמן המתיחה
        val b = View(s).apply {
            background = GlassDrawable(d).apply {
                cornerRadius = h / 2f
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

        // תמיד נפתחת גם למטה: למעלה ליד המצלמה מי שלח / אחוזים, ומתחת הטקסט.
        // הגודל לפי הטקסט: מילה אחת = כרטיס קטן בשורה אחת, הרבה מלל = רחב יותר ועד 3 שורות
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        left.measure(free, free)
        // כל ההתראות בשורה אחת (כמו הודעה), בלי כרטיס שיורד למטה:
        // הטקסט הארוך עובר לצד שמאל של החור וזז פעם אחת עד הסוף
        val expanded = extra != null
        val body: TextView? = null
        var longMarquee: MarqueeText? = null
        if (long != null) {
            left.removeAllViews()
            val flat = android.text.SpannableStringBuilder(long).also { sb ->
                var i = sb.indexOf("\n")
                while (i >= 0) {
                    sb.replace(i, i + 1, " · ")
                    i = sb.indexOf("\n")
                }
            }
            longMarquee = MarqueeText(s, flat, 12.5f, if (longColor == Color.WHITE) Color.rgb(225, 230, 238) else longColor)
            left.addView(longMarquee, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = (4 * d).toInt() })
        }
        fit(left, maxSide)
        fit(right, maxSide)
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxSide, View.MeasureSpec.AT_MOST)
        val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        left.measure(wSpec, hSpec)
        right.measure(wSpec, hSpec)
        var side = min(max(left.measuredWidth, right.measuredWidth) + ((if (compact) 1 else 6) * d).toInt(), maxSide)
        // נגן: תמיד ברוחב המלא (בין השעה לסוללה)
        // נגן וכל הגלולות הפתוחות: תמיד ברוחב המלא, עד השעה והסוללה. רק הגלולות הקטנות (סימן שיחה, מוזיקה, ניווט) צמודות לתוכן
        if (extra != null || !tight) side = maxSide
        // גלולות קטנות: כל צד ברוחב שלו (בגודל המלל), והגלולה זזה קצת הצידה כדי שהחור יישב בדיוק ברווח שבאמצע
        val small = tight && extra == null
        val leftW = if (small) min(left.measuredWidth + (2 * d).toInt(), maxSide) else side
        val rightW = if (small) min(right.measuredWidth + (2 * d).toInt(), maxSide) else side
        val shift = (rightW - leftW) / 2f

        // גובה הכרטיס לפי כמות הטקסט: כמה שורות שצריך (עד 3), ולא יותר
        var bodyH = 0
        if (body != null) {
            // רוחב לפי הטקסט: אם הוא נכנס בשתי שורות קצרות, הכרטיס לא יהיה רחב מדי.
            // עם קצת מרווח, כי מילים לא נשברות בדיוק באמצע
            body.measure(free, free)
            val lineW = body.measuredWidth
            val cardMax = maxW - 2 * padW
            val lines = ((lineW + cardMax - 1) / cardMax).coerceIn(1, 3)
            val bodyW = min(cardMax, if (lines == 1) lineW + (8 * d).toInt() else lineW / lines + (40 * d).toInt())
            side = max(side, (bodyW - gapW + 1) / 2).coerceAtMost(maxSide)
            val cardInner = 2 * side + gapW
            body.measure(
                View.MeasureSpec.makeMeasureSpec(cardInner, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            bodyH = body.measuredHeight + (2 * d).toInt()
        }
        // מתחת לטקסט: הנגן (פס התקדמות וכפתורים)
        var extraH = 0
        if (extra != null) {
            extra.measure(
                View.MeasureSpec.makeMeasureSpec(2 * side + gapW, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            extraH = extra.measuredHeight + (6 * d).toInt()
            extra.alpha = 0f
        }
        val targetW = max(leftW + gapW + rightW + 2 * padW, collapsedW)
        val targetH = h + bodyH + extraH
        if (longMarquee != null) {
            // טקסט שזז: עובר לאורך כל הגלולה (גם מתחת לחור), והסמל או התמונה בקצה הימני
            right.gravity = Gravity.END or Gravity.CENTER_VERTICAL
            p.addView(left, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f))
            p.addView(right, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.MATCH_PARENT))
        } else {
            p.addView(left, LinearLayout.LayoutParams(leftW, LinearLayout.LayoutParams.MATCH_PARENT))
            p.addView(View(s), LinearLayout.LayoutParams(gapW, 1))
            p.addView(right, LinearLayout.LayoutParams(rightW, LinearLayout.LayoutParams.MATCH_PARENT))
        }

        // החלון רק קצת גדול מהגלולה (מקום לקפיצה של המתיחה), ושקוף: רואים מה שמתחת.
        // לא לחרוג מלמעלה: אם החור קרוב לקצה, הגלולה יורדת קצת
        val pillTop = max(0, (cy - h / 2f).toInt())
        val winW = min((targetW * 1.12f + 2 * kotlin.math.abs(shift)).toInt(), screenW)
        if (expanded) (b.background as GradientDrawable).cornerRadius = min(h / 2f, 20 * d)
        root.addView(
            b,
            FrameLayout.LayoutParams(collapsedW, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pillTop },
        )
        root.addView(
            p,
            FrameLayout.LayoutParams(targetW, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pillTop },
        )
        b.translationX = shift
        p.translationX = shift
        if (body != null) {
            root.addView(
                body,
                FrameLayout.LayoutParams(2 * side + gapW, bodyH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    .apply { topMargin = pillTop + h - (6 * d).toInt() },
            )
            if (onTap != null) body.setOnClickListener { onTap(); hide() }
        }
        if (extra != null) {
            root.addView(
                extra,
                FrameLayout.LayoutParams(2 * side + gapW, extraH, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
                    .apply { topMargin = pillTop + h - (6 * d).toInt() + bodyH },
            )
        }

        // הנגן: נגיעה בכל מקום אחר במסך סוגרת אותו
        val lp = NotchOverlay.params(winW, pillTop + targetH + (6 * d).toInt(), (cx - winW / 2f).toInt(), 0, touchable = newKind != "dial")
        if (newKind == "player" || newKind == "callmenu") {
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
            root.setOnTouchListener { _, e ->
                if (e.actionMasked == MotionEvent.ACTION_OUTSIDE) {
                    closedByOutside = SystemClock.elapsedRealtime()
                    menuOpen = false
                    hide()
                }
                false
            }
        }
        runCatching { wm.addView(root, lp) }.onFailure { return }
        window = root
        pill = p
        bg = b
        this.body = body
        kind = newKind

        // מתיחה אלסטית: קודם לצדדים, ואם צריך גם למטה, ועוצרת עם קפיצה קטנה ועדינה
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (instant) 0L else if (expanded) 520L else 420L
            interpolator = OvershootInterpolator(0.9f)
            addUpdateListener {
                val f = it.animatedValue as Float
                // בכרטיס: הרוחב נגמר מהר (60% מהזמן) ואז הגובה. הקפיצה הקטנה רק בסוף
                val fw = if (expanded) (f / 0.6f).coerceAtMost(1f) + max(0f, f - 1f) else f
                val fh = if (expanded) ((f - 0.35f) / 0.65f).coerceIn(0f, 1.1f) else 0f
                val lp = b.layoutParams
                lp.width = (collapsedW + (targetW - collapsedW) * fw).toInt().coerceAtLeast(collapsedW)
                lp.height = (h + (bodyH + extraH) * fh).toInt().coerceAtLeast(h)
                b.layoutParams = lp
            }
            start()
        }
        val dl = if (instant) 0L else 1L
        left.animate().alpha(1f).setStartDelay(220 * dl).setDuration(180 * dl).start()
        right.animate().alpha(1f).setStartDelay(220 * dl).setDuration(180 * dl).start()
        body?.animate()?.alpha(1f)?.setStartDelay(340)?.setDuration(180)?.start()
        extra?.animate()?.alpha(1f)?.setStartDelay(360)?.setDuration(180)?.start()
        this.extra = extra

        if (autoMs != null) main.postDelayed(autoClose, autoMs)
        // טקסט שזז: נסגרת רק אחרי שסיים לזוז, ועוד שנייה לקרוא את הסוף
        if (autoMs != null) longMarquee?.let { m ->
            m.post {
                if (window !== root) return@post
                main.removeCallbacks(autoClose)
                main.postDelayed(autoClose, max(2500L, m.durationMs(m.width) + 900L))
            }
        }
    }

    /** מתכווצת חזרה לעיגול ונעלמת */
    fun hide() {
        if (kind == "callmenu") menuOpen = false
        main.removeCallbacks(autoClose)
        val p = pill ?: return removeNow()
        val b = bg ?: return removeNow()
        kind = null
        p.animate().alpha(0f).setDuration(120).start()
        outline?.animate()?.alpha(0f)?.setDuration(100)?.start()
        outline = null
        body?.animate()?.alpha(0f)?.setDuration(100)?.start()
        extra?.animate()?.alpha(0f)?.setDuration(100)?.start()
        main.removeCallbacks(playerTick)
        val w0 = b.width.coerceAtLeast(collapsedW)
        val h0 = b.height
        val hMin = p.height.takeIf { it > 0 } ?: h0
        ValueAnimator.ofFloat(1f, 0f).apply {
            duration = 240
            interpolator = AccelerateInterpolator()
            addUpdateListener {
                val f = it.animatedValue as Float
                val lp = b.layoutParams
                lp.width = (collapsedW + (w0 - collapsedW) * f).toInt()
                lp.height = (hMin + (h0 - hMin) * f).toInt()
                b.layoutParams = lp
            }
            start()
        }
        main.postDelayed({ if (pill === p) removeNow() }, 260)
    }

    private fun removeNow() {
        shownKey = null
        window?.let { w -> runCatching { NotchOverlay.wm?.removeView(w) } }
        window = null
        pill = null
        bg = null
        body = null
        extra = null
        outline = null
        main.removeCallbacks(playerTick)
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
        // כל הטקסטים בצד, גם אלה שבתוך שורות פנימיות (שם מעל הודעה וכו')
        val texts = ArrayList<TextView>()
        fun collect(v: View) {
            if (v is TextView) {
                if (v.layoutParams == null || v.layoutParams.width == LinearLayout.LayoutParams.WRAP_CONTENT) texts.add(v)
            } else if (v is android.view.ViewGroup) {
                for (i in 0 until v.childCount) collect(v.getChildAt(i))
            }
        }
        collect(side)
        if (texts.isNotEmpty()) {
            // קודם מקטינים קצת את הכתב
            val scale = max(0.85f, maxSide.toFloat() / need)
            texts.forEach { it.setTextSize(TypedValue.COMPLEX_UNIT_PX, it.textSize * scale) }
            // ואם עדיין ארוך: מקצרים כל טקסט בכמה שחסר (עם "…"), כמה פעמים עד שנכנס
            repeat(4) {
                side.measure(free, free)
                val over = side.measuredWidth - maxSide
                if (over <= 0) return
                val minW = (24 * side.resources.displayMetrics.density).toInt()
                texts.forEach { t ->
                    t.measure(free, free)
                    t.maxWidth = (t.measuredWidth - over).coerceAtLeast(minW)
                }
            }
        }
        // עדיין לא נכנס (כמה כפתורים בצד אחד): מקטינים את הכפתורים והמרווחים שלהם באותו יחס,
        // כדי שאף כפתור לא ייחתך בקצה הגלולה
        side.measure(free, free)
        val total = side.measuredWidth
        if (total <= maxSide) return
        val ratio = (maxSide - 1).toFloat() / total
        for (i in 0 until side.childCount) {
            val v = side.getChildAt(i)
            val lp = v.layoutParams as? LinearLayout.LayoutParams ?: continue
            if (lp.width > 0) lp.width = (lp.width * ratio).toInt()
            if (lp.height > 0) lp.height = (lp.height * ratio).toInt()
            lp.marginStart = (lp.marginStart * ratio).toInt()
            lp.marginEnd = (lp.marginEnd * ratio).toInt()
            v.layoutParams = lp
            if (v is ImageView) {
                v.setPadding((v.paddingLeft * ratio).toInt(), (v.paddingTop * ratio).toInt(), (v.paddingRight * ratio).toInt(), (v.paddingBottom * ratio).toInt())
            }
        }
    }

    /** כפתור "דוגמא" במסך הבית: מראה איך תיראה הגלולה, גם כשהמתגים כבויים */
    fun demo(type: String): Boolean {
        val s = NotchOverlay.service ?: return false
        if (inCallPill()) return true
        val grey = Color.rgb(210, 216, 228)
        when (type) {
            "msg", "long" -> {
                val green = NotchPrefs.tint(NotchOverlay.service, "msg", Color.rgb(37, 211, 102))
                val text = if (type == "msg") "מה קורה?" else "אני כבר בדרך, תגיד לי אם צריך להביא משהו מהסופר?"
                msgRow("דני", text, green, null)?.let { closeAfterMarquee(it) }
            }
            "charge" -> {
                val green = NotchPrefs.tint(NotchOverlay.service, "charge", Color.rgb(62, 209, 140))
                val b = Battery.read(s, null)
                val text = "מלאה בעוד שעה ו-10 דק׳"
                show("charge", 240, green, 2500, onTap = null, long = text) { ctx, left, right ->
                    right.addView(label(ctx, "⚡ ${b.percent}%", 14.5f, green, bold = true))
                    left.addView(label(ctx, text, 13.5f, Color.WHITE))
                }
            }
            "music" -> {
                val purple = NotchPrefs.tint(NotchOverlay.service, "music", Color.rgb(179, 136, 255))
                show("music", 240, purple, 3500, onTap = null, long = "שיר לדוגמא") { ctx, left, right ->
                    right.addView(label(ctx, "♪", 14f, purple, bold = true))
                    left.addView(label(ctx, "שיר לדוגמא", 13.5f, Color.WHITE))
                }
            }
            // הדמיות לכל סוג בגלולה החיה, גם כשהמתג שלו כבוי
            else -> {
                demoMode = true
                try {
                    when (type) {
                        "call" -> {
                            showCall("demo", "אמא", null, null)
                            main.postDelayed({ callNotificationRemoved("demo") }, 4000)
                        }
                        "live" -> {
                            if (kind != null) removeNow()
                            musicLive(null)
                            musicProgress(0.4f)
                            main.postDelayed({ if (kind == "musicLive" && NotchOverlay.playingController(s) == null) hide() }, 4500)
                        }
                        "song" -> {
                            if (kind != "musicLive") {
                                if (kind != null) removeNow()
                                musicLive(null)
                            }
                            main.postDelayed({
                                demoMode = true
                                try { showMusic("שיר לדוגמא") } finally { demoMode = false }
                            }, 700)
                            main.postDelayed({ if (kind == "musicLive" && NotchOverlay.playingController(s) == null) hide() }, 5500)
                        }
                        "nav" -> {
                            if (kind != null) removeNow()
                            showNav("demo", "com.waze", "בעוד 300 מ׳", "פנה ימינה לרחוב הרצל", null, null)
                            main.postDelayed({ navEnded("demo") }, 5000)
                        }
                        "buds" -> showEarbuds("Galaxy Buds", 80)
                        "download" -> showDownloaded("סידור.pdf", null)
                        "shot" -> showScreenshot(null, android.net.Uri.EMPTY)
                        "player", "player_progress", "player_skip", "player_cover" -> {
                            // הנגן צריך שיר אמיתי שמתנגן; אם אין, אומרים את זה בגלולה עצמה
                            val c = NotchOverlay.playingController(s)
                            if (kind != null) removeNow()
                            // בכל הדמיה רק מה שהמתג שלה מוסיף, כדי לראות את ההבדל: נגן בסיסי, או רק הפס, רק קודם/הבא, רק התמונה
                            val only = when (type) {
                                "player_progress" -> Triple(true, false, false)
                                "player_skip" -> Triple(false, true, false)
                                "player_cover" -> Triple(false, false, true)
                                else -> Triple(false, false, false)
                            }
                            if (c != null) showPlayer(c, only)
                            else {
                                val purple = NotchPrefs.tint(s, "music", Color.rgb(179, 136, 255))
                                show("music", 240, purple, 3000, onTap = null, long = "תפעיל שיר, ואז ▶ יראה את הנגן") { ctx, _, right ->
                                    right.addView(label(ctx, "♪", 14f, purple, bold = true))
                                }
                            }
                        }
                    }
                } finally {
                    demoMode = false
                }
            }
        }
        return true
    }

    /** בזמן הדמיה: מראים גם כשהמתג של הסוג כבוי */
    private var demoMode = false

    /** נכנסו לאפליקציה שבה קיצורים כבוי: סוגרים, חוץ משיחה */
    fun hideUnlessCall() {
        if (kind != null && !inCallPill()) hide()
    }

    // ---------- רכיבים קטנים ----------

    /** "דני: מה קורה?" עם השם מודגש ולבן, כדי שהשם לא ייחתך ויהיה צמוד למצלמה */
    private fun senderAndText(sender: String, text: String): CharSequence =
        android.text.SpannableStringBuilder().apply {
            append(sender.trim())
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            append(": ")
            append(text.trim())
        }

    private fun label(ctx: Context, text: CharSequence, sp: Float, color: Int, bold: Boolean = false) = TextView(ctx).apply {
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
        // צל דק: הכתב נשאר חד גם כשהזכוכית שקופה קצת מעל מסך בהיר
        setShadowLayer(1.5f * d, 0f, 0.5f * d, Color.argb(150, 0, 0, 0))
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

/**
 * חלון של גלולה שאפשר לסגור בהחלקה למעלה: הגלולה עולה עם האצבע,
 * ואם מחליקים מספיק היא נסגרת. החלקה קצרה מדי מחזירה אותה למקום.
 * תופס את ההחלקה לפני הכפתורים שבתוכה, כך שלחיצה רגילה עדיין עובדת.
 */
@SuppressLint("ViewConstructor")
class SwipeUpFrame(ctx: Context, private val onSwipeUp: () -> Unit) : FrameLayout(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val slop = android.view.ViewConfiguration.get(ctx).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var done = false

    private fun lift(dy: Float) {
        for (i in 0 until childCount) getChildAt(i).translationY = dy
    }

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; done = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = e.rawY - downY
                val dx = e.rawX - downX
                if (-dy > slop / 2f && -dy > kotlin.math.abs(dx)) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            // נגיעה בחלק שאין בו כפתור: לוקחים אותה, כדי לקבל את ההחלקה
            MotionEvent.ACTION_DOWN -> {
                downX = e.rawX; downY = e.rawY; dragging = false; done = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dy = (e.rawY - downY).coerceAtMost(0f)
                if (!dragging && -dy > slop / 2f && -dy > kotlin.math.abs(e.rawX - downX)) dragging = true
                if (dragging && !done) lift(dy * 0.6f)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                // כל החלקה למעלה סוגרת, גם קצרה מאוד
                if (dragging && !done) {
                    done = true
                    onSwipeUp()
                }
                dragging = false
            }
        }
        return true
    }
}

/**
 * טקסט בשורה אחת שזז פעם אחת: קודם רואים את ההתחלה (בעברית מימין), אחרי רגע הוא זז לאט
 * עד שרואים את הסוף, ונעצר. טקסט קצר שנכנס לא זז. הקצוות נמוגים כדי שהמעבר ייראה רך
 */
@SuppressLint("ViewConstructor")
class MarqueeText(ctx: Context, text: CharSequence, sp: Float, color: Int) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    private val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color
        textSize = sp * ctx.resources.displayMetrics.scaledDensity
        setShadowLayer(1.5f * ctx.resources.displayMetrics.density, 0f, 0.5f * ctx.resources.displayMetrics.density, Color.argb(150, 0, 0, 0))
    }
    private val textW = kotlin.math.ceil(android.text.Layout.getDesiredWidth(text, paint)).toInt() + 2
    private val layout: android.text.StaticLayout =
        android.text.StaticLayout.Builder.obtain(text, 0, text.length, paint, textW)
            .setTextDirection(android.text.TextDirectionHeuristics.FIRSTSTRONG_RTL)
            .setAlignment(android.text.Layout.Alignment.ALIGN_NORMAL)
            .setMaxLines(1)
            .build()
    private var offset = 0f
    private var anim: ValueAnimator? = null
    private val fade = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT)
    }

    /** כמה זמן כל התנועה לוקחת (כדי שהגלולה תיסגר רק אחרי שקראו הכול) */
    fun durationMs(width: Int): Long {
        val extra = textW - width
        return if (extra <= 0) 0L else 400L + (extra / (90 * d) * 1000).toLong()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = when (MeasureSpec.getMode(widthMeasureSpec)) {
            MeasureSpec.UNSPECIFIED -> textW
            else -> min(textW, MeasureSpec.getSize(widthMeasureSpec))
        }
        setMeasuredDimension(w, layout.height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        anim?.cancel()
        val extra = textW - w
        // ההתחלה של עברית מימין: מיישרים לימין, ואז מזיזים ימינה עד שרואים את הסוף משמאל
        offset = (w - textW).toFloat()
        if (extra > 0) {
            anim = ValueAnimator.ofFloat(offset, 0f).apply {
                startDelay = 400
                duration = (extra / (90 * d) * 1000).toLong()
                interpolator = android.view.animation.AccelerateDecelerateInterpolator()
                addUpdateListener {
                    offset = it.animatedValue as Float
                    invalidate()
                }
                start()
            }
        }
    }

    override fun onDetachedFromWindow() {
        anim?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: android.graphics.Canvas) {
        val save = c.saveLayer(0f, 0f, width.toFloat(), height.toFloat(), null)
        c.save()
        c.translate(offset, 0f)
        layout.draw(c)
        c.restore()
        // קצוות נמוגים רק בצד שיש בו עוד טקסט
        val fw = 12 * d
        if (offset < -0.5f) {
            fade.shader = android.graphics.LinearGradient(0f, 0f, fw, 0f, Color.BLACK, Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, fw, height.toFloat(), fade)
        }
        if (offset + textW > width + 0.5f) {
            fade.shader = android.graphics.LinearGradient(width - fw, 0f, width.toFloat(), 0f, Color.TRANSPARENT, Color.BLACK, android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(width - fw, 0f, width.toFloat(), height.toFloat(), fade)
        }
        c.restoreToCount(save)
    }
}

/**
 * זכוכית כהה: רקע כהה ושקוף-למחצה (רואים קצת את מה שמתחת), ברק לבן רך בחלק העליון,
 * והמסגרת הצבעונית הדקה כרגיל. זה עדיין GradientDrawable, כך שכל מה שמשנה פינות ומסגרת ממשיך לעבוד.
 * כהה מספיק כדי שכתב לבן יהיה קריא גם מעל מסך לבן לגמרי
 */
class GlassDrawable(private val d: Float) : GradientDrawable() {
    companion object {
        val TOP = Color.rgb(30, 33, 42)
        val BOTTOM = Color.rgb(8, 9, 13)
    }

    private val shine = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val edge = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        style = android.graphics.Paint.Style.STROKE
        strokeWidth = 0.8f * d
    }
    private val rect = android.graphics.RectF()
    private val glowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val clip = android.graphics.Path()

    /** הילה בצבעי תמונת השיר (בנגן). 0 = בלי הילה */
    var glowA = 0
    var glowB = 0

    init {
        orientation = Orientation.TOP_BOTTOM
        colors = intArrayOf(TOP, BOTTOM)
    }

    override fun onBoundsChange(r: android.graphics.Rect) {
        super.onBoundsChange(r)
        val h = r.height().toFloat().coerceAtLeast(1f)
        // ברק: לבן שקוף למעלה שנעלם עד האמצע
        shine.shader = android.graphics.LinearGradient(
            0f, r.top.toFloat(), 0f, r.top + h * 0.55f,
            Color.argb(34, 255, 255, 255), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP,
        )
        // קו אור דק בקצה העליון, כמו השתקפות בזכוכית
        edge.shader = android.graphics.LinearGradient(
            0f, r.top.toFloat(), 0f, r.top + h * 0.5f,
            Color.argb(90, 255, 255, 255), Color.TRANSPARENT, android.graphics.Shader.TileMode.CLAMP,
        )
    }

    override fun draw(c: android.graphics.Canvas) {
        super.draw(c)
        val inset = 1.6f * d
        rect.set(bounds.left + inset, bounds.top + inset, bounds.right - inset, bounds.bottom - inset)
        if (rect.width() <= 0 || rect.height() <= 0) return
        val r = (cornerRadius - inset).coerceAtLeast(0f).coerceAtMost(rect.height() / 2f)
        // הילה צבעונית: צבע אחד מלמעלה בצד אחד, השני מלמטה בצד השני, בתוך הצורה של הגלולה
        if (glowA != 0) {
            clip.reset()
            clip.addRoundRect(rect, r, r, android.graphics.Path.Direction.CW)
            c.save()
            c.clipPath(clip)
            fun blob(cx: Float, cy: Float, rad: Float, col: Int) {
                glowPaint.shader = android.graphics.RadialGradient(
                    cx, cy, rad.coerceAtLeast(1f),
                    Color.argb(120, Color.red(col), Color.green(col), Color.blue(col)), Color.TRANSPARENT,
                    android.graphics.Shader.TileMode.CLAMP,
                )
                c.drawRect(rect, glowPaint)
            }
            blob(rect.right - rect.width() * 0.1f, rect.top, rect.width() * 0.75f, glowA)
            blob(rect.left, rect.bottom, rect.width() * 0.7f, if (glowB != 0) glowB else glowA)
            c.restore()
        }
        c.drawRoundRect(rect, r, r, shine)
        c.drawRoundRect(rect, r, r, edge)
    }
}

/**
 * גל קול במקום פס התקדמות: פסים בגבהים שונים, הלבנים עד איפה שהשיר נמצא.
 * גוררים עליו כדי לקפוץ בשיר
 */
@SuppressLint("ViewConstructor")
class WaveBar(ctx: Context, private val onSeek: (Float) -> Unit, private val onDrag: (Boolean) -> Unit) : View(ctx) {
    private val d = ctx.resources.displayMetrics.density
    /** 0 עד 1000 */
    var progress = 0
        set(v) {
            field = v.coerceIn(0, 1000)
            invalidate()
        }
    /** לכל שיר צורת גל קצת אחרת */
    var seed = 0f
        set(v) {
            field = v
            invalidate()
        }
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val r = android.graphics.RectF()

    override fun onDraw(c: android.graphics.Canvas) {
        val n = 46
        val gap = 2 * d
        val bw = (width - gap * (n - 1)) / n
        if (bw <= 0) return
        val done = progress / 1000f
        for (i in 0 until n) {
            val wave = kotlin.math.abs(kotlin.math.sin(i * 0.7f + seed) * 0.7f + kotlin.math.sin(i * 1.9f + seed * 0.3f) * 0.3f)
            val h = height * (0.22f + 0.78f * wave).coerceIn(0.22f, 1f)
            val x = i * (bw + gap)
            r.set(x, (height - h) / 2, x + bw, (height + h) / 2)
            paint.color = if ((i + 0.5f) / n <= done) Color.WHITE else Color.argb(60, 255, 255, 255)
            c.drawRoundRect(r, bw / 2, bw / 2, paint)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                onDrag(true)
                progress = (e.x / width * 1000).toInt()
            }
            MotionEvent.ACTION_MOVE -> progress = (e.x / width * 1000).toInt()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                progress = (e.x / width * 1000).toInt()
                onSeek(progress / 1000f)
                onDrag(false)
            }
        }
        return true
    }
}

/** שני הצבעים הבולטים בתמונת השיר (בשביל ההילה בנגן). null אם התמונה בלי צבע */
fun artColors(bmp: Bitmap?): Pair<Int, Int>? {
    if (bmp == null) return null
    return runCatching {
        val small = Bitmap.createScaledBitmap(bmp, 24, 24, true)
        val bins = FloatArray(12)
        val sumR = FloatArray(12)
        val sumG = FloatArray(12)
        val sumB = FloatArray(12)
        val hsv = FloatArray(3)
        for (y in 0 until 24) for (x in 0 until 24) {
            val p = small.getPixel(x, y)
            Color.colorToHSV(p, hsv)
            if (hsv[1] < 0.25f || hsv[2] < 0.25f) continue
            val b = ((hsv[0] / 30f).toInt()).coerceIn(0, 11)
            val w = hsv[1] * hsv[2]
            bins[b] += w
            sumR[b] += Color.red(p) * w
            sumG[b] += Color.green(p) * w
            sumB[b] += Color.blue(p) * w
        }
        val order = bins.indices.sortedByDescending { bins[it] }
        if (bins[order[0]] <= 0f) return@runCatching null
        fun col(b: Int) = Color.rgb((sumR[b] / bins[b]).toInt(), (sumG[b] / bins[b]).toInt(), (sumB[b] / bins[b]).toInt())
        val a = col(order[0])
        // הצבע השני: הכי חזק שרחוק מספיק מהראשון (לפחות 2 "פלחים" בגלגל הצבעים)
        val second = order.drop(1).firstOrNull { bins[it] > 0f && kotlin.math.abs(it - order[0]).let { dd -> min(dd, 12 - dd) } >= 2 }
        a to (second?.let { col(it) } ?: a)
    }.getOrNull()
}
