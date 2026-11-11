package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
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
import android.hardware.display.DisplayManager
import android.os.BatteryManager
import android.os.Build
import android.view.Display
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/**
 * מה שמצויר מעל החור של המצלמה: טבעת (סוללה, טעינה, נורת התראות) ואזור נגיעה שקוף.
 * רץ בתוך שירות הנגישות של קיצורים, כדי שיהיה מעל כל האפליקציות.
 */
@SuppressLint("StaticFieldLeak")
object NotchOverlay {
    private var service: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var ring: RingView? = null
    private var zone: View? = null
    private var batteryReceiver: BroadcastReceiver? = null
    private var level = 100
    private var charging = false

    /** התראות שעוד לא נקראו: מפתח ההתראה ← הצבע שלה */
    private val pulses = LinkedHashMap<String, Int>()

    fun attach(s: AccessibilityService) {
        service = s
        wm = s.getSystemService(WindowManager::class.java)
        val r = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val sc = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (l >= 0 && sc > 0) level = l * 100 / sc
                val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
                updateRing()
            }
        }
        batteryReceiver = r
        s.registerReceiver(r, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        build()
    }

    fun detach() {
        removeViews()
        batteryReceiver?.let { r -> service?.let { runCatching { it.unregisterReceiver(r) } } }
        batteryReceiver = null
        service = null
    }

    /** אחרי שינוי בהגדרות או סיבוב המסך */
    fun refresh() {
        if (service == null) return
        removeViews()
        build()
    }

    fun isRunning() = service != null

    /** מצב כיוון: כל עוד קיצורים פתוחה, מראים עיגול ורוד בולט ואת אזור הנגיעה, כדי לראות איפה הם יושבים */
    private var preview = false

    fun setPreview(on: Boolean) {
        if (preview == on) return
        preview = on
        refresh()
    }

    // ---------- נורת התראות ----------
    fun notificationPosted(key: String, color: Int) {
        pulses.remove(key)
        pulses[key] = color
        updateRing()
    }

    fun notificationRemoved(key: String) {
        if (pulses.remove(key) != null) updateRing()
    }

    private fun updateRing() {
        val s = service ?: return
        ring?.update(
            level, charging,
            if (NotchPrefs.notifyLight(s)) pulses.values.lastOrNull() else null,
            NotchPrefs.onlyCharging(s), NotchPrefs.chargeAnim(s),
        )
    }

    private fun removeViews() {
        ring?.let { v -> runCatching { wm?.removeView(v) } }
        zone?.let { v -> runCatching { wm?.removeView(v) } }
        ring = null
        zone = null
    }

    /** איפה החור של המצלמה (בפיקסלים) */
    private fun cutout(s: Context): Rect {
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

    private fun params(w: Int, h: Int, x: Int, y: Int, touchable: Boolean) = WindowManager.LayoutParams(
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

    /** איפה ובאיזה גודל לצייר: מרכז, רדיוס, עובי, גודל חלון הטבעת וגודל אזור הנגיעה (בפיקסלים) */
    private class Geom(val cx: Float, val cy: Float, val radius: Float, val thick: Float, val ringSize: Int, val zoneSize: Int)

    private fun geom(s: Context): Geom {
        val d = s.resources.displayMetrics.density
        val c = cutout(s)
        val cx = c.exactCenterX() + NotchPrefs.offsetX(s) * d
        val cy = c.exactCenterY() + NotchPrefs.offsetY(s) * d
        val radius = max(c.width(), c.height()) / 2f + (4f + NotchPrefs.size(s)) * d
        val thick = NotchPrefs.thickness(s) * d
        // חלון הטבעת בגודל קבוע לפי הגדלים המקסימליים, כדי שאפשר יהיה לשנות בלי לבנות מחדש
        val ringSize = ((radius + 4f * d * 4 + 6 * d) * 2).toInt()
        val zoneSize = ((radius + 14 * d) * 2).toInt()
        return Geom(cx, cy, radius, thick, ringSize, zoneSize)
    }

    /**
     * עדכון חי בזמן הזזת המחוונים: מזיזים ומשנים את הגודל של מה שכבר מצויר,
     * בלי להסיר ולהוסיף מחדש (כך זה חלק ומהיר)
     */
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

        val d = s.resources.displayMetrics.density
        val g = geom(s)
        val cx = g.cx
        val cy = g.cy
        val radius = g.radius
        val thick = g.thick

        if (NotchPrefs.ringOn(s) || preview) {
            val size = g.ringSize
            val v = RingView(s, radius, thick, preview)
            runCatching {
                w.addView(v, params(size, size, (cx - size / 2f).toInt(), (cy - size / 2f).toInt(), touchable = false))
                ring = v
            }
            updateRing()
        }

        // אין אף תנועה עם פעולה: לא שמים אזור נגיעה, כדי לא לחסום את שורת המצב
        val anyGesture = Gesture.entries.any { NotchPrefs.action(s, it).type != ActionType.NONE }
        if (!anyGesture && !NotchPrefs.showZone(s)) return

        // אזור הנגיעה: קצת יותר גדול מהחור, כדי שיהיה נוח לפגוע
        val zs = g.zoneSize
        val z = View(s)
        // אזור הנגיעה מסומן רק כשהמתג "להראות את אזור הנגיעה" דלוק (ורוד כשקיצורים פתוחה)
        if (NotchPrefs.showZone(s)) {
            z.setBackgroundColor(if (preview) Color.argb(60, 255, 64, 129) else Color.argb(70, 255, 255, 255))
        }
        attachGestures(s, z)
        runCatching {
            w.addView(z, params(zs, zs, (cx - zs / 2f).toInt(), (cy - zs / 2f).toInt(), touchable = true))
            zone = z
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachGestures(s: AccessibilityService, v: View) {
        fun run(g: Gesture) {
            val a = NotchPrefs.action(s, g)
            if (a.type == ActionType.NONE) return
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
            NotchActions.perform(s, a)
        }
        val detector = GestureDetector(s, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent) = true
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                run(Gesture.TAP)
                return true
            }
            override fun onDoubleTap(e: MotionEvent): Boolean {
                run(Gesture.DOUBLE)
                return true
            }
            override fun onLongPress(e: MotionEvent) {
                v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                val a = NotchPrefs.action(s, Gesture.LONG)
                if (a.type != ActionType.NONE) NotchActions.perform(s, a)
            }
            override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
                val start = e1 ?: return false
                val dx = e2.rawX - start.rawX
                val dy = e2.rawY - start.rawY
                when {
                    abs(dy) > abs(dx) && dy > 0 -> run(Gesture.DOWN)
                    abs(dx) > abs(dy) && dx > 0 -> run(Gesture.RIGHT)
                    abs(dx) > abs(dy) && dx < 0 -> run(Gesture.LEFT)
                }
                return true
            }
        })
        v.setOnTouchListener { _, e -> detector.onTouchEvent(e) }
    }
}

/** הטבעת: דקה וזוהרת. ירוק / כתום / אדום לפי הסוללה, נקודה מסתובבת בטעינה, הבהוב בהתראה */
@SuppressLint("ViewConstructor")
class RingView(
    ctx: Context,
    private var radius: Float,
    private var thick: Float,
    private val preview: Boolean = false,
) : View(ctx) {
    private var level = 100
    private var charging = false
    private var pulse: Int? = null
    private var onlyCharging = false
    private var chargeAnim = true
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

    /** גודל ועובי חדשים (בזמן הזזת המחוונים) */
    fun setGeometry(radius: Float, thick: Float) {
        this.radius = radius
        this.thick = thick
        track.strokeWidth = thick
        arc.strokeWidth = thick
        invalidate()
    }

    fun update(level: Int, charging: Boolean, pulse: Int?, onlyCharging: Boolean, chargeAnim: Boolean) {
        this.level = level
        this.charging = charging
        this.pulse = pulse
        this.onlyCharging = onlyCharging
        this.chargeAnim = chargeAnim
        val moving = pulse != null || (charging && chargeAnim)
        if (moving && !anim.isStarted) anim.start()
        if (!moving && anim.isStarted) anim.cancel()
        invalidate()
    }

    override fun onDetachedFromWindow() {
        anim.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(c: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)

        // מצב כיוון: עיגול ורוד בולט, כדי לראות בדיוק איפה הטבעת יושבת
        if (preview) {
            arc.color = Color.rgb(255, 64, 129)
            arc.alpha = 255
            arc.strokeWidth = max(thick, 2.5f * resources.displayMetrics.density)
            c.drawCircle(cx, cy, radius, arc)
            arc.strokeWidth = thick
            return
        }

        // נורת התראות: הבהוב רך בצבע של האפליקציה
        pulse?.let { col ->
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

        if (onlyCharging && !charging) return

        val col = when {
            level <= 15 -> Color.rgb(226, 75, 74)
            level <= 40 -> Color.rgb(239, 159, 39)
            else -> Color.rgb(62, 209, 140)
        }
        c.drawCircle(cx, cy, radius, track)
        val sweep = 360f * level / 100f
        glow.color = col
        glow.alpha = 75
        glow.strokeWidth = thick * 3.2f
        c.drawArc(rect, -90f, sweep, false, glow)
        arc.color = col
        arc.alpha = 255
        c.drawArc(rect, -90f, sweep, false, arc)

        // טעינה: נקודה לבנה מסתובבת
        if (charging && chargeAnim) {
            val ang = phase * 2 * PI - PI / 2
            c.drawCircle(cx + radius * cos(ang).toFloat(), cy + radius * sin(ang).toFloat(), thick * 1.1f, dot)
        }
    }
}
