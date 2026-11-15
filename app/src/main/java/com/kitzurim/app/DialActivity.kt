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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.nestedScroll
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

    /** מוחק שיחה אחת מהרשימה (לפי המספר והזמן שלה) */
    fun delete(ctx: Context, e: PhoneEntry): Boolean {
        if (!can(ctx, Manifest.permission.WRITE_CALL_LOG)) {
            (ctx as? android.app.Activity)?.requestPermissions(arrayOf(Manifest.permission.WRITE_CALL_LOG), 10)
            return false
        }
        return runCatching {
            ctx.contentResolver.delete(
                CallLog.Calls.CONTENT_URI,
                CallLog.Calls.NUMBER + "=? AND " + CallLog.Calls.DATE + "=?",
                arrayOf(e.number, e.date.toString()),
            ) > 0
        }.getOrDefault(false)
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
        start = intent?.data?.schemeSpecificPart ?: ""
        ui = true
        setContent {
            KitzurimTheme {
                Box(
                    Modifier
                        .fillMaxSize()
                        // זוכרים איפה נגעו לאחרונה: משם העיגול של המעבר למסך השיחה יוצא
                        .pointerInput(Unit) {
                            awaitPointerEventScope {
                                while (true) {
                                    val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    e.changes.firstOrNull()?.let { lastTouch = it.position }
                                }
                            }
                        },
                ) {
                    // מספר חדש (מקישור או מאפליקציה אחרת): החייגן נפתח מחדש עם המספר
                    androidx.compose.runtime.key(start) {
                        DialerUi(start, reload) { call(it) }
                    }
                    CallReveal(reveal)
                }
            }
        }
    }

    // מספר החיוג שהגיע מבחוץ (קישור טלפון). מתחלף כשהחייגן כבר פתוח ומגיע מספר חדש
    private var start by mutableStateOf("")

    /**
     * החייגן כבר פתוח: לא פותחים עוד עותק מעליו (אחרת צריך ללחוץ "חזור" שוב ושוב),
     * רק מעדכנים לפי מה שהגיע
     */
    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.getStringExtra(EXTRA_CALL)?.let { n ->
            call(n)
            return
        }
        intent.data?.schemeSpecificPart?.takeIf { it.isNotBlank() }?.let { start = it }
    }

    override fun onResume() {
        super.onResume()
        reload++
        FavoritesWidget.refresh(this)
    }

    private fun askPermissions() {
        val need = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.READ_CALL_LOG, Manifest.permission.WRITE_CALL_LOG, Manifest.permission.CALL_PHONE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (need.isNotEmpty()) requestPermissions(need.toTypedArray(), 8)
    }

    // המעבר למסך השיחה: עיגול שגדל מהמקום שנגעו בו והופך לרקע של מסך השיחה
    private var ui = false
    private var lastTouch: androidx.compose.ui.geometry.Offset? = null
    private var reveal by mutableStateOf<Reveal?>(null)

    private fun call(number: String) {
        if (number.isBlank() || reveal != null) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pending = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 9)
            return
        }
        // מהווידג'ט (אין חייגן על המסך): מחייגים ישר
        if (!ui) {
            if (PhoneBook.call(this, number)) finish()
            return
        }
        // אותם צבעים כמו במסך השיחה, כדי שהמעבר יהיה רציף
        val name = Calls.lookup(this, number)?.first ?: number
        val (a, b) = dialColors(name)
        val dm = resources.displayMetrics
        reveal = Reveal(lastTouch ?: androidx.compose.ui.geometry.Offset(dm.widthPixels / 2f, dm.heightPixels * 0.8f), a, b)
        // מחייגים כשהעיגול כמעט מילא את המסך, ומסך השיחה נפתח מעליו בהדרגה
        window.decorView.postDelayed({
            if (!PhoneBook.call(this, number)) {
                reveal = null
                return@postDelayed
            }
            // החייגן נשאר פתוח מתחת (ובאפליקציות האחרונות), כמו בסמסונג: כשהשיחה נגמרת חוזרים אליו.
            // רק מורידים את העיגול, אחרי שמסך השיחה כבר מכסה
            window.decorView.postDelayed({ reveal = null }, 900)
        }, 380)
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
    // אחרי מחיקת שיחה: טוענים את הרשימה מחדש
    var changed by remember { mutableIntStateOf(0) }
    val recents = remember(reload, changed) { PhoneBook.recents(ctx) }
    val contacts = remember(reload) { PhoneBook.contacts(ctx) }
    val missed = remember(recents) { recents.filter { it.type == CallLog.Calls.MISSED_TYPE } }
    androidx.activity.compose.BackHandler(enabled = keypad) { keypad = false }

    // יד אחת (כמו בסמסונג): למעלה כותרת גדולה ומה שחשוב עכשיו, והרשימה מתחילה נמוך.
    // כשגוללים למעלה הכותרת מתכווצת לשורה קטנה, וכשחוזרים לראש הרשימה היא נפתחת שוב
    val low = remember { NotchPrefs.dialLow(ctx) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val screenH = androidx.compose.ui.platform.LocalConfiguration.current.screenHeightDp
    val smallPx = with(density) { 44.dp.toPx() }
    val rangePx = with(density) { (screenH * 0.27f).dp.toPx() } - smallPx
    var headPx by remember { androidx.compose.runtime.mutableFloatStateOf(rangePx) }
    val shrink = remember(low, rangePx) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            private fun take(dy: Float): androidx.compose.ui.geometry.Offset {
                val old = headPx
                headPx = (headPx + dy).coerceIn(0f, rangePx)
                return androidx.compose.ui.geometry.Offset(0f, headPx - old)
            }
            // גוללים למעלה: קודם הכותרת מתכווצת, ורק אז הרשימה זזה
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource) =
                if (!low || available.y >= 0) androidx.compose.ui.geometry.Offset.Zero else take(available.y)
            // גוללים למטה: כשהרשימה הגיעה לראש, הכותרת נפתחת
            override fun onPostScroll(
                consumed: androidx.compose.ui.geometry.Offset,
                available: androidx.compose.ui.geometry.Offset,
                source: androidx.compose.ui.input.nestedscroll.NestedScrollSource,
            ) = if (!low || available.y <= 0) androidx.compose.ui.geometry.Offset.Zero else take(available.y)
        }
    }
    // המקלדת של החיפוש יורדת כשגוללים את הרשימה, או כשנוגעים במסך מתחת לשורת החיפוש
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    var searchBottom by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
    val hideKb = remember(focus) {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput && available.y != 0f) focus.clearFocus()
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D13))
            .statusBarsPadding()
            .navigationBarsPadding()
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val e = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                        val p = e.changes.firstOrNull() ?: continue
                        if (p.pressed && !p.previousPressed && p.position.y > searchBottom) focus.clearFocus()
                    }
                }
            }
            .nestedScroll(hideKb)
            .then(if (low) Modifier.nestedScroll(shrink) else Modifier),
    ) {
        if (keypad) {
            Column(Modifier.fillMaxSize()) {
                Text(
                    "→ חזרה לרשימה",
                    color = Muted,
                    fontSize = 14.sp,
                    modifier = Modifier.clickable { keypad = false }.padding(18.dp),
                )
                Box(Modifier.weight(1f)) { Keypad(start, contacts, onCall) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                if (low) {
                    // 1 = פתוחה לגמרי, 0 = שורה קטנה
                    val f = if (rangePx > 0) headPx / rangePx else 0f
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .height(with(density) { (smallPx + headPx).toDp() })
                            .clipToBounds()
                            .padding(horizontal = 20.dp),
                    ) {
                        // מקופלת: "שיחות" קטן בשורה העליונה
                        Text(
                            "שיחות",
                            color = Color.White.copy(alpha = (1f - f * 2f).coerceIn(0f, 1f)),
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.align(Alignment.CenterStart),
                        )
                        // פתוחה: "שיחות" בגדול, ומתחת מה שחשוב עכשיו
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(bottom = 10.dp)
                                .graphicsLayer {
                                    alpha = ((f - 0.35f) / 0.65f).coerceIn(0f, 1f)
                                    val s = 0.85f + 0.15f * f
                                    scaleX = s
                                    scaleY = s
                                },
                        ) {
                            Text("שיחות", color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold)
                            Spacer(Modifier.height(8.dp))
                            // רק שיחות שלא נענו מהיממה האחרונה (ישנות כבר לא "חשובות עכשיו")
                            val fresh = remember(missed) { missed.filter { it.date > System.currentTimeMillis() - 24L * 60 * 60 * 1000 } }
                            if (fresh.isNotEmpty()) {
                                // שיחות שלא נענו: הקשה פותחת את הלשונית "לא נענו"
                                val names = fresh.map { it.name }.distinct().take(2).joinToString(", ")
                                Text(
                                    "↙ ${if (fresh.size == 1) "שיחה אחת שלא נענתה" else "${fresh.size} שיחות שלא נענו"} · $names",
                                    color = Color(0xFFFFB3B5),
                                    fontSize = 13.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(14.dp))
                                        .background(Color(0x26FF5A5F))
                                        .border(1.dp, Color(0x66FF5A5F), RoundedCornerShape(14.dp))
                                        .clickable { tab = 3 }
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                )
                            } else {
                                // אין מה להשלים: כמה שיחות היו היום
                                val dayStart = remember(recents) {
                                    java.util.Calendar.getInstance().apply {
                                        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
                                        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                }
                                val today = recents.count { it.date >= dayStart }
                                Text(
                                    when (today) {
                                        0 -> "אין שיחות שלא נענו 👍"
                                        1 -> "שיחה אחת היום · אין שיחות שלא נענו"
                                        else -> "$today שיחות היום · אין שיחות שלא נענו"
                                    },
                                    color = Muted,
                                    fontSize = 13.sp,
                                )
                            }
                        }
                    }
                }
                // חיפוש לפי שם או מספר, בכל לשונית
                Box(
                    Modifier
                        .fillMaxWidth()
                        // איפה נגמרת שורת החיפוש: נגיעה מתחתיה מורידה את המקלדת
                        .onGloballyPositioned { searchBottom = it.positionInParent().y + it.size.height }
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
                // חיפוש: תמיד בכל מקום, לא רק בלשונית הפתוחה. קודם שיחות אחרונות, ואחר כך אנשי קשר.
                // שם, מספר, וגם ספרות לפי האותיות שעל המקשים (כמו במקלדת)
                val searching = q.isNotBlank()
                val shown = if (!searching) base else remember(q, contacts, recents) {
                    val digits = q.filter { it.isDigit() }
                    val onlyDigits = q.trim().all { it.isDigit() || it == '-' || it == ' ' || it == '+' }
                    fun hit(e: PhoneEntry) = e.name.contains(q.trim(), ignoreCase = true) ||
                        (digits.length >= 2 && e.number.filter { c -> c.isDigit() }.contains(digits))
                    val smart = if (onlyDigits && digits.isNotEmpty()) smartSearch(digits, contacts, 50) else emptyList()
                    // קודם השיחות האחרונות (מהחדשה לישנה), ואחר כך אנשי קשר שלא הופיעו בהן
                    fun key(e: PhoneEntry) = e.number.filter(Char::isDigit)
                    val smartRecent = if (onlyDigits && digits.isNotEmpty()) smartSearch(digits, recents, 500).map { key(it) }.toSet() else emptySet()
                    val recent = recents.filter { hit(it) || key(it) in smartRecent }.distinctBy { key(it) }
                    val seen = recent.map { key(it) }.toSet()
                    val people = (contacts.filter { hit(it) } + smart).distinctBy { key(it) }.filter { key(it) !in seen }
                    recent + people
                }
                val empty = if (searching) "לא נמצא כלום" else when (tab) {
                    0 -> "אין עדיין שיחות"
                    1 -> "לא נמצאו אנשי קשר"
                    2 -> "אין מועדפים. מסמנים ⭐ באנשי הקשר של סמסונג"
                    else -> "אין שיחות שלא נענו 👍"
                }
                Box(Modifier.weight(1f)) {
                    EntryList(shown, empty, showTime = searching || tab == 0 || tab == 3, recents = recents, onCall = onCall, onChanged = { changed++ })
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

/** המעבר למסך השיחה: מאיפה העיגול יוצא, ובאיזה צבעים (של איש הקשר) */
private class Reveal(val at: androidx.compose.ui.geometry.Offset, val a: Color, val b: Color)

/**
 * עיגול שגדל מהכפתור שנלחץ עד שהוא ממלא את המסך, וכבר נראה כמו הרקע של מסך השיחה
 * (כהה עם הילות בצבעי איש הקשר), עם טבעת ירוקה דקה בקצה שנעלמת. ככה החייגן "נפתח" לתוך מסך השיחה
 */
@Composable
private fun CallReveal(r: Reveal?) {
    if (r == null) return
    val p = remember(r) { androidx.compose.animation.core.Animatable(0f) }
    androidx.compose.runtime.LaunchedEffect(r) {
        p.animateTo(1f, androidx.compose.animation.core.tween(420, easing = androidx.compose.animation.core.FastOutSlowInEasing))
    }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = r.at
        val far = kotlin.math.hypot(kotlin.math.max(c.x, size.width - c.x), kotlin.math.max(c.y, size.height - c.y))
        val rad = far * p.value
        val clip = androidx.compose.ui.graphics.Path().apply {
            addOval(androidx.compose.ui.geometry.Rect(c, rad.coerceAtLeast(0.1f)))
        }
        clipPath(clip) {
            drawRect(Color(0xFF0B0D13))
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(r.a.copy(alpha = 0.62f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(size.width * 0.85f, 0f),
                    radius = size.width * 1.15f,
                ),
            )
            drawRect(
                androidx.compose.ui.graphics.Brush.radialGradient(
                    listOf(r.b.copy(alpha = 0.5f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(0f, size.height),
                    radius = size.width * 1.1f,
                ),
            )
        }
        if (p.value < 1f) {
            drawCircle(
                G.copy(alpha = 1f - p.value),
                radius = rad,
                center = c,
                style = androidx.compose.ui.graphics.drawscope.Stroke(3.dp.toPx()),
            )
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

/** כל אות לאיזה מקש היא שייכת, כמו במקלדת טלפון (עברית ואנגלית). ככה מקלידים "476" ומוצאים את "משה" */
private val T9: Map<Char, Char> = buildMap {
    fun add(key: Char, letters: String) = letters.forEach { put(it, key) }
    add('2', "abcדהו"); add('3', "defאבג"); add('4', "ghiמנםן"); add('5', "jklיכלך")
    add('6', "mnoזחט"); add('7', "pqrsרשת"); add('8', "tuvצקץ"); add('9', "wxyzסעפף")
}

/** מה כתוב מתחת לכל מקש */
private val KeyLetters = mapOf(
    '2' to "דהו ABC", '3' to "אבג DEF", '4' to "מנ GHI", '5' to "יכל JKL",
    '6' to "זחט MNO", '7' to "רשת PQRS", '8' to "צק TUV", '9' to "סעפ WXYZ",
)

private fun t9(s: String) = buildString { s.lowercase().forEach { ch -> if (ch.isDigit()) append(ch) else T9[ch]?.let { append(it) } ?: append(' ') } }

/**
 * חיפוש חכם במקלדת: אנשי הקשר שהמספר שלהם מכיל את הספרות, או שהשם שלהם מתאים לאותיות של המקשים.
 * קודם מי ששם (או מילה בשם) מתחיל בזה, אחר כך מי שזה באמצע השם, ואחר כך לפי המספר
 */
private fun smartSearch(digits: String, contacts: List<PhoneEntry>, limit: Int = 3): List<PhoneEntry> {
    if (digits.isEmpty() || digits.any { !it.isDigit() }) return emptyList()
    return contacts.distinctBy { it.number.filter(Char::isDigit) }.mapNotNull { e ->
        val words = e.name.split(' ').filter { it.isNotBlank() }.map { t9(it) }
        val whole = words.joinToString("")
        val score = when {
            words.any { it.startsWith(digits) } || whole.startsWith(digits) -> 0
            whole.contains(digits) -> 1
            e.number.filter(Char::isDigit).contains(digits) -> 2
            else -> return@mapNotNull null
        }
        score to e
    }.sortedWith(compareBy({ it.first }, { it.second.name })).map { it.second }.take(limit)
}

@Composable
private fun Keypad(start: String, contacts: List<PhoneEntry>, onCall: (String) -> Unit) {
    var number by remember { mutableStateOf(start) }
    // המקשים פתוחים. גוררים את התוצאות: המקשים יורדים, ויש מקום לגלול בכל התוצאות
    var pad by remember { mutableStateOf(true) }
    // חיפוש חכם: תוך כדי הקלדה מופיעים אנשי הקשר שמתאימים (לפי המספר או לפי האותיות שעל המקשים)
    val found = remember(number, contacts) { smartSearch(number, contacts, 50) }
    val foldPad = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y != 0f && source == androidx.compose.ui.input.nestedscroll.NestedScrollSource.UserInput) pad = false
                return androidx.compose.ui.geometry.Offset.Zero
            }
        }
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        LazyColumn(
            Modifier.weight(1f).fillMaxWidth().nestedScroll(foldPad),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.Bottom),
        ) {
            items(found.size) { i ->
                val e = found[i]
                val (c1, c2) = dialColors(e.name)
                // הקשה על איש קשר מחייגת אליו
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .clickable { onCall(e.number) }
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(c1, c2))),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(e.name.trim().take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                    }
                    Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                        Text(e.name, color = Color.White, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(e.number, color = Muted, fontSize = 12.sp, maxLines = 1)
                    }
                    Text("✆︎", color = G, fontSize = 20.sp)
                }
            }
        }
        // המספר שהוקלד. הקשה עליו מחזירה את המקשים
        Text(
            number.ifEmpty { " " },
            color = Color.White,
            fontSize = if (number.length > 12) 28.sp else 36.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).clickable { pad = true },
        )
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                androidx.compose.animation.AnimatedVisibility(
                    visible = pad,
                    enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) +
                        androidx.compose.animation.expandVertically(androidx.compose.animation.core.tween(300), expandFrom = Alignment.Bottom),
                    exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
                        androidx.compose.animation.shrinkVertically(androidx.compose.animation.core.tween(280), shrinkTowards = Alignment.Bottom),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(12.dp))
                        listOf("123", "456", "789", "*0#").forEach { row ->
                            Row(Modifier.padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                row.forEach { ch ->
                                    Box(
                                        Modifier
                                            .size(70.dp)
                                            .clip(CircleShape)
                                            .background(Color.White.copy(alpha = 0.07f))
                                            .clickable { number += ch },
                                        contentAlignment = Alignment.Center,
                                    ) {
                                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                            Text(ch.toString(), color = Color.White, fontSize = 28.sp, lineHeight = 30.sp)
                                            // האותיות של המקש, כמו בטלפון רגיל (בשביל החיפוש החכם)
                                            val letters = KeyLetters[ch]
                                            if (letters != null) Text(letters, color = Muted, fontSize = 9.5.sp, lineHeight = 11.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                Row(
                    Modifier.padding(top = 12.dp, bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // המקשים מוסתרים: כפתור להחזיר אותם
                    Box(
                        Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .then(if (!pad) Modifier.clickable { pad = true } else Modifier),
                        contentAlignment = Alignment.Center,
                    ) { if (!pad) Text("⌨︎", color = Muted, fontSize = 26.sp) }
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
private fun EntryList(list: List<PhoneEntry>, empty: String, showTime: Boolean, recents: List<PhoneEntry>, onCall: (String) -> Unit, onChanged: () -> Unit = {}) {
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
                        // שיחה (יש לה זמן): סוג השיחה והזמן. איש קשר: המספר
                        val sub = if (showTime && e.date > 0) {
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
                            // שיחה מהרשימה (יש לה זמן): אפשר למחוק אותה
                            if (showTime && e.date > 0) {
                                Text(
                                    "🗑 מחיקה",
                                    color = Red,
                                    fontSize = 12.sp,
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .clickable {
                                            if (PhoneBook.delete(ctx, e)) {
                                                open = null
                                                Actions.toast(ctx, "השיחה נמחקה")
                                                onChanged()
                                            }
                                        },
                                )
                            }
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
