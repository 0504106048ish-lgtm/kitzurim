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
        if (!AutoSend.armed(this)) return
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
}
