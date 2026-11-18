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
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.layout.width
import androidx.compose.ui.focus.onFocusChanged
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
import androidx.compose.ui.graphics.asImageBitmap
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
                    val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: CallerId.nameFor(ctx, num) ?: num.ifBlank { "מספר חסוי" }
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
        if (intent?.getBooleanExtra(EXTRA_KEYPAD, false) == true) padReq++
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
                    androidx.compose.runtime.key(start, padReq) {
                        DialerUi(start, reload, openPad = padReq > 0) { call(it) }
                    }
                    CallReveal(reveal)
                }
            }
        }
    }

    // מספר החיוג שהגיע מבחוץ (קישור טלפון). מתחלף כשהחייגן כבר פתוח ומגיע מספר חדש
    private var start by mutableStateOf("")

    // "הוסף שיחה" ממסך השיחה: החייגן נפתח ישר על המקלדת (כל בקשה חדשה פותחת אותה מחדש)
    private var padReq by mutableIntStateOf(0)

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
        if (intent.getBooleanExtra(EXTRA_KEYPAD, false)) padReq++
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
        val name = Calls.lookup(this, number)?.first ?: CallerId.nameFor(this, number) ?: number
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
        const val EXTRA_KEYPAD = "open_keypad"
    }
}

private val G = Color(0xFF2AD49A)
private val Muted = Color(0xFF8B95A7)
private val Red = Color(0xFFFF4D5E)

/** הלשוניות של החייגן */
private val DialTabs = listOf("אחרונות", "אנשי קשר", "מועדפים", "לא נענו")

@Composable
private fun DialerUi(start: String, reload: Int, openPad: Boolean = false, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    var tab by remember { mutableIntStateOf(0) }
    var keypad by remember { mutableStateOf(start.isNotEmpty() || openPad) }
    var q by remember { mutableStateOf("") }
    // אחרי מחיקת שיחה: טוענים את הרשימה מחדש
    var changed by remember { mutableIntStateOf(0) }
    val recents = remember(reload, changed) { PhoneBook.recents(ctx) }
    val contacts = remember(reload) { PhoneBook.contacts(ctx) }
    val missed = remember(recents) { recents.filter { it.type == CallLog.Calls.MISSED_TYPE } }
    androidx.activity.compose.BackHandler(enabled = keypad) { keypad = false }
    // חזור בזמן חיפוש: מבטל את החיפוש (ולא יוצא מהאפליקציה)
    val backFocus = androidx.compose.ui.platform.LocalFocusManager.current
    androidx.activity.compose.BackHandler(enabled = !keypad && q.isNotEmpty()) {
        q = ""
        backFocus.clearFocus()
    }

    // יד אחת (כמו בסמסונג): למעלה כותרת גדולה ומה שחשוב עכשיו, והרשימה מתחילה נמוך.
    // כשגוללים למעלה הכותרת מתכווצת לשורה קטנה, וכשחוזרים לראש הרשימה היא נפתחת שוב
    // יד אחת: תמיד (כמו בסמסונג)
    val low = true
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
                Box(Modifier.weight(1f)) { Keypad(start, contacts, recents, onCall) }
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
                            // שיחות שלא נענו שעוד מחכות לך: מהיממה האחרונה, ורק אם עוד לא דיברתם אחר כך
                            // (התקשרת בחזרה או שהוא התקשר שוב וענית: נעלמת מכאן). אפשר גם להסתיר ב-✕
                            var dismissedAt by remember { androidx.compose.runtime.mutableLongStateOf(ctx.getSharedPreferences("dial", 0).getLong("missed_seen", 0L)) }
                            val fresh = remember(missed, recents, dismissedAt) {
                                fun k(e: PhoneEntry) = e.number.filter(Char::isDigit).takeLast(9)
                                missed.filter { m ->
                                    m.date > System.currentTimeMillis() - 24L * 60 * 60 * 1000 && m.date > dismissedAt &&
                                        recents.none { it.date > m.date && it.type != CallLog.Calls.MISSED_TYPE && k(it) == k(m) }
                                }
                            }
                            if (fresh.isNotEmpty()) {
                                // שיחות שלא נענו: הקשה פותחת את הלשונית "לא נענו"
                                val names = fresh.map { it.name }.distinct().take(2).joinToString(", ")
                                Row(verticalAlignment = Alignment.CenterVertically) {
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
                                // ✕: ראיתי, להסתיר (שיחה חדשה שלא נענתה תופיע שוב)
                                Text(
                                    "✕",
                                    color = Muted,
                                    fontSize = 15.sp,
                                    modifier = Modifier
                                        .padding(start = 6.dp)
                                        .clip(CircleShape)
                                        .clickable {
                                            val now = System.currentTimeMillis()
                                            ctx.getSharedPreferences("dial", 0).edit().putLong("missed_seen", now).apply()
                                            dismissedAt = now
                                        }
                                        .padding(8.dp),
                                )
                                }
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
                // בולטת: רקע בהיר יותר ומסגרת, שנצבעת ירוק כשכותבים; ✕ מנקה
                var searchFocused by remember { mutableStateOf(false) }
                val active = searchFocused || q.isNotEmpty()
                Row(
                    Modifier
                        .fillMaxWidth()
                        // איפה נגמרת שורת החיפוש: נגיעה מתחתיה מורידה את המקלדת
                        .onGloballyPositioned { searchBottom = it.positionInParent().y + it.size.height }
                        .padding(start = 14.dp, end = 14.dp, top = 14.dp)
                        .clip(RoundedCornerShape(18.dp))
                        .background(Color(0xFF242A36))
                        .border(
                            if (active) 1.5.dp else 1.dp,
                            if (active) G else Color.White.copy(alpha = 0.16f),
                            RoundedCornerShape(18.dp),
                        )
                        .padding(start = 14.dp, end = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("🔍", fontSize = 16.sp)
                    Spacer(Modifier.width(10.dp))
                    Box(Modifier.weight(1f).padding(vertical = 14.dp)) {
                        if (q.isEmpty()) Text("חיפוש שם או מספר…", color = Color.White.copy(alpha = 0.62f), fontSize = 16.sp)
                        BasicTextField(
                            value = q,
                            onValueChange = { q = it },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
                            cursorBrush = SolidColor(G),
                            modifier = Modifier.fillMaxWidth().onFocusChanged { searchFocused = it.isFocused },
                        )
                    }
                    if (q.isNotEmpty()) Text(
                        "✕",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 16.sp,
                        modifier = Modifier.clip(CircleShape).clickable { q = "" }.padding(10.dp),
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
                // מספרים בלי שם ברשימה: כפתור שמזהה את כולם ב-Truecaller
                val unknownNums = remember(recents) {
                    recents.filter { it.number.isNotBlank() && it.name == it.number }.map { it.number }.distinctBy { CallerId.key(it) }
                }
                val hasTc = remember { CallerId.installed(ctx) }
                if (!searching && tab == 0 && hasTc && unknownNums.isNotEmpty()) {
                    val busy = CallerId.batchText
                    Text(
                        busy ?: "🔎 זהה ${unknownNums.size} מספרים עם ${CallerId.appName(ctx)}",
                        color = Color(0xFF64B5F6),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF64B5F6).copy(alpha = 0.14f))
                            .clickable(enabled = busy == null) { CallerId.identifyAll(ctx, unknownNums) }
                            .padding(horizontal = 14.dp, vertical = 7.dp),
                    )
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun Keypad(start: String, contacts: List<PhoneEntry>, recents: List<PhoneEntry>, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    // רטט קל בכל הקשה, כמו במקלדת של הטלפון
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    fun buzz() = haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.TextHandleMove)
    var number by remember { mutableStateOf(start) }
    // המקשים פתוחים. גוררים את התוצאות: המקשים יורדים, ויש מקום לגלול בכל התוצאות
    var pad by remember { mutableStateOf(true) }
    // תוצאה שפתוחה עם הפעולות שלה (חיוג, הודעה, וואטסאפ...)
    var openNum by remember { mutableStateOf<String?>(null) }
    // חיפוש חכם: תוך כדי הקלדה מופיעים אנשי הקשר שמתאימים (לפי המספר או לפי האותיות שעל המקשים)
    // וגם מספרים מהשיחות האחרונות שאינם שמורים כאנשי קשר
    val found = remember(number, contacts, recents) {
        val people = smartSearch(number, contacts, 50)
        val known = people.map { it.number.filter(Char::isDigit) }.toSet() + contacts.map { it.number.filter(Char::isDigit) }
        val digits = number.filter(Char::isDigit)
        val strangers = if (digits.length < 2) emptyList() else recents
            .filter { val k = it.number.filter(Char::isDigit); k.contains(digits) && k !in known }
            .distinctBy { it.number.filter(Char::isDigit) }.take(20)
        people + strangers
    }
    // מספר שהוקלד ואינו שמור: אפשר להוסיף אותו לאנשי הקשר
    val digitsNow = number.filter(Char::isDigit)
    val unknown = digitsNow.length >= 6 && contacts.none { it.number.filter(Char::isDigit).takeLast(9) == digitsNow.takeLast(9) }
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
                val isOpen = openNum == e.number
                // הקשה על איש קשר: המספר שלו נכנס לשורה (לא מחייג), ונפתחות הפעולות שלו
                // (חיוג, הודעה, וואטסאפ, העתקה, איש קשר). הקשה נוספת סוגרת
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(Color.White.copy(alpha = if (isOpen) 0.08f else 0.05f))
                        .animateContentSize(androidx.compose.animation.core.spring(dampingRatio = 0.85f, stiffness = 420f)),
                ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            number = e.number.filter { c -> c.isDigit() || c == '+' || c == '*' || c == '#' }
                            openNum = if (isOpen) null else e.number
                            // נפתח: המקשים יורדים כדי שיהיה מקום לפעולות. נסגר: המקשים חוזרים
                            pad = isOpen
                        }
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
                    Text(if (isOpen) "˄" else "˅", color = Muted, fontSize = 14.sp)
                }
                if (isOpen) {
                    Box(Modifier.padding(start = 4.dp, end = 4.dp, bottom = 10.dp)) { ContactActions(e.number, onCall) }
                }
                }
            }
        }
        // המספר שהוקלד. הקשה עליו מחזירה את המקשים, ולחיצה ארוכה מדביקה מספר שהועתק
        Text(
            number.ifEmpty { " " },
            color = Color.White,
            fontSize = if (number.length > 12) 28.sp else 36.sp,
            textAlign = TextAlign.Center,
            maxLines = 1,
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp).combinedClickable(
                onClick = { pad = true },
                onLongClick = {
                    val clip = runCatching {
                        ctx.getSystemService(android.content.ClipboardManager::class.java)?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()
                    }.getOrNull().orEmpty()
                    val pasted = clip.filter { it.isDigit() || it == '+' || it == '*' || it == '#' }
                    if (pasted.isNotEmpty()) { number = pasted; pad = true; buzz() } else Actions.toast(ctx, "אין מספר להדביק")
                },
            ),
        )
        if (unknown) {
            Text(
                "＋ הוסף לאנשי קשר",
                color = G,
                fontSize = 14.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable {
                        runCatching {
                            ctx.startActivity(
                                android.content.Intent(android.content.Intent.ACTION_INSERT_OR_EDIT)
                                    .setType(ContactsContract.Contacts.CONTENT_ITEM_TYPE)
                                    .putExtra(ContactsContract.Intents.Insert.PHONE, number),
                            )
                        }
                    }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }
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
                                            // הקשה: הספרה. לחיצה ארוכה על 0: "+" (מספר מחו"ל)
                                            .combinedClickable(
                                                onClick = { number += ch; buzz() },
                                                onLongClick = if (ch == '0') ({ number += '+'; buzz() }) else null,
                                            ),
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
                            // הקשה: מוחקת ספרה. לחיצה ארוכה: מוחקת הכול
                            .combinedClickable(
                                onClick = { number = number.dropLast(1); buzz() },
                                onLongClick = { number = ""; buzz() },
                            ),
                        contentAlignment = Alignment.Center,
                    ) { Text("⌫", color = Muted, fontSize = 26.sp) }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    // בחירה של כמה שיחות למחיקה: לחיצה ארוכה על שיחה מתחילה, ואז הקשה מסמנת עוד
    var selected by remember { mutableStateOf(setOf<String>()) }
    val selecting = selected.isNotEmpty()
    fun selKey(e: PhoneEntry) = "${e.number}|${e.date}"
    val haptic = androidx.compose.ui.platform.LocalHapticFeedback.current
    androidx.activity.compose.BackHandler(enabled = selecting) { selected = emptySet() }
    val deletable = list.filter { showTime && it.date > 0 }
    Column(Modifier.fillMaxSize()) {
    if (selecting) {
        // למעלה: כמה נבחרו, בחר הכול, מחק, ביטול
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 6.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1A1E27))
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("${selected.size} נבחרו", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            val all = selected.size == deletable.size
            Text(
                if (all) "בטל הכול" else "בחר הכול",
                color = G,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { selected = if (all) emptySet() else deletable.map { selKey(it) }.toSet() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "🗑 מחק",
                color = Red,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable {
                        var n = 0
                        for (e in deletable) {
                            if (selKey(e) !in selected) continue
                            if (PhoneBook.delete(ctx, e)) n++ else if (n == 0) break
                        }
                        if (n > 0) {
                            Actions.toast(ctx, if (n == 1) "שיחה אחת נמחקה" else "$n שיחות נמחקו")
                            selected = emptySet()
                            onChanged()
                        }
                    }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
            Text(
                "ביטול",
                color = Muted,
                fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { selected = emptySet() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
        items(list.size) { idx ->
            val e = list[idx]
            val key = "$idx|${e.number}|${e.date}"
            val isOpen = open == key && !selecting
            val canSel = showTime && e.date > 0
            val isSel = selKey(e) in selected
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
                        // לחיצה ארוכה על שיחה: מתחילים לבחור כמה למחיקה. במצב בחירה הקשה מסמנת
                        .combinedClickable(
                            onClick = {
                                if (selecting) {
                                    if (canSel) selected = if (isSel) selected - selKey(e) else selected + selKey(e)
                                } else {
                                    open = if (isOpen) null else key
                                }
                            },
                            onLongClick = if (canSel) ({
                                haptic.performHapticFeedback(androidx.compose.ui.hapticfeedback.HapticFeedbackType.LongPress)
                                selected = if (isSel) selected - selKey(e) else selected + selKey(e)
                            }) else null,
                        )
                        .padding(horizontal = 10.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (selecting && canSel) {
                        // מצב בחירה: עיגול סימון במקום התמונה
                        Box(
                            Modifier
                                .size(42.dp)
                                .clip(CircleShape)
                                .background(if (isSel) G else Color.White.copy(alpha = 0.08f))
                                .border(1.5.dp, if (isSel) G else Muted, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) { if (isSel) Text("✓", color = Color(0xFF04261A), fontSize = 20.sp, fontWeight = FontWeight.Bold) }
                    } else Box(
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
                        ContactActions(e.number, onCall)
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
}

/** כפתור עגול קטן בחלון שנפתח מתחת לאיש הקשר */
@Composable
private fun ActionDot(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, bg: Color, fg: Color, onClick: () -> Unit) {
    DotShell(label, bg, onClick) {
        androidx.compose.foundation.Image(
            androidx.compose.ui.graphics.vector.rememberVectorPainter(icon),
            null,
            Modifier.size(21.dp),
            colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(fg),
        )
    }
}

/** העיגול עם הסמל והשם מתחתיו (זכוכית בצבע עדין, עם קו דק מסביב) */
@Composable
private fun DotShell(label: String, bg: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clip(RoundedCornerShape(14.dp)).clickable { onClick() }.padding(4.dp),
    ) {
        Box(
            Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(bg)
                .border(1.dp, Color.White.copy(alpha = 0.08f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { content() }
        Text(label, color = Color(0xFFC3C8D2), fontSize = 11.sp, modifier = Modifier.padding(top = 5.dp))
    }
}

/** וואטסאפ: הסמל האמיתי של האפליקציה שמותקנת בטלפון (ואם אין וואטסאפ, הכפתור לא מופיע) */
@Composable
private fun WhatsAppDot(onClick: () -> Unit) {
    val ctx = LocalContext.current
    val logo = remember {
        listOf("com.whatsapp", "com.whatsapp.w4b").firstNotNullOfOrNull { p ->
            runCatching { ctx.packageManager.getApplicationIcon(p) }.getOrNull()
        }?.let { d ->
            runCatching {
                val b = android.graphics.Bitmap.createBitmap(144, 144, android.graphics.Bitmap.Config.ARGB_8888)
                d.setBounds(0, 0, 144, 144)
                d.draw(android.graphics.Canvas(b))
                b.asImageBitmap()
            }.getOrNull()
        }
    } ?: return
    DotShell("וואטסאפ", Color(0xFF25D366).copy(alpha = 0.16f), onClick) {
        androidx.compose.foundation.Image(logo, null, Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)))
    }
}

/** סמלים בקו דק, באותו סגנון של מסך השיחה */
private object DialIcons {
    val call = CallIcons.phone
    val message = lineIcon("M4.5 5h15a1.5 1.5 0 0 1 1.5 1.5v9a1.5 1.5 0 0 1 -1.5 1.5H10l-4.5 3.5V17H4.5A1.5 1.5 0 0 1 3 15.5v-9A1.5 1.5 0 0 1 4.5 5z M7.5 10h9 M7.5 13h5.5")
    val copy = lineIcon("M9.5 8.5h9a1.5 1.5 0 0 1 1.5 1.5v9.5a1.5 1.5 0 0 1 -1.5 1.5h-9A1.5 1.5 0 0 1 8 19.5V10a1.5 1.5 0 0 1 1.5 -1.5z M5 15.5V4.5A1.5 1.5 0 0 1 6.5 3h9")
    val contact = lineIcon("M12 4a3.8 3.8 0 1 1 0 7.6a3.8 3.8 0 1 1 0 -7.6z M4.5 20.5a7.5 7.5 0 0 1 15 0")
}

/** שורת הפעולות של מספר: חיוג, הודעה, וואטסאפ, העתקה, איש קשר (ברשימה ובתוצאות של המקלדת) */
@Composable
private fun ContactActions(number: String, onCall: (String) -> Unit) {
    val ctx = LocalContext.current
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
        ActionDot(DialIcons.call, "חיוג", G, Color(0xFF04261A)) { onCall(number) }
        ActionDot(DialIcons.message, "הודעה", Color(0xFF4FB3FF).copy(alpha = 0.16f), Color(0xFF7CC6FF)) {
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_SENDTO, Uri.parse("smsto:" + number))) }
        }
        WhatsAppDot {
            val n = Actions.normalizePhone(number)
            runCatching { ctx.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, Uri.parse("https://wa.me/$n"))) }
        }
        ActionDot(DialIcons.copy, "העתקה", Color(0xFFFFB340).copy(alpha = 0.16f), Color(0xFFFFC56B)) {
            runCatching {
                ctx.getSystemService(android.content.ClipboardManager::class.java)
                    ?.setPrimaryClip(android.content.ClipData.newPlainText("מספר", number))
            }
            Actions.toast(ctx, "📋 המספר הועתק")
        }
        ActionDot(DialIcons.contact, "איש קשר", Color(0xFFB388FF).copy(alpha = 0.16f), Color(0xFFC9A8FF)) {
            runCatching {
                ctx.startActivity(android.content.Intent(ContactsContract.Intents.SHOW_OR_CREATE_CONTACT, Uri.parse("tel:" + number)))
            }
        }
    }
}
