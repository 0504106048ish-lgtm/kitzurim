package com.kitzurim.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf

/**
 * מה שמסך הבית של הטלפון צריך מההתראות: כמה הודעות מחכות בכל אפליקציה (המספר האדום על הסמל),
 * וההודעה האחרונה שהגיעה (בשביל הכרטיס החכם).
 */
object HomeFeed {
    /** חבילה ← כמה הודעות מחכות */
    val badges = mutableStateMapOf<String, Int>()

    /** ההודעה האחרונה ומתי הגיעה */
    val lastMsg = mutableStateOf<Pair<LastMsg, Long>?>(null)

    fun message(m: LastMsg) {
        lastMsg.value = m to System.currentTimeMillis()
    }

    /** ההודעה נקראה (ההתראה ירדה): לא מראים אותה יותר בכרטיס */
    fun gone(key: String) {
        if (lastMsg.value?.first?.key == key) lastMsg.value = null
    }

    /** סופרים מחדש את כל ההתראות שבמסך ההתראות */
    fun recount(l: NotificationListenerService) {
        val all = runCatching { l.activeNotifications }.getOrNull() ?: return
        val counts = HashMap<String, Int>()
        all.forEach { sbn ->
            val n = sbn.notification
            if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return@forEach
            if (n.flags and Notification.FLAG_ONGOING_EVENT != 0) return@forEach
            if (sbn.packageName == l.packageName) return@forEach
            val add = if (n.number > 0) n.number else 1
            counts[sbn.packageName] = (counts[sbn.packageName] ?: 0) + add
        }
        badges.keys.filter { it !in counts }.forEach { badges.remove(it) }
        counts.forEach { (k, v) -> if (badges[k] != v) badges[k] = v }
    }
}
