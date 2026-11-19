package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.content.ClipboardManager
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/**
 * העתקת טקסט: כשמעתיקים משהו, הגלולה מראה מה הועתק (עם שיתוף בהקשה).
 * אנדרואיד לא נותן לקרוא את הלוח ברקע, אז מזהים את ההעתקה דרך הנגישות:
 * לחיצה על "העתק", או ההודעה הקטנה "הועתק". את הטקסט לוקחים מהבחירה האחרונה,
 * ואם אין, מחלון קטן ובלתי נראה שמקבל לרגע את המיקוד וקורא את הלוח.
 */
object ClipWatch {
    private val main = Handler(Looper.getMainLooper())
    private var lastSel: String? = null
    private var selAt = 0L
    private var lastShown = ""
    private var shownAt = 0L
    private var pending = false

    private val copyWords = listOf("העתק", "העתקה", "copy")
    private val copiedWords = listOf("הועתק", "copied")

    fun onEvent(s: AccessibilityService, e: AccessibilityEvent) {
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillClip(s)) return
        when (e.eventType) {
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED -> {
                val t = e.text?.joinToString("") ?: return
                val from = e.fromIndex
                val to = e.toIndex
                if (from in 0 until to && to <= t.length) {
                    lastSel = t.substring(from, to)
                    selAt = SystemClock.elapsedRealtime()
                }
            }
            AccessibilityEvent.TYPE_VIEW_CLICKED -> {
                val label = ((e.text?.joinToString(" ") ?: "") + " " + (e.contentDescription ?: "")).trim().lowercase()
                // רק כפתור שכל מה שכתוב עליו הוא "העתק" (לא כל לחיצה שיש בה את המילה)
                if (label.length <= 20 && copyWords.any { label.contains(it) }) trigger(s)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                if (e.parcelableData is Notification) return // התראה רגילה, לא ההודעה הקטנה "הועתק"
                val t = e.text?.joinToString(" ")?.lowercase() ?: return
                if (copiedWords.any { t.contains(it) }) trigger(s)
            }
        }
    }

    // ההחזרה שלנו ללוח (בשביל המקלדת) לא נחשבת העתקה חדשה
    private var ownUntil = 0L

    private fun trigger(s: AccessibilityService) {
        if (pending || SystemClock.elapsedRealtime() < ownUntil) return
        pending = true
        main.postDelayed({
            pending = false
            val sel = lastSel?.takeIf { SystemClock.elapsedRealtime() - selAt < 60_000 }
            lastSel = null
            // יש טקסט מסומן: מראים אותו. אין: קוראים מהלוח מה הועתק
            // (נבדק: גם כשקיצורים כבויה המקלדת של גוגל לא מציעה להדביק, אז הקריאה לא הסיבה)
            if (!sel.isNullOrBlank()) show(sel) else readClip(s) { show(it) }
        }, 250)
    }

    private fun show(text: String) {
        val now = SystemClock.elapsedRealtime()
        if (text == lastShown && now - shownAt < 4000) return
        lastShown = text
        shownAt = now
        LivePill.showClip(text)
    }

    /** חלון בגודל נקודה שמקבל מיקוד לרגע: רק כך מותר לקרוא את הלוח (רק כשמקישים על הגלולה) */
    fun readClip(s: AccessibilityService, onText: (String) -> Unit) {
        val wm = s.getSystemService(WindowManager::class.java) ?: return
        val v = View(s)
        val lp = WindowManager.LayoutParams(
            1, 1,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }
        var done = false
        fun finish() {
            if (done) return
            done = true
            runCatching { wm.removeView(v) }
        }
        v.viewTreeObserver.addOnWindowFocusChangeListener { has ->
            if (!has || done) return@addOnWindowFocusChangeListener
            val cm = s.getSystemService(ClipboardManager::class.java)
            val data = runCatching { cm?.primaryClip }.getOrNull()
            val clip = runCatching { data?.getItemAt(0)?.coerceToText(s)?.toString() }.getOrNull()
            finish()
            if (!clip.isNullOrBlank()) onText(clip)
        }
        runCatching { wm.addView(v, lp) }.onFailure { return }
        main.postDelayed({ finish() }, 800)
    }
}
