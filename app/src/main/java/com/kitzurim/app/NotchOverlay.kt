package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.view.Display
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * מה שמצויר מעל החור של המצלמה: טבעת (סוללה, טעינה, התראות, עוצמת קול, שיר, שיחה) ואזור נגיעה שקוף.
 * רץ בתוך שירות הנגישות של קיצורים, כדי שיהיה מעל כל האפליקציות.
 */
@SuppressLint("StaticFieldLeak")
object NotchOverlay {
    /** השירות והחלונות: משותפים עם הגלולה החיה */
    internal var service: AccessibilityService? = null
        private set
    internal var wm: WindowManager? = null
        private set
    private var ring: RingView? = null
    private var zone: View? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var volumeReceiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())

    private var level = 100
    private var charging = false
    private var batteryReady = false

    /** התראות שעוד לא נקראו: מפתח ההתראה ← הצבע שלה */
    private val pulses = LinkedHashMap<String, Int>()
    /** כמה שניות כל נורה כבר דלקה בזמן שהמסך דלוק */
    private val pulseSeen = HashMap<String, Int>()

    // מצבים זמניים של הטבעת
    private var volume: Float? = null
    private var music: Float? = null
    private var callRinging = false
    private var callStart = 0L

    /** האפליקציה שפתוחה עכשיו (בשביל תנועות לפי אפליקציה) */
    var currentPackage: String? = null
        private set

    /** עברו לאפליקציה אחרת: אם היא ברשימת "כבוי ב...", מסתירים הכול (ומחזירים כשיוצאים ממנה) */
    fun setCurrentApp(pkg: String) {
        if (pkg == currentPackage) return
        val s = service
        val wasOff = s != null && NotchPrefs.isOffIn(s, currentPackage)
        currentPackage = pkg
        if (s == null) return
        val nowOff = NotchPrefs.isOffIn(s, pkg)
        if (nowOff) LivePill.hideUnlessCall()
        if (wasOff != nowOff) refresh()
    }

    /** מסך שוכב, או מסך מלא כשהמתג "להסתיר במסך מלא" דלוק: לא מראים גלולה (חוץ משיחה) */
    fun pillBlocked(): Boolean {
        val s = service ?: return true
        if (s.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return true
        return fullscreen && NotchPrefs.hideFullscreen(s) && !preview
    }

    /** קיצורים כבוי באפליקציה שפתוחה עכשיו */
    fun offHere(): Boolean {
        val s = service ?: return false
        return !preview && NotchPrefs.isOffIn(s, currentPackage)
    }

    /** מסך מלא (סרטון, משחק): מסתירים אם המתג דלוק */
    private var fullscreen = false

    /** מצב כיוון: כל עוד קיצורים פתוחה, מראים עיגול ורוד כדי לראות איפה הטבעת יושבת */
    private var preview = false

    fun attach(s: AccessibilityService) {
        service = s
        wm = s.getSystemService(WindowManager::class.java)
        val b = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && sc > 0) level = l * 100 / sc
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                val was = charging
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                // חיברו מטען: הגלולה מראה את האחוז והזמן עד מלא
                if (charging && !was && batteryReady) {
                    val info = Battery.read(c, i)
                    LivePill.showCharging(info.percent, info.minutesToFull)
                }
                // ההודעה הראשונה מגיעה מיד כשהשירות עולה: לא נחשבת כחיבור מטען
                batteryReady = true
                updateRing()
            }
        }
        batteryReceiver = b
        s.registerReceiver(b, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        // שינוי עוצמת קול
        val v = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) = onVolumeChanged(i)
        }
        volumeReceiver = v
        runCatching { s.registerReceiver(v, IntentFilter("android.media.VOLUME_CHANGED_ACTION")) }

        build()
        main.post(ticker)
    }

    fun detach() {
        main.removeCallbacks(ticker)
        LivePill.hide()
        batteryReady = false
        removeViews()
        service?.let { s ->
            batteryReceiver?.let { runCatching { s.unregisterReceiver(it) } }
            volumeReceiver?.let { runCatching { s.unregisterReceiver(it) } }
        }
        batteryReceiver = null
        volumeReceiver = null
        service = null
    }

    /** אחרי שינוי בהגדרות או סיבוב המסך */
    fun refresh() {
        if (service == null) return
        removeViews()
        build()
    }

    fun isRunning() = service != null

    fun setPreview(on: Boolean) {
        if (preview == on) return
        preview = on
        refresh()
    }

    fun setFullscreen(on: Boolean) {
        if (fullscreen == on) return
        fullscreen = on
        val s = service ?: return
        if (NotchPrefs.hideFullscreen(s)) refresh()
    }

    // ---------- נורת התראות ----------
    fun notificationPosted(key: String, color: Int) {
        pulses.remove(key)
        pulses[key] = color
        pulseSeen.remove(key) // הודעה חדשה: הטיימר מתחיל מחדש
        updateRing()
    }

    fun notificationRemoved(key: String) {
        pulseSeen.remove(key)
        if (pulses.remove(key) != null) updateRing()
    }

    // ---------- עוצמת קול ----------
    private val clearVolume = Runnable {
        volume = null
        updateRing()
    }

    private fun onVolumeChanged(i: Intent) {
        val s = service ?: return
        if (!NotchPrefs.volumeRing(s)) return
        val am = s.getSystemService(AudioManager::class.java) ?: return
        // העוצמה שבאמת השתנתה (מדיה, צלצול או שיחה), כך שהטבעת זזה עם כל לחיצה למעלה או למטה
        val stream = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_TYPE", -1)
        if (stream != AudioManager.STREAM_MUSIC && stream != AudioManager.STREAM_RING &&
            stream != AudioManager.STREAM_VOICE_CALL
        ) {
            return
        }
        val value = i.getIntExtra("android.media.EXTRA_VOLUME_STREAM_VALUE", -1)
            .takeIf { it >= 0 } ?: am.getStreamVolume(stream)
        val max = am.getStreamMaxVolume(stream).coerceAtLeast(1)
        volume = value.toFloat() / max
        updateRing()
        // נעלמת שנייה וחצי אחרי הלחיצה האחרונה
        main.removeCallbacks(clearVolume)
        main.postDelayed(clearVolume, 1500)
    }


    // ---------- שיר ושיחה: בודקים כל שנייה ----------
    private var lastTitle: String? = null

    /** יש אזור נגיעה בשביל הנגן (שיר מתנגן עכשיו) */
    private var musicZone = false

    /** הנגן שמנגן עכשיו (צריך גישה להתראות) */
    internal fun playingController(s: Context): MediaController? = try {
        val msm = s.getSystemService(MediaSessionManager::class.java)
        msm?.getActiveSessions(ComponentName(s, ReplyListener::class.java))
            ?.firstOrNull {
                it.playbackState?.state == PlaybackState.STATE_PLAYING &&
                    !it.metadata?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE).isNullOrBlank()
            }
    } catch (_: Exception) {
        null
    }

    private val ticker = object : Runnable {
        override fun run() {
            val s = service ?: return
            var changed = false

            // נורת התראות: נעלמת אחרי זמן מסוים שהמסך דלוק (ראית אותה). כשהמסך כבוי לא סופרים, אז היא מחכה לך
            val limit = NotchPrefs.pulseSeconds(s)
            if (limit > 0 && pulses.isNotEmpty() && s.getSystemService(PowerManager::class.java)?.isInteractive == true) {
                pulses.keys.forEach { pulseSeen[it] = (pulseSeen[it] ?: 0) + 1 }
                val old = pulses.keys.filter { (pulseSeen[it] ?: 0) >= limit }
                if (old.isNotEmpty()) {
                    old.forEach { pulses.remove(it); pulseSeen.remove(it) }
                    changed = true
                }
            }
            val wantCalls = NotchPrefs.callRing(s) || (NotchPrefs.pill(s) && NotchPrefs.pillCalls(s))

            // שיחה: לפי מצב השמע של הטלפון (עובד גם לשיחות וואטסאפ, בלי הרשאה נוספת)
            if (wantCalls) {
                val mode = s.getSystemService(AudioManager::class.java)?.mode ?: AudioManager.MODE_NORMAL
                val ringingNow = mode == AudioManager.MODE_RINGTONE
                val inCall = mode == AudioManager.MODE_IN_CALL || mode == AudioManager.MODE_IN_COMMUNICATION
                if (ringingNow != callRinging) {
                    callRinging = ringingNow
                    changed = true
                }
                if (inCall && callStart == 0L) {
                    callStart = SystemClock.elapsedRealtime()
                    changed = true
                } else if (!inCall && callStart != 0L) {
                    callStart = 0L
                    LivePill.callEnded()
                    changed = true
                }
                if (inCall) {
                    changed = true // כל שנייה מתקדם
                    LivePill.inCall(((SystemClock.elapsedRealtime() - callStart) / 1000).toInt())
                }
            } else if (callRinging || callStart != 0L) {
                callRinging = false
                callStart = 0L
                changed = true
            }

            // שיר: מהנגן שמנגן עכשיו (צריך גישה להתראות)
            val wantMusic = NotchPrefs.musicRing(s) || (NotchPrefs.pill(s) && NotchPrefs.pillMusic(s))
            val now = if (wantMusic) nowPlaying(s) else null
            // אורך השיר לא ידוע: טבעת מלאה, כדי שעדיין יראו שמשהו מתנגן
            val m = if (NotchPrefs.musicRing(s)) now?.let { it.second ?: 1f } else null
            if (m != music) {
                music = m
                changed = true
            }
            // שיר חדש התחיל: הגלולה מראה את השם לשלוש שניות
            val title = now?.first
            if (title != null && title != lastTitle) LivePill.showMusic(title)
            lastTitle = title

            // נגן בהקשה: כשאין תנועות אחרות, אזור הנגיעה קיים רק בזמן שמתנגן שיר (או שהנגן פתוח)
            if (NotchPrefs.musicTap(s) && !NotchPrefs.anyGesture(s) && !NotchPrefs.showZone(s)) {
                val want = LivePill.isPlayerOpen() || playingController(s) != null
                if (want != musicZone) {
                    musicZone = want
                    refresh()
                    main.postDelayed(this, 1000)
                    return
                }
            }

            if (changed) updateRing()
            main.postDelayed(this, 1000)
        }
    }

    /** מה מתנגן עכשיו: שם השיר, וכמה ממנו עבר (0 עד 1) */
    private fun nowPlaying(s: Context): Pair<String, Float?>? = try {
        val msm = s.getSystemService(MediaSessionManager::class.java)
        val controllers = msm?.getActiveSessions(ComponentName(s, ReplyListener::class.java)) ?: emptyList()
        val c = controllers.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
        if (c == null) {
            null
        } else {
            val md = c.metadata
            val title = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: ""
            val duration = md?.getLong(android.media.MediaMetadata.METADATA_KEY_DURATION) ?: 0L
            val st = c.playbackState
            val progress = if (st == null || duration <= 0) {
                null
            } else {
                val pos = st.position + ((SystemClock.elapsedRealtime() - st.lastPositionUpdateTime) * st.playbackSpeed).toLong()
                (pos.toFloat() / duration).coerceIn(0f, 1f)
            }
            // סרטון (למשל בוואטסאפ) בלי שם: זה לא שיר, לא מראים כלום
            if (title.isBlank()) null else title to progress
        }
    } catch (_: Exception) {
        null
    }


    private fun updateRing() {
        val s = service ?: return
        ring?.update(
            RingState(
                level = level,
                charging = charging,
                pulse = if (NotchPrefs.notifyLight(s)) pulses.values.lastOrNull() else null,
                onlyCharging = NotchPrefs.onlyCharging(s),
                chargeAnim = NotchPrefs.chargeAnim(s),
                battery = NotchPrefs.ringOn(s),
                volume = volume,
                music = music,
                ringing = callRinging,
                // בזמן שיחה הזמן מופיע בגלולה; הטבעת לא מתמלאת (רק מהבהבת כשמצלצלים)
                callSeconds = null,
            )
        )
    }

    private fun removeViews() {
        ring?.let { v -> runCatching { wm?.removeView(v) } }
        zone?.let { v -> runCatching { wm?.removeView(v) } }
        ring = null
        zone = null
    }

    /** איפה החור של המצלמה (בפיקסלים) */
    internal fun cutout(s: Context): Rect {
        val d = s.resources.displayMetrics.density
        if (Build.VERSION.SDK_INT >= 29) {
            val disp = s.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY)
            disp?.cutout?.boundingRects?.firstOrNull { it.top < 200 * d && it.width() < 300 * d }?.let { return Rect(it) }
        }
        // לא נמצא: באמצע למעלה
        val w = s.resources.displayMetrics.widthPixels
        val size = (22 * d).toInt()
        val top = (12 * d).toInt()
        return Rect(w / 2 - size / 2, top, w / 2 + size / 2, top + size)
    }

    internal fun params(w: Int, h: Int, x: Int, y: Int, touchable: Boolean) = WindowManager.LayoutParams(
        w, h,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.LEFT
        this.x = x
        this.y = y
        if (Build.VERSION.SDK_INT >= 30) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else if (Build.VERSION.SDK_INT >= 28) {
            layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** איפה ובאיזה גודל לצייר (בפיקסלים) */
    private class Geom(val cx: Float, val cy: Float, val radius: Float, val thick: Float, val ringSize: Int, val zoneSize: Int)

    private fun geom(s: Context): Geom {
        val d = s.resources.displayMetrics.density
        val c = cutout(s)
        val cx = c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = c.exactCenterY() + NotchPrefs.offsetY(s) * d
        val radius = max(c.width(), c.height()) / 2f + (4f + NotchPrefs.size(s)) * d
        val thick = NotchPrefs.thickness(s) * d
        // חלון הטבעת גדול מספיק לעובי המקסימלי ולנקודות של זמן השיחה
        val ringSize = ((radius + 4f * d * 4 + 10 * d) * 2).toInt()
        val zoneSize = ((radius + NotchPrefs.zoneExtra(s) * d) * 2).toInt()
        return Geom(cx, cy, radius, thick, ringSize, zoneSize)
    }

    /** עדכון חי בזמן הזזת המחוונים: מזיזים את מה שכבר מצויר, בלי לבנות מחדש */
    fun live() {
        val s = service ?: return
        val w = wm ?: return
        val g = geom(s)
        ring?.let { v ->
            v.setGeometry(g.radius, g.thick)
            (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.width = g.ringSize
                lp.height = g.ringSize
                lp.x = (g.cx - g.ringSize / 2f).toInt()
                lp.y = (g.cy - g.ringSize / 2f).toInt()
                runCatching { w.updateViewLayout(v, lp) }
            }
        }
        zone?.let { v ->
            (v.layoutParams as? WindowManager.LayoutParams)?.let { lp ->
                lp.width = g.zoneSize
                lp.height = g.zoneSize
                lp.x = (g.cx - g.zoneSize / 2f).toInt()
                lp.y = (g.cy - g.zoneSize / 2f).toInt()
                runCatching { w.updateViewLayout(v, lp) }
            }
        }
    }

    private fun build() {
        val s = service ?: return
        val w = wm ?: return
        if (!NotchPrefs.enabled(s)) return
        // במסך שוכב החור זז לצד: לא מציגים
        if (s.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE) return
        // מסך מלא (סרטון, משחק): מסתירים הכול, אם המתג דלוק
        if (fullscreen && NotchPrefs.hideFullscreen(s) && !preview) return
        // אפליקציה שבחרת שבה קיצורים כבוי
        if (offHere()) return

        val d = s.resources.displayMetrics.density
        val g = geom(s)

        val needRing = preview || NotchPrefs.ringOn(s) || NotchPrefs.notifyLight(s) ||
            NotchPrefs.volumeRing(s) || NotchPrefs.musicRing(s) || NotchPrefs.callRing(s)
        if (needRing) {
            val v = RingView(s, g.radius, g.thick, preview)
            runCatching {
                w.addView(v, params(g.ringSize, g.ringSize, (g.cx - g.ringSize / 2f).toInt(), (g.cy - g.ringSize / 2f).toInt(), touchable = false))
                ring = v
            }
            updateRing()
        }

        // אין אף תנועה עם פעולה: לא שמים אזור נגיעה, כדי לא לחסום את שורת המצב
        // (חוץ מכשמתנגן שיר והנגן בהקשה דלוק: אז יש אזור נגיעה רק בזמן השיר)
        if (!NotchPrefs.anyGesture(s) && !NotchPrefs.showZone(s) && !(NotchPrefs.musicTap(s) && musicZone)) return

        val z = View(s)
        // אזור הנגיעה מסומן רק כשהמתג "להראות את אזור הנגיעה" דלוק: עיגול דק בלי מילוי
        if (NotchPrefs.showZone(s)) {
            z.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setStroke((1.5f * d).toInt(), if (preview) Color.rgb(255, 64, 129) else Color.argb(160, 255, 255, 255))
                setColor(Color.TRANSPARENT)
            }
        }
        attachGestures(s, z)
        runCatching {
            w.addView(z, params(g.zoneSize, g.zoneSize, (g.cx - g.zoneSize / 2f).toInt(), (g.cy - g.zoneSize / 2f).toInt(), touchable = true))
            zone = z
        }
    }

    /**
     * הנגיעות בחור: הקשה, כפולה, משולשת, לחיצה ארוכה, והחלקות (לפי מרחק, ברגע שהאצבע עברה מספיק).
     * הקשות נספרות: אם אין פעולה להקשה כפולה ומשולשת, הקשה אחת פועלת מיד בלי לחכות.
     */
    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(s: AccessibilityService, v: View) {
        val d = s.resources.displayMetrics.density
        val swipeDist = 18 * d
        val tapWindow = ViewConfiguration.getDoubleTapTimeout().toLong().coerceAtLeast(250)
        val longTime = ViewConfiguration.getLongPressTimeout().toLong()

        fun action(g: Gesture) = NotchPrefs.actionFor(s, g, currentPackage)

        fun fire(g: Gesture) {
            // שיר מתנגן: הקשה על העיגול פותחת את הנגן (והקשה נוספת סוגרת)
            if (g == Gesture.TAP && NotchPrefs.musicTap(s)) {
                // נגיעה על העיגול כשהנגן פתוח: הוא כבר נסגר מהנגיעה הזו, לא פותחים שוב
                if (LivePill.closedJustNow()) return
                if (LivePill.isPlayerOpen()) {
                    LivePill.hide()
                    return
                }
                val c = playingController(s)
                if (c != null) {
                    if (NotchPrefs.haptic(s)) {
                        v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING)
                    }
                    LivePill.showPlayer(c)
                    return
                }
            }
            val a = action(g)
            if (a.type == ActionType.NONE) return
            if (NotchPrefs.haptic(s)) {
                v.performHapticFeedback(
                    if (g == Gesture.LONG) HapticFeedbackConstants.LONG_PRESS else HapticFeedbackConstants.VIRTUAL_KEY,
                    HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING,
                )
            }
            NotchActions.perform(s, a)
        }

        var downX = 0f
        var downY = 0f
        var handled = false
        var taps = 0
        val resolveTaps = Runnable {
            val g = when (taps) {
                1 -> Gesture.TAP
                2 -> Gesture.DOUBLE
                else -> Gesture.TRIPLE
            }
            taps = 0
            fire(g)
        }
        val longPress = Runnable {
            handled = true
            taps = 0
            main.removeCallbacks(resolveTaps)
            fire(Gesture.LONG)
        }

        v.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    handled = false
                    main.removeCallbacks(resolveTaps)
                    main.postDelayed(longPress, longTime)
                }
                MotionEvent.ACTION_MOVE -> if (!handled) {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    val g = when {
                        dy > swipeDist && abs(dy) > abs(dx) -> Gesture.DOWN
                        dx > swipeDist && abs(dx) > abs(dy) -> Gesture.RIGHT
                        dx < -swipeDist && abs(dx) > abs(dy) -> Gesture.LEFT
                        else -> null
                    }
                    if (g != null) {
                        handled = true
                        taps = 0
                        main.removeCallbacks(longPress)
                        fire(g)
                    }
                }
                MotionEvent.ACTION_UP -> {
                    main.removeCallbacks(longPress)
                    if (!handled) {
                        taps++
                        val wantDouble = action(Gesture.DOUBLE).type != ActionType.NONE
                        val wantTriple = action(Gesture.TRIPLE).type != ActionType.NONE
                        when {
                            // אין פעולה להקשות נוספות: לא מחכים
                            taps == 1 && !wantDouble && !wantTriple -> { taps = 0; fire(Gesture.TAP) }
                            taps == 2 && !wantTriple -> { taps = 0; fire(Gesture.DOUBLE) }
                            taps >= 3 -> { taps = 0; fire(Gesture.TRIPLE) }
                            else -> main.postDelayed(resolveTaps, tapWindow)
                        }
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                }
            }
            true
        }
    }
}

/** כל מה שהטבעת צריכה לדעת כדי לצייר */
data class RingState(
    val level: Int,
    val charging: Boolean,
    val pulse: Int?,
    val onlyCharging: Boolean,
    val chargeAnim: Boolean,
    val battery: Boolean,
    val volume: Float?,
    val music: Float?,
    val ringing: Boolean,
    val callSeconds: Int?,
)

/**
 * הטבעת: דקה וזוהרת. מה שמוצג, לפי סדר החשיבות:
 * צלצול ← זמן שיחה ← עוצמת קול ← שיר ← התראה ← סוללה
 */
@SuppressLint("ViewConstructor")
class RingView(
    ctx: Context,
    private var radius: Float,
    private var thick: Float,
    private val preview: Boolean = false,
) : View(ctx) {
    private var st = RingState(100, false, null, false, false, true, null, null, false, null)
    private var phase = 0f

    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = Color.argb(40, 255, 255, 255)
        strokeWidth = thick
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = thick
    }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeWidth = thick * 3.2f
    }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }

    private val anim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600
        repeatCount = ValueAnimator.INFINITE
        interpolator = LinearInterpolator()
        addUpdateListener {
            phase = it.animatedValue as Float
            invalidate()
        }
    }

    fun setGeometry(radius: Float, thick: Float) {
        this.radius = radius
        this.thick = thick
        track.strokeWidth = thick
        arc.strokeWidth = thick
        invalidate()
    }

    fun update(state: RingState) {
        st = state
        val moving = st.ringing || st.pulse != null || (st.battery && st.charging && st.chargeAnim)
        if (moving && !anim.isStarted) anim.start()
        if (!moving && anim.isStarted) anim.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    private fun glowArc(c: Canvas, rect: RectF, col: Int, sweep: Float, glowAlpha: Int = 75) {
        glow.color = col
        glow.alpha = glowAlpha
        glow.strokeWidth = thick * 3.2f
        c.drawArc(rect, -90f, sweep, false, glow)
        arc.color = col
        arc.alpha = 255
        arc.strokeWidth = thick
        c.drawArc(rect, -90f, sweep, false, arc)
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
        val density = resources.displayMetrics.density

        // מצב כיוון: עיגול ורוד בולט
        if (preview) {
            arc.color = Color.rgb(255, 64, 129)
            arc.alpha = 255
            arc.strokeWidth = max(thick, 2.5f * density)
            c.drawCircle(cx, cy, radius, arc)
            arc.strokeWidth = thick
            return
        }

        // צלצול: הבהוב כחול חזק
        if (st.ringing) {
            val a = (0.5 + 0.5 * sin(phase * 4 * PI)).toFloat()
            val col = Color.rgb(79, 179, 255)
            glow.color = col
            glow.alpha = (80 + 150 * a).toInt()
            glow.strokeWidth = thick * (3f + 3f * a)
            c.drawCircle(cx, cy, radius, glow)
            arc.color = col
            arc.alpha = 255
            c.drawCircle(cx, cy, radius, arc)
            return
        }

        // בשיחה: קו של השניות, ונקודה לכל דקה שעברה
        st.callSeconds?.let { secs ->
            val col = Color.rgb(42, 212, 154)
            c.drawCircle(cx, cy, radius, track)
            glowArc(c, rect, col, 360f * (secs % 60) / 60f)
            val minutes = min(secs / 60, 30)
            val r2 = radius + thick * 2.5f + 2 * density
            for (i in 0 until minutes) {
                val ang = -PI / 2 + i * 2 * PI / 12
                dot.color = col
                c.drawCircle(cx + r2 * cos(ang).toFloat(), cy + r2 * sin(ang).toFloat(), thick * 0.8f, dot)
            }
            dot.color = Color.WHITE
            return
        }

        // עוצמת קול: כחול, לשנייה
        st.volume?.let { v ->
            c.drawCircle(cx, cy, radius, track)
            glowArc(c, rect, Color.rgb(79, 179, 255), 360f * v, 95)
            return
        }

        // שיר: סגול, מתמלא עד סוף השיר
        st.music?.let { m ->
            c.drawCircle(cx, cy, radius, track)
            glowArc(c, rect, Color.rgb(179, 136, 255), 360f * m)
            return
        }

        // נורת התראות: הבהוב רך בצבע של האפליקציה
        st.pulse?.let { col ->
            val a = (0.5 + 0.5 * sin(phase * 2 * PI)).toFloat()
            glow.color = col
            glow.alpha = (60 + 120 * a).toInt()
            glow.strokeWidth = thick * (2.5f + 2f * a)
            c.drawCircle(cx, cy, radius, glow)
            arc.color = col
            arc.alpha = 255
            c.drawCircle(cx, cy, radius, arc)
            return
        }

        // סוללה
        if (!st.battery) return
        if (st.onlyCharging && !st.charging) return
        val col = when {
            st.level <= 15 -> Color.rgb(226, 75, 74)
            st.level <= 40 -> Color.rgb(239, 159, 39)
            else -> Color.rgb(62, 209, 140)
        }
        c.drawCircle(cx, cy, radius, track)
        glowArc(c, rect, col, 360f * st.level / 100f)
        // טעינה: נקודה לבנה מסתובבת
        if (st.charging && st.chargeAnim) {
            val ang = phase * 2 * PI - PI / 2
            c.drawCircle(cx + radius * cos(ang).toFloat(), cy + radius * sin(ang).toFloat(), thick * 1.1f, dot)
        }
    }
}
