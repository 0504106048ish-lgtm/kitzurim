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
    }

    override fun onResume() {
        super.onResume()
        visible = true
        LivePill.callEnded() // בזמן שהמסך הזה פתוח לא צריך את הגלולה
        updateProximity()
    }

    override fun onPause() {
        super.onPause()
        visible = false
    }

    override fun onDestroy() {
        proximity?.let { if (it.isHeld) it.release() }
        proximity = null
        super.onDestroy()
    }

    /** כשהטלפון ליד האוזן: המסך נכבה, כדי שהלחי לא תלחץ על כפתורים */
    private fun updateProximity() {
        val c = Calls.main()
        val want = c != null && Calls.state(c) != Call.STATE_RINGING && Calls.route != CallAudioState.ROUTE_SPEAKER
        val pm = getSystemService(PowerManager::class.java) ?: return
        if (proximity == null && pm.isWakeLockLevelSupported(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK)) {
            proximity = pm.newWakeLock(PowerManager.PROXIMITY_SCREEN_OFF_WAKE_LOCK, "kitzurim:call")
        }
        val w = proximity ?: return
        if (want && !w.isHeld) w.acquire(3 * 60 * 60 * 1000L)
        if (!want && w.isHeld) w.release()
    }

    companion object {
        /** מסך השיחה פתוח עכשיו (אז הגלולה ליד המצלמה לא מופיעה) */
        @Volatile
        var visible = false
    }
}

private val Green = Color(0xFF2AD49A)
private val Red = Color(0xFFFF4D5E)
private val Soft = Color(0xFFA8B0C0)

@Suppress("DEPRECATION")
@Composable
private fun CallScreenUi(c: Call?, version: Int) {
    val ctx = LocalContext.current
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
    ) {
        if (c == null) {
            Text(
                "השיחה הסתיימה",
                Modifier.align(Alignment.Center),
                color = Soft,
                fontSize = 18.sp,
            )
            return@Box
        }
        val st = Calls.state(c)
        val name = remember(c, version) { Calls.name(ctx, c) }
        val number = remember(c) { Calls.number(c) }
        val photo = remember(c) { Calls.photo(ctx, c) }
        val ringing = st == Call.STATE_RINGING
        var keypad by remember { mutableStateOf(false) }

        // שעון: מתעדכן כל שנייה
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(500)
            }
        }
        val at = Calls.answeredAt[c]
        val status = when {
            ringing -> if (Calls.list.size > 1) "שיחה ממתינה" else "שיחה נכנסת"
            st == Call.STATE_DIALING || st == Call.STATE_CONNECTING -> "מחייג…"
            st == Call.STATE_HOLDING -> "בהמתנה"
            st == Call.STATE_DISCONNECTING || st == Call.STATE_DISCONNECTED -> "השיחה הסתיימה"
            at != null -> clock((now - at) / 1000)
            else -> "מתחבר…"
        }

        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(if (ringing) 90.dp else 70.dp))
            Text(
                if (ringing) status else "",
                color = Green,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(if (ringing) 36.dp else 8.dp))
            Avatar(name, photo, if (ringing) 118 else 92, breathing = ringing)
            Spacer(Modifier.height(if (ringing) 36.dp else 22.dp))
            Text(name, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, maxLines = 2)
            if (number != null && number != name) {
                Text(number, color = Soft, fontSize = 14.sp, modifier = Modifier.padding(top = 4.dp))
            }
            if (!ringing) {
                Text(
                    status,
                    color = if (at != null && st == Call.STATE_ACTIVE) Green else Soft,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            Spacer(Modifier.weight(1f))

            if (ringing) {
                SlideToAnswer { Calls.answer(c) }
                Spacer(Modifier.height(26.dp))
                Box(
                    Modifier
                        .clip(RoundedCornerShape(24.dp))
                        .background(Red.copy(alpha = 0.15f))
                        .border(1.5.dp, Red, RoundedCornerShape(24.dp))
                        .clickable { Calls.reject(c) }
                        .padding(horizontal = 28.dp, vertical = 12.dp),
                ) {
                    Text("✕  דחה", color = Red, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(46.dp))
            } else {
                if (keypad) {
                    Keypad { ch -> Calls.dtmf(c, ch) }
                } else {
                    // השתקה, מקלדת, רמקול, המתנה
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundBtn("🎙︎", if (Calls.muted) "בטל השתקה" else "השתק", Calls.muted) { Calls.toggleMute() }
                        RoundBtn("⌨︎", "מקלדת", false) { keypad = true }
                        RoundBtn("🔊︎", "רמקול", Calls.route == CallAudioState.ROUTE_SPEAKER) { Calls.toggleSpeaker() }
                    }
                    Spacer(Modifier.height(18.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        RoundBtn("⏸︎", if (st == Call.STATE_HOLDING) "המשך" else "המתנה", st == Call.STATE_HOLDING) { Calls.toggleHold(c) }
                        if (Calls.list.count { Calls.state(it) == Call.STATE_HOLDING } > 0 && st == Call.STATE_ACTIVE) {
                            RoundBtn("⇄", "החלף", false) { Calls.swap() }
                        }
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
                            .clickable { Calls.hangup(c) },
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

