package com.kitzurim.app

import android.app.Activity
import android.app.NotificationManager
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import android.service.notification.NotificationListenerService

/**
 * ברכב ההודעות של וואטסאפ קופצות, ובטלפון לא.
 * אנדרואיד אוטו מקפיץ הודעה רק אם החלון הקופץ שלה דלוק בטלפון. אצלך הוא כבוי (בטלפון רק הגלולה של קיצורים),
 * אז כשמתחברים לרכב קיצורים מדליקה את החלון הקופץ של ההודעות בוואטסאפ, וכשמתנתקים מחזירה בדיוק כמו שהיה.
 * אנדרואיד מרשה לשנות הגדרות התראה של אפליקציה אחרת רק אחרי אישור חד-פעמי של מכשיר מחובר (כאן: הרכב).
 */
object CarPopup {
    private val apps = listOf("com.whatsapp", "com.whatsapp.w4b")
    private fun sp(c: Context) = c.getSharedPreferences("car_popup", Context.MODE_PRIVATE)

    /** המאזין להתראות של קיצורים (רק הוא יכול לשנות את ההגדרות של וואטסאפ) */
    @Volatile var listener: NotificationListenerService? = null

    fun on(c: Context) = sp(c).getBoolean("on", true)
    fun setOn(c: Context, v: Boolean) {
        sp(c).edit().putBoolean("on", v).apply()
        listener?.let { l -> if (v && CarMirror.connected(l)) boost(l) else restore(l) }
    }

    /** האם כבר אישרת פעם אחת (מכשיר מקושר לקיצורים) */
    fun approved(c: Context): Boolean = runCatching {
        val cdm = c.getSystemService(CompanionDeviceManager::class.java) ?: return false
        if (Build.VERSION.SDK_INT >= 33) cdm.myAssociations.isNotEmpty()
        else @Suppress("DEPRECATION") cdm.associations.isNotEmpty()
    }.getOrDefault(false)

    /** האישור החד-פעמי: אנדרואיד מראה רשימת מכשירי בלוטות', בוחרים את הרכב */
    fun approve(a: Activity, done: () -> Unit) {
        val cdm = a.getSystemService(CompanionDeviceManager::class.java) ?: run {
            Actions.toast(a, "הטלפון לא מאפשר את זה"); return
        }
        // הרשימה מראה כל מה שבסביבה: בלוטות' רגיל, בלוטות' חסכוני (שעונים, אוזניות, טלוויזיות) ורשתות Wi-Fi.
        // לא משנה מה בוחרים: האישור עצמו הוא מה שחשוב (רכב שכבר מחובר לא תמיד מופיע, כי הוא לא "מחפש")
        val req = AssociationRequest.Builder()
            .addDeviceFilter(BluetoothDeviceFilter.Builder().build())
            .addDeviceFilter(android.companion.BluetoothLeDeviceFilter.Builder().build())
            .addDeviceFilter(android.companion.WifiDeviceFilter.Builder().build())
            .build()
        val cb = object : CompanionDeviceManager.Callback() {
            private fun ask(sender: IntentSender) {
                runCatching { a.startIntentSenderForResult(sender, 4710, null, 0, 0, 0) }
                    .onFailure { Actions.toast(a, "לא הצלחתי לפתוח את החלון של האישור") }
            }
            @Deprecated("Deprecated in Java")
            override fun onDeviceFound(sender: IntentSender) = ask(sender)
            override fun onAssociationPending(sender: IntentSender) = ask(sender)
            override fun onAssociationCreated(info: AssociationInfo) {
                Actions.toast(a, "אושר. ברכב ההודעות של וואטסאפ יקפצו")
                listener?.let { l -> if (CarMirror.connected(l)) boost(l) }
                done()
            }
            override fun onFailure(error: CharSequence?) {
                Actions.toast(a, "לא אושר" + (error?.let { ": $it" } ?: ""))
            }
        }
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) cdm.associate(req, a.mainExecutor, cb)
            else @Suppress("DEPRECATION") cdm.associate(req, cb, null)
        }.onFailure { Actions.toast(a, "הטלפון לא מאפשר את זה") }
    }

    /** ערוצי ההודעות של וואטסאפ (צ'אט וקבוצה), לא שיחות ולא שקטים */
    private fun isChat(id: String) = id.contains("chat", true) && !id.contains("silent", true) && !id.contains("call", true)

    /** מחוברים לרכב: מדליקים את החלון הקופץ להודעות של וואטסאפ (רק מה שהיה "התראה" בלי חלון קופץ) */
    fun boost(l: NotificationListenerService) {
        if (!on(l)) return
        if (!approved(l)) { note(l, "❌ אין אישור חד-פעמי"); return }
        val user = android.os.Process.myUserHandle()
        val saved = (sp(l).getStringSet("saved", emptySet()) ?: emptySet()).toMutableSet()
        var changed = 0
        var high = 0
        var err: String? = null
        apps.forEach { pkg ->
            val chans = runCatching { l.getNotificationChannels(pkg, user) }
                .onFailure { err = it.javaClass.simpleName + ": " + it.message }
                .getOrNull() ?: return@forEach
            chans.filter { isChat(it.id) }.forEach { ch ->
                when {
                    ch.importance >= NotificationManager.IMPORTANCE_HIGH -> high++
                    // "חסום" (NONE) נשאר חסום. כל השאר (שקט / התראה בלי קפיצה) עולה לקפיצה
                    ch.importance > NotificationManager.IMPORTANCE_NONE -> {
                        // זוכרים מה היה, כדי להחזיר בדיוק את זה
                        if (saved.none { it.startsWith("$pkg|${ch.id}|") }) saved += "$pkg|${ch.id}|${ch.importance}"
                        ch.importance = NotificationManager.IMPORTANCE_HIGH
                        runCatching { l.updateNotificationChannel(pkg, user, ch) }
                            .onSuccess { changed++ }
                            .onFailure { err = it.javaClass.simpleName + ": " + it.message }
                    }
                }
            }
        }
        sp(l).edit().putStringSet("saved", saved).apply()
        note(l, when {
            err != null -> "❌ אנדרואיד לא נתן לשנות: $err"
            changed > 0 -> "✅ הודלק חלון קופץ ל-$changed ערוצים של וואטסאפ"
            high > 0 -> "✅ החלון הקופץ של וואטסאפ דלוק ($high ערוצים)"
            else -> "❌ לא נמצאו ערוצי הודעות של וואטסאפ"
        })
        if (changed > 0) ActivityLog.add(l, "🚗", "ברכב: ההודעות של וואטסאפ קופצות")
    }

    /** מה קרה בפעם האחרונה (מוצג במסך, כדי לדעת מה עובד ומה לא) */
    private fun note(c: Context, s: String) {
        val t = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        sp(c).edit().putString("last", "$t  $s").apply()
    }

    /** מצב מלא, בשביל השורה במסך: אישור, רכב, ומה מצב כל ערוץ של וואטסאפ עכשיו */
    fun status(c: Context): String {
        val l = listener
        val lines = mutableListOf<String>()
        lines += (if (approved(c)) "✅" else "❌") + " אישור חד-פעמי"
        lines += (if (CarMirror.connected(c)) "✅ מחובר לרכב" else "⚪ לא מחובר לרכב עכשיו")
        if (l == null) lines += "❌ גישה להתראות לא פעילה"
        else apps.forEach { pkg ->
            val chans = runCatching { l.getNotificationChannels(pkg, android.os.Process.myUserHandle()) }
            chans.onFailure { lines += "❌ אין גישה לערוצים: ${it.javaClass.simpleName}" }
            chans.getOrNull()?.filter { isChat(it.id) }?.forEach { ch ->
                val imp = when (ch.importance) {
                    NotificationManager.IMPORTANCE_HIGH, NotificationManager.IMPORTANCE_MAX -> "קופץ"
                    NotificationManager.IMPORTANCE_DEFAULT -> "בלי קפיצה"
                    NotificationManager.IMPORTANCE_LOW, NotificationManager.IMPORTANCE_MIN -> "שקט"
                    NotificationManager.IMPORTANCE_NONE -> "חסום"
                    else -> ch.importance.toString()
                }
                lines += "• ${ch.name ?: ch.id}: $imp"
            }
        }
        sp(c).getString("last", null)?.let { lines += "פעם אחרונה: $it" }
        return lines.joinToString("\n")
    }

    /** התנתקו מהרכב (או כיבית): מחזירים את ההגדרות של וואטסאפ בדיוק כמו שהיו */
    fun restore(l: NotificationListenerService) {
        val saved = sp(l).getStringSet("saved", emptySet()) ?: emptySet()
        if (saved.isEmpty()) return
        val user = android.os.Process.myUserHandle()
        val left = saved.toMutableSet()
        saved.forEach { e ->
            val parts = e.split("|")
            if (parts.size != 3) { left -= e; return@forEach }
            val (pkg, id, imp) = parts
            val ch = runCatching { l.getNotificationChannels(pkg, user) }.getOrNull()?.firstOrNull { it.id == id }
            if (ch == null) { left -= e; return@forEach }
            // שינית בעצמך בזמן הנסיעה? משאירים את מה שבחרת
            if (ch.importance == NotificationManager.IMPORTANCE_HIGH) {
                ch.importance = imp.toIntOrNull() ?: NotificationManager.IMPORTANCE_DEFAULT
                if (runCatching { l.updateNotificationChannel(pkg, user, ch) }.isFailure) return@forEach
            }
            left -= e
        }
        sp(l).edit().putStringSet("saved", left).apply()
    }

    /** כל בדיקה של מצב הרכב: מחוברים = מדליקים, לא מחוברים = מחזירים */
    fun sync(l: NotificationListenerService) {
        runCatching { if (CarMirror.connected(l)) boost(l) else restore(l) }
    }
}
