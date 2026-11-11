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

@Composable
private fun DialerUi(start: String, reload: Int, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(if (start.isNotEmpty()) 0 else 1) }
    val recents = remember(reload) { PhoneBook.recents(ctx) }
    val contacts = remember(reload) { PhoneBook.contacts(ctx) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Neon.Bg)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Box(Modifier.weight(1f)) {
            when (tab) {
                0 -> Keypad(start, onCall)
                1 -> EntryList(recents, "אין עדיין שיחות", showTime = true, onCall = onCall)
                2 -> ContactsTab(contacts, onCall)
                else -> EntryList(contacts.filter { it.starred }.distinctBy { it.name }, "אין מועדפים. מסמנים ⭐ באנשי הקשר של סמסונג", false, onCall)
            }
        }
        // לשוניות למטה
        Row(
            Modifier
                .fillMaxWidth()
                .padding(10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Neon.Card),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            listOf("⌨︎" to "מקלדת", "🕘︎" to "אחרונות", "👤︎" to "אנשי קשר", "⭐︎" to "מועדפים").forEachIndexed { i, (ic, name) ->
                val on = tab == i
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(18.dp))
                        .clickable { tab = i }
                        .padding(vertical = 9.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(ic, color = if (on) G else Muted, fontSize = 18.sp)
                    Text(name, color = if (on) G else Muted, fontSize = 11.sp, fontWeight = if (on) FontWeight.Bold else null)
                }
            }
        }
    }
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
private fun ContactsTab(all: List<PhoneEntry>, onCall: (String) -> Unit) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        // חיפוש לפי שם או מספר
        Box(
            Modifier
                .fillMaxWidth()
                .padding(14.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Neon.Card)
                .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            if (q.isEmpty()) Text("🔍 חיפוש איש קשר", color = Muted, fontSize = 15.sp)
            BasicTextField(
                value = q,
                onValueChange = { q = it },
                singleLine = true,
                textStyle = TextStyle(color = Color.White, fontSize = 15.sp),
                cursorBrush = SolidColor(G),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        val digits = q.filter { it.isDigit() }
        val shown = if (q.isBlank()) all else all.filter {
            it.name.contains(q, ignoreCase = true) || (digits.length >= 2 && it.number.filter { c -> c.isDigit() }.contains(digits))
        }
        EntryList(shown, "לא נמצאו אנשי קשר", false, onCall)
    }
}

@Composable
private fun EntryList(list: List<PhoneEntry>, empty: String, showTime: Boolean, onCall: (String) -> Unit) {
    if (list.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(empty, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(30.dp))
        }
        return
    }
    val fmt = remember { SimpleDateFormat("d/M HH:mm", Locale.getDefault()) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 10.dp)) {
        items(list) { e ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .clickable { onCall(e.number) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1F2A44)),
                    contentAlignment = Alignment.Center,
                ) { Text(e.name.trim().take(1), color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                    Text(
                        e.name,
                        color = if (e.type == CallLog.Calls.MISSED_TYPE) Red else Color.White,
                        fontSize = 16.sp,
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
                Text("✆︎", color = G, fontSize = 20.sp)
            }
        }
    }
}

