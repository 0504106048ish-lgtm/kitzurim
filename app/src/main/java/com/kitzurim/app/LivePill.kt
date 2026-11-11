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

    fun showMessage(sender: String, text: String, color: Int, open: PendingIntent?, fromRing: Boolean = false) {
        val s = NotchOverlay.service ?: return
        // הקשה על העיגול הדלוק: מראים גם אם הגלולה של הודעות כבויה
        if (!fromRing && (!NotchPrefs.pill(s) || !NotchPrefs.pillMessages(s))) return
        if (inCallPill()) return // שיחה חשובה יותר
        // הודעה ארוכה: נשארת קצת יותר זמן כדי שיספיקו לקרוא
        show(
            "msg", 300, color, if (text.length > 40) 5500 else 3500,
            onTap = { launch(open) }, long = senderAndText(sender, text), longColor = Color.rgb(210, 216, 228),
        ) { ctx, _, right ->
            // ליד המצלמה רק העיגול עם האות; השם וההודעה בשורה מתחת, כך שהגודל לפי כמות הטקסט
            right.addView(letter(ctx, sender, color))
        }
    }

    fun showCall(key: String, name: String, decline: PendingIntent?, answer: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s)) return
        callKey = key
        val blue = NotchPrefs.tint(NotchOverlay.service, "call", Color.rgb(79, 179, 255))
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s)) return
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
                    show("incall", 150, green, null, onTap = { openCallMenu() }) { ctx, left, right ->
                        val tv = label(ctx, "•••", 14f, Color.WHITE, bold = true)
                        timerText = tv
                        left.addView(tv)
                        right.addView(phoneIcon(ctx, green))
                    }
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
            // אחר כך: קטן, משמאל השניות ומימין טלפון. הקשה פותחת את התפריט
            else -> if (kind != "incall") {
                show("incall", 150, green, null, onTap = { openCallMenu() }) { ctx, left, right ->
                    val tv = label(ctx, t, 14f, Color.WHITE, bold = true)
                    timerText = tv
                    left.addView(tv)
                    right.addView(phoneIcon(ctx, green))
                }
            } else {
                timerText?.text = t
            }
        }
    }

    /** תפריט השיחה: שם, זמן, וכפתורים מההתראה של השיחה (השתקה, ניתוק, רמקול) */
    private fun openCallMenu() {
        // קיצורים היא אפליקציית הטלפון: הקשה על הגלולה מחזירה למסך השיחה המלא
        val s = NotchOverlay.service
        if (s != null && Calls.main() != null) {
            runCatching {
                s.startActivity(
                    android.content.Intent(s, CallActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
                )
            }
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
        val s = NotchOverlay.service ?: return
        val wm = NotchOverlay.wm ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCalls(s)) return
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

        val root = FrameLayout(s)
        val bgDrawable = GradientDrawable().apply {
            cornerRadius = 40 * d
            colors = intArrayOf(Color.rgb(20, 32, 42), Color.rgb(7, 9, 13))
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = screenH * 0.6f
            setStroke((1.5f * d).toInt(), Color.TRANSPARENT)
        }
        val box = View(s).apply { background = bgDrawable }
        root.addView(box, FrameLayout.LayoutParams(screenW, screenH))
        val lp = NotchOverlay.params(screenW, screenH, 0, 0, touchable = false)
        runCatching { wm.addView(root, lp) }.onFailure { return }

        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 420
            interpolator = android.view.animation.DecelerateInterpolator(1.6f)
            addUpdateListener {
                val f = it.animatedValue as Float
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
                // בסוף: הופך לשחור עם המסגרת הירוקה של הגלולה
                if (f > 0.7f) {
                    bgDrawable.colors = intArrayOf(Color.BLACK, Color.BLACK)
                    bgDrawable.setStroke((1.5f * d).toInt(), green)
                }
            }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    // הגלולה של השיחה נכנסת למקום, ורק אז המסגרת שהתכווצה נעלמת
                    inCall(99)
                    main.postDelayed({ runCatching { wm.removeView(root) } }, 120)
                }
            })
            start()
        }
    }

    private fun showCallMenu() {
        val s = NotchOverlay.service ?: return
        if (!menuOpen) return
        val d = s.resources.displayMetrics.density
        val green = NotchPrefs.tint(NotchOverlay.service, "call", Color.rgb(42, 212, 154))
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        val name = label(s, callName ?: "שיחה", 15f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER }
        val secs = if (answeredAt > 0) ((System.currentTimeMillis() - answeredAt) / 1000).toInt() else 0
        val time = label(s, if (answeredAt > 0) clock(secs * 1000L) else "מחייג…", 13f, green).apply { gravity = Gravity.CENTER }
        box.addView(name, LinearLayout.LayoutParams(-1, -2))
        box.addView(time, LinearLayout.LayoutParams(-1, -2))
        val row = LinearLayout(s).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER
        }
        // כפתור: קודם מה שיש בהתראה של השיחה, ואם אין, קיצורים עושה את זה בעצמה
        fun btn(symbol: String, bg: Int, wide: Boolean, a: Notification.Action?, own: () -> Unit) = TextView(s).apply {
            text = symbol
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            background = GradientDrawable().apply {
                cornerRadius = 21 * d
                setColor(bg)
            }
            layoutParams = LinearLayout.LayoutParams(((if (wide) 54 else 42) * d).toInt(), (42 * d).toInt()).apply {
                marginStart = (9 * d).toInt()
                marginEnd = (9 * d).toInt()
            }
            setOnClickListener {
                val sent = a != null && runCatching { a.actionIntent.send() }.isSuccess
                if (!sent) runCatching { own() }
                main.removeCallbacks(autoClose)
                main.postDelayed(autoClose, 6000)
            }
        }
        val dark = Color.rgb(42, 47, 58)
        val audio = s.getSystemService(android.media.AudioManager::class.java)
        @Suppress("DEPRECATION")
        row.addView(btn("🎙︎", dark, false, callAction(listOf("השתק", "mute"))) {
            audio?.let { it.isMicrophoneMute = !it.isMicrophoneMute }
        })
        row.addView(btn("✆︎", Color.rgb(229, 72, 77), true, callAction(listOf("נתק", "סיים", "סיום", "hang", "end"))) {
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
        row.addView(btn("🔊︎", dark, false, callAction(listOf("רמקול", "speaker"))) {
            audio?.let { it.isSpeakerphoneOn = !it.isSpeakerphoneOn }
        })
        if (row.childCount > 0) box.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = (8 * d).toInt() })
        // הקשה על השורה של המצלמה: חוזרים לגלולה הקטנה
        show("callmenu", 300, green, 6000, onTap = { menuOpen = false }, extra = box) { ctx, _, right ->
            right.addView(phoneIcon(ctx, green))
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
    private const val PLAYER_MS = 7000L

    private class PlayerViews(
        val title: TextView,
        val artist: TextView,
        val cover: ImageView?,
        val seek: SeekBar?,
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

    /** מתי הנגן נסגר בנגיעה מחוץ לו: אם הנגיעה הייתה על העיגול, לא פותחים אותו מיד שוב */
    private var closedByOutside = 0L
    fun closedJustNow() = SystemClock.elapsedRealtime() - closedByOutside < 500

    /** כל נגיעה בנגן מאריכה את הזמן שהוא פתוח */
    private fun keepOpen() {
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, PLAYER_MS)
    }

    fun showPlayer(c: MediaController) {
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
        val cover = if (NotchPrefs.playerCover(s)) ImageView(s).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            background = GradientDrawable().apply {
                cornerRadius = 8 * d
                setColor(purple)
            }
            clipToOutline = true
            layoutParams = LinearLayout.LayoutParams((44 * d).toInt(), (44 * d).toInt()).apply { marginEnd = (10 * d).toInt() }
        } else null
        cover?.let { row.addView(it) }
        val texts = LinearLayout(s).apply { orientation = LinearLayout.VERTICAL }
        val title = label(s, "", 14.5f, Color.WHITE, bold = true)
        val artist = label(s, "", 12.5f, grey)
        texts.addView(title)
        texts.addView(artist)
        row.addView(texts, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        box.addView(row)

        // פס התקדמות: אפשר לגרור כדי לקפוץ בשיר
        var seek: SeekBar? = null
        var pos: TextView? = null
        var dur: TextView? = null
        if (NotchPrefs.playerProgress(s)) {
            seek = SeekBar(s).apply {
                max = 1000
                progressTintList = ColorStateList.valueOf(purple)
                progressBackgroundTintList = ColorStateList.valueOf(Color.rgb(60, 64, 76))
                thumbTintList = ColorStateList.valueOf(Color.WHITE)
                setPadding((6 * d).toInt(), 0, (6 * d).toInt(), 0)
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {}
                    override fun onStartTrackingTouch(sb: SeekBar) {
                        dragging = true
                        main.removeCallbacks(autoClose)
                    }
                    override fun onStopTrackingTouch(sb: SeekBar) {
                        dragging = false
                        val length = c.metadata?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L
                        if (length > 0) runCatching { c.transportControls.seekTo(length * sb.progress / 1000) }
                        keepOpen()
                    }
                })
            }
            box.addView(seek, LinearLayout.LayoutParams(-1, (26 * d).toInt()).apply { topMargin = (6 * d).toInt() })
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
                setColor(Color.WHITE)
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
        val skip = NotchPrefs.playerSkip(s)
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
        if (kind == "player") main.post(playerTick)
    }

    /** מעדכן את הנגן: שם, תמונה, מיקום בשיר, והכפתור הפעלה / השהיה */
    private fun updatePlayer() {
        val c = player ?: return
        val v = pv ?: return
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillNav(s)) return
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillNav(s)) return
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillMusicLive(s)) return
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillEarbuds(s)) return
        if (inCallPill() || kind == "player") return
        val c = NotchPrefs.tint(NotchOverlay.service, "buds", Color.rgb(160, 168, 255))
        val text = if (battery != null) "$name · $battery%" else name
        if (kind == "buds") {
            body?.text = text
            return
        }
        show("buds", 260, c, 3500, onTap = null, long = text) { ctx, _, right ->
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
        show("shot", 260, blue, 3500, onTap = open, long = line, longColor = Color.WHITE) { ctx, _, right ->
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillDownloads(s)) return
        if (inCallPill() || kind == "player") return
        val blue = NotchPrefs.tint(NotchOverlay.service, "download", Color.rgb(79, 179, 255))
        val line = android.text.SpannableStringBuilder().apply {
            append("הורד ✓")
            setSpan(android.text.style.StyleSpan(Typeface.BOLD), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            setSpan(android.text.style.ForegroundColorSpan(Color.WHITE), 0, length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            if (name.isNotBlank()) append("  ").append(name.trim())
        }
        show("download", 280, blue, 3500, onTap = { launch(open) }, long = line) { ctx, _, right ->
            right.addView(label(ctx, "⬇︎", 15f, blue, bold = true))
        }
    }

    /**
     * שיר חדש התחיל: שורה דקה אחת (תמונה קטנה, "שם השיר · הזמר") שיורדת מהעיגול שסביב המצלמה
     * אל מתחת לשורת המצב, ואחרי 3 שניות חוזרת לתוך העיגול. הקשה פותחת את הנגן המלא.
     */
    private var songWindow: FrameLayout? = null
    private var songClosing: FrameLayout? = null

    /** הפעם הבאה שהגלולה מופיעה: בלי אנימציה (חוזרת למקום שהיא כבר הייתה בו) */
    private var instantNext = false

    fun showMusic(title: String) {
        val s = NotchOverlay.service ?: return
        val wm = NotchOverlay.wm ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillMusic(s)) return
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
        val tv = label(s, line, 12.5f, Color.WHITE)
        content.addView(tv)

        // רוחב לפי התוכן, ולא יותר מהרווח שבין השעה לסוללה
        val maxW = (screenW - 2 * 58 * d).toInt()
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        content.measure(free, free)
        val endW = min(content.measuredWidth.toFloat() + 6 * d, maxW.toFloat())
        if (content.measuredWidth > maxW) tv.maxWidth = (maxW - 40 * d).toInt()

        val winW = max(endW, sW).toInt() + (8 * d).toInt()
        val winX = (cx - winW / 2f).toInt()
        val winH = (endTop + pillH + 6 * d).toInt()
        val cxIn = cx - winX
        var onSwipe: () -> Unit = {}
        val root = SwipeUpFrame(s) { onSwipe() }
        // משמאל לימין במכוון: אחרת בעברית המיקום נמדד מצד ימין והשורה "בורחת" לימין
        root.layoutDirection = View.LAYOUT_DIRECTION_LTR
        val box = FrameLayout(s).apply {
            background = GradientDrawable().apply {
                cornerRadius = sH / 2f
                setColor(Color.BLACK)
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
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCharging(s)) return
        if (inCallPill()) return
        val green = NotchPrefs.tint(NotchOverlay.service, "charge", Color.rgb(62, 209, 140))
        val text = minutesToFull?.let { "מלאה בעוד " + Battery.minutesText(it) } ?: "בטעינה"
        show("charge", 240, green, 3000, onTap = null, long = text) { ctx, left, right ->
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
        val h = if (tight) max(hole * 0.85f + 2 * d, 24 * d).toInt() else (max(hole.toFloat(), 22 * d) + 18 * d).toInt()
        // רוחב לפי התוכן, עד הרווח שבין השעה לסוללה (שלא נכסה אותן)
        val maxW = if (landscape) min(screenW - 2 * 58 * d, 380 * d).toInt() else (screenW - 2 * 58 * d).toInt()
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

        // תמיד נפתחת גם למטה: למעלה ליד המצלמה מי שלח / אחוזים, ומתחת הטקסט.
        // הגודל לפי הטקסט: מילה אחת = כרטיס קטן בשורה אחת, הרבה מלל = רחב יותר ועד 3 שורות
        val free = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        left.measure(free, free)
        val expanded = long != null || extra != null
        if (long != null) left.removeAllViews()
        val body: TextView? = if (long == null) null else label(s, long, 13.5f, longColor).apply {
            maxLines = 3
            gravity = Gravity.RIGHT
            alpha = 0f
            setLineSpacing(0f, 1.1f)
        }
        fit(left, maxSide)
        fit(right, maxSide)
        val wSpec = View.MeasureSpec.makeMeasureSpec(maxSide, View.MeasureSpec.AT_MOST)
        val hSpec = View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.AT_MOST)
        left.measure(wSpec, hSpec)
        right.measure(wSpec, hSpec)
        var side = min(max(left.measuredWidth, right.measuredWidth) + ((if (compact) 1 else 6) * d).toInt(), maxSide)
        // נגן: תמיד ברוחב המלא (בין השעה לסוללה)
        if (extra != null) side = maxSide

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
        val targetW = max(2 * side + gapW + 2 * padW, collapsedW)
        val targetH = h + bodyH + extraH
        p.addView(left, LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))
        p.addView(View(s), LinearLayout.LayoutParams(gapW, 1))
        p.addView(right, LinearLayout.LayoutParams(side, LinearLayout.LayoutParams.MATCH_PARENT))

        // החלון רק קצת גדול מהגלולה (מקום לקפיצה של המתיחה), ושקוף: רואים מה שמתחת.
        // לא לחרוג מלמעלה: אם החור קרוב לקצה, הגלולה יורדת קצת
        val pillTop = max(0, (cy - h / 2f).toInt())
        val winW = min((targetW * 1.12f).toInt(), screenW)
        if (expanded) (b.background as GradientDrawable).cornerRadius = min(h / 2f, 20 * d)
        root.addView(
            b,
            FrameLayout.LayoutParams(collapsedW, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pillTop },
        )
        root.addView(
            p,
            FrameLayout.LayoutParams(targetW, h, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = pillTop },
        )
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
    }

    /** מתכווצת חזרה לעיגול ונעלמת */
    fun hide() {
        if (kind == "callmenu") menuOpen = false
        main.removeCallbacks(autoClose)
        val p = pill ?: return removeNow()
        val b = bg ?: return removeNow()
        kind = null
        p.animate().alpha(0f).setDuration(120).start()
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
        window?.let { w -> runCatching { NotchOverlay.wm?.removeView(w) } }
        window = null
        pill = null
        bg = null
        body = null
        extra = null
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
        if (inCallPill()) return true
        val grey = Color.rgb(210, 216, 228)
        when (type) {
            "msg", "long" -> {
                val green = NotchPrefs.tint(NotchOverlay.service, "msg", Color.rgb(37, 211, 102))
                val text = if (type == "msg") "מה קורה?" else "אני כבר בדרך, תגיד לי אם צריך להביא משהו מהסופר?"
                show("msg", 300, green, if (text.length > 40) 5500 else 3500, onTap = null, long = senderAndText("דני", text), longColor = grey) { ctx, _, right ->
                    right.addView(letter(ctx, "דני", green))
                }
            }
            "charge" -> {
                val green = NotchPrefs.tint(NotchOverlay.service, "charge", Color.rgb(62, 209, 140))
                val b = Battery.read(s, null)
                val text = "מלאה בעוד שעה ו-10 דק׳"
                show("charge", 240, green, 3000, onTap = null, long = text) { ctx, left, right ->
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
        }
        return true
    }

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
