package com.kitzurim.app

import android.app.NotificationManager
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.os.SystemClock

/**
 * הפוך = שקט: מניחים את הטלפון על המסך והוא משתתק. מרימים אותו, הצלצול חוזר,
 * והגלולה מספרת מה הגיע בינתיים. כבוי כברירת מחדל (מדליקים בהגדרות).
 */
object FaceDown {
    private var sm: SensorManager? = null
    private var listening = false
    private var ctx: Context? = null

    /** הטלפון הפוך עכשיו, והשתקנו אותו */
    var active = false
        private set
    private var savedMode = -1
    private var ourMode = -1

    private var downSince = 0L
    private var upSince = 0L

    private val senders = LinkedHashSet<String>()
    private var messages = 0
    private val calls = LinkedHashSet<String>()

    /** להתחיל או להפסיק להקשיב לחיישן לפי המתג (קוראים כשהשירות עולה וכשמשנים את המתג) */
    fun update(c: Context) {
        ctx = c.applicationContext
        val want = NotchPrefs.faceDown(c)
        val m = sm ?: c.getSystemService(SensorManager::class.java).also { sm = it } ?: return
        if (want && !listening) {
            val acc = m.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) ?: return
            listening = m.registerListener(listener, acc, SensorManager.SENSOR_DELAY_NORMAL)
        } else if (!want && listening) {
            m.unregisterListener(listener)
            listening = false
            if (active) faceUp(c, quiet = true)
        }
    }

    private val listener = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val c = ctx ?: return
            val z = e.values[2]
            val flat = kotlin.math.abs(e.values[0]) < 2.5f && kotlin.math.abs(e.values[1]) < 2.5f
            val now = SystemClock.elapsedRealtime()
            if (!active) {
                // על המסך ושקט (לא בתנועה) לפחות שנייה וחצי
                if (z < -8.5f && flat) {
                    if (downSince == 0L) downSince = now
                    if (now - downSince > 1500) faceDownNow(c)
                } else downSince = 0L
            } else {
                // הרימו: המסך כבר לא כלפי מטה (חצי שנייה, כדי שתזוזה קטנה לא תחזיר את הצלצול)
                if (z > -4f) {
                    if (upSince == 0L) upSince = now
                    if (now - upSince > 500) faceUp(c)
                } else upSince = 0L
            }
        }

        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun faceDownNow(c: Context) {
        val am = c.getSystemService(AudioManager::class.java) ?: return
        // כבר בשקט: אין מה לעשות (וגם לא נחזיר צלצול שלא היה)
        if (am.ringerMode == AudioManager.RINGER_MODE_SILENT) return
        val nm = c.getSystemService(NotificationManager::class.java)
        // שקט מלא צריך הרשאת "נא לא להפריע". בלי ההרשאה: רטט בלבד
        val target = if (nm?.isNotificationPolicyAccessGranted == true) AudioManager.RINGER_MODE_SILENT else AudioManager.RINGER_MODE_VIBRATE
        if (am.ringerMode == target) return
        savedMode = am.ringerMode
        runCatching { am.ringerMode = target }.onFailure { return }
        ourMode = target
        active = true
        upSince = 0L
        senders.clear(); messages = 0; calls.clear()
        ActivityLog.add(c, "🙃", "הטלפון הפוך: הושתק")
    }

    private fun faceUp(c: Context, quiet: Boolean = false) {
        active = false
        downSince = 0L
        val am = c.getSystemService(AudioManager::class.java)
        // מחזירים את הצלצול רק אם אף אחד לא שינה אותו בינתיים
        if (am != null && savedMode >= 0 && am.ringerMode == ourMode) runCatching { am.ringerMode = savedMode }
        savedMode = -1
        ourMode = -1
        if (quiet) return
        val parts = ArrayList<String>()
        if (calls.isNotEmpty()) parts.add(if (calls.size == 1) "שיחה מ${calls.first()}" else "${calls.size} שיחות")
        if (messages > 0) {
            val names = senders.toList()
            val from = when {
                names.isEmpty() -> ""
                names.size == 1 -> " מ${names[0]}"
                names.size == 2 -> " מ${names[0]} ו${names[1]}"
                else -> " מ${names[0]}, ${names[1]} ועוד"
            }
            parts.add((if (messages == 1) "הודעה" else "$messages הודעות") + from)
        }
        LivePill.showFaceUp(parts.joinToString(" · ").ifEmpty { null })
        senders.clear(); messages = 0; calls.clear()
    }

    fun noteMessage(sender: String) {
        if (!active) return
        messages++
        senders.add(sender.trim())
    }

    fun noteCall(key: String, name: String) {
        if (!active) return
        // אותה שיחה מתעדכנת כמה פעמים: נספרת פעם אחת
        if (calls.none { it == name }) calls.add(name.trim())
    }
}
