package com.kitzurim.app

import android.accessibilityservice.AccessibilityService
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import org.json.JSONObject

/** התנועות על החור של המצלמה */
enum class Gesture(val label: String) {
    TAP("הקשה"),
    DOUBLE("הקשה כפולה"),
    TRIPLE("הקשה משולשת"),
    LONG("לחיצה ארוכה"),
    DOWN("החלקה למטה"),
    RIGHT("החלקה ימינה"),
    LEFT("החלקה שמאלה"),
}

/** מה צריך לבחור אחרי שבוחרים את הפעולה */
enum class ArgKind { NONE, APP, CONTACT, URL }

/** כל הפעולות שאפשר לשים על תנועה */
enum class ActionType(val emoji: String, val label: String, val arg: ArgKind = ArgKind.NONE) {
    NONE("🚫", "בלי פעולה"),
    SCREENSHOT("📸", "צילום מסך"),
    TORCH("🔦", "פנס"),
    VOICE("🎙️", "פקודה קולית"),
    NOTIFICATIONS("🔔", "התראות"),
    QUICK_SETTINGS("⚙️", "הגדרות מהירות"),
    BACK("↩️", "חזרה"),
    HOME("🏠", "מסך הבית"),
    RECENTS("🗂️", "אפליקציות אחרונות"),
    LAST_APP("🔁", "חזרה לאפליקציה הקודמת"),
    LOCK("🔒", "נעילת מסך"),
    POWER("⏻", "תפריט כיבוי"),
    SPLIT("◫", "מסך מפוצל"),
    PLAY_PAUSE("⏯️", "מוזיקה: הפעלה / השהיה (מתחלף)"),
    PLAY("▶️", "מוזיקה: הפעלה"),
    PAUSE("⏸️", "מוזיקה: השהיה"),
    STOP("⏹️", "מוזיקה: עצירה"),
    NEXT("⏭️", "מוזיקה: השיר הבא"),
    PREV("⏮️", "מוזיקה: השיר הקודם"),
    VOLUME("🔊", "עוצמת קול"),
    RINGER("🔕", "צלצול ← רטט ← שקט"),
    CAMERA("📷", "מצלמה"),
    ASSISTANT("✨", "Google Assistant"),
    NAV_HOME("🧭", "ניווט הביתה"),
    DRIVING("🚗", "מצב נהיגה (הדלקה / כיבוי)"),
    BATTERY_INFO("🔋", "מידע על הסוללה"),
    ROTATE("🔄", "סיבוב מסך אוטומטי (הדלקה / כיבוי)"),
    APP("📱", "פתיחת אפליקציה…", ArgKind.APP),
    WHATSAPP("💬", "צ'אט בוואטסאפ עם…", ArgKind.CONTACT),
    CALL("📞", "התקשרות ל…", ArgKind.CONTACT),
    URL("🌐", "פתיחת אתר…", ArgKind.URL),
}

/** פעולה עם מה שבחרת לה (אפליקציה, איש קשר או אתר) */
data class NotchAction(val type: ActionType, val value: String = "", val name: String = "") {
    fun title(): String = type.emoji + " " + when (type.arg) {
        ArgKind.NONE -> type.label
        ArgKind.APP -> "לפתוח את $name"
        ArgKind.CONTACT -> if (type == ActionType.CALL) "להתקשר ל$name" else "צ'אט עם $name"
        ArgKind.URL -> "לפתוח $name"
    }

    fun toJson(): String = JSONObject().put("t", type.name).put("v", value).put("n", name).toString()

    companion object {
        fun fromJson(s: String?): NotchAction? = try {
            val o = JSONObject(s ?: return null)
            NotchAction(ActionType.valueOf(o.getString("t")), o.optString("v"), o.optString("n"))
        } catch (_: Exception) {
            null
        }
    }
}

/** הפנס: זוכר אם הוא דולק */
object TorchCtl {
    private var on = false
    private var id: String? = null
    private var registered = false

    fun toggle(ctx: Context): Boolean? {
        val cm = ctx.getSystemService(CameraManager::class.java) ?: return null
        if (!registered) {
            registered = true
            id = try {
                cm.cameraIdList.firstOrNull {
                    cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                }
            } catch (_: Exception) {
                null
            }
            cm.registerTorchCallback(object : CameraManager.TorchCallback() {
                override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                    if (cameraId == id) on = enabled
                }
            }, Handler(Looper.getMainLooper()))
        }
        val cam = id ?: return null
        return try {
            cm.setTorchMode(cam, !on)
            on = !on
            on
        } catch (_: Exception) {
            null
        }
    }
}

object NotchActions {
    private fun start(s: Context, intent: Intent, failText: String) {
        try {
            s.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: ActivityNotFoundException) {
            Actions.toast(s, failText)
        } catch (_: SecurityException) {
            Actions.toast(s, failText)
        }
    }

    private fun media(s: Context, code: Int) {
        val am = s.getSystemService(AudioManager::class.java) ?: return
        val t = SystemClock.uptimeMillis()
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0))
        am.dispatchMediaKeyEvent(KeyEvent(t, t, KeyEvent.ACTION_UP, code, 0))
    }

    fun perform(s: AccessibilityService, a: NotchAction) {
        when (a.type) {
            ActionType.NONE -> {}
            ActionType.SCREENSHOT -> if (Build.VERSION.SDK_INT >= 28) {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT)
            }
            ActionType.TORCH -> when (TorchCtl.toggle(s)) {
                true -> Actions.toast(s, "🔦 הפנס נדלק")
                false -> Actions.toast(s, "🔦 הפנס כבה")
                null -> Actions.toast(s, "אי אפשר להדליק את הפנס כרגע")
            }
            ActionType.VOICE -> start(s, Intent(s, VoiceActivity::class.java), "לא הצלחתי לפתוח")
            ActionType.NOTIFICATIONS -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
            ActionType.QUICK_SETTINGS -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS)
            ActionType.BACK -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            ActionType.HOME -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
            ActionType.RECENTS -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
            ActionType.LAST_APP -> {
                // שתי לחיצות מהירות על "אחרונות" עוברות לאפליקציה הקודמת
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                Handler(Looper.getMainLooper()).postDelayed({
                    s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS)
                }, 280)
            }
            ActionType.LOCK -> if (Build.VERSION.SDK_INT >= 28) {
                s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
            }
            ActionType.POWER -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_POWER_DIALOG)
            ActionType.SPLIT -> s.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN)
            ActionType.PLAY_PAUSE -> media(s, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            ActionType.PLAY -> media(s, KeyEvent.KEYCODE_MEDIA_PLAY)
            ActionType.PAUSE -> media(s, KeyEvent.KEYCODE_MEDIA_PAUSE)
            ActionType.STOP -> media(s, KeyEvent.KEYCODE_MEDIA_STOP)
            ActionType.NEXT -> media(s, KeyEvent.KEYCODE_MEDIA_NEXT)
            ActionType.PREV -> media(s, KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            ActionType.VOLUME -> s.getSystemService(AudioManager::class.java)
                ?.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_SAME, AudioManager.FLAG_SHOW_UI)
            ActionType.RINGER -> {
                val am = s.getSystemService(AudioManager::class.java) ?: return
                val next = when (am.ringerMode) {
                    AudioManager.RINGER_MODE_NORMAL -> AudioManager.RINGER_MODE_VIBRATE
                    AudioManager.RINGER_MODE_VIBRATE ->
                        if (QuietPlaces.dndAllowed(s)) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_NORMAL
                    else -> AudioManager.RINGER_MODE_NORMAL
                }
                QuietPlaces.setRinger(s, next)
                Actions.toast(
                    s,
                    when (next) {
                        AudioManager.RINGER_MODE_NORMAL -> "🔔 צלצול"
                        AudioManager.RINGER_MODE_VIBRATE -> "📳 רטט"
                        else -> "🔕 שקט"
                    },
                )
            }
            ActionType.CAMERA -> start(s, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA), "לא נמצאה מצלמה")
            ActionType.ASSISTANT -> start(s, Intent(Intent.ACTION_VOICE_COMMAND), "לא נמצא עוזר קולי")
            ActionType.NAV_HOME -> {
                if (Prefs.home(s).isBlank()) {
                    Actions.toast(s, "צריך להקליד כתובת בית בהגדרות")
                } else {
                    Actions.navigateHome(s, Prefs.home(s))
                }
            }
            ActionType.DRIVING -> {
                val on = !AutoPrefs.drivingActive(s)
                AutoPrefs.setDrivingActive(s, on)
                Actions.toast(s, if (on) "🚗 מצב נהיגה פעיל" else "מצב נהיגה כבה")
                if (!on) DriveSummary.show(s)
            }
            ActionType.BATTERY_INFO -> {
                val b = Battery.read(s, null)
                val pct = b.precise?.let { "%.2f".format(it) } ?: "${b.percent}"
                val parts = mutableListOf("🔋 $pct%")
                if (b.charging) parts += "בטעינה"
                b.minutesToFull?.let { parts += "מלאה בעוד ${Battery.minutesText(it)}" }
                b.tempC?.let { parts += "${"%.1f".format(it)}°C" }
                Actions.toast(s, parts.joinToString(" · "))
            }
            ActionType.ROTATE -> {
                // צריך אישור "שינוי הגדרות מערכת" (פעם אחת)
                if (!android.provider.Settings.System.canWrite(s)) {
                    Actions.toast(s, "צריך לאשר לקיצורים \"שינוי הגדרות מערכת\", ואז לנסות שוב")
                    start(
                        s,
                        Intent(android.provider.Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + s.packageName)),
                        "לא הצלחתי לפתוח את ההגדרות",
                    )
                } else {
                    val key = android.provider.Settings.System.ACCELEROMETER_ROTATION
                    val on = android.provider.Settings.System.getInt(s.contentResolver, key, 0) == 1
                    android.provider.Settings.System.putInt(s.contentResolver, key, if (on) 0 else 1)
                    Actions.toast(s, if (on) "🔒 הסיבוב ננעל" else "🔄 סיבוב אוטומטי דלוק")
                }
            }
            ActionType.APP -> {
                val i = s.packageManager.getLaunchIntentForPackage(a.value)
                if (i == null) Actions.toast(s, "האפליקציה לא נמצאה") else start(s, i, "לא הצלחתי לפתוח")
            }
            ActionType.WHATSAPP -> Actions.openWhatsApp(s, a.value, null)
            ActionType.CALL -> {
                val uri = Uri.parse("tel:" + a.value)
                try {
                    s.startActivity(Intent(Intent.ACTION_CALL, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: SecurityException) {
                    start(s, Intent(Intent.ACTION_DIAL, uri), "לא הצלחתי לחייג")
                }
            }
            ActionType.URL -> {
                val url = if (a.value.startsWith("http")) a.value else "https://" + a.value
                start(s, Intent(Intent.ACTION_VIEW, Uri.parse(url)), "לא הצלחתי לפתוח את האתר")
            }
        }
    }
}

/** הגדרות החור והטבעת */
object NotchPrefs {
    private fun sp(c: Context) = c.getSharedPreferences("kitzurim_notch", Context.MODE_PRIVATE)


    fun action(c: Context, g: Gesture): NotchAction =
        // ברירת מחדל: כל התנועות כבויות, עד שבוחרים פעולה
        NotchAction.fromJson(sp(c).getString("g_" + g.name, null)) ?: NotchAction(ActionType.NONE)

    fun setAction(c: Context, g: Gesture, a: NotchAction) = sp(c).edit().putString("g_" + g.name, a.toJson()).apply()

    fun enabled(c: Context) = sp(c).getBoolean("enabled", true)
    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("enabled", v).apply()

    fun ringOn(c: Context) = sp(c).getBoolean("ring", false)
    fun setRingOn(c: Context, v: Boolean) = sp(c).edit().putBoolean("ring", v).apply()

    /** הטבעת מופיעה רק בזמן טעינה */
    fun onlyCharging(c: Context) = sp(c).getBoolean("only_charging", false)
    fun setOnlyCharging(c: Context, v: Boolean) = sp(c).edit().putBoolean("only_charging", v).apply()

    fun chargeAnim(c: Context) = sp(c).getBoolean("charge_anim", false)
    fun setChargeAnim(c: Context, v: Boolean) = sp(c).edit().putBoolean("charge_anim", v).apply()

    /** נורת התראות: הטבעת מהבהבת בצבע של האפליקציה כשיש הודעה שלא ראית */
    fun notifyLight(c: Context) = sp(c).getBoolean("notify_light", false)
    fun setNotifyLight(c: Context, v: Boolean) = sp(c).edit().putBoolean("notify_light", v).apply()

    /** עובי הטבעת בנקודות (dp) */
    fun thickness(c: Context) = sp(c).getFloat("thick", 1.6f)
    fun setThickness(c: Context, v: Float) = sp(c).edit().putFloat("thick", v).apply()

    /** כיוון: גודל ומיקום (dp), למקרה שהטבעת לא יושבת בדיוק על החור */
    fun size(c: Context) = sp(c).getFloat("size", 0f)
    fun setSize(c: Context, v: Float) = sp(c).edit().putFloat("size", v).apply()
    fun offsetX(c: Context) = sp(c).getFloat("dx", 0f)
    fun setOffsetX(c: Context, v: Float) = sp(c).edit().putFloat("dx", v).apply()
    fun offsetY(c: Context) = sp(c).getFloat("dy", 0f)
    fun setOffsetY(c: Context, v: Float) = sp(c).edit().putFloat("dy", v).apply()

    /** כמה גדול אזור הנגיעה מסביב לחור (dp) */
    fun zoneExtra(c: Context) = sp(c).getFloat("zone_extra", 14f)
    fun setZoneExtra(c: Context, v: Float) = sp(c).edit().putFloat("zone_extra", v).apply()

    /** מחזיר את המיקום, הגודל והעובי להתחלה */
    fun resetPosition(c: Context) = sp(c).edit()
        .remove("size").remove("dx").remove("dy").remove("thick").remove("zone_extra").apply()

    /** להראות את אזור הנגיעה (כדי לכוון) */
    fun showZone(c: Context) = sp(c).getBoolean("show_zone", false)
    fun setShowZone(c: Context, v: Boolean) = sp(c).edit().putBoolean("show_zone", v).apply()

    // ---------- עוד דברים שהטבעת מראה (כולם מתחילים כבויים) ----------
    /** עוצמת קול: כשמשנים ווליום, הטבעת מתמלאת לפי העוצמה לשנייה */
    fun volumeRing(c: Context) = sp(c).getBoolean("volume_ring", false)
    fun setVolumeRing(c: Context, v: Boolean) = sp(c).edit().putBoolean("volume_ring", v).apply()

    /** התקדמות השיר: קו שמתמלא עד סוף השיר */
    fun musicRing(c: Context) = sp(c).getBoolean("music_ring", false)
    fun setMusicRing(c: Context, v: Boolean) = sp(c).edit().putBoolean("music_ring", v).apply()

    /** שיחות: הבהוב בזמן צלצול, וזמן השיחה בזמן שמדברים */
    fun callRing(c: Context) = sp(c).getBoolean("call_ring", false)
    fun setCallRing(c: Context, v: Boolean) = sp(c).edit().putBoolean("call_ring", v).apply()

    // ---------- התנהגות ----------
    /** רטט קטן בכל נגיעה בחור */
    fun haptic(c: Context) = sp(c).getBoolean("haptic", false)
    fun setHaptic(c: Context, v: Boolean) = sp(c).edit().putBoolean("haptic", v).apply()

    /** להסתיר את הטבעת ואת אזור הנגיעה בסרטון, משחק או תמונה במסך מלא */
    fun hideFullscreen(c: Context) = sp(c).getBoolean("hide_full", false)
    fun setHideFullscreen(c: Context, v: Boolean) = sp(c).edit().putBoolean("hide_full", v).apply()

    // ---------- תנועות לפי אפליקציה ----------
    private fun appsJson(c: Context): JSONObject =
        try { JSONObject(sp(c).getString("apps", "{}") ?: "{}") } catch (_: Exception) { JSONObject() }

    /** האפליקציות שיש להן תנועות משלהן: חבילה ← שם */
    fun apps(c: Context): List<Pair<String, String>> {
        val o = appsJson(c)
        return o.keys().asSequence().map { it to o.getString(it) }.sortedBy { it.second }.toList()
    }

    fun addApp(c: Context, pkg: String, name: String) {
        sp(c).edit().putString("apps", appsJson(c).put(pkg, name).toString()).apply()
    }

    fun removeApp(c: Context, pkg: String) {
        val o = appsJson(c)
        o.remove(pkg)
        val e = sp(c).edit().putString("apps", o.toString())
        Gesture.entries.forEach { e.remove("a_${pkg}_${it.name}") }
        e.apply()
    }

    /** הפעולה של תנועה באפליקציה מסוימת, או null = כמו בכל מקום */
    fun appAction(c: Context, pkg: String, g: Gesture): NotchAction? =
        NotchAction.fromJson(sp(c).getString("a_${pkg}_${g.name}", null))

    fun setAppAction(c: Context, pkg: String, g: Gesture, a: NotchAction?) {
        val e = sp(c).edit()
        if (a == null) e.remove("a_${pkg}_${g.name}") else e.putString("a_${pkg}_${g.name}", a.toJson())
        e.apply()
    }

    /** מה התנועה עושה עכשיו: לפי האפליקציה הפתוחה אם הוגדר לה, ואחרת הרגיל */
    fun actionFor(c: Context, g: Gesture, pkg: String?): NotchAction =
        pkg?.let { appAction(c, it, g) } ?: action(c, g)

    /** האם יש בכלל תנועה כלשהי עם פעולה (בכל מקום או באפליקציה כלשהי) */
    fun anyGesture(c: Context): Boolean =
        Gesture.entries.any { action(c, it).type != ActionType.NONE } ||
            apps(c).any { (pkg, _) -> Gesture.entries.any { appAction(c, pkg, it)?.let { a -> a.type != ActionType.NONE } == true } }
}
