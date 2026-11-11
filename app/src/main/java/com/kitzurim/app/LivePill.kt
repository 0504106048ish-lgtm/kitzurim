package com.kitzurim.app

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.PlaybackState
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
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

    fun showMessage(sender: String, text: String, color: Int, open: PendingIntent?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillMessages(s)) return
        if (kind == "call" || kind == "incall") return // שיחה חשובה יותר
        // הודעה ארוכה: נשארת קצת יותר זמן כדי שיספיקו לקרוא
        show(
            "msg", 300, color, if (text.length > 40) 5500 else 3500,
            onTap = { runCatching { open?.send() } }, long = senderAndText(sender, text), longColor = Color.rgb(210, 216, 228),
        ) { ctx, _, right ->
            // ליד המצלמה רק העיגול עם האות; השם וההודעה בשורה מתחת, כך שהגודל לפי כמות הטקסט
            right.addView(letter(ctx, sender, color))
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

    /** כל נגיעה בנגן מאריכה את הזמן שהוא פתוח */
    private fun keepOpen() {
        main.removeCallbacks(autoClose)
        main.postDelayed(autoClose, PLAYER_MS)
    }

    fun showPlayer(c: MediaController) {
        val s = NotchOverlay.service ?: return
        if (kind == "call" || kind == "incall") return
        val d = s.resources.displayMetrics.density
        val purple = Color.rgb(179, 136, 255)
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
                runCatching { c.sessionActivity?.send() }
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

    fun showMusic(title: String) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillMusic(s)) return
        // הנגן פתוח: הוא כבר מתעדכן לשיר החדש בעצמו
        if (kind == "call" || kind == "incall" || kind == "player") return
        val purple = Color.rgb(179, 136, 255)
        show("music", 240, purple, 3500, onTap = null, long = title) { ctx, left, right ->
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
            right.addView(disc)
            left.addView(label(ctx, title, 13.5f, Color.WHITE))
        }
    }

    fun showCharging(percent: Int, minutesToFull: Long?) {
        val s = NotchOverlay.service ?: return
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillCharging(s)) return
        if (kind == "call" || kind == "incall") return
        val green = Color.rgb(62, 209, 140)
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
        var side = min(max(left.measuredWidth, right.measuredWidth) + (6 * d).toInt(), maxSide)
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

        runCatching {
            wm.addView(
                root,
                NotchOverlay.params(winW, pillTop + targetH + (6 * d).toInt(), (cx - winW / 2f).toInt(), 0, touchable = newKind != "incall"),
            )
        }.onFailure { return }
        window = root
        pill = p
        bg = b
        this.body = body
        kind = newKind

        // מתיחה אלסטית: קודם לצדדים, ואם צריך גם למטה, ועוצרת עם קפיצה קטנה ועדינה
        ValueAnimator.ofFloat(0f, 1f).apply {
            duration = if (expanded) 520 else 420
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
        left.animate().alpha(1f).setStartDelay(220).setDuration(180).start()
        right.animate().alpha(1f).setStartDelay(220).setDuration(180).start()
        body?.animate()?.alpha(1f)?.setStartDelay(340)?.setDuration(180)?.start()
        extra?.animate()?.alpha(1f)?.setStartDelay(360)?.setDuration(180)?.start()
        this.extra = extra

        if (autoMs != null) main.postDelayed(autoClose, autoMs)
    }

    /** מתכווצת חזרה לעיגול ונעלמת */
    fun hide() {
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
        if (kind == "call" || kind == "incall") return true
        val grey = Color.rgb(210, 216, 228)
        when (type) {
            "msg", "long" -> {
                val green = Color.rgb(37, 211, 102)
                val text = if (type == "msg") "מה קורה?" else "אני כבר בדרך, תגיד לי אם צריך להביא משהו מהסופר?"
                show("msg", 300, green, if (text.length > 40) 5500 else 3500, onTap = null, long = senderAndText("דני", text), longColor = grey) { ctx, _, right ->
                    right.addView(letter(ctx, "דני", green))
                }
            }
            "charge" -> {
                val green = Color.rgb(62, 209, 140)
                val b = Battery.read(s, null)
                val text = "מלאה בעוד שעה ו-10 דק׳"
                show("charge", 240, green, 3000, onTap = null, long = text) { ctx, left, right ->
                    right.addView(label(ctx, "⚡ ${b.percent}%", 14.5f, green, bold = true))
                    left.addView(label(ctx, text, 13.5f, Color.WHITE))
                }
            }
            "music" -> {
                val purple = Color.rgb(179, 136, 255)
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
        if (kind != null && kind != "call" && kind != "incall") hide()
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
