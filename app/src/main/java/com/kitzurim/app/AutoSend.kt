package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * שליחה אוטומטית בוואטסאפ: אחרי שקיצורים פותחת צ'אט עם הודעה כתובה,
 * שירות הנגישות לוחץ על "שלח". הוא פועל רק כשקיצורים ביקשה (עד 15 שניות אחרי), ורק בוואטסאפ.
 */
object AutoSend {
    private const val WINDOW_MS = 15_000L

    private fun sp(c: Context) = c.getSharedPreferences("kitzurim_send", Context.MODE_PRIVATE)

    /** קיצורים עומדת לפתוח צ'אט עם הודעה: מותר ללחוץ "שלח" בשניות הקרובות */
    fun arm(c: Context) = sp(c).edit().putLong("armed_at", System.currentTimeMillis()).apply()
    fun disarm(c: Context) = sp(c).edit().remove("armed_at").apply()
    fun armed(c: Context) = System.currentTimeMillis() - sp(c).getLong("armed_at", 0) < WINDOW_MS

    /** האם אישרת את שירות הנגישות של קיצורים */
    fun allowed(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(ctx, AutoSendService::class.java).flattenToString()
        return flat.split(":").any { it.equals(me, ignoreCase = true) }
    }
}

class AutoSendService : AccessibilityService() {
    private val sendIds = listOf("com.whatsapp:id/send", "com.whatsapp.w4b:id/send")

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val type = event?.eventType ?: 0
        // איזו אפליקציה פתוחה (בשביל תנועות לפי אפליקציה)
        if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val pkg = event?.packageName?.toString()
            // לא מחליפים בגלל שורת ההתראות, המקלדת או קיצורים עצמה
            if (pkg != null && pkg != "com.android.systemui" && pkg != packageName && !pkg.contains("inputmethod") &&
                !pkg.contains("honeyboard")
            ) {
                NotchOverlay.currentPackage = pkg
            }
        }
        // מסך מלא: שורת המצב נעלמה מהמסך
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED || type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            checkFullscreen()
        }

        if (!AutoSend.armed(this)) return
        if (event?.packageName?.toString()?.startsWith("com.whatsapp") != true) return
        val root = rootInActiveWindow ?: return
        val button: AccessibilityNodeInfo = sendIds.firstNotNullOfOrNull { id ->
            root.findAccessibilityNodeInfosByViewId(id)?.firstOrNull()
        } ?: return
        if (button.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            AutoSend.disarm(this)
            Actions.toast(this, "✓ נשלח")
        }
    }

    override fun onInterrupt() {}

    /** מסך מלא = אין חלון מערכת דק למעלה (שורת המצב) */
    private fun checkFullscreen() {
        val d = resources.displayMetrics.density
        val screenW = resources.displayMetrics.widthPixels
        val list = try { windows } catch (_: Exception) { return }
        val bounds = android.graphics.Rect()
        val statusBarShown = list.any { w ->
            w.getBoundsInScreen(bounds)
            w.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SYSTEM &&
                bounds.top <= 0 && bounds.height() in 1..(80 * d).toInt() && bounds.width() > screenW / 2
        }
        NotchOverlay.setFullscreen(!statusBarShown)
    }

    // החור של המצלמה: הטבעת ואזור הנגיעה רצים כל עוד השירות פעיל
    override fun onServiceConnected() {
        super.onServiceConnected()
        NotchOverlay.attach(this)
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        NotchOverlay.detach()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        NotchOverlay.detach()
        super.onDestroy()
    }

    // סיבוב מסך: לבנות מחדש (במסך שוכב הטבעת מוסתרת)
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        NotchOverlay.refresh()
    }
}
