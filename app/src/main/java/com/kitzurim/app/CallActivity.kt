package com.kitzurim.app

import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.telecom.Call
import android.telecom.CallAudioState
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.animation.togetherWith
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** מסך השיחה בניאון: שיחה נכנסת, מחייג, ובזמן שיחה */
@Suppress("DEPRECATION")
class CallActivity : ComponentActivity() {
    private var proximity: PowerManager.WakeLock? = null
    private var preview = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // מופיע גם על מסך הנעילה, ומדליק את המסך כשמצלצלים
        if (Build.VERSION.SDK_INT >= 27) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
        } else {
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
            )
        }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        // הרקע של החלון באותו צבע כהה של מסך השיחה, כדי שלא יהבהב שחור לפני שהמסך מצויר
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(11, 13, 19)))
        neonBars()
        // תצוגה מקדימה מההגדרות: שיחה לדוגמא, ואז "להפעיל" או "לא עכשיו"
        if (intent?.getBooleanExtra(PREVIEW, false) == true) {
            preview = true
            setContent {
                KitzurimTheme {
                    PreviewScreen(
                        onEnable = { Calls.requestDefault(this) },
                        onClose = { finish() },
                    )
                }
            }
            return
        }
        setContent {
            KitzurimTheme {
                val v = Calls.version
                val c = Calls.main()
                LaunchedEffect(v) {
                    updateProximity()
                    if (c == null) {
                        delay(900)
                        if (Calls.main() == null) finishAndRemoveTask()
                    }
                }
                CallScreenUi(c, v)
            }
        }
        // "חזור" בזמן שיחה: לא סוגרים את השיחה, רק יוצאים, וכל המסך מתכווץ לגלולה ליד המצלמה
        onBackPressedDispatcher.addCallback(this, object : androidx.activity.OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (Calls.main() != null) {
                    // רק אנימציה אחת: המסך מתכווץ לחור. המסך עצמו נעלם מיד, כדי שהטלפון לא יעיף אותו גם לאייקון
                    if (Calls.state(Calls.main()!!) != Call.STATE_RINGING) { vanish(); LivePill.shrinkFromScreen() }
                    moveTaskToBack(true)
                    noSystemAnim()
                } else {
                    finish()
                }
            }
        })
    }

    /** לחצת על הבית (העיגול למטה) בזמן שיחה: המסך מתכווץ לגלולה ליד המצלמה */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        val c = Calls.main()
        if (!preview && c != null && Calls.state(c) != Call.STATE_RINGING) { vanish(); LivePill.shrinkFromScreen() }
    }

    /** חזרנו מהשאלה של הטלפון "להפוך לאפליקציית הטלפון?": סוגרים את התצוגה המקדימה */
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (preview) {
            if (Calls.isDefault(this)) {
                Actions.toast(this, "📞 מעכשיו השיחות במסך הניאון של קיצורים")
                // הקיצור "טלפון" והווידג'ט של המועדפים: הטלפון שואל אם להוסיף אותם למסך הבית
                if (NotchPrefs.dialerHome(this)) DialerHome.install(applicationContext)
            }
            finish()
        }
    }

    /** המסך נעלם מיד (שקוף), כך שהאנימציה של הטלפון ("עף לאייקון") לא נראית, רק הגלולה שמתכווצת לחור */
    private fun vanish() {
        window.attributes = window.attributes.apply { alpha = 0f }
    }

    private fun noSystemAnim() {
        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 34) overrideActivityTransition(OVERRIDE_TRANSITION_CLOSE, 0, 0) else overridePendingTransition(0, 0)
    }

    override fun onResume() {
        super.onResume()
        // חוזרים למסך: שוב גלוי
        if (window.attributes.alpha != 1f) window.attributes = window.attributes.apply { alpha = 1f }
        if (preview) return
        visible = true
        LivePill.callEnded() // בזמן שהמסך הזה פתוח לא צריך את הגלולה
        updateProximity()
        // המסך הגדול פתוח: ההתראה של השיחה נכנסת בשקט (בלי שורה קופצת למעלה)
        CallNotif.update(this)
        // הגלולה שהתרחבה לכל המסך נמוגה רק אחרי שהמסך הזה באמת צויר (בלי רגע שחור באמצע)
        if (Build.VERSION.SDK_INT >= 29) window.decorView.viewTreeObserver.registerFrameCommitCallback { window.decorView.postDelayed({ LivePill.callScreenDrawn() }, 40) }
        else window.decorView.postDelayed({ LivePill.callScreenDrawn() }, 120)
    }

    override fun onPause() {
        super.onPause()
        visible = false
        // יצאו מהמסך בזמן צלצול: ההתראה הקופצת חוזרת
        if (!preview) CallNotif.update(this)
    }

    override fun onDestroy() {
        runCatching { proximity?.let { if (it.isHeld) it.release() } }
        proximity = null
        super.onDestroy()
    }

    /** כשהטלפון ליד האוזן: המסך נכבה, כדי שהלחי לא תלחץ על כפתורים */
    private fun updateProximity() {
        // החיישן מנוהל לכל השיחה (גם כשמסך השיחה סגור), ראו Proximity
        Proximity.update(this)
    }

    companion object {
        const val PREVIEW = "preview"

        /** מסך השיחה פתוח עכשיו (אז הגלולה ליד המצלמה לא מופיעה) */
        @Volatile
        var visible = false
    }
}

private val Green = Color(0xFF2AD49A)
private val Red = Color(0xFFFF4D5E)
private val Soft = Color(0xFFA8B0C0)

/** סמלים בקו דק (במקום אימוג'י), מצוירים מנתיב כמו ב-SVG */
internal fun lineIcon(d: String, fill: Boolean = false, stroke: Float = 1.7f) =
    androidx.compose.ui.graphics.vector.ImageVector.Builder(
        defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f,
    ).addPath(
        pathData = androidx.compose.ui.graphics.vector.PathParser().parsePathString(d).toNodes(),
        fill = if (fill) androidx.compose.ui.graphics.SolidColor(Color.White) else null,
        stroke = if (fill) null else androidx.compose.ui.graphics.SolidColor(Color.White),
        strokeLineWidth = stroke,
        strokeLineCap = androidx.compose.ui.graphics.StrokeCap.Round,
        strokeLineJoin = androidx.compose.ui.graphics.StrokeJoin.Round,
    ).build()

internal object CallIcons {
    val mute = lineIcon("M9 5.5a3 3 0 0 1 6 0v6a3 3 0 0 1 -6 0z M5 11a7 7 0 0 0 14 0 M12 18v3.5 M8.5 21.5h7")
    val keys = lineIcon("M6 5h0 M12 5h0 M18 5h0 M6 11h0 M12 11h0 M18 11h0 M6 17h0 M12 17h0 M18 17h0 M12 22h0", stroke = 2.8f)
    val speaker = lineIcon("M3.5 9.5v5h3.5l5 4.5v-14l-5 4.5z M15.5 9a4 4 0 0 1 0 6 M18.5 6a8 8 0 0 1 0 12")
    val hold = lineIcon("M8.5 5.5v13 M15.5 5.5v13", stroke = 2.6f)
    val swap = lineIcon("M5 8h13 M15 5l3 3 -3 3 M19 16h-13 M9 13l-3 3 3 3")
    val record = lineIcon("M12 4.5a7.5 7.5 0 1 0 0.01 0 M12 9a3 3 0 1 0 0.01 0", stroke = 2.2f)
    val bluetooth = lineIcon("M7 7.5l10 9 -5 4.5v-18l5 4.5 -10 9")
    val addCall = lineIcon("M9.5 4.5a3.5 3.5 0 1 1 0 7a3.5 3.5 0 1 1 0 -7z M3.5 20a6 6 0 0 1 12 0 M19 8v6 M16 11h6")
    val merge = lineIcon("M6 4v5a6 6 0 0 0 6 6 M18 4v5a6 6 0 0 1 -6 6v5 M9 18l3 3 3 -3")
    val phone = lineIcon(
        "M5 3.5h3.6a1 1 0 0 1 0.9 0.6l1.8 4.3a1 1 0 0 1 -0.3 1.2L9 11a12 12 0 0 0 4 4l1.4 -2a1 1 0 0 1 1.2 -0.3l4.3 1.8a1 1 0 0 1 0.6 0.9V19a2 2 0 0 1 -2 2A16 16 0 0 1 3 5.5a2 2 0 0 1 2 -2z",
        fill = true,
    )
}

@Composable
private fun CallIcon(v: androidx.compose.ui.graphics.vector.ImageVector, color: Color, size: androidx.compose.ui.unit.Dp, rotate: Float = 0f) {
    Image(
        androidx.compose.ui.graphics.vector.rememberVectorPainter(v),
        null,
        Modifier
            .size(size)
            .graphicsLayer { rotationZ = rotate },
        colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(color),
    )
}

/** מה שמוצג במסך השיחה (משיחה אמיתית או מהתצוגה המקדימה) */
private class CallView(
    val name: String,
    val number: String?,
    val photo: android.graphics.Bitmap?,
    val ringing: Boolean,
    val topText: String,
    val status: String,
    val statusGreen: Boolean,
    val muted: Boolean,
    val speaker: Boolean,
    val holding: Boolean,
    val canSwap: Boolean,
    // הקלטת שיחה: הכפתור מופיע רק אם הטלפון מאפשר (ומדליקים בהגדרות)
    val canRecord: Boolean = false,
    val recording: Boolean = false,
    // בלוטוס: הכפתור מופיע רק כשיש אוזניות/רכב מחוברים, ודלוק כשהקול עובר אליהם
    val hasBluetooth: Boolean = false,
    val bluetoothOn: Boolean = false,
    // הוספת שיחה, ומיזוג כשיש שתי שיחות
    val canAdd: Boolean = false,
    val canMerge: Boolean = false,
    // השם הגיע מ-Truecaller (לא שמור אצלך)
    val fromTruecaller: Boolean = false,
    // ספאם לפי Truecaller
    val spam: Boolean = false,
    // מאיזו אפליקציה הגיע הזיהוי (Truecaller או Me)
    val idSource: String = "Truecaller",
    // ההודעה האחרונה שלו בוואטסאפ
    val lastMsg: String? = null,
    // שיחה נכנסת: הצלצול הושתק
    val silenced: Boolean = false,
)

/** הודעות מוכנות כשדוחים שיחה */
private val QuickReplies = listOf(
    "אני לא יכול לדבר עכשיו, אחזור אליך",
    "אני בנהיגה, אחזור אליך בהקדם",
    "אני באמצע משהו, כתוב לי",
    "אחזור אליך בעוד כמה דקות",
)

/** כפתור קטן בפס הזכוכית של שיחה נכנסת */
@Composable
private fun QuickAction(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable { onClick() }
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        Box(
            Modifier
                .size(42.dp)
                .clip(CircleShape)
                .background(if (on) Color.White.copy(alpha = 0.9f) else Color.White.copy(alpha = 0.12f))
                .border(1.dp, Color.White.copy(alpha = 0.2f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { Text(icon, fontSize = 17.sp) }
        Text(label, color = Color.White.copy(alpha = 0.88f), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}

/** מה קורה כשלוחצים על הכפתורים */
private class CallAct(
    val answer: () -> Unit,
    val reject: () -> Unit,
    val hangup: () -> Unit,
    val mute: () -> Unit,
    val speaker: () -> Unit,
    val hold: () -> Unit,
    val swap: () -> Unit,
    val dtmf: (Char) -> Unit,
    val record: () -> Unit = {},
    val bluetooth: () -> Unit = {},
    val addCall: () -> Unit = {},
    val merge: () -> Unit = {},
    val rejectWithMessage: (String) -> Unit = {},
    val remind: () -> Unit = {},
    val silence: () -> Unit = {},
)

@Composable
private fun GlassBg(acc: Pair<Color, Color>, content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D13))
            .drawBehind {
                // הילה בצבע הראשון למעלה בצד אחד, והשני למטה בצד השני
                drawRect(
                    Brush.radialGradient(
                        listOf(acc.first.copy(alpha = 0.62f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(size.width * 0.85f, 0f),
                        radius = size.width * 1.15f,
                    ),
                )
                drawRect(
                    Brush.radialGradient(
                        listOf(acc.second.copy(alpha = 0.5f), Color.Transparent),
                        center = androidx.compose.ui.geometry.Offset(0f, size.height),
                        radius = size.width * 1.1f,
                    ),
                )
            },
        content = content,
    )
}

/** שני צבעים לאיש קשר: מהתמונה שלו, ואם אין תמונה אז זוג קבוע לפי השם (תמיד אותו צבע לאותו אדם) */
private fun contactColors(name: String, photo: android.graphics.Bitmap?): Pair<Color, Color> {
    artColors(photo)?.let { return Color(it.first) to Color(it.second) }
    val pairs = listOf(
        0xFF3A7BD5 to 0xFF6A4CFF,
        0xFFFF6A88 to 0xFFFF9A62,
        0xFF2AD49A to 0xFF1A7FA0,
        0xFFFFB340 to 0xFFFF6A3D,
        0xFFB388FF to 0xFF6A4CFF,
        0xFF4FB3FF to 0xFF2AD49A,
        0xFFE5484D to 0xFF8E44AD,
    )
    val p = pairs[(name.hashCode() and 0x7fffffff) % pairs.size]
    return Color(p.first) to Color(p.second)
}

@Composable
private fun NeonBg(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF0F2A24), Color(0xFF06090C)),
                    radius = 1400f,
                    center = androidx.compose.ui.geometry.Offset(540f, 500f),
                )
            ),
        content = content,
    )
}

/** שיחה אמיתית */
@Suppress("DEPRECATION")
@Composable
private fun CallScreenUi(c: Call?, version: Int) {
    val ctx = LocalContext.current
    if (c == null) {
        NeonBg { Text("השיחה הסתיימה", Modifier.align(Alignment.Center), color = Soft, fontSize = 18.sp) }
        return
    }
    val st = Calls.state(c)
    // מספר (כמו *8787) נכתב משמאל לימין, כדי שהכוכבית לא תקפוץ לצד השני
    val name = remember(c, version) { Calls.name(ctx, c).let { n -> if (n.any { it in 'א'..'ת' }) n else "\u2066" + n + "\u2069" } }
    val number = remember(c) { Calls.number(c) }
    // התמונה נטענת ברקע, כדי שהמסך ייפתח מיד (בלי רגע שחור עד שהתמונה מוכנה)
    val photo by androidx.compose.runtime.produceState<android.graphics.Bitmap?>(null, c) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { Calls.photo(ctx, c) }
    }
    val ringing = st == Call.STATE_RINGING
    // הקלטת שיחה: רק אם הודלקה בהגדרות (ושם כבר נבדק שהטלפון מאפשר)
    val recOk = remember(c) { CallRecorder.enabled(ctx) }
    // שעון: מתעדכן כל חצי שנייה
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val at = Calls.answeredAt[c]
    val status = when {
        st == Call.STATE_DIALING || st == Call.STATE_CONNECTING -> "מחייג…"
        st == Call.STATE_HOLDING -> "בהמתנה"
        st == Call.STATE_DISCONNECTING || st == Call.STATE_DISCONNECTED -> "השיחה הסתיימה"
        at != null -> clock((now - at) / 1000)
        else -> "מתחבר…"
    }
    val view = CallView(
        name = name,
        fromTruecaller = remember(c, version) { Calls.lookup(ctx, number) == null && CallerId.nameFor(ctx, number) != null },
        idSource = remember(c, version) { CallerId.sourceFor(ctx, number) },
        spam = remember(c, version) { Calls.lookup(ctx, number) == null && CallerId.isSpam(ctx, number) },
        lastMsg = remember(c, name) { LastChat.forCall(ctx, number, Calls.name(ctx, c)) },
        number = number,
        photo = photo,
        ringing = ringing,
        topText = if (Calls.list.size > 1) "שיחה ממתינה" else "שיחה נכנסת",
        status = status,
        statusGreen = at != null && st == Call.STATE_ACTIVE,
        muted = Calls.muted,
        speaker = Calls.route == CallAudioState.ROUTE_SPEAKER,
        holding = st == Call.STATE_HOLDING,
        canSwap = st == Call.STATE_ACTIVE && Calls.list.any { Calls.state(it) == Call.STATE_HOLDING },
        // הקלטה: זמינה מרגע שענו (אם עוד לא אישרת מיקרופון, הלחיצה הראשונה מבקשת)
        canRecord = st == Call.STATE_ACTIVE || st == Call.STATE_HOLDING,
        recording = CallRecorder.recording,
        hasBluetooth = Calls.hasBluetooth(),
        bluetoothOn = Calls.route == CallAudioState.ROUTE_BLUETOOTH,
        canAdd = st == Call.STATE_ACTIVE && Calls.list.size < 2,
        canMerge = Calls.canMerge(),
        silenced = Calls.silenced,
    )
    val act = remember(c) {
        CallAct(
            answer = { Calls.answer(c) },
            reject = { Calls.reject(c) },
            hangup = { Calls.hangup(c) },
            mute = { Calls.toggleMute() },
            speaker = { Calls.toggleSpeaker() },
            hold = { Calls.toggleHold(c) },
            swap = { Calls.swap() },
            dtmf = { ch -> Calls.dtmf(c, ch) },
            record = {
                if (!CallRecorder.permitted(ctx)) {
                    // עוד אין אישור למיקרופון: מבקשים, ואחרי האישור לוחצים שוב "הקלט"
                    (ctx as? android.app.Activity)?.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 21)
                    Actions.toast(ctx, "אשר מיקרופון ולחץ שוב \"הקלט\"")
                } else {
                    CallRecorder.setEnabled(ctx, true)
                    CallRecorder.toggle(ctx, name)
                }
            },
            bluetooth = { Calls.toggleBluetooth() },
            // הוספת שיחה: נפתח החייגן עם המקלדת; כשמחייגים, השיחה הנוכחית עוברת להמתנה
            addCall = {
                runCatching {
                    ctx.startActivity(
                        android.content.Intent(ctx, DialActivity::class.java)
                            .putExtra(DialActivity.EXTRA_KEYPAD, true)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            merge = { Calls.merge() },
            // דוחים ושולחים הודעה מוכנה (אנדרואיד שולח אותה כ-SMS)
            rejectWithMessage = { m -> runCatching { c.reject(true, m) } },
            // דוחים, ובעוד שעה תזכורת להתקשר בחזרה (הקשה עליה מחייגת)
            remind = {
                CallBackReminder.schedule(ctx, Calls.name(ctx, c), number ?: "", 60)
                Actions.toast(ctx, "⏰ אזכיר לך להתקשר בעוד שעה")
                Calls.reject(c)
            },
            silence = { Calls.silence(ctx) },
        )
    }
    CallFace(view, act)
}

/** תצוגה מקדימה: שיחה לדוגמא, כדי לראות איך זה ייראה לפני שמפעילים */
@Composable
private fun PreviewScreen(onEnable: () -> Unit, onClose: () -> Unit) {
    var active by remember { mutableStateOf(false) }
    var muted by remember { mutableStateOf(false) }
    var speaker by remember { mutableStateOf(false) }
    var holding by remember { mutableStateOf(false) }
    var start by remember { mutableLongStateOf(0L) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(500)
        }
    }
    val view = CallView(
        name = "אמא",
        number = "050-1234567",
        photo = null,
        ringing = !active,
        topText = "שיחה נכנסת",
        status = if (holding) "בהמתנה" else clock((now - start) / 1000),
        statusGreen = !holding,
        muted = muted,
        speaker = speaker,
        holding = holding,
        canSwap = false,
    )
    val act = CallAct(
        answer = {
            active = true
            start = System.currentTimeMillis()
        },
        reject = { active = false },
        hangup = {
            active = false
            muted = false
            speaker = false
            holding = false
        },
        mute = { muted = !muted },
        speaker = { speaker = !speaker },
        hold = { holding = !holding },
        swap = {},
        dtmf = {},
    )
    Box(Modifier.fillMaxSize()) {
        CallFace(view, act, top = 180.dp)
        // למעלה: הסבר וכפתורים
        Column(
            Modifier
                .align(Alignment.TopCenter)
                .padding(top = 34.dp, start = 16.dp, end = 16.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1A2030))
                .border(1.dp, Color(0xFF2A3346), RoundedCornerShape(18.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("👀 תצוגה מקדימה", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(
                if (active) "ככה נראית שיחה. לחץ על הכפתור האדום כדי לחזור לשיחה נכנסת" else "ככה תיראה שיחה נכנסת. נסה להחליק ימינה",
                color = Soft,
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Green)
                        .clickable { onEnable() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("להפעיל", color = Color.Black, fontSize = 14.sp, fontWeight = FontWeight.Bold) }
                Box(
                    Modifier
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.08f))
                        .clickable { onClose() }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                ) { Text("לא עכשיו", color = Color.White, fontSize = 14.sp) }
            }
        }
    }
}

/** המסך עצמה: אותו עיצוב לשיחה אמיתית ולתצוגה המקדימה */
@Composable
private fun CallFace(v: CallView, act: CallAct, top: androidx.compose.ui.unit.Dp = 70.dp) {
    var keypad by remember { mutableStateOf(false) }
    // זכוכית בצבעי איש הקשר: רקע כהה עם הילה בשני צבעים (מהתמונה שלו, או צבעים קבועים לפי השם)
    val acc = remember(v.name, v.photo) { contactColors(v.name, v.photo) }
    // כניסה רכה: הכול מופיע יחד ומיד, עולה קצת למעלה ומתבהר (בלי רקע שחור שמחכה לכפתורים)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(Unit) { appear.animateTo(1f, tween(420, easing = FastOutSlowInEasing)) }
    // הסדר של הכפתורים: כל אחד מסדר לעצמו בהגדרות ("סידור הכפתורים במסך השיחה")
    val ctx = LocalContext.current
    val order = remember { CallButtons.load(ctx) }

    /** כפתור אחד לפי השם שלו בסידור */
    @Composable
    fun Btn(id: String, small: Boolean, big: Boolean = false) {
        when (id) {
            "hold" -> RoundBtn(CallIcons.hold, if (v.holding) "המשך" else "המתנה", v.holding, small = small) { act.hold() }
            // הוסף שיחה: כשיש שתי שיחות הוא הופך ל"החלף", ולידו "מיזוג". בזמן חיוג חלש, ונדלק כשעונים
            "add" -> {
                if (v.canSwap) RoundBtn(CallIcons.swap, "החלף", false, small = small) { act.swap() }
                else RoundBtn(CallIcons.addCall, "הוסף שיחה", false, small = small, enabled = v.canAdd) { act.addCall() }
                if (v.canMerge) RoundBtn(CallIcons.merge, "מיזוג", false, small = small) { act.merge() }
            }
            // הקלטה: לבן ודלוק בזמן ההקלטה
            "record" -> RoundBtn(CallIcons.record, if (v.recording) "עצור הקלטה" else "הקלט", v.recording, small = small, enabled = v.canRecord) { act.record() }
            "mute" -> RoundBtn(CallIcons.mute, if (v.muted) "בטל השתקה" else "השתק", v.muted, small = small) { act.mute() }
            "keys" -> RoundBtn(CallIcons.keys, if (keypad) "הסתר" else "מקלדת", keypad, small = small) { keypad = !keypad }
            "speaker" -> RoundBtn(CallIcons.speaker, "רמקול", v.speaker, small = small) { act.speaker() }
            // בלוטוס: רק כשיש אוזניות או רכב מחוברים
            "bt" -> if (v.hasBluetooth) RoundBtn(CallIcons.bluetooth, "בלוטוס", v.bluetoothOn, small = small) { act.bluetooth() }
            // ניתוק: גדול כשהוא בשורה משלו למטה, ובגודל רגיל כשהעבירו אותו לשורה של הכפתורים
            "hang" -> HangBtn(big = big, small = small) { act.hangup() }
        }
    }

    /** אותו כפתור בתוך הקפסולה (כשהמקלדת פתוחה): רק סמל */
    @Composable
    fun Cap(id: String) {
        when (id) {
            "hold" -> CapBtn(CallIcons.hold, if (v.holding) "המשך" else "המתנה", v.holding) { act.hold() }
            "add" -> {
                if (v.canSwap) CapBtn(CallIcons.swap, "החלף", false) { act.swap() }
                else CapBtn(CallIcons.addCall, "הוסף שיחה", false, enabled = v.canAdd) { act.addCall() }
                if (v.canMerge) CapBtn(CallIcons.merge, "מיזוג", false) { act.merge() }
            }
            "record" -> CapBtn(CallIcons.record, "הקלט", v.recording, enabled = v.canRecord) { act.record() }
            "mute" -> CapBtn(CallIcons.mute, "השתק", v.muted) { act.mute() }
            "keys" -> CapBtn(CallIcons.keys, "הסתר מקלדת", false, lit = true) { keypad = false }
            "speaker" -> CapBtn(CallIcons.speaker, "רמקול", v.speaker) { act.speaker() }
            "bt" -> if (v.hasBluetooth) CapBtn(CallIcons.bluetooth, "בלוטוס", v.bluetoothOn) { act.bluetooth() }
        }
    }
    GlassBg(acc) {
        Column(
            Modifier
                .fillMaxSize()
                // מקלדת פתוחה: הקשה על מקום ריק במסך סוגרת אותה (המקשים והכפתורים לא)
                .clickable(
                    enabled = keypad && !v.ringing,
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { keypad = false }
                .graphicsLayer {
                    alpha = appear.value
                    translationY = (1f - appear.value) * 28.dp.toPx()
                    val s = 0.97f + 0.03f * appear.value
                    scaleX = s
                    scaleY = s
                }
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // מקום למעלה (בתצוגה המקדימה יותר, כי שם יש מסגרת הסבר)
            Spacer(Modifier.height(if (v.ringing) top + 20.dp else top))
            if (v.ringing) Text(v.topText, color = Color.White.copy(alpha = 0.8f), fontSize = 15.sp)
            Spacer(Modifier.height(if (v.ringing) 30.dp else 0.dp))
            // המקלדת פתוחה: התמונה מתכווצת ונעלמת בעדינות, והשם קטן, כדי לפנות מקום למקלדת
            androidx.compose.animation.AnimatedVisibility(
                visible = !keypad || v.ringing,
                enter = androidx.compose.animation.fadeIn(tween(260)) + androidx.compose.animation.expandVertically(tween(320)),
                exit = androidx.compose.animation.fadeOut(tween(180)) + androidx.compose.animation.shrinkVertically(tween(300)),
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Avatar(v.name, v.photo, if (v.ringing) 118 else 104, breathing = v.ringing, acc = acc)
                    Spacer(Modifier.height(if (v.ringing) 30.dp else 22.dp))
                }
            }
            val nameSize by androidx.compose.animation.core.animateFloatAsState(if (keypad && !v.ringing) 24f else 32f, tween(300), label = "name")
            Text(v.name, color = Color.White, fontSize = nameSize.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center, maxLines = 2)
            if (v.number != null && v.number != v.name) {
                Text(v.number, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
                if (v.spam) Text("⚠️ ספאם לפי ${v.idSource}", color = Color(0xFFFF6B6B), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 2.dp))
                else if (v.fromTruecaller) Text("זוהה ע״י ${v.idSource}", color = Color(0xFF64B5F6), fontSize = 12.sp, modifier = Modifier.padding(top = 2.dp))
            }
            // "על מה דיברנו": ההודעה האחרונה שלו בוואטסאפ (לא כשהמקלדת פתוחה)
            if (v.lastMsg != null && (v.ringing || !keypad)) {
                Text(
                    v.lastMsg,
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 2,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier
                        .padding(top = 10.dp, start = 28.dp, end = 28.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.10f))
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
            if (!v.ringing) {
                Text(
                    v.status,
                    color = Color.White.copy(alpha = 0.78f),
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            if (v.ringing) {
                // פס זכוכית למטה: שלוש פעולות מהירות, החלקה לענות, ודחה
                var msgs by remember { mutableStateOf(false) }
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.13f), Color.White.copy(alpha = 0.06f))))
                        .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(28.dp))
                        .padding(14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    // שלח הודעה: נפתחת רשימה של הודעות מוכנות. בחירה דוחה את השיחה ושולחת את ההודעה
                    androidx.compose.animation.AnimatedVisibility(visible = msgs) {
                        Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            QuickReplies.forEach { m ->
                                Text(
                                    m,
                                    color = Color.White,
                                    fontSize = 14.sp,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color.White.copy(alpha = 0.08f))
                                        .clickable { act.rejectWithMessage(m) }
                                        .padding(horizontal = 14.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.SpaceAround) {
                        QuickAction("💬", "שלח הודעה", msgs) { msgs = !msgs }
                        QuickAction("⏰", "תזכיר לי", false) { act.remind() }
                        QuickAction("🔕", "השתק", v.silenced) { act.silence() }
                    }
                    // ענה ודחה באותה החלקה: ימינה לענות, שמאלה לדחות (אין כפתור שאפשר ללחוץ בטעות)
                    SlideAnswerDecline(onAnswer = { act.answer() }, onDecline = { act.reject() })
                }
                Spacer(Modifier.height(30.dp))
            } else {
                // המקלדת נפתחת מעל הכפתורים (נפרשת למעלה ומתבהרת, והמקשים מופיעים אחד אחרי השני),
                // כך שהשתקה ורמקול תמיד נשארים גלויים מתחתיה
                androidx.compose.animation.AnimatedVisibility(
                    visible = keypad,
                    enter = androidx.compose.animation.fadeIn(tween(240)) +
                        androidx.compose.animation.expandVertically(tween(340, easing = FastOutSlowInEasing), expandFrom = Alignment.Bottom) +
                        androidx.compose.animation.scaleIn(tween(340, easing = FastOutSlowInEasing), initialScale = 0.92f),
                    exit = androidx.compose.animation.fadeOut(tween(160)) +
                        androidx.compose.animation.shrinkVertically(tween(280, easing = FastOutSlowInEasing), shrinkTowards = Alignment.Bottom),
                ) {
                    // הקשה ברווח בין המקשים לא סוגרת את המקלדת
                    Column(
                        Modifier.clickable(interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }, indication = null) {},
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Keypad { ch -> act.dtmf(ch) }
                        Spacer(Modifier.height(14.dp))
                    }
                }
                // למעלה: המתנה, הוסף שיחה, הקלט (נעלמים כשהמקלדת פתוחה)
                androidx.compose.animation.AnimatedVisibility(
                    visible = !keypad,
                    enter = androidx.compose.animation.fadeIn(tween(240)) + androidx.compose.animation.expandVertically(tween(300)),
                    exit = androidx.compose.animation.fadeOut(tween(140)) + androidx.compose.animation.shrinkVertically(tween(260)),
                ) {
                    Column {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            order[0].forEach { Btn(it, false) }
                        }
                        Spacer(Modifier.height(18.dp))
                    }
                }
                androidx.compose.animation.AnimatedContent(
                    targetState = keypad,
                    transitionSpec = {
                        (androidx.compose.animation.fadeIn(tween(260, 80)) + androidx.compose.animation.scaleIn(tween(300, easing = FastOutSlowInEasing), initialScale = 0.9f)) togetherWith
                            androidx.compose.animation.fadeOut(tween(140))
                    },
                    contentAlignment = Alignment.BottomCenter,
                    label = "btns",
                ) { open ->
                    if (!open) Column(Modifier.fillMaxWidth()) {
                        // באמצע, קרוב לאגודל
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            order[1].forEach { Btn(it, false) }
                        }
                        Spacer(Modifier.height(26.dp))
                        // השורה של הניתוק (או מה שהועבר לשם)
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                            order[2].forEach { Btn(it, false, big = true) }
                        }
                    } else Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // מקלדת פתוחה: כל הכפתורים שנשארים בקפסולת זכוכית צרה אחת, והניתוק מתחת כגלולה אדומה
                        // (לפי הסידור שבחרת; הכפתורים מהשורה העליונה שנעלמת לא נכנסים, חוץ ממקלדת)
                        val ids = (order[1] + order[2] + order[0].filter { it == "keys" || it == "hang" })
                        Row(
                            Modifier
                                .shadow(12.dp, RoundedCornerShape(30.dp))
                                .clip(RoundedCornerShape(30.dp))
                                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.18f), Color.White.copy(alpha = 0.07f))))
                                .border(1.dp, Color.White.copy(alpha = 0.16f), RoundedCornerShape(30.dp))
                                .padding(5.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            ids.filter { it != "hang" }.forEach { Cap(it) }
                        }
                        if ("hang" in ids) {
                            Spacer(Modifier.height(14.dp))
                            Box(
                                Modifier
                                    .size(width = 128.dp, height = 50.dp)
                                    .shadow(14.dp, RoundedCornerShape(25.dp), ambientColor = Red, spotColor = Red)
                                    .clip(RoundedCornerShape(25.dp))
                                    .background(Red)
                                    .semantics { contentDescription = "ניתוק" }
                                    .clickable { act.hangup() },
                                contentAlignment = Alignment.Center,
                            ) { CallIcon(CallIcons.phone, Color.White, 26.dp, rotate = 135f) }
                        }
                    }
                }
                // הכפתורים קצת יותר למעלה, נוח יותר לאגודל (עם המקלדת פחות, שיהיה לה מקום)
                val bottom by androidx.compose.animation.core.animateDpAsState(if (keypad) 40.dp else 84.dp, tween(300), label = "bottom")
                Spacer(Modifier.height(bottom))
            }
        }
    }
}

/** תמונה או האות הראשונה, עם שתי טבעות ירוקות שנושמות כשמצלצלים */
@Composable
private fun Avatar(name: String, photo: android.graphics.Bitmap?, sizeDp: Int, breathing: Boolean, acc: Pair<Color, Color>) {
    // כשמצלצל: גלים בצבע של איש הקשר יוצאים מהתמונה כל הזמן, כמו טבעות במים
    val t = rememberInfiniteTransition(label = "waves")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2200, easing = androidx.compose.animation.core.LinearEasing)), label = "p")
    Box(Modifier.size((sizeDp + 34).dp), contentAlignment = Alignment.Center) {
        if (breathing) {
            for (k in 0 until 3) {
                val f = (p + k / 3f) % 1f
                Box(
                    Modifier
                        .size(sizeDp.dp)
                        .graphicsLayer {
                            val s = 1f + 1.3f * f
                            scaleX = s
                            scaleY = s
                            alpha = (1f - f) * 0.85f
                        }
                        .border(2.dp, if (k % 2 == 0) acc.second else acc.first, CircleShape),
                )
            }
        }
        if (photo != null) {
            Image(
                photo.asImageBitmap(),
                null,
                Modifier
                    .size(sizeDp.dp)
                    .clip(CircleShape),
                contentScale = ContentScale.Crop,
            )
        } else {
            // עיגול בשני הצבעים של איש הקשר, עם האות בלבן וצל רך
            Box(
                Modifier
                    .size(sizeDp.dp)
                    .shadow(20.dp, CircleShape)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(acc.first, acc.second))),
                contentAlignment = Alignment.Center,
            ) {
                val first = name.trim().firstOrNull { it.isLetterOrDigit() || it == '*' || it == '#' }?.toString() ?: "?"
                Text(first, color = Color.White, fontSize = (sizeDp * 0.4f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/**
 * כפתור אחד באמצע: מחליקים ימינה לענות (ירוק), שמאלה לדחות (אדום).
 * רק החלקה של רוב הדרך עושה משהו; נגיעה או החלקה קטנה, הכפתור חוזר לאמצע. ככה אי אפשר לדחות או לענות בטעות
 */
@Composable
private fun SlideAnswerDecline(onAnswer: () -> Unit, onDecline: () -> Unit) {
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .height(66.dp)
                .clip(RoundedCornerShape(33.dp))
                .background(Color.White.copy(alpha = 0.07f))
                .border(1.dp, Color.White.copy(alpha = 0.13f), RoundedCornerShape(33.dp)),
        ) {
            val density = LocalDensity.current
            val knobPx = with(density) { 56.dp.toPx() }
            val padPx = with(density) { 5.dp.toPx() }
            // כמה אפשר לזוז מהאמצע לכל צד
            val reach = ((constraints.maxWidth - knobPx - 2 * padPx) / 2f).coerceAtLeast(1f)
            val x = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            var done by remember { mutableStateOf(false) }
            // רמז: הכפתור זז קצת ימינה ושמאלה כל כמה שניות
            LaunchedEffect(Unit) {
                while (!done) {
                    delay(1600)
                    if (x.value == 0f && !done) {
                        x.animateTo(with(density) { 14.dp.toPx() }, tween(240))
                        x.animateTo(with(density) { -14.dp.toPx() }, tween(320))
                        x.animateTo(0f, spring())
                    }
                }
            }
            val f = x.value / reach // -1 (דחה) עד 1 (ענה)
            // משמאל "דחה" באדום, מימין "ענה" בירוק; מתחזקים כשמתקרבים אליהם
            Text(
                "‹ דחה",
                Modifier.align(Alignment.CenterStart).padding(start = 22.dp).alpha((0.55f + 0.45f * (-f).coerceAtLeast(0f)) * (1f - f.coerceAtLeast(0f))),
                color = Color(0xFFFF8A94),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            Text(
                "ענה ›",
                Modifier.align(Alignment.CenterEnd).padding(end = 22.dp).alpha((0.55f + 0.45f * f.coerceAtLeast(0f)) * (1f - (-f).coerceAtLeast(0f))),
                color = Green,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
            )
            // צבע הכפתור: לבן באמצע, ירוק כשהולכים לענות, אדום כשהולכים לדחות
            val knobColor = when {
                f > 0f -> androidx.compose.ui.graphics.lerp(Color.White, Green, (f * 1.6f).coerceAtMost(1f))
                f < 0f -> androidx.compose.ui.graphics.lerp(Color.White, Red, (-f * 1.6f).coerceAtMost(1f))
                else -> Color.White
            }
            Box(
                Modifier
                    .absoluteOffset { IntOffset(x.value.roundToInt(), 0) }
                    .align(Alignment.Center)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(knobColor)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { dx ->
                            if (!done) scope.launch { x.snapTo((x.value + dx).coerceIn(-reach, reach)) }
                        },
                        onDragStopped = {
                            when {
                                x.value > reach * 0.75f -> {
                                    done = true
                                    x.animateTo(reach, tween(120))
                                    onAnswer()
                                }
                                x.value < -reach * 0.75f -> {
                                    done = true
                                    x.animateTo(-reach, tween(120))
                                    onDecline()
                                }
                                else -> x.animateTo(0f, spring())
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                // הטלפון מסתובב לניתוק כשמחליקים לדחות
                CallIcon(CallIcons.phone, if (f < -0.3f) Color.White else Color.Black, 26.dp, rotate = if (f < 0f) 135f * (-f).coerceAtMost(1f) else 0f)
            }
        }
    }
}

@Composable
private fun RoundBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    small: Boolean = false,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    // קטן כשהמקלדת פתוחה, בתנועה רכה
    val sz by androidx.compose.animation.core.animateDpAsState(if (small) 52.dp else 64.dp, tween(300), label = "btn")
    // כפתור שעוד לא זמין (למשל הוסף שיחה בזמן חיוג): מופיע חלש, ונדלק כשעונים
    val fade by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else 0.35f, tween(300), label = "en")
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp).alpha(fade)) {
        // כפתור זכוכית: לבן שקוף עם ברק למעלה ומסגרת דקה; דלוק = לבן מלא וסמל כהה
        Box(
            Modifier
                .size(sz)
                .shadow(10.dp, CircleShape)
                .clip(CircleShape)
                .background(
                    if (on) Brush.verticalGradient(listOf(Color.White, Color(0xFFE3E6EE)))
                    else Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.24f), Color.White.copy(alpha = 0.08f))),
                )
                .border(1.dp, Color.White.copy(alpha = if (on) 0f else 0.22f), CircleShape)
                .clickable(enabled = enabled) { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            CallIcon(icon, if (on) Color.Black else Color.White, 26.dp)
        }
        Text(label, color = Color.White.copy(alpha = 0.85f), fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** מקלדת מספרים בזמן שיחה ("הקש 1 לשירות") */
@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    var typed by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(typed.takeLast(16), color = Color.White, fontSize = 24.sp, maxLines = 1, modifier = Modifier.height(34.dp))
            listOf("123", "456", "789", "*0#").forEachIndexed { r, row ->
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    row.forEachIndexed { c, ch ->
                        // המקשים מופיעים אחד אחרי השני, מלמטה למעלה (קרוב לאצבע קודם)
                        val show = remember { Animatable(0f) }
                        LaunchedEffect(Unit) {
                            kotlinx.coroutines.delay(((3 - r) * 3 + c) * 18L)
                            show.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = 500f))
                        }
                        Box(
                            Modifier
                                .size(62.dp)
                                .scale(0.6f + 0.4f * show.value)
                                .alpha(show.value.coerceIn(0f, 1f))
                                .clip(CircleShape)
                                // זכוכית כמו שאר הכפתורים: ברק עדין למעלה ומסגרת דקה
                                .background(Brush.verticalGradient(listOf(Color.White.copy(alpha = 0.16f), Color.White.copy(alpha = 0.05f))))
                                .border(1.dp, Color.White.copy(alpha = 0.14f), CircleShape)
                                .clickable {
                                    typed += ch
                                    onKey(ch)
                                },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(ch.toString(), color = Color.White, fontSize = 26.sp)
                        }
                    }
                }
            }
        }
    }
}

private fun clock(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, (s / 60) % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
}


/** הצבעים של מסך השיחה כצבעים רגילים, בשביל הגלולה שמתרחבת לכל המסך (שתיראה כמו המסך עצמו) */
internal fun contactArgb(name: String, photo: android.graphics.Bitmap?): Pair<Int, Int> =
    contactColors(name, photo).let { (a, b) ->
        android.graphics.Color.argb(255, (a.red * 255).toInt(), (a.green * 255).toInt(), (a.blue * 255).toInt()) to
            android.graphics.Color.argb(255, (b.red * 255).toInt(), (b.green * 255).toInt(), (b.blue * 255).toInt())
    }

/**
 * הסדר של הכפתורים במסך השיחה: שלוש שורות (למעלה, באמצע, ולמטה איפה שכפתור הניתוק).
 * כל אחד יכול לסדר לעצמו: מה למעלה, מה למטה, באיזה צד, וגם איפה כפתור הניתוק והבלוטוס.
 * hold=המתנה, add=הוסף שיחה (ו"החלף"/"מיזוג" כשיש שתי שיחות), record=הקלט, mute=השתק, keys=מקלדת,
 * speaker=רמקול, bt=בלוטוס, hang=ניתוק
 */
internal object CallButtons {
    private const val KEY = "call_btns"
    val DEFAULT = listOf(listOf("hold", "add", "record"), listOf("mute", "keys", "speaker", "bt"), listOf("hang"))
    private fun sp(c: android.content.Context) = c.getSharedPreferences("call_ui", android.content.Context.MODE_PRIVATE)

    fun load(c: android.content.Context): List<List<String>> {
        val s = runCatching { sp(c).getString(KEY, null) }.getOrNull() ?: return DEFAULT
        val parts = s.split("|").map { p -> p.split(",").filter { it.isNotBlank() } }.toMutableList()
        // סידור ישן (שתי שורות, בלי ניתוק): הניתוק למטה כרגיל
        if (parts.size == 2) parts += listOf("hang")
        if (parts.size != 3) return DEFAULT
        // כפתור חסר או כפול (למשל אחרי עדכון): חוזרים לסדר הרגיל
        if (parts.flatten().sorted() != DEFAULT.flatten().sorted()) return DEFAULT
        return parts
    }

    fun save(c: android.content.Context, r: List<List<String>>) {
        runCatching { sp(c).edit().putString(KEY, r.joinToString("|") { it.joinToString(",") }).apply() }
    }

    fun reset(c: android.content.Context) {
        runCatching { sp(c).edit().remove(KEY).apply() }
    }

    /** מחליפים מקום בין שני כפתורים (גם בין השורות) */
    fun swap(r: List<List<String>>, x: String, y: String): List<List<String>> =
        r.map { l -> l.map { if (it == x) y else if (it == y) x else it } }

    fun look(id: String) = when (id) {
        "hold" -> CallIcons.hold to "המתנה"
        "add" -> CallIcons.addCall to "הוסף שיחה"
        "record" -> CallIcons.record to "הקלט"
        "mute" -> CallIcons.mute to "השתק"
        "keys" -> CallIcons.keys to "מקלדת"
        "speaker" -> CallIcons.speaker to "רמקול"
        "hang" -> CallIcons.phone to "ניתוק"
        else -> CallIcons.bluetooth to "בלוטוס"
    }
}

/** כפתור הניתוק האדום: גדול כשהוא בשורה משלו, ובגודל של שאר הכפתורים כשהוא בתוך שורה */
/** כפתור בקפסולה: סמל בלבד, דלוק = לבן מלא וסמל כהה */
@Composable
private fun CapBtn(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    on: Boolean,
    enabled: Boolean = true,
    lit: Boolean = false,
    onClick: () -> Unit,
) {
    val bg by androidx.compose.animation.animateColorAsState(
        when { on -> Color.White; lit -> Color.White.copy(alpha = 0.2f); else -> Color.Transparent }, tween(200), label = "cap",
    )
    Box(
        Modifier
            .size(width = 50.dp, height = 44.dp)
            .alpha(if (enabled) 1f else 0.35f)
            .clip(RoundedCornerShape(22.dp))
            .background(bg)
            .semantics { contentDescription = label }
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) { CallIcon(icon, if (on) Color(0xFF15171C) else Color.White, 22.dp) }
}

@Composable
private fun HangBtn(big: Boolean, small: Boolean = false, dim: Boolean = false, onClick: () -> Unit) {
    val sz = if (big) 70.dp else if (small) 52.dp else 64.dp
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(if (big) 90.dp else 84.dp)) {
        Box(
            Modifier
                .size(sz)
                .shadow(if (dim) 0.dp else 16.dp, CircleShape, ambientColor = Red, spotColor = Red)
                .clip(CircleShape)
                .background(if (dim) Red.copy(alpha = 0.55f) else Red)
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) { CallIcon(CallIcons.phone, Color.White, if (big) 30.dp else 26.dp, rotate = 135f) }
        if (!big) Text("ניתוק", color = Color.White, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

/** מסך "סידור הכפתורים במסך השיחה": מקישים על כפתור ואז על המקום החדש, והם מתחלפים */
class CallButtonsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(11, 13, 19)))
        setContent { KitzurimTheme { CallButtonsEditor(onDone = { finish() }) } }
    }
}

@Composable
private fun CallButtonsEditor(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var rows by remember { mutableStateOf(CallButtons.load(ctx)) }
    var sel by remember { mutableStateOf<String?>(null) }
    fun tap(id: String) {
        val s = sel
        if (s == null) {
            sel = id
        } else {
            if (s != id) {
                rows = CallButtons.swap(rows, s, id)
                CallButtons.save(ctx, rows)
            }
            sel = null
        }
    }
    // הכפתור שנבחר "רוקד" קצת, כמו בעריכת מסך הבית
    val t = rememberInfiniteTransition(label = "wiggle")
    val wig by t.animateFloat(-4f, 4f, infiniteRepeatable(tween(140), androidx.compose.animation.core.RepeatMode.Reverse), label = "w")

    @Composable
    fun EditBtn(id: String, big: Boolean = false) {
        val (icon, label) = CallButtons.look(id)
        Box(Modifier.graphicsLayer { rotationZ = if (sel == id) wig else 0f }) {
            if (id == "hang") HangBtn(big = big, dim = sel == id) { tap(id) }
            else RoundBtn(icon, label, sel == id) { tap(id) }
        }
    }

    GlassBg(Color(0xFF3A7BD5) to Color(0xFF6A4CFF)) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(80.dp))
            Text("סידור הכפתורים", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.SemiBold)
            Text(
                if (sel == null) "מקישים על כפתור, ואז על המקום שאליו להעביר אותו" else "עכשיו מקישים על המקום החדש",
                color = Soft,
                fontSize = 15.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 10.dp),
            )
            Spacer(Modifier.weight(1f))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { rows[0].forEach { EditBtn(it) } }
            Spacer(Modifier.height(18.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) { rows[1].forEach { EditBtn(it) } }
            Spacer(Modifier.height(26.dp))
            // השורה של כפתור הניתוק (אפשר להחליף אותו עם כל כפתור אחר)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { rows[2].forEach { EditBtn(it, big = true) } }
            Spacer(Modifier.height(30.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                Text(
                    "↺ איפוס",
                    color = Soft,
                    fontSize = 16.sp,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .clickable {
                            CallButtons.reset(ctx)
                            rows = CallButtons.DEFAULT
                            sel = null
                        }
                        .padding(horizontal = 18.dp, vertical = 10.dp),
                )
                Text(
                    "✓ סיום",
                    color = Color(0xFF04261A),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(20.dp))
                        .background(Green)
                        .clickable { onDone() }
                        .padding(horizontal = 24.dp, vertical = 10.dp),
                )
            }
            Spacer(Modifier.height(50.dp))
        }
    }
}
