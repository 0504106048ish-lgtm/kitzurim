package com.kitzurim.app

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import androidx.core.content.IntentCompat

/** הטלפון התחבר לרכב או התנתק ממנו: מדליק ומכבה את מצב הנהיגה */
class CarReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
            ?: return
        if (device.address != AutoPrefs.carAddress(ctx)) return
        val enabled = AutoPrefs.drivingEnabled(ctx)
        when (intent.action) {
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                AutoPrefs.setDrivingActive(ctx, true)
                if (enabled) Actions.toast(ctx, "🚗 מצב נהיגה: מענה אוטומטי פעיל")
            }
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                AutoPrefs.setDrivingActive(ctx, false)
                if (enabled) Actions.toast(ctx, "מצב נהיגה כבה")
            }
        }
    }
}

/** קורא את ההתראות של וואטסאפ, ובזמן נהיגה עונה פעם אחת לכל צ'אט פרטי */
class ReplyListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (!AutoPrefs.drivingEnabled(this) || !AutoPrefs.drivingActive(this)) return
        if (sbn.packageName != "com.whatsapp" && sbn.packageName != "com.whatsapp.w4b") return
        val n = sbn.notification
        // התראת "סיכום" של כמה צ'אטים: לא עונים עליה
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // קבוצות: לא עונים
        val style = NotificationCompat.MessagingStyle.extractMessagingStyleFromNotification(n)
        if (style?.isGroupConversation == true) return
        if (n.extras.getBoolean(Notification.EXTRA_IS_GROUP_CONVERSATION, false)) return

        val chat = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        if (AutoPrefs.alreadyReplied(this, chat)) return

        // הכפתור "השב" שבהתראה: ממלאים בו את הטקסט ושולחים
        val action = n.actions?.firstOrNull { !it.remoteInputs.isNullOrEmpty() } ?: return
        val inputs = action.remoteInputs
        val results = Bundle()
        inputs.forEach { results.putCharSequence(it.resultKey, AutoPrefs.replyText(this)) }
        val fill = Intent()
        RemoteInput.addResultsToIntent(inputs, fill, results)
        try {
            action.actionIntent.send(this, 0, fill)
            AutoPrefs.markReplied(this, chat)
        } catch (_: PendingIntent.CanceledException) {
        }
    }
}

object Driving {
    /** האם אישרת לקיצורים לקרוא התראות */
    fun listenerAllowed(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, ReplyListener::class.java).flattenToString()
        return flat.split(":").any { it == me }
    }

    /** המכשירים שמחוברים לטלפון בבלוטות' (שם, כתובת) */
    @SuppressLint("MissingPermission")
    fun pairedDevices(ctx: Context): List<Pair<String, String>> = try {
        ctx.getSystemService(BluetoothManager::class.java)?.adapter?.bondedDevices
            ?.map { (it.name ?: it.address) to it.address }
            ?.sortedBy { it.first }
            ?: emptyList()
    } catch (_: SecurityException) {
        emptyList()
    }
}
