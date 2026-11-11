package com.kitzurim.app

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.role.RoleManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.provider.ContactsContract
import android.provider.Settings
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.telecom.TelecomManager
import android.telecom.VideoProfile
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * קיצורים כאפליקציית הטלפון: כשבוחרים בה בהגדרות, כל השיחות (נכנסות ויוצאות) עוברות כאן,
 * והמסך שלהן הוא מסך הניאון של קיצורים. אנשי קשר והיסטוריה נשארים באפליקציית הטלפון של סמסונג.
 */
@Suppress("DEPRECATION")
object Calls {
    var service: KitzInCallService? = null
    val list = mutableStateListOf<Call>()

    /** עולה בכל שינוי, כדי שהמסך יתעדכן */
    var version by mutableIntStateOf(0)
    var muted by mutableStateOf(false)
    var route by mutableIntStateOf(CallAudioState.ROUTE_EARPIECE)

    /** מתי כל שיחה התחילה באמת (אחרי שענו), בשעון רגיל */
    val answeredAt = HashMap<Call, Long>()

    fun changed() {
        version++
    }

    fun state(c: Call): Int = c.state

    /** השיחה שמוצגת במסך: קודם זו שמצלצלת, אחר כך הפעילה, אחר כך זו שמחייגים */
    fun main(): Call? =
        list.firstOrNull { state(it) == Call.STATE_RINGING }
            ?: list.firstOrNull { state(it) == Call.STATE_ACTIVE }
            ?: list.firstOrNull { state(it) == Call.STATE_DIALING || state(it) == Call.STATE_CONNECTING }
            ?: list.firstOrNull { state(it) == Call.STATE_HOLDING }
            ?: list.firstOrNull()

    fun number(c: Call): String? = c.details?.handle?.schemeSpecificPart

    /** השם של מי שבשיחה: מאנשי הקשר, ואם אין אז המספר */
    fun name(ctx: Context, c: Call): String {
        val d = c.details
        if (Build.VERSION.SDK_INT >= 30) d?.contactDisplayName?.takeIf { it.isNotBlank() }?.let { return it }
        d?.callerDisplayName?.takeIf { it.isNotBlank() }?.let { return it }
        val n = number(c)
        lookup(ctx, n)?.first?.let { return it }
        return n?.takeIf { it.isNotBlank() } ?: "מספר חסוי"
    }

    /** שם ותמונה מאנשי הקשר לפי המספר */
    fun lookup(ctx: Context, number: String?): Pair<String?, String?>? {
        if (number.isNullOrBlank()) return null
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return null
        return runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            ctx.contentResolver.query(
                uri,
                arrayOf(ContactsContract.PhoneLookup.DISPLAY_NAME, ContactsContract.PhoneLookup.PHOTO_URI),
                null, null, null,
            )?.use { cur -> if (cur.moveToFirst()) cur.getString(0) to cur.getString(1) else null }
        }.getOrNull()
    }

    fun photo(ctx: Context, c: Call): Bitmap? {
        val p = lookup(ctx, number(c))?.second ?: return null
        return runCatching { ctx.contentResolver.openInputStream(Uri.parse(p))?.use { BitmapFactory.decodeStream(it) } }.getOrNull()
    }

    fun answer(c: Call) = c.answer(VideoProfile.STATE_AUDIO_ONLY)
    fun reject(c: Call) = c.reject(false, null)
    fun hangup(c: Call) = c.disconnect()

    fun toggleMute() {
        service?.setMuted(!muted)
    }

    fun toggleSpeaker() {
        service?.setAudioRoute(
            if (route == CallAudioState.ROUTE_SPEAKER) CallAudioState.ROUTE_WIRED_OR_EARPIECE else CallAudioState.ROUTE_SPEAKER
        )
    }

    fun toggleHold(c: Call) {
        if (state(c) == Call.STATE_HOLDING) c.unhold() else c.hold()
    }

    fun dtmf(c: Call, ch: Char) {
        c.playDtmfTone(ch)
        c.stopDtmfTone()
    }

    /** שתי שיחות: מחליפים בין הפעילה לזו שבהמתנה */
    fun swap() {
        val held = list.firstOrNull { state(it) == Call.STATE_HOLDING } ?: return
        held.unhold()
    }

    // ---------- האם קיצורים היא אפליקציית הטלפון ----------

    fun isDefault(ctx: Context): Boolean =
        ctx.getSystemService(TelecomManager::class.java)?.defaultDialerPackage == ctx.packageName

    /** מבקש מהטלפון להפוך את קיצורים לאפליקציית הטלפון (הטלפון שואל אותך) */
    fun requestDefault(a: Activity) {
        val i = if (Build.VERSION.SDK_INT >= 29) {
            a.getSystemService(RoleManager::class.java)?.createRequestRoleIntent(RoleManager.ROLE_DIALER)
        } else {
            Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, a.packageName)
        }
        runCatching { a.startActivityForResult(i, 77) }
    }

    /** אנדרואיד לא נותן לאפליקציה לוותר לבד: פותחים את המסך של אפליקציות ברירת מחדל */
    fun openDefaultApps(ctx: Context) {
        runCatching {
            ctx.startActivity(Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.onFailure {
            runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}

/** השירות שהטלפון מפעיל לכל שיחה, כשקיצורים היא אפליקציית הטלפון */
@Suppress("DEPRECATION")
class KitzInCallService : InCallService() {
    private val cb = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = onState(call)
        override fun onDetailsChanged(call: Call, details: Call.Details) {
            Calls.changed()
            CallNotif.update(this@KitzInCallService)
        }
    }

    private fun onState(call: Call) {
        if (Calls.state(call) == Call.STATE_ACTIVE && call !in Calls.answeredAt) {
            Calls.answeredAt[call] = System.currentTimeMillis()
        }
        Calls.changed()
        CallNotif.update(this)
    }

    override fun onCallAdded(call: Call) {
        Calls.service = this
        Calls.list.add(call)
        call.registerCallback(cb)
        onState(call)
        // מסך השיחה בניאון
        runCatching {
            startActivity(
                Intent(this, CallActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            )
        }
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(cb)
        Calls.list.remove(call)
        Calls.answeredAt.remove(call)
        Calls.changed()
        if (Calls.list.isEmpty()) {
            CallNotif.cancel(this)
            LivePill.callEnded()
        } else {
            CallNotif.update(this)
        }
    }

    override fun onCallAudioStateChanged(audioState: CallAudioState) {
        Calls.muted = audioState.isMuted
        Calls.route = audioState.route
        Calls.changed()
    }

    override fun onDestroy() {
        if (Calls.service === this) Calls.service = null
        super.onDestroy()
    }
}

/** ההתראה של השיחה: שם, זמן, וכפתורים (השתק / רמקול / נתק). לחיצה מחזירה למסך השיחה */
@Suppress("DEPRECATION")
object CallNotif {
    private const val CH_RING = "calls_ring"
    private const val CH_ON = "calls_on"
    private const val ID = 4711

    private fun pi(ctx: Context, action: String, code: Int) = PendingIntent.getBroadcast(
        ctx, code, Intent(ctx, CallActionReceiver::class.java).setAction(action),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun update(ctx: Context) {
        if (!Notify.allowed(ctx)) return
        val c = Calls.main() ?: return cancel(ctx)
        val nm = ctx.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_RING, "שיחה נכנסת", NotificationManager.IMPORTANCE_HIGH).apply { setSound(null, null) })
        nm.createNotificationChannel(NotificationChannel(CH_ON, "שיחה פעילה", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(
            ctx, 1, Intent(ctx, CallActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val st = Calls.state(c)
        val ringing = st == Call.STATE_RINGING
        // סימן כיוון מימין לשמאל בהתחלה: מספר כמו *8787 לא בורח הצידה ולא נכנס מתחת לסמל
        val name = "‏" + Calls.name(ctx, c)
        val pic = avatar(ctx, Calls.name(ctx, c))
        fun base() = NotificationCompat.Builder(ctx, if (ringing) CH_RING else CH_ON)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle(name)
            .setCategory(NotificationCompat.CATEGORY_CALL)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .apply {
                if (ringing) setContentText("שיחה נכנסת")
                else {
                    val at = Calls.answeredAt[c]
                    if (at != null) {
                        setWhen(at).setShowWhen(true).setUsesChronometer(true)
                            .setContentText(if (st == Call.STATE_HOLDING) "בהמתנה" else "בשיחה")
                    } else {
                        setContentText("מחייג…")
                    }
                }
            }

        // התראת שיחה של אנדרואיד: עיגול עם האות, כפתור נתק אדום גדול, וזמן השיחה ליד השעה
        val styled = runCatching {
            val who = androidx.core.app.Person.Builder()
                .setName(name)
                .setIcon(androidx.core.graphics.drawable.IconCompat.createWithBitmap(pic))
                .setImportant(true)
                .build()
            val b = base()
                // אנדרואיד דורש מסך מלא בהתראת שיחה; בשיחה פעילה הערוץ שקט, אז הוא לא קופץ
                .setFullScreenIntent(open, ringing)
            if (ringing) {
                b.setStyle(NotificationCompat.CallStyle.forIncomingCall(who, pi(ctx, "decline", 2), pi(ctx, "answer", 3)))
            } else {
                b.setStyle(NotificationCompat.CallStyle.forOngoingCall(who, pi(ctx, "hangup", 6)))
                    .addAction(0, if (Calls.muted) "בטל השתקה" else "השתק", pi(ctx, "mute", 4))
                    .addAction(0, "רמקול", pi(ctx, "speaker", 5))
            }
            b.build()
        }.getOrNull()

        // אם הטלפון לא תומך: ההתראה הרגילה עם שלושה כפתורים
        fun plain() = base().setLargeIcon(pic).apply {
            if (ringing) {
                setFullScreenIntent(open, true)
                    .addAction(0, "דחה", pi(ctx, "decline", 2))
                    .addAction(0, "ענה", pi(ctx, "answer", 3))
            } else {
                addAction(0, if (Calls.muted) "בטל השתקה" else "השתק", pi(ctx, "mute", 4))
                    .addAction(0, "רמקול", pi(ctx, "speaker", 5))
                    .addAction(0, "נתק", pi(ctx, "hangup", 6))
            }
        }.build()
        runCatching { nm.notify(ID, styled ?: plain()) }.onFailure {
            // אם אנדרואיד דחה את התראת השיחה המיוחדת: שולחים את הרגילה
            if (styled != null) runCatching { nm.notify(ID, plain()) }
        }
    }


    /** עיגול ירוק עם האות הראשונה (או סמל טלפון למספר), במקום התמונה בהתראה */
    private fun avatar(ctx: Context, name: String): Bitmap {
        val px = (64 * ctx.resources.displayMetrics.density).toInt()
        val bmp = Bitmap.createBitmap(px, px, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(bmp)
        val p = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        p.color = android.graphics.Color.rgb(42, 212, 154)
        cv.drawCircle(px / 2f, px / 2f, px / 2f, p)
        val first = name.trim().firstOrNull { it.isLetter() }?.toString() ?: "✆"
        p.color = android.graphics.Color.rgb(4, 38, 26)
        p.textSize = px * 0.45f
        p.typeface = android.graphics.Typeface.DEFAULT_BOLD
        p.textAlign = android.graphics.Paint.Align.CENTER
        cv.drawText(first, px / 2f, px / 2f - (p.descent() + p.ascent()) / 2f, p)
        return bmp
    }
    fun cancel(ctx: Context) {
        ctx.getSystemService(NotificationManager::class.java)?.cancel(ID)
    }
}

/** הכפתורים שבהתראה של השיחה */
class CallActionReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, i: Intent) {
        val c = Calls.main() ?: return
        when (i.action) {
            "answer" -> Calls.answer(c)
            "decline" -> Calls.reject(c)
            "hangup" -> Calls.hangup(c)
            "mute" -> Calls.toggleMute()
            "speaker" -> Calls.toggleSpeaker()
        }
        CallNotif.update(ctx)
    }
}
