package com.kitzurim.app

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.addPathNodes
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** הצבע של כל נושא במסך הבית (הסמל והרקע הקטן שלו) */
val PageColors = mapOf(
    "touch" to Color(0xFF2AD49A),
    "pill" to Color(0xFFB18CFF),
    "ring" to Color(0xFFFFB84D),
    "colors" to Color(0xFFFF6FAE),
    "calls" to Color(0xFF4C8DF6),
    "music" to Color(0xFFFF7A59),
    "apps" to Color(0xFF38C6E8),
    "more" to Color(0xFF8B95A7),
)

private fun circ(cx: Float, cy: Float, r: Float) = "M${cx - r},$cy a$r,$r 0 1,0 ${2 * r},0 a$r,$r 0 1,0 ${-2 * r},0 "

/** סמלי קו (24×24), כמו הסמלים שמציירים את עצמם בגלולה */
private val LinePaths = mapOf(
    "touch" to "M9 11V5a2 2 0 0 1 4 0v6 M13 10a2 2 0 0 1 4 0v3a6 6 0 0 1-6 6h-1a5 5 0 0 1-4-2l-2-3a1.6 1.6 0 0 1 2.6-2L9 14",
    "pill" to "M8 8h8a4 4 0 0 1 0 8H8a4 4 0 0 1 0-8z " + circ(12f, 12f, 1.5f),
    "ring" to circ(12f, 12f, 8f) + circ(12f, 12f, 2.5f),
    "colors" to circ(12f, 12f, 8f) + circ(9f, 10f, 1f) + circ(14f, 9f, 1f) + "M15 14a2 2 0 0 0 2-2",
    "calls" to "M5 4h4l2 5-3 2a11 11 0 0 0 5 5l2-3 5 2v4a2 2 0 0 1-2 2A16 16 0 0 1 3 6a2 2 0 0 1 2-2",
    "music" to "M9 18V6l10-2v12 " + circ(7f, 18f, 2f) + circ(17f, 16f, 2f),
    "apps" to "M9 3h6a3 3 0 0 1 3 3v12a3 3 0 0 1-3 3H9a3 3 0 0 1-3-3V6a3 3 0 0 1 3-3z M10 18h4",
    "more" to circ(6f, 12f, 1.2f) + circ(12f, 12f, 1.2f) + circ(18f, 12f, 1.2f),
    "moon" to "M19 14A8 8 0 1 1 10 5a6 6 0 0 0 9 9",
    "car" to "M5 16V12l2-5h10l2 5v4 M4 16h16 " + circ(8f, 16f, 1.5f) + circ(16f, 16f, 1.5f),
    "bolt" to "M13 3 6 13h5l-1 8 7-10h-5z",
)

/** סמל קו שמצייר את עצמו כשהוא מופיע */
@Composable
fun PageIcon(id: String, color: Color, size: Int = 22) {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }
    val trim by animateFloatAsState(if (shown) 1f else 0f, tween(750, delayMillis = 150, easing = FastOutSlowInEasing), label = "draw")
    val v = ImageVector.Builder(defaultWidth = size.dp, defaultHeight = size.dp, viewportWidth = 24f, viewportHeight = 24f)
        .addPath(
            pathData = addPathNodes(LinePaths[id] ?: LinePaths.getValue("more")),
            fill = null,
            stroke = SolidColor(color),
            strokeLineWidth = 1.8f,
            strokeLineCap = StrokeCap.Round,
            strokeLineJoin = StrokeJoin.Round,
            trimPathEnd = trim.coerceAtLeast(0.001f),
        )
        .build()
    Image(rememberVectorPainter(v), contentDescription = null, Modifier.size(size.dp))
}

/** הריבוע הצבעוני עם הסמל, באריח של נושא */
@Composable
fun PageBadge(id: String) {
    val c = PageColors[id] ?: Color(0xFF8B95A7)
    Box(
        Modifier.size(36.dp).clip(RoundedCornerShape(11.dp)).background(c.copy(alpha = 0.13f)),
        contentAlignment = Alignment.Center,
    ) { PageIcon(id, c) }
}

/**
 * החלון הגדול למעלה במסך הבית: החור של המצלמה "חי", והגלולה מדגימה בעצמה מה היא עושה
 * (הודעה, שיחה, טעינה, שיר). מתחת: מצב והמתג הראשי.
 */
@Composable
fun LiveHoleHero(on: Boolean, gestures: Int, pillOn: Boolean, onToggle: (Boolean) -> Unit, onOpen: () -> Unit) {
    val demos = listOf(
        Triple("18:26", "אבא", "💬 הודעה: מי שלח ומה כתב"),
        Triple("0:42", "משה", "📞 בזמן שיחה: הזמן והשם"),
        Triple("⚡ 64%", "40 דק׳", "🔋 מחברים מטען"),
        Triple("♫", "שיר חדש", "🎵 מה מתנגן עכשיו"),
    )
    var i by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(500); open = true
            delay(2600); open = false
            delay(550); i = (i + 1) % demos.size
        }
    }
    val w by animateDpAsState(if (open) 176.dp else 28.dp, spring(dampingRatio = 0.62f, stiffness = 260f), label = "w")
    val textA by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 260 else 120, delayMillis = if (open) 220 else 0), label = "a")
    val d = demos[i]
    val green = Neon.Green
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF14302A), Color(0xFF0F1520)), radius = 700f))
            .border(1.dp, Neon.Line, RoundedCornerShape(22.dp))
            .clickable { onOpen() }
            .padding(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // הגלולה: סביב חור שחור, עם טקסט בשני הצדדים של המצלמה
        Box(
            Modifier
                .height(28.dp)
                .width(w)
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                .border(1.dp, green.copy(alpha = 0.18f), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).alpha(textA), verticalAlignment = Alignment.CenterVertically) {
                Text(d.second, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(d.first, color = green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF05070A)).border(1.dp, Color(0xFF20252E), CircleShape))
        }
        Text(
            d.third,
            Modifier.padding(top = 10.dp).alpha(0.4f + 0.6f * textA),
            color = Neon.Muted,
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
        )
        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (on) "● פעיל" else "○ כבוי",
                    color = if (on) green else Neon.Muted,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    "$gestures תנועות · הגלולה ${if (pillOn) "דלוקה" else "כבויה"}",
                    color = Neon.Muted,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Switch(checked = on, onCheckedChange = onToggle)
        }
    }
}

/** שורת מתגים מהירים מתחת לחלון: מדליקים ומכבים בלי להיכנס לדף */
@Composable
fun QuickToggles(ctx: Context, onChange: () -> Unit) {
    class Q(val icon: String, val label: String, val on: Boolean, val set: (Boolean) -> Unit)
    val items = listOf(
        Q("pill", "גלולה", NotchPrefs.pill(ctx)) { NotchPrefs.setPill(ctx, it) },
        Q("ring", "טבעת", NotchPrefs.ringOn(ctx)) { NotchPrefs.setRingOn(ctx, it); NotchOverlay.refresh() },
        Q("moon", "הפוך = שקט", NotchPrefs.faceDown(ctx)) { v ->
            NotchPrefs.setFaceDown(ctx, v)
            FaceDown.update(ctx)
            // שקט מלא צריך הרשאת "נא לא להפריע" (בלעדיה: רק רטט)
            val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
            if (v && nm?.isNotificationPolicyAccessGranted != true) {
                Actions.toast(ctx, "להדליק את קיצורים כאן, כדי שהטלפון ישתתק לגמרי (בלי זה: רק רטט)")
                runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
            }
        },
        Q("car", "רכב", NotchPrefs.carMirror(ctx)) { NotchPrefs.setCarMirror(ctx, it) },
        Q("bolt", "טעינה נוזלית", NotchPrefs.chargeFx(ctx)) { NotchPrefs.setChargeFx(ctx, it) },
    )
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items.forEach { q ->
            val c = if (q.on) Neon.GreenSoft else Neon.Muted
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(if (q.on) Neon.Green.copy(alpha = 0.08f) else Neon.Tile)
                    .border(1.dp, if (q.on) Neon.Green.copy(alpha = 0.4f) else Neon.Line, RoundedCornerShape(16.dp))
                    .clickable {
                        q.set(!q.on)
                        Actions.toast(ctx, "${q.label} · ${if (!q.on) "דלוק" else "כבוי"}")
                        onChange()
                    }
                    .padding(horizontal = 11.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                PageIcon(q.icon, c, 16)
                Text(q.label, color = c, fontSize = 12.sp, maxLines = 1)
            }
        }
    }
}

// =====================================================================
//  תצוגה חיה בראש הדפים: רואים מיד את מה שמדליקים ומכבים
// =====================================================================

@Composable
fun LivePreview(page: String) {
    when (page) {
        "ring" -> RingPreview()
        "pill" -> PillPreview()
        "colors" -> ColorsPreview()
        else -> {}
    }
}

/** מסגרת משותפת: רקע כהה, ומתחת שורה קטנה שמסבירה מה רואים */
@Composable
private fun PreviewFrame(caption: String, content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.radialGradient(listOf(Color(0xFF14302A), Color(0xFF0F1520)), radius = 700f))
            .border(1.dp, Neon.Line, RoundedCornerShape(20.dp))
            .padding(vertical = 16.dp, horizontal = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Text(caption, Modifier.padding(top = 10.dp), color = Neon.Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
    }
}

/** הטבעת כמו שהיא עכשיו: סוללה אמיתית, והבהוב של הודעה לדוגמה אם נורת ההתראות דלוקה */
@Composable
private fun RingPreview() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val battery = NotchPrefs.ringOn(ctx)
    val light = NotchPrefs.notifyLight(ctx)
    val level = remember {
        ctx.getSystemService(android.os.BatteryManager::class.java)?.getIntProperty(android.os.BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 70
    }
    val msgColor = Color(NotchPrefs.tint(ctx, "msg", 0xFF25D366.toInt()))
    val batColor = Color(NotchPrefs.tint(ctx, "charge", 0xFF3ED18C.toInt()))
    // הודעה לדוגמה: כל כמה שניות הטבעת מהבהבת בצבע של הודעה
    var pulse by remember { mutableStateOf(false) }
    LaunchedEffect(light) {
        while (light) {
            delay(1800); pulse = true
            delay(1900); pulse = false
        }
    }
    val inf = androidx.compose.animation.core.rememberInfiniteTransition(label = "p")
    val blink by inf.animateFloat(0.3f, 1f, androidx.compose.animation.core.infiniteRepeatable(tween(420), androidx.compose.animation.core.RepeatMode.Reverse), label = "b")
    val fill by animateFloatAsState(if (battery) level / 100f else 0f, tween(700), label = "f")
    val caption = when {
        pulse -> "💬 הודעה שלא קראת: הטבעת מהבהבת"
        battery && light -> "🔋 $level% סוללה · 💬 תהבהב כשתגיע הודעה"
        battery -> "🔋 $level% סוללה"
        light -> "הטבעת מופיעה רק כשיש הודעה שלא קראת"
        else -> "הטבעת כבויה"
    }
    PreviewFrame(caption) {
        androidx.compose.foundation.Canvas(Modifier.size(84.dp)) {
            val c = center
            val r = size.minDimension / 2f - 8.dp.toPx()
            val w = 5.dp.toPx()
            if (pulse) {
                drawCircle(msgColor.copy(alpha = blink * 0.35f), radius = r + 5.dp.toPx(), center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(9.dp.toPx()))
                drawCircle(msgColor.copy(alpha = blink), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
            } else {
                if (battery || light) drawCircle(Color(0x1FFFFFFF), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(w))
                else drawCircle(Color(0x22FFFFFF), radius = r, center = c, style = androidx.compose.ui.graphics.drawscope.Stroke(1.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(8f, 8f))))
                if (fill > 0f) drawArc(
                    batColor, -90f, 360f * fill, false,
                    topLeft = androidx.compose.ui.geometry.Offset(c.x - r, c.y - r),
                    size = androidx.compose.ui.geometry.Size(2 * r, 2 * r),
                    style = androidx.compose.ui.graphics.drawscope.Stroke(w, cap = StrokeCap.Round),
                )
            }
            drawCircle(Color.Black, radius = r * 0.42f, center = c)
        }
    }
}

/** הגלולה מדגימה את מה שדלוק בה: הודעה, שיחה, טעינה, שיר */
@Composable
private fun PillPreview() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val on = NotchPrefs.pill(ctx)
    val demos = buildList {
        if (NotchPrefs.pillMessages(ctx)) add(Triple("18:26", "אבא", "💬 הודעה: מי שלח ומה כתב"))
        if (NotchPrefs.pillCalls(ctx)) add(Triple("0:42", "משה", "📞 בזמן שיחה: הזמן והשם"))
        if (NotchPrefs.pillCharging(ctx) || NotchPrefs.chargeFx(ctx)) add(Triple("⚡ 64%", "40 דק׳", "🔋 מחברים מטען"))
        if (NotchPrefs.pillMusic(ctx)) add(Triple("♫", "שיר חדש", "🎵 מה מתנגן עכשיו"))
    }
    var i by remember { mutableIntStateOf(0) }
    var open by remember { mutableStateOf(false) }
    LaunchedEffect(on, demos.size) {
        open = false
        while (on && demos.isNotEmpty()) {
            delay(500); open = true
            delay(2600); open = false
            delay(550); i = (i + 1) % demos.size
        }
    }
    val w by animateDpAsState(if (open) 176.dp else 28.dp, spring(dampingRatio = 0.62f, stiffness = 260f), label = "w")
    val a by animateFloatAsState(if (open) 1f else 0f, tween(if (open) 260 else 120, delayMillis = if (open) 220 else 0), label = "a")
    val d = demos.getOrNull(i % demos.size.coerceAtLeast(1))
    val caption = when {
        !on -> "הגלולה כבויה"
        d == null -> "כל סוגי הגלולה כבויים"
        else -> d.third
    }
    PreviewFrame(caption) {
        Box(
            Modifier.height(28.dp).width(w).clip(RoundedCornerShape(14.dp)).background(Color.Black)
                .border(1.dp, if (NotchPrefs.pillFrame(ctx)) Neon.Green.copy(alpha = 0.45f) else Color.Transparent, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            if (d != null) Row(Modifier.fillMaxWidth().padding(horizontal = 10.dp).alpha(a), verticalAlignment = Alignment.CenterVertically) {
                Text(d.second, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Spacer(Modifier.weight(1f))
                Text(d.first, color = Neon.Green, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
            Box(Modifier.size(12.dp).clip(CircleShape).background(Color(0xFF05070A)).border(1.dp, Color(0xFF20252E), CircleShape))
        }
    }
}

/** כל הצבעים שבחרת, כל אחד בטבעת קטנה */
@Composable
private fun ColorsPreview() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val list = listOf(
        Triple("msg", "הודעות", 0xFF25D366), Triple("call", "שיחות", 0xFF2AD49A), Triple("music", "מוזיקה", 0xFFB388FF),
        Triple("nav", "ניווט", 0xFF33CCFF), Triple("charge", "טעינה", 0xFF3ED18C), Triple("volume", "ווליום", 0xFF4FB3FF),
    )
    PreviewFrame("ככה כל סוג נראה סביב המצלמה") {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEach { (k, name, def) ->
                val col = Color(NotchPrefs.tint(ctx, k, def.toInt()))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(
                        Modifier.size(34.dp).border(3.dp, col, CircleShape).padding(10.dp).clip(CircleShape).background(Color.Black),
                    )
                    Text(name, color = Neon.Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}
