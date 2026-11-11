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
import androidx.compose.ui.draw.scale
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
                    moveTaskToBack(true)
                    if (Calls.state(Calls.main()!!) != Call.STATE_RINGING) LivePill.shrinkFromScreen()
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
        if (!preview && c != null && Calls.state(c) != Call.STATE_RINGING) LivePill.shrinkFromScreen()
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

    override fun onResume() {
        super.onResume()
        if (preview) return
        visible = true
        LivePill.callEnded() // בזמן שהמסך הזה פתוח לא צריך את הגלולה
        updateProximity()
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onDestroy() {
        runCatching { proximity?.let { if (it.isHeld) it.release() } }
        proximity = null
        super.onDestroy()
    }

    /** כשהטלפון ליד האוזן: המסך נכבה, כדי שהלחי לא תלחץ על כפתורים */
    private fun updateProximity() {
        val c = Calls.main()
        val want = c != null && Calls.state(c) != Call.STATE_RINGING && Calls.route != CallAudioState.ROUTE_SPEAKER
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = runCatching { pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "kitzurim:call") }.getOrNull()
        }
        val w = proximity ?: return
        // עטוף: תקלה כאן לא תפיל את האפליקציה באמצע שיחה
        runCatching {
            if (want && !w.isHeld) w.acquire(3 * 60 * 60 * 1000L)
            if (!want && w.isHeld) w.release()
        }
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
)

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
)

@Composable
private fun NeonBg(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.radialGradient(
                    listOf(Color(0xFF14202A), Color(0xFF07090D)),
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
    val name = remember(c, version) { Calls.name(ctx, c) }
    val number = remember(c) { Calls.number(c) }
    val photo = remember(c) { Calls.photo(ctx, c) }
    val ringing = st == Call.STATE_RINGING
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
    NeonBg {
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // מקום למעלה (בתצוגה המקדימה יותר, כי שם יש מסגרת הסבר)
            Spacer(Modifier.height(if (v.ringing) top + 20.dp else top))
            if (v.ringing) Text(v.topText, color = Green, fontSize = 15.sp)
            Spacer(Modifier.height(if (v.ringing) 30.dp else 0.dp))
            Avatar(v.name, v.photo, if (v.ringing) 118 else 92, breathing = v.ringing)
            Spacer(Modifier.height(if (v.ringing) 30.dp else 20.dp))
            Text(v.name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            if (v.number != null && v.number != v.name) {
                Text(v.number, color = Soft, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (!v.ringing) {
                Text(
                    v.status,
                    color = if (v.statusGreen) Green else Soft,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            if (v.ringing) {
                SlideToAnswer { act.answer() }
                Spacer(Modifier.height(26.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Red.copy(alpha = 0.15f))
                        .border(1.5.dp, Red, RoundedCornerShape(24.dp))
                        .clickable { act.reject() }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                ) {
                    Text("✕  דחה", color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(46.dp))
            } else {
                if (keypad) {
                    Keypad { ch -> act.dtmf(ch) }
                } else {
                    // השתקה, מקלדת, רמקול, המתנה
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundBtn("🎙︎", if (v.muted) "בטל השתקה" else "השתק", v.muted) { act.mute() }
                        RoundBtn("⌨︎", "מקלדת", false) { keypad = true }
                        RoundBtn("🔊︎", "רמקול", v.speaker) { act.speaker() }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundBtn("⏸︎", if (v.holding) "המשך" else "המתנה", v.holding) { act.hold() }
                        if (v.canSwap) RoundBtn("⇄", "החלף", false) { act.swap() }
                    }
                }
                Spacer(Modifier.height(26.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (keypad) {
                        Text(
                            "הסתר",
                            color = Soft,
                            fontSize = 15.sp,
                            modifier = Modifier
                                .clickable { keypad = false }
                                .padding(16.dp),
                        )
                    }
                    Box(
                        Modifier
                            .size(width = 76.dp, height = 64.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Red)
                            .clickable { act.hangup() },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("✆︎", color = Color.White, fontSize = 26.sp)
                    }
                }
                Spacer(Modifier.height(46.dp))
            }
        }
    }
}

/** תמונה או האות הראשונה, עם שתי טבעות ירוקות שנושמות כשמצלצלים */
@Composable
private fun Avatar(name: String, photo: android.graphics.Bitmap?, sizeDp: Int, breathing: Boolean) {
    val t = rememberInfiniteTransition(label = "breath")
    val a by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "a")
    val b by t.animateFloat(1f, 0f, infiniteRepeatable(tween(1600, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "b")
    Box(Modifier.size((sizeDp + 34).dp), contentAlignment = Alignment.Center) {
        if (breathing) {
            listOf(a, b).forEach { f ->
                Box(
                    Modifier
                        .size((sizeDp + 26).dp)
                        .scale(1f + 0.14f * f)
                        .alpha(0.9f - 0.7f * f)
                        .border(2.dp, Green, CircleShape),
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
            Box(
                Modifier
                    .size(sizeDp.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(Color(0xFF3A7BD5), Color(0xFF6A4CFF)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(name.trim().take(1), color = Color.White, fontSize = (sizeDp * 0.38f).sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** מחליקים את הכפתור הירוק ימינה כדי לענות. לא עד הסוף: הוא חוזר, כדי שלא יענו בטעות */
@Composable
private fun SlideToAnswer(onAnswer: () -> Unit) {
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
            val maxX = (constraints.maxWidth - knobPx - 2 * padPx).coerceAtLeast(1f)
            val x = remember { Animatable(0f) }
            val scope = rememberCoroutineScope()
            var done by remember { mutableStateOf(false) }
            // רמז: הכפתור זז קצת ימינה כל כמה שניות
            LaunchedEffect(Unit) {
                while (!done) {
                    delay(1400)
                    if (x.value == 0f) {
                        x.animateTo(with(density) { 16.dp.toPx() }, tween(260))
                        x.animateTo(0f, spring())
                    }
                }
            }
            Text(
                "החלק ימינה כדי לענות",
                Modifier
                    .align(Alignment.Center)
                    .alpha(1f - x.value / maxX),
                color = Color(0xFFB8C2D4),
                fontSize = 14.sp,
            )
            Box(
                Modifier
                    .absoluteOffset { IntOffset((padPx + x.value).roundToInt(), 0) }
                    .align(Alignment.CenterStart)
                    .size(56.dp)
                    .clip(CircleShape)
                    .background(Green)
                    .draggable(
                        orientation = Orientation.Horizontal,
                        state = rememberDraggableState { d ->
                            scope.launch { x.snapTo((x.value + d).coerceIn(0f, maxX)) }
                        },
                        onDragStopped = {
                            if (x.value > maxX * 0.7f) {
                                done = true
                                x.animateTo(maxX, tween(120))
                                onAnswer()
                            } else {
                                x.animateTo(0f, spring())
                            }
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text("✆︎", color = Color.Black, fontSize = 24.sp)
            }
        }
    }
}

@Composable
private fun RoundBtn(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(84.dp)) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(if (on) Green else Color.White.copy(alpha = 0.09f))
                .clickable { onClick() },
            contentAlignment = Alignment.Center,
        ) {
            Text(icon, color = if (on) Color.Black else Color.White, fontSize = 22.sp)
        }
        Text(label, color = Soft, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp), maxLines = 1)
    }
}

/** מקלדת מספרים בזמן שיחה ("הקש 1 לשירות") */
@Composable
private fun Keypad(onKey: (Char) -> Unit) {
    var typed by remember { mutableStateOf("") }
    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(typed, color = Color.White, fontSize = 24.sp, modifier = Modifier.height(34.dp))
            listOf("123", "456", "789", "*0#").forEach { row ->
                Row(Modifier.padding(vertical = 5.dp), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                    row.forEach { ch ->
                        Box(
                            Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.08f))
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

