package com.kitzurim.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.CallLog
import android.provider.ContactsContract
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** איש קשר או מספר מהיסטוריה */
data class PhoneEntry(val name: String, val number: String, val starred: Boolean = false, val type: Int = 0, val date: Long = 0)

/** קריאת אנשי קשר והיסטוריית שיחות (רק אם יש הרשאה) */
object PhoneBook {
    private fun can(ctx: Context, p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    fun contacts(ctx: Context): List<PhoneEntry> {
        if (!can(ctx, Manifest.permission.READ_CONTACTS)) return emptyList()
        val out = LinkedHashMap<String, PhoneEntry>()
        runCatching {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.STARRED,
                ),
                null, null,
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0) ?: continue
                    val num = c.getString(1) ?: continue
                    val key = name + "|" + num.filter { it.isDigit() }.takeLast(9)
                    if (key !in out) out[key] = PhoneEntry(name, num, c.getInt(2) == 1)
                }
            }
        }
        return out.values.toList()
    }

    fun favorites(ctx: Context): List<PhoneEntry> = contacts(ctx).filter { it.starred }.distinctBy { it.name }

    fun recents(ctx: Context): List<PhoneEntry> {
        if (!can(ctx, Manifest.permission.READ_CALL_LOG)) return emptyList()
        val out = ArrayList<PhoneEntry>()
        runCatching {
            ctx.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE),
                null, null,
                CallLog.Calls.DATE + " DESC",
            )?.use { c ->
                while (c.moveToNext() && out.size < 80) {
                    val num = c.getString(0) ?: ""
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: num.ifBlank { "מספר חסוי" }
                    out += PhoneEntry(name, num, type = c.getInt(2), date = c.getLong(3))
                }
            }
        }
        return out
    }

    /** מחייג מיד (צריך הרשאת חיוג; לאפליקציית הטלפון יש אותה) */
    fun call(ctx: Context, number: String): Boolean {
        if (!can(ctx, Manifest.permission.CALL_PHONE)) return false
        return runCatching {
            ctx.getSystemService(TelecomManager::class.java)?.placeCall(Uri.fromParts("tel", number, null), Bundle())
            true
        }.getOrDefault(false)
    }
}

/**
 * החייגן של קיצורים (כשקיצורים היא אפליקציית הטלפון): מקלדת, שיחות אחרונות, אנשי קשר ומועדפים.
 * נפתח מהקיצור "טלפון" במסך הבית.
 */
class DialActivity : ComponentActivity() {
    private var pending: String? = null
    private var reload by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // מהווידג'ט של המועדפים: מחייגים מיד
        intent?.getStringExtra(EXTRA_CALL)?.let { n ->
            call(n)
            return
        }
        neonBars()
        askPermissions()
        val start = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            KitzurimTheme {
                DialerUi(start, reload) { call(it) }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        reload++
        FavoritesWidget.refresh(this)
    }

    private fun askPermissions() {
        val need = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.CALL_PHONE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 8)
    }

    private fun call(number: String) {
        if (number.isBlank()) return
        if (!PhoneBook.call(this, number)) {
            pending = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 9)
            return
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        reload++
        val n = pending ?: return
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) call(n)
    }

    companion object {
        const val EXTRA_CALL = "call_number"
    }
}

private val G = Color(0xFF2AD49A)
private val Muted = Color(0xFF8B95A7)
private val Red = Color(0xFFFF4D5E)

/** הלשוניות של החייגן */
private val DialTabs = listOf("אחרונות", "אנשי קשר", "מועדפים", "לא נענו")

@Composable
private fun DialerUi(start: String, reload: Int, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var keypad by remember { mutableStateOf(start.isNotEmpty()) }
    var q by remember { mutableStateOf("") }
    val recents = remember(reload) { PhoneBook.recents(ctx) }
    val contacts = remember(reload) { PhoneBook.contacts(ctx) }
    val missed = remember(recents) { recents.filter { it.type == CallLog.Calls.MISSED_TYPE } }
    androidx.activity.compose.BackHandler(enabled = keypad) { keypad = false }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D13))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        if (keypad) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "→ חזרה לרשימה",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { keypad = false }.padding(18.dp),
                )
                Box(Modifier.weight(1f)) { Keypad(start, onCall) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                // חיפוש לפי שם או מספר, בכל לשונית
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 14.dp, end = 14.dp, top = 14.dp)
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color(0xFF1A1E27))
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    if (q.isEmpty()) Text("🔍 חיפוש שם או מספר…", color = Muted, fontSize = 15.sp)
                    BasicTextField(
                        value = q,
                        onValueChange = { q = it },
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                        cursorBrush = SolidColor(G),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                // לשוניות: פסים מעוגלים, הנבחר ירוק
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    DialTabs.forEachIndexed { i, name ->
                        val on = tab == i
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) G else Color(0xFF1A1E27))
                                .clickable { tab = i }
                                .padding(horizontal = 12.dp, vertical = 7.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(name, color = if (on) Color(0xFF04261A) else Color(0xFFC3C8D2), fontSize = 12.5.sp, fontWeight = if (on) FontWeight.Bold else null)
                            if (i == 3 && missed.isNotEmpty()) {
                                Text(" ${missed.size}", color = if (on) Color(0xFF04261A) else Red, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                val base = when (tab) {
                    0 -> recents
                    1 -> contacts
                    2 -> contacts.filter { it.starred }.distinctBy { it.name }
                    else -> missed
                }
                val digits = q.filter { it.isDigit() }
                val shown = if (q.isBlank()) base else base.filter {
                    it.name.contains(q, ignoreCase = true) || (digits.length >= 2 && it.number.filter { c -> c.isDigit() }.contains(digits))
                }
                val empty = when (tab) {
                    0 -> "אין עדיין שיחות"
                    1 -> "לא נמצאו אנשי קשר"
                    2 -> "אין מועדפים. מסמנים ⭐ באנשי הקשר של סמסונג"
                    else -> "אין שיחות שלא נענו 👍"
                }
                Box(Modifier.weight(1f)) {
                    EntryList(shown, empty, showTime = tab == 0 || tab == 3, recents = recents, onCall = onCall)
                }
            }
            // כפתור המקלדת, בפינה
            Box(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(18.dp)
                    .size(58.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(G)
                    .clickable { keypad = true },
                contentAlignment = Alignment.Center,
            ) { Text("⌨︎", color = Color(0xFF04261A), fontSize = 24.sp) }
        }
    }
}

/** שני צבעים לאיש קשר לפי השם (תמיד אותו צבע לאותו אדם) */
private fun dialColors(name: String): Pair<Color, Color> {
    val pairs = listOf(
        0xFF3A7BD5 to 0xFF6A4CFF, 0xFFFF6A88 to 0xFFFF9A62, 0xFF2AD49A to 0xFF1A7FA0, 0xFFFFB340 to 0xFFFF6A3D,
        0xFFB388FF to 0xFF6A4CFF, 0xFF4FB3FF to 0xFF2AD49A, 0xFFE5484D to 0xFF8E44AD,
    )
    val p = pairs[(name.hashCode() and 0x7fffffff) % pairs.size]
    return Color(p.first) to Color(p.second)
}

@Composable
private fun Keypad(start: String, onCall: (String) -> Unit) {
    var number by remember { mutableStateOf(start) }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.weight(1f))
        Text(
            number.ifEmpty { " " },
            color = Color.White,
            fontSize = if (number.length > 12) 28.sp else 36.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(18.dp))
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                listOf("123", "456", "789", "*0#").forEach { row ->
                    Row(Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                        row.forEach { ch ->
                            Box(
                                Modifier
                                    .size(72.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = 0.07f))
                                    .clickable { number += ch },
                                contentAlignment = Alignment.Center,
                            ) { Text(ch.toString(), color = Color.White, fontSize = 30.sp) }
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Spacer(Modifier.size(72.dp))
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(G)
                            .clickable { onCall(number) },
                        contentAlignment = Alignment.Center,
                    ) { Text("✆︎", color = Color.Black, fontSize = 30.sp) }
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable { number = number.dropLast(1) },
                        contentAlignment = Alignment.Center,
                    ) { Text("⌫", color = Muted, fontSize = 26.sp) }
                }
            }
        }
    }
}

@Composable
private fun EntryList(list: List<PhoneEntry>, empty: String, showTime: Boolean, recents: List<PhoneEntry>, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(30.dp))
        }
        return
    }
    val fmt = remember { SimpleDateFormat("d/M HH:mm", Locale.getDefault()) }
    // רק שורה אחת פתוחה בכל פעם
    var open by remember { mutableStateOf<String?>(null) }
    val weekAgo = remember { System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000 }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(list.size) { idx ->
            val e = list[idx]
            val key = "$idx|${e.number}|${e.date}"
            val isOpen = open == key
            val (c1, c2) = dialColors(e.name)
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 2.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (isOpen) Color.White.copy(alpha = 0.06f) else Color.Transparent),
            ) {
                // הקשה לא מחייגת: פותחת מתחת חלון קטן עם פעולות
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { open = if (isOpen) null else key }
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c2))),
                        contentAlignment = Alignment.Center,
                    ) { Text(e.name.trim().take(1), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                        Text(
                            e.name,
                            color = if (e.type == CallLog.Calls.MISSED_TYPE) Red else Color.White,
                            fontSize = 15.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        val sub = if (showTime) {
                            val kind = when (e.type) {
                                CallLog.Calls.INCOMING_TYPE -> "↙ נכנסת"
                                CallLog.Calls.OUTGOING_TYPE -> "↗ יוצאת"
                                CallLog.Calls.MISSED_TYPE -> "✕ לא נענתה"
                                else -> ""
                            }
                            "$kind · ${fmt.format(Date(e.date))}"
                        } else {
                            e.number
                        }
                        Text(sub, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text(if (isOpen) "⌃" else "⌄", color = Muted, fontSize = 18.sp)
                }
                // החלון הקטן: נפתח ומתרחב מתוך השורה של איש הקשר
                androidx.compose.animation.AnimatedVisibility(
                    visible = isOpen,
                    enter = androidx.compose.animation.expandVertically(
                        androidx.compose.animation.core.spring(dampingRatio = 0.8f, stiffness = 380f),
                        expandFrom = Alignment.Top,
                    ) + androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.scaleIn(
                            androidx.compose.animation.core.tween(260),
                            initialScale = 0.94f,
                            transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0f),
                        ),
                    exit = androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(200), shrinkTowards = Alignment.Top) +
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(150)),
                ) {
                    Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                            ActionDot("✆︎", "חיוג", G, Color(0xFF04261A)) { onCall(e.number) }
                            ActionDot("💬", "הודעה", Color.White.copy(alpha = 0.1f), Color.White) {
                                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse("smsto:" + e.number))) }
                            }
                            ActionDot("🟢", "וואטסאפ", Color(0x3325D366), Color.White) {
                                val n = Actions.normalizePhone(e.number)
                                runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n"))) }
                            }
                            ActionDot("📋", "העתקה", Color.White.copy(alpha = 0.1f), Color.White) {
                                runCatching {
                                    ctx.getSystemService(android.content.ClipboardManager::class.java)
                                        ?.setPrimaryClip(android.content.ClipData.newPlainText("מספר", e.number))
                                }
                                Actions.toast(ctx, "📋 המספר הועתק")
                            }
                            ActionDot("👤", "איש קשר", Color.White.copy(alpha = 0.1f), Color.White) {
                                runCatching {
                                    ctx.startActivity(
                                        android.content.Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.parse("tel:" + e.number)),
                                    )
                                }
                            }
                        }
                        val digits = e.number.filter { it.isDigit() }.takeLast(9)
                        val week = if (digits.isEmpty()) 0 else recents.count {
                            it.date > weekAgo && it.number.filter { c -> c.isDigit() }.takeLast(9) == digits
                        }
                        Row(Modifier.fillMaxWidth().padding(top = 10.dp, start = 4.dp, end = 4.dp)) {
                            Text(e.number, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f))
                            Text(if (week > 0) "$week שיחות השבוע" else "", color = Muted, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

/** כפתור עגול קטן בחלון שנפתח מתחת לאיש הקשר */
@Composable
private fun ActionDot(icon: String, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(4.dp),
    ) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(bg),
            contentAlignment = Alignment.Center,
        ) { Text(icon, color = fg, fontSize = 18.sp) }
        Text(label, color = Color(0xFFC3C8D2), fontSize = 11.sp, modifier = Modifier.padding(top = 4.dp))
    }
}
