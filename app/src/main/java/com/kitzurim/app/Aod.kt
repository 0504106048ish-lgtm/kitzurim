package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextClock

/**
 * "תמיד מופעל" של קיצורים: הודעה מגיעה כשהמסך כבוי ← המסך נדלק לכמה שניות, שחור לגמרי ובבהירות נמוכה,
 * עם הגלולה של ההודעה סביב המצלמה והשעה בקטן, ונכבה לבד. נראה כמו מסך "תמיד מופעל" ולא כמו מסך שנדלק.
 * לא נדלק בכיס או בתיק (חיישן קרבה) וכשהטלפון הפוך על השולחן.
 */
object Aod {
    /** ההודעה שהמסך השחור מראה */
    internal var pending: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /** הגיעה הודעה: אם המסך כבוי ומותר, בודקים שהטלפון לא בכיס ולא הפוך, ומדליקים */
    fun message(c: Context, show: () -> Unit) {
        if (!NotchPrefs.aodMsg(c) || !NotchPrefs.onLockScreen(c)) return
        val pm = c.getSystemService(PowerManager::class.java) ?: return
        if (pm.isInteractive) return
        // בזמן שיחה לא מדליקים
        if (LivePill.inCallPill()) return
        // כבר דולק: רק מחליפים את ההודעה
        AodActivity.current?.let { a ->
            pending = show
            a.showNow()
            return
        }
        safeToWake(c) { ok ->
            if (!ok) return@safeToWake
            pending = show
            runCatching {
                val ctx = NotchOverlay.service ?: c
                ctx.startActivity(
                    Intent(ctx, AodActivity::class.java)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS),
                )
            }
        }
    }

    /** קוראים לרגע את חיישן הקרבה ואת כיוון הטלפון: לא בכיס ולא הפוך על המסך */
    private fun safeToWake(c: Context, done: (Boolean) -> Unit) {
        val sm = c.getSystemService(SensorManager::class.java)
        if (sm == null) { done(true); return }
        val prox = sm.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        val acc = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        var near: Boolean? = if (prox == null) false else null
        var faceDown: Boolean? = if (acc == null) false else null
        var finished = false
        lateinit var l: SensorEventListener
        fun finish() {
            if (finished) return
            finished = true
            runCatching { sm.unregisterListener(l) }
            done(near != true && faceDown != true)
        }
        l = object : SensorEventListener {
            override fun onSensorChanged(e: SensorEvent) {
                when (e.sensor.type) {
                    Sensor.TYPE_PROXIMITY -> near = e.values[0] < (prox?.maximumRange ?: 5f).coerceAtMost(5f)
                    // המסך כלפי מטה: z שלילי חזק
                    Sensor.TYPE_ACCELEROMETER -> faceDown = e.values[2] < -7f
                }
                if (near != null && faceDown != null) finish()
            }
            override fun onAccuracyChanged(s: Sensor?, a: Int) {}
        }
        prox?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_FASTEST) }
        acc?.let { sm.registerListener(l, it, SensorManager.SENSOR_DELAY_FASTEST) }
        // חיישן שלא ענה מהר: מחליטים לפי מה שיש
        main.postDelayed({ finish() }, 450)
    }

    /** אחרי כמה שניות: המסך נכבה שוב (נעילה) */
    internal fun sleep() {
        NotchOverlay.service?.let { s ->
            if (Build.VERSION.SDK_INT >= 28) runCatching { s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }
        }
    }
}

/** המסך השחור עצמו: שעה קטנה, והגלולה (שמצוירת מעליו) סביב המצלמה */
class AodActivity : Activity() {
    companion object {
        var current: AodActivity? = null
    }

    private val main = Handler(Looper.getMainLooper())
    private var touched = false
    private val off = Runnable {
        if (touched) return@Runnable
        finish()
        overridePendingTransition(0, 0)
        Aod.sleep()
    }

    /** פתחו את הטלפון בזמן שהמסך השחור דלוק: נעלמים בלי לכבות את המסך */
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            touched = true
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        current = this
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val d = resources.displayMetrics.density
        // בהירות גבוהה: הרקע שחור לגמרי (פיקסלים שחורים כבויים ולא צורכים סוללה), אז רק הגלולה נדלקת, בוהקת וברורה
        window.attributes = window.attributes.apply { screenBrightness = 0.85f }
        // שחור לגמרי, בלי שעה: רק ההתראה בגלולה
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        // נגיעה במסך: רוצים להשתמש בטלפון, אז לא מכבים אותו (וממשיכים למסך הנעילה)
        root.setOnTouchListener { _: View, e: MotionEvent ->
            if (e.actionMasked == MotionEvent.ACTION_DOWN) {
                touched = true
                main.removeCallbacks(off)
                finish()
            }
            true
        }
        setContentView(root)
        // שחור לגמרי: בלי שורת המצב ושורת הניווט
        @Suppress("DEPRECATION")
        root.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        runCatching { registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_PRESENT)) }
    }

    override fun onResume() {
        super.onResume()
        showNow()
    }

    /** מראים את ההודעה בגלולה. המסך נכבה כשהגלולה נסגרת (או אחרי 8 שניות לכל היותר) */
    fun showNow() {
        main.removeCallbacks(off)
        main.removeCallbacks(watch)
        // רגע אחרי שהמסך נדלק, כדי שהגלולה תצויר עליו
        main.postDelayed({ Aod.pending?.invoke() }, 250)
        main.postDelayed(watch, 1500)
        main.postDelayed(off, 8000)
    }

    /** הגלולה נסגרה: אין מה להראות, אז המסך נכבה מיד (לא נשאר מסך שחור) */
    private val watch = object : Runnable {
        override fun run() {
            if (!LivePill.showing()) {
                main.removeCallbacks(off)
                main.postDelayed(off, 250)
            } else main.postDelayed(this, 400)
        }
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        runCatching { unregisterReceiver(unlocked) }
        if (current === this) current = null
        super.onDestroy()
    }
}
