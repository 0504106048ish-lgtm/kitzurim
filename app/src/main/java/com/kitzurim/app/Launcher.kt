package com.kitzurim.app

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Process
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

// =====================================================================
//  מסך הבית של הטלפון: "שעון סביב החור"
//  התאריך מתעגל סביב המצלמה, כרטיס חכם שמתחלף, אפליקציות עם שם וסמל, ומעגן.
//  כל אפליקציה אפשר לערוך: שם, תמונה מהגלריה או אימוג'י בצבע.
// =====================================================================

/** אפליקציה אחת במסך הבית */
class HomeApp(
    val key: String,
    val component: ComponentName,
    val original: String,
    val icon: ImageBitmap?,
)

/** כל מה שנשמר: מה במסך, מה במעגן, שמות ותמונות שערכת, מה מוסתר */
object LauncherStore {
    fun sp(c: Context) = c.getSharedPreferences("launcher", Context.MODE_PRIVATE)

    private fun list(c: Context, k: String): List<String>? =
        sp(c).getString(k, null)?.split("\n")?.filter { it.isNotBlank() }

    private fun setList(c: Context, k: String, v: List<String>) = sp(c).edit().putString(k, v.joinToString("\n")).apply()

    fun home(c: Context) = list(c, "home")
    fun setHome(c: Context, v: List<String>) = setList(c, "home", v)
    fun dock(c: Context) = list(c, "dock")
    fun setDock(c: Context, v: List<String>) = setList(c, "dock", v)
    fun hidden(c: Context) = list(c, "hidden")?.toSet() ?: emptySet()
    fun setHidden(c: Context, v: Set<String>) = setList(c, "hidden", v.toList())
    fun recent(c: Context) = list(c, "recent") ?: emptyList()
    fun used(c: Context, key: String) = setList(c, "recent", (listOf(key) + recent(c).filter { it != key }).take(8))

    fun name(c: Context, key: String): String? = sp(c).getString("name_$key", null)
    fun setName(c: Context, key: String, v: String?) = sp(c).edit().apply { if (v.isNullOrBlank()) remove("name_$key") else putString("name_$key", v) }.apply()
    fun emoji(c: Context, key: String): String? = sp(c).getString("emoji_$key", null)
    fun emojiColor(c: Context, key: String): Int = sp(c).getInt("ecolor_$key", 0xFF2AD49A.toInt())
    fun setEmoji(c: Context, key: String, e: String?, color: Int) =
        sp(c).edit().apply { if (e.isNullOrBlank()) remove("emoji_$key") else putString("emoji_$key", e).putInt("ecolor_$key", color) }.apply()

    fun iconFile(c: Context, key: String) = File(File(c.filesDir, "home_icons").apply { mkdirs() }, "${key.hashCode().toUInt()}.png")

    /** תמונה מהגלריה: חותכים לריבוע באמצע ומקטינים */
    fun saveIcon(c: Context, key: String, uri: Uri): Boolean = runCatching {
        val src = c.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return false
        val side = minOf(src.width, src.height)
        val sq = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val out = Bitmap.createScaledBitmap(sq, 192, 192, true)
        iconFile(c, key).outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
        true
    }.getOrDefault(false)

    fun clearIcon(c: Context, key: String) {
        iconFile(c, key).delete()
        setEmoji(c, key, null, 0)
    }
}

/** האם מסך הבית של קיצורים דלוק (אפשר לבחור בו כמסך הבית) */
object LauncherSwitch {
    private fun cn(c: Context) = ComponentName(c, LauncherActivity::class.java)
    fun enabled(c: Context) =
        c.packageManager.getComponentEnabledSetting(cn(c)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED

    fun set(c: Context, on: Boolean) {
        c.packageManager.setComponentEnabledSetting(
            cn(c),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
    }

    /** האם קיצורים היא עכשיו מסך הבית */
    fun isDefault(c: Context): Boolean = runCatching {
        c.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_DEFAULT_ONLY)
            ?.activityInfo?.packageName == c.packageName
    }.getOrDefault(false)

    fun openHomeSettings(c: Context) {
        runCatching { c.startActivity(Intent(Settings.ACTION_HOME_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { c.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }
}

class LauncherActivity : ComponentActivity() {
    /** לחצו "בית" כשמסך הבית כבר פתוח: סוגרים מגירה וחלונות */
    private val homeAgain = mutableIntStateOf(0)

    /** אפליקציה הותקנה / הוסרה */
    private val pkgTick = mutableIntStateOf(0)
    private val pkgReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            pkgTick.intValue++
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // הכרטיס החכם מראה גם את האירוע הבא ביומן: מבקשים פעם אחת
        val sp = LauncherStore.sp(this)
        if (checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED && !sp.getBoolean("asked_cal", false)) {
            sp.edit().putBoolean("asked_cal", true).apply()
            requestPermissions(arrayOf(Manifest.permission.READ_CALENDAR), 41)
        }
        setContent { KitzurimTheme { LauncherScreen(homeAgain.intValue, pkgTick.intValue) } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        homeAgain.intValue++
    }

    override fun onStart() {
        super.onStart()
        val f = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        }
        runCatching { registerReceiver(pkgReceiver, f) }
        pkgTick.intValue++
    }

    override fun onStop() {
        runCatching { unregisterReceiver(pkgReceiver) }
        super.onStop()
    }
}

// ---------------------------------------------------------------------

private fun loadApps(c: Context): List<HomeApp> {
    val pm = c.packageManager
    val ri = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
    val size = (52 * c.resources.displayMetrics.density).toInt()
    return ri.filter { it.activityInfo.packageName != c.packageName || it.activityInfo.name.endsWith("LaunchActivity") }
        .map { r ->
            val cn = ComponentName(r.activityInfo.packageName, r.activityInfo.name)
            val icon = runCatching { r.loadIcon(pm).toBitmap(size, size).asImageBitmap() }.getOrNull()
            HomeApp(cn.flattenToString(), cn, r.loadLabel(pm).toString(), icon)
        }
        .distinctBy { it.key }
        .sortedBy { it.original }
}

/** מה יש במסך בהתחלה: האפליקציות המוכרות שמותקנות */
private fun defaultHome(c: Context, apps: List<HomeApp>): Pair<List<String>, List<String>> {
    fun pkgOf(i: Intent) = runCatching { c.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName }.getOrNull()
    fun byPkg(p: String?) = apps.firstOrNull { it.component.packageName == p }?.key
    val dialer = byPkg(pkgOf(Intent(Intent.ACTION_DIAL)))
    val sms = byPkg(pkgOf(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))))
    val browser = byPkg(pkgOf(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))))
    val camera = byPkg(pkgOf(Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE)))
    val dock = listOfNotNull(dialer, sms, browser, camera).distinct()
    val wanted = listOf(
        "com.whatsapp", "com.spotify.music", "com.waze", "com.google.android.calendar", "com.samsung.android.calendar",
        "com.google.android.gm", "com.sec.android.gallery3d", "com.google.android.apps.photos", "com.google.android.youtube",
        "com.google.android.apps.maps", "com.sec.android.app.clockpackage", "com.android.settings",
    )
    val home = (wanted.mapNotNull { byPkg(it) } + apps.map { it.key }).filter { it !in dock }.distinct().take(8)
    return home to dock
}

private fun launch(c: Context, app: HomeApp) {
    LauncherStore.used(c, app.key)
    runCatching {
        c.getSystemService(LauncherApps::class.java).startMainActivity(app.component, Process.myUserHandle(), null, null)
    }.onFailure {
        runCatching {
            c.startActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }
    }
}

/** הקיצורים שהאפליקציה עצמה מציעה (כמו "צ'אט חדש" בוואטסאפ). עובד כשקיצורים היא מסך הבית */
private fun shortcutsOf(c: Context, app: HomeApp): List<ShortcutInfo> = runCatching {
    val la = c.getSystemService(LauncherApps::class.java)
    if (!la.hasShortcutHostPermission()) return emptyList()
    val q = LauncherApps.ShortcutQuery()
        .setPackage(app.component.packageName)
        .setQueryFlags(
            LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST or
                LauncherApps.ShortcutQuery.FLAG_MATCH_PINNED,
        )
    (la.getShortcuts(q, Process.myUserHandle()) ?: emptyList()).filter { it.isEnabled }.sortedBy { it.rank }.take(4)
}.getOrDefault(emptyList())

/** פעולה של מערכת דרך הנגישות של קיצורים (פתיחת ההתראות, נעילה) */
private fun globalAction(a: Int): Boolean = NotchOverlay.service?.performGlobalAction(a) == true

private val shadowText = TextStyle(shadow = Shadow(Color(0xAA000000), Offset(0f, 2f), 8f))

// ---------------------------------------------------------------------

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(homeAgain: Int, pkgTick: Int) {
    val ctx = LocalContext.current
    val haptic = LocalHapticFeedback.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var apps by remember { mutableStateOf<List<HomeApp>>(emptyList()) }
    var edits by remember { mutableIntStateOf(0) } // שינוי בשמות / תמונות / מסך
    LaunchedEffect(pkgTick) { apps = withContext(Dispatchers.IO) { loadApps(ctx) } }

    var drawer by remember { mutableStateOf(false) }
    var menuFor by remember { mutableStateOf<HomeApp?>(null) }
    var editFor by remember { mutableStateOf<HomeApp?>(null) }
    var bgMenu by remember { mutableStateOf(false) }
    var picking by remember { mutableStateOf(false) } // "להוסיף אפליקציה למסך": בוחרים מהמגירה
    LaunchedEffect(homeAgain) { drawer = false; menuFor = null; bgMenu = false; picking = false }

    // רשימות המסך והמעגן (בפעם הראשונה: ברירת מחדל)
    val byKey = apps.associateBy { it.key }
    if (apps.isNotEmpty() && LauncherStore.home(ctx) == null) {
        val (h, d) = defaultHome(ctx, apps)
        LauncherStore.setHome(ctx, h)
        LauncherStore.setDock(ctx, d)
    }
    val home = remember(apps, edits) { (LauncherStore.home(ctx) ?: emptyList()).mapNotNull { byKey[it] } }
    val dock = remember(apps, edits) { (LauncherStore.dock(ctx) ?: emptyList()).mapNotNull { byKey[it] } }

    BackHandler(enabled = true) { /* במסך הבית "חזרה" לא יוצא לשום מקום */ }

    Box(Modifier.fillMaxSize()) {
        // ----- המסך עצמו -----
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    var sum = 0f
                    detectVerticalDragGestures(
                        onDragStart = { sum = 0f },
                        onDragEnd = {
                            if (sum < -120) drawer = true
                            else if (sum > 120) globalAction(AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS)
                        },
                    ) { _, dy -> sum += dy }
                }
                .pointerInput(Unit) {
                    detectTapGestures(
                        // הקשה כפולה על מקום ריק: נועל את המסך (כמו בסמסונג)
                        onDoubleTap = {
                            if (Build.VERSION.SDK_INT >= 28) globalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN)
                        },
                        // לחיצה ארוכה על מקום ריק (לא על אפליקציה: לה יש תפריט משלה)
                        onLongPress = {
                            scope.launch {
                                delay(40)
                                if (menuFor == null) { haptic.performHapticFeedback(HapticFeedbackType.LongPress); bgMenu = true }
                            }
                        },
                    )
                },
        ) {
            HoleClock()
            Column(
                Modifier
                    .fillMaxSize()
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp)
                    .padding(top = 200.dp, bottom = 10.dp),
            ) {
                SmartCard()
                Spacer(Modifier.height(18.dp))
                // האפליקציות במסך: 4 בשורה, סמל ושם
                home.chunked(4).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
                        row.forEach { a ->
                            Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                                AppIcon(a, edits, showName = true, onClick = { launch(ctx, a) }, onLong = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = a
                                })
                            }
                        }
                        repeat(4 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                Spacer(Modifier.weight(1f))
                // ידית: החלקה למעלה = כל האפליקציות
                Column(
                    Modifier.fillMaxWidth().clickable { drawer = true }.padding(bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.width(36.dp).height(4.dp).clip(CircleShape).background(Color(0x99FFFFFF)))
                }
                // המעגן: זכוכית, בלי שמות
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(28.dp))
                        .background(Color(0x26FFFFFF))
                        .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(28.dp))
                        .padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    dock.forEach { a ->
                        AppIcon(a, edits, showName = false, onClick = { launch(ctx, a) }, onLong = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = a
                        })
                    }
                    if (dock.isEmpty()) Text("לחיצה ארוכה על אפליקציה ← \"למעגן\"", color = Color(0xAAFFFFFF), fontSize = 12.sp)
                }
            }
        }

        // ----- המגירה: כל האפליקציות וחיפוש -----
        AnimatedVisibility(
            drawer,
            enter = slideInVertically(tween()) { it / 3 } + fadeIn(),
            exit = slideOutVertically { it / 3 } + fadeOut(),
        ) {
            Drawer(
                apps = apps,
                edits = edits,
                picking = picking,
                onClose = { drawer = false; picking = false },
                onOpen = { a ->
                    if (picking) {
                        val h = LauncherStore.home(ctx) ?: emptyList()
                        if (a.key !in h) LauncherStore.setHome(ctx, h + a.key)
                        picking = false; drawer = false; edits++
                        Actions.toast(ctx, "📌 ${label(ctx, a)} נוסף למסך")
                    } else {
                        drawer = false; launch(ctx, a)
                    }
                },
                onLong = { haptic.performHapticFeedback(HapticFeedbackType.LongPress); menuFor = it },
            )
        }

        // ----- תפריט של אפליקציה (לחיצה ארוכה) -----
        menuFor?.let { a ->
            AppMenu(a, onDismiss = { menuFor = null }, onEdit = { menuFor = null; editFor = a }, onChanged = { edits++ })
        }
        editFor?.let { a ->
            EditDialog(a, onDismiss = { editFor = null }, onSaved = { editFor = null; edits++ })
        }
        if (bgMenu) {
            Sheet(onDismiss = { bgMenu = false }) {
                SheetRow("📌", "להוסיף אפליקציה למסך") { bgMenu = false; picking = true; drawer = true }
                SheetRow("🖼️", "טפט") {
                    bgMenu = false
                    runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "טפט")) }
                }
                SheetRow("🕳️", "הגדרות קיצורים") { bgMenu = false; ctx.startActivity(Intent(ctx, HomeActivity::class.java)) }
                SheetRow("🏠", "להחליף מסך בית") { bgMenu = false; LauncherSwitch.openHomeSettings(ctx) }
            }
        }
    }
}

private fun label(c: Context, a: HomeApp) = LauncherStore.name(c, a.key) ?: a.original

private fun <T> tween() = androidx.compose.animation.core.tween<T>(320, easing = androidx.compose.animation.core.FastOutSlowInEasing)

/** הסמל של אפליקציה: התמונה שבחרת, האימוג'י שבחרת, או המקורי. עם מספר הודעות אדום */
@Composable
private fun AppGlyph(a: HomeApp, edits: Int, size: Int = 52) {
    val ctx = LocalContext.current
    val custom = remember(a.key, edits) {
        LauncherStore.iconFile(ctx, a.key).takeIf { it.exists() }?.let { f ->
            runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull()
        }
    }
    val emoji = remember(a.key, edits) { LauncherStore.emoji(ctx, a.key) }
    val badge = HomeFeed.badges[a.component.packageName] ?: 0
    Box(Modifier.size((size + 6).dp)) {
        Box(Modifier.size(size.dp).align(Alignment.Center)) {
            when {
                custom != null -> Image(custom, null, Modifier.fillMaxSize().clip(RoundedCornerShape((size * 0.3f).dp)))
                emoji != null -> Box(
                    Modifier.fillMaxSize().clip(RoundedCornerShape((size * 0.3f).dp))
                        .background(Color(LauncherStore.emojiColor(ctx, a.key))),
                    contentAlignment = Alignment.Center,
                ) { Text(emoji, fontSize = (size * 0.48f).sp) }
                a.icon != null -> Image(a.icon, null, Modifier.fillMaxSize())
                else -> Box(Modifier.fillMaxSize().clip(RoundedCornerShape(16.dp)).background(Color(0xFF333A48)))
            }
        }
        if (badge > 0) {
            Text(
                if (badge > 99) "99+" else "$badge",
                Modifier
                    .align(Alignment.TopEnd)
                    .clip(CircleShape)
                    .background(Color(0xFFFF3B4F))
                    .border(1.5.dp, Color(0x55000000), CircleShape)
                    .padding(horizontal = 5.dp, vertical = 1.dp),
                color = Color.White,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AppIcon(a: HomeApp, edits: Int, showName: Boolean, onClick: () -> Unit, onLong: () -> Unit) {
    val ctx = LocalContext.current
    Column(
        Modifier
            .width(76.dp)
            .clip(RoundedCornerShape(14.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLong)
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        AppGlyph(a, edits)
        if (showName) {
            val name = remember(a.key, edits) { label(ctx, a) }
            Text(
                name,
                color = Color.White,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                style = shadowText,
                modifier = Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------

/** התאריך מתעגל מתחת לחור של המצלמה, עם טבעת דקה סביבו, ומתחת השעה בגדול */
@Composable
private fun HoleClock() {
    val view = LocalView.current
    val density = LocalDensity.current
    val statusTop = WindowInsets.statusBars.getTop(density)
    var now by remember { mutableStateOf(Date()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = Date()
            delay(60_000L - System.currentTimeMillis() % 60_000L + 50)
        }
    }
    // המקום של החור: מהמערכת (אם יש), אחרת באמצע למעלה
    var hole by remember { mutableStateOf<Offset?>(null) }
    LaunchedEffect(Unit) {
        delay(50)
        val r = if (Build.VERSION.SDK_INT >= 28) view.rootWindowInsets?.displayCutout?.boundingRects?.firstOrNull() else null
        if (r != null) hole = Offset(r.exactCenterX(), r.exactCenterY())
    }
    val day = remember(now) { SimpleDateFormat("EEEE · d בMMMM", Locale("iw")).format(now) }
    val time = remember(now) { SimpleDateFormat("HH:mm", Locale.getDefault()).format(now) }
    val green = Neon.Green
    Canvas(Modifier.fillMaxWidth().height(200.dp)) {
        val c = hole ?: Offset(size.width / 2f, if (statusTop > 0) statusTop / 2f else 18.dp.toPx())
        // טבעת מקווקוות דקה סביב המצלמה
        drawCircle(
            green.copy(alpha = 0.4f), radius = 22.dp.toPx(), center = c,
            style = Stroke(1.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(6f, 9f))),
        )
        // התאריך על קשת מתחת לחור (כמו חיוך)
        val r = 62.dp.toPx()
        val path = android.graphics.Path().apply {
            addArc(c.x - r, c.y - r, c.x + r, c.y + r, 165f, -150f)
        }
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.argb(230, 255, 255, 255)
            textSize = 14.sp.toPx()
            textAlign = Paint.Align.LEFT
            letterSpacing = 0.08f
            setShadowLayer(6f, 0f, 2f, android.graphics.Color.argb(150, 0, 0, 0))
        }
        // באמצע הקשה: מתחילים במרחק של חצי מההפרש בין אורך הקשת לאורך הטקסט
        val len = android.graphics.PathMeasure(path, false).length
        val start = ((len - p.measureText(day)) / 2f).coerceAtLeast(0f)
        drawContext.canvas.nativeCanvas.drawTextOnPath(day, path, start, 0f, p)
        // השעה בגדול מתחת
        val tp = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = android.graphics.Color.WHITE
            textSize = 64.sp.toPx()
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create("sans-serif-thin", Typeface.NORMAL)
            setShadowLayer(12f, 0f, 3f, android.graphics.Color.argb(140, 0, 0, 0))
        }
        drawContext.canvas.nativeCanvas.drawText(time, c.x, c.y + r + 62.dp.toPx(), tp)
    }
}

// ---------------------------------------------------------------------

private class Smart(val icon: String, val color: Color, val title: String, val sub: String, val onClick: () -> Unit, val play: (() -> Unit)? = null)

/** כרטיס אחד שמתחלף לבד: מה מתנגן, ההודעה האחרונה, האירוע הבא, הסוללה */
@Composable
private fun SmartCard() {
    val ctx = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(5000); tick++
        }
    }
    val msg by HomeFeed.lastMsg
    val items = remember(tick, msg) {
        val list = ArrayList<Smart>()
        // מה מתנגן
        runCatching {
            NotchOverlay.service?.let { s -> NotchOverlay.playingController(s) }?.let { mc ->
                val md = mc.metadata
                val t = md?.getString(android.media.MediaMetadata.METADATA_KEY_TITLE) ?: return@let
                val ar = md.getString(android.media.MediaMetadata.METADATA_KEY_ARTIST) ?: ""
                list += Smart("🎵", Color(0xFF1DB954), t, if (ar.isNotBlank()) "$ar · הקשה = לנגן" else "הקשה = לנגן", {
                    runCatching { mc.sessionActivity?.send() }
                }, { runCatching { mc.transportControls.pause() } })
            }
        }
        // ההודעה האחרונה (משעתיים האחרונות, ושעוד לא נקראה)
        msg?.let { (m, at) ->
            if (System.currentTimeMillis() - at < 2 * 3600_000L) {
                list += Smart("💬", Color(m.color), m.sender, m.text, { runCatching { m.open?.send() } })
            }
        }
        // האירוע הבא ביומן (ב־12 השעות הקרובות)
        nextEvent(ctx)?.let { (title, begin) ->
            val mins = ((begin - System.currentTimeMillis()) / 60_000L).toInt()
            val whenTxt = if (mins < 60) "בעוד $mins דק׳" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(begin))
            list += Smart("📅", Color(0xFFFF6FAE), title, whenTxt, {
                runCatching {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setData(Uri.parse("content://com.android.calendar/time/$begin"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            })
        }
        // הסוללה (תמיד יש)
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val pct = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: 0
        val charging = bm?.isCharging == true
        list += Smart("🔋", Color(0xFF2AD49A), "$pct%", if (charging) "בטעינה" else "סוללה", {
            runCatching { ctx.startActivity(Intent(Intent.ACTION_POWER_USAGE_SUMMARY).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        })
        list
    }
    val cur = items[tick % items.size]
    Crossfade(cur, label = "smart") { s ->
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0x2EFFFFFF))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(22.dp))
                .clickable { s.onClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(RoundedCornerShape(12.dp)).background(s.color.copy(alpha = 0.25f)),
                contentAlignment = Alignment.Center,
            ) { Text(s.icon, fontSize = 18.sp) }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                Text(s.title, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(s.sub, color = Color(0xCCFFFFFF), fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            s.play?.let { pause ->
                Text("⏸", Modifier.clip(CircleShape).clickable { pause() }.padding(8.dp), color = Color.White, fontSize = 18.sp)
            }
            // נקודות: כמה כרטיסים יש
            if (items.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                items.indices.forEach { i ->
                    Box(Modifier.size(if (i == tick % items.size) 6.dp else 4.dp).clip(CircleShape).background(if (i == tick % items.size) Color.White else Color(0x66FFFFFF)))
                }
            }
        }
    }
}

private fun nextEvent(c: Context): Pair<String, Long>? = runCatching {
    if (c.checkSelfPermission(Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED) return null
    val now = System.currentTimeMillis()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
        .appendPath(now.toString()).appendPath((now + 12 * 3600_000L).toString()).build()
    c.contentResolver.query(
        uri,
        arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY),
        null, null, CalendarContract.Instances.BEGIN + " ASC",
    )?.use { cur ->
        while (cur.moveToNext()) {
            val begin = cur.getLong(1)
            if (cur.getInt(2) == 0 && begin > now) return (cur.getString(0) ?: "אירוע") to begin
        }
        null
    }
}.getOrNull()

// ---------------------------------------------------------------------

private class Person2(val name: String, val number: String)

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun Drawer(
    apps: List<HomeApp>,
    edits: Int,
    picking: Boolean,
    onClose: () -> Unit,
    onOpen: (HomeApp) -> Unit,
    onLong: (HomeApp) -> Unit,
) {
    val ctx = LocalContext.current
    val kb = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    var q by remember { mutableStateOf("") }
    var showHidden by remember { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    // גוללים: המקלדת יורדת
    LaunchedEffect(grid) { snapshotFlow { grid.isScrollInProgress }.collect { if (it) { kb?.hide(); focus.clearFocus() } } }
    // חזרה: קודם מנקה חיפוש, אחר כך סוגר
    BackHandler { if (q.isNotEmpty()) q = "" else onClose() }

    val hidden = remember(edits) { LauncherStore.hidden(ctx) }
    val visible = apps.filter { it.key !in hidden }
    val query = q.trim()
    val hits = if (query.isEmpty()) visible else apps.filter {
        label(ctx, it).contains(query, ignoreCase = true) || it.original.contains(query, ignoreCase = true)
    }
    val people = remember(query) { if (query.length >= 2) contacts(ctx, query) else emptyList() }
    val recent = remember(edits) { LauncherStore.recent(ctx).mapNotNull { k -> apps.firstOrNull { it.key == k } }.filter { it.key !in hidden }.take(4) }

    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xF20B0E14))
            .systemBarsPadding()
            .imePadding()
            .padding(horizontal = 14.dp),
    ) {
        if (picking) Text("בוחרים אפליקציה להוספה למסך", color = Neon.GreenSoft, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp))
        OutlinedTextField(
            value = q,
            onValueChange = { q = it },
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 8.dp),
            singleLine = true,
            placeholder = { Text("🔍 חיפוש אפליקציה, איש קשר…") },
            trailingIcon = { if (q.isNotEmpty()) Text("✕", Modifier.clickable { q = "" }.padding(10.dp), color = Neon.Muted) },
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { hits.firstOrNull()?.let(onOpen) }),
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(4),
            state = grid,
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (query.isEmpty() && recent.isNotEmpty() && !picking) {
                item(span = { GridItemSpan(4) }) { Label("בשימוש לאחרונה") }
                items(recent, key = { "r" + it.key }) { a ->
                    Box(contentAlignment = Alignment.Center) { AppIcon(a, edits, true, { onOpen(a) }, { onLong(a) }) }
                }
                item(span = { GridItemSpan(4) }) { Label("כל האפליקציות · א־ת") }
            }
            items(hits.sortedBy { label(ctx, it) }, key = { it.key }) { a ->
                Box(contentAlignment = Alignment.Center) { AppIcon(a, edits, true, { onOpen(a) }, { onLong(a) }) }
            }
            if (query.isNotEmpty()) {
                if (people.isNotEmpty()) {
                    item(span = { GridItemSpan(4) }) { Label("אנשי קשר") }
                    people.forEach { p ->
                        item(span = { GridItemSpan(4) }) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Neon.Tile)
                                    .clickable { runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${p.number}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("👤 ${p.name}", Modifier.weight(1f), color = Neon.Text)
                                Text(p.number, color = Neon.Muted, fontSize = 12.sp)
                            }
                        }
                    }
                }
                item(span = { GridItemSpan(4) }) {
                    Text(
                        "🌐 לחפש \"$query\" באינטרנט",
                        Modifier.fillMaxWidth().padding(top = 6.dp).clip(RoundedCornerShape(14.dp)).background(Neon.Tile)
                            .clickable {
                                runCatching {
                                    ctx.startActivity(Intent(Intent.ACTION_WEB_SEARCH).putExtra(android.app.SearchManager.QUERY, query).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                                }.onFailure {
                                    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + Uri.encode(query))).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                                }
                            }
                            .padding(12.dp),
                        color = Neon.Text,
                    )
                }
            } else if (hidden.isNotEmpty()) {
                item(span = { GridItemSpan(4) }) {
                    Text(
                        if (showHidden) "🙈 להסתיר שוב את המוסתרות" else "🙈 אפליקציות מוסתרות (${hidden.size})",
                        Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable { showHidden = !showHidden }.padding(8.dp),
                        color = Neon.Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    )
                }
                if (showHidden) items(apps.filter { it.key in hidden }, key = { "h" + it.key }) { a ->
                    Box(contentAlignment = Alignment.Center) { AppIcon(a, edits, true, { onOpen(a) }, { onLong(a) }) }
                }
            }
            item(span = { GridItemSpan(4) }) { Spacer(Modifier.height(20.dp)) }
        }
    }
}

@Composable
private fun Label(t: String) =
    Text(t, color = Neon.Muted, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp, bottom = 2.dp, start = 4.dp))

private fun contacts(c: Context, q: String): List<Person2> = runCatching {
    if (c.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val uri = Uri.withAppendedPath(ContactsContract.CommonDataKinds.Phone.CONTENT_FILTER_URI, Uri.encode(q))
    val out = LinkedHashMap<String, Person2>()
    c.contentResolver.query(
        uri,
        arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
        null, null, null,
    )?.use { cur ->
        while (cur.moveToNext() && out.size < 5) {
            val n = cur.getString(0) ?: continue
            if (n !in out) out[n] = Person2(n, cur.getString(1) ?: "")
        }
    }
    out.values.toList()
}.getOrDefault(emptyList())

// ---------------------------------------------------------------------

/** חלון תחתון פשוט */
@Composable
private fun Sheet(onDismiss: () -> Unit, content: @Composable () -> Unit) {
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color(0x88000000)).clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
        ) { onDismiss() },
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(10.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF1A2030))
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                ) { }
                .padding(vertical = 8.dp),
        ) { content() }
    }
}

@Composable
private fun SheetRow(icon: String, text: String, color: Color = Neon.Text, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(icon, fontSize = 18.sp, modifier = Modifier.width(32.dp))
        Text(text, color = color, fontSize = 15.sp)
    }
}

/** לחיצה ארוכה על אפליקציה */
@Composable
private fun AppMenu(a: HomeApp, onDismiss: () -> Unit, onEdit: () -> Unit, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val home = LauncherStore.home(ctx) ?: emptyList()
    val dock = LauncherStore.dock(ctx) ?: emptyList()
    val hidden = LauncherStore.hidden(ctx)
    val shortcuts = remember(a.key) { shortcutsOf(ctx, a) }
    fun done(t: String) { Actions.toast(ctx, t); onChanged(); onDismiss() }
    Sheet(onDismiss) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            AppGlyph(a, 0, 40)
            Text(label(ctx, a), color = Neon.Text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(start = 10.dp))
        }
        // הקיצורים של האפליקציה עצמה
        shortcuts.forEach { s ->
            SheetRow("↗", (s.shortLabel ?: s.longLabel ?: "").toString()) {
                onDismiss()
                runCatching { ctx.getSystemService(LauncherApps::class.java).startShortcut(s, null, null) }
            }
        }
        SheetRow("✏️", "עריכה: שם ותמונה") { onEdit() }
        if (a.key in home) {
            SheetRow("◀", "להזיז קדימה") {
                val i = home.indexOf(a.key)
                if (i > 0) LauncherStore.setHome(ctx, home.toMutableList().apply { add(i - 1, removeAt(i)) })
                onChanged()
            }
            SheetRow("▶", "להזיז אחורה") {
                val i = home.indexOf(a.key)
                if (i < home.size - 1) LauncherStore.setHome(ctx, home.toMutableList().apply { add(i + 1, removeAt(i)) })
                onChanged()
            }
            SheetRow("➖", "להוריד מהמסך") { LauncherStore.setHome(ctx, home - a.key); done("ירד מהמסך (נשאר בכל האפליקציות)") }
        } else {
            SheetRow("📌", "להוסיף למסך") { LauncherStore.setHome(ctx, (home - a.key) + a.key); done("📌 נוסף למסך") }
        }
        if (a.key in dock) {
            SheetRow("⬆", "להוציא מהמעגן") { LauncherStore.setDock(ctx, dock - a.key); done("יצא מהמעגן") }
        } else if (dock.size < 5) {
            SheetRow("⬇", "להעביר למעגן") { LauncherStore.setDock(ctx, dock + a.key); LauncherStore.setHome(ctx, home - a.key); done("עבר למעגן") }
        }
        if (a.key in hidden) {
            SheetRow("👁", "להחזיר לרשימה") { LauncherStore.setHidden(ctx, hidden - a.key); done("חזר לרשימה") }
        } else {
            SheetRow("🙈", "להסתיר מהרשימה") {
                LauncherStore.setHidden(ctx, hidden + a.key)
                LauncherStore.setHome(ctx, home - a.key)
                LauncherStore.setDock(ctx, dock - a.key)
                done("הוסתר. אפשר למצוא אותו בחיפוש")
            }
        }
        SheetRow("ℹ️", "פרטי אפליקציה") {
            onDismiss()
            runCatching {
                ctx.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${a.component.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        SheetRow("🗑️", "להסיר את האפליקציה", Color(0xFFFF6B7A)) {
            onDismiss()
            runCatching {
                ctx.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${a.component.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}

/** עריכה מלאה: שם, תמונה מהגלריה, אימוג'י בצבע, או חזרה למקורי */
@Composable
private fun EditDialog(a: HomeApp, onDismiss: () -> Unit, onSaved: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(LauncherStore.name(ctx, a.key) ?: a.original) }
    var emoji by remember { mutableStateOf(LauncherStore.emoji(ctx, a.key) ?: "") }
    var color by remember { mutableIntStateOf(LauncherStore.emojiColor(ctx, a.key)) }
    var tick by remember { mutableIntStateOf(0) }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null && LauncherStore.saveIcon(ctx, a.key, uri)) {
            LauncherStore.setEmoji(ctx, a.key, null, color)
            emoji = ""
            tick++
        }
    }
    val colors = listOf(0xFF2AD49A, 0xFF4C8DF6, 0xFFB18CFF, 0xFFFF6FAE, 0xFFFF7A59, 0xFFFFB84D, 0xFF38C6E8, 0xFF1A2030).map { it.toInt() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("עריכת אפליקציה") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                // תצוגה: איך זה ייראה במסך
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(58.dp)) {
                        val f = LauncherStore.iconFile(ctx, a.key)
                        val img = remember(tick) { if (f.exists()) runCatching { BitmapFactory.decodeFile(f.path)?.asImageBitmap() }.getOrNull() else null }
                        when {
                            emoji.isNotBlank() -> Box(
                                Modifier.fillMaxSize().clip(RoundedCornerShape(17.dp)).background(Color(color)),
                                contentAlignment = Alignment.Center,
                            ) { Text(emoji, fontSize = 28.sp) }
                            img != null -> Image(img, null, Modifier.fillMaxSize().clip(RoundedCornerShape(17.dp)))
                            a.icon != null -> Image(a.icon, null, Modifier.fillMaxSize())
                        }
                    }
                    Text(name.ifBlank { a.original }, Modifier.padding(start = 12.dp), fontWeight = FontWeight.Bold)
                }
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    singleLine = true,
                    label = { Text("שם") },
                    trailingIcon = { if (name != a.original) Text("↺", Modifier.clickable { name = a.original }.padding(10.dp)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                TextButton(onClick = { pick.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                    Text("🖼️ תמונה מהגלריה")
                }
                OutlinedTextField(
                    value = emoji,
                    onValueChange = { emoji = it.take(4) },
                    singleLine = true,
                    label = { Text("או אימוג'י (למשל 🙂)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                if (emoji.isNotBlank()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    colors.forEach { cc ->
                        Box(
                            Modifier.size(26.dp).clip(CircleShape).background(Color(cc))
                                .border(2.dp, if (cc == color) Color.White else Color.Transparent, CircleShape)
                                .clickable { color = cc },
                        )
                    }
                }
                TextButton(onClick = {
                    LauncherStore.clearIcon(ctx, a.key)
                    emoji = ""; name = a.original; tick++
                }) { Text("↺ לחזור לסמל ולשם המקוריים") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                LauncherStore.setName(ctx, a.key, name.trim().takeIf { it != a.original })
                if (emoji.isNotBlank()) {
                    LauncherStore.iconFile(ctx, a.key).delete()
                    LauncherStore.setEmoji(ctx, a.key, emoji.trim(), color)
                } else {
                    LauncherStore.setEmoji(ctx, a.key, null, color)
                }
                onSaved()
            }) { Text("שמירה") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("ביטול") } },
    )
}
