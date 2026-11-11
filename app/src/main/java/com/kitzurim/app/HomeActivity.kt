package com.kitzurim.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** המסך הראשי: החור של המצלמה. מה כל תנועה עושה, איך הטבעת נראית, וכיוון המיקום */
class HomeActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        // כל עוד המסך הזה פתוח: עיגול ורוד סביב החור, כדי לראות איפה הוא יושב ולכוון
        NotchOverlay.setPreview(true)
    }

    override fun onPause() {
        NotchOverlay.setPreview(false)
        super.onPause()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // שורת המצב: סמלים בהירים על הרקע הכהה
        neonBars()
        setContent { KitzurimTheme { HomeScreen(resumes.intValue) } }
    }
}

@Composable
fun HomeScreen(resumes: Int) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme

    // בחירת פעולה: לתנועה בכל מקום (pkg = null), או לתנועה באפליקציה מסוימת
    var picking by remember { mutableStateOf<Target?>(null) }
    var pickingApp by remember { mutableStateOf<Target?>(null) }
    var pickingUrl by remember { mutableStateOf<Target?>(null) }
    var addingApp by remember { mutableStateOf(false) }
    var pickingPopupApp by remember { mutableStateOf(false) }
    var addingOffApp by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPos by remember { mutableStateOf(false) }
    var pendingContact by remember { mutableStateOf<Pair<Target, ActionType>?>(null) }

    /** שומר פעולה. באפליקציה מסוימת, null = "כמו בכל מקום" */
    fun save(t: Target, a: NotchAction?) {
        if (t.pkg == null) {
            NotchPrefs.setAction(ctx, t.g, a ?: NotchAction(ActionType.NONE))
        } else {
            NotchPrefs.setAppAction(ctx, t.pkg, t.g, a)
        }
        NotchOverlay.refresh()
        Actions.toast(ctx, "${t.g.label}: ${a?.title() ?: "כמו בכל מקום"}")
        refresh++
    }

    val shotPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { refresh++ }
    val contactLauncher = rememberLauncherForActivityResult(PickPhone()) { uri ->
        val p = pendingContact
        pendingContact = null
        val picked = uri?.let {
            try {
                ctx.contentResolver.query(
                    it,
                    arrayOf(
                        android.provider.ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
            } catch (_: Exception) {
                null
            }
        }
        if (p != null && picked != null) save(p.first, NotchAction(p.second, picked.second, picked.first))
    }

    key(resumes, refresh) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("קיצורים", style = MaterialTheme.typography.headlineMedium)
                        Text("החור של המצלמה הוא כפתור", color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "הגדרות", tint = scheme.onSurfaceVariant)
                    }
                }

                // ---------- מצב ----------
                val serviceOn = AutoSend.allowed(ctx)
                if (!serviceOn) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(scheme.errorContainer)
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text("החור של המצלמה עוד לא פעיל", style = MaterialTheme.typography.titleMedium, color = scheme.onErrorContainer)
                        Text(
                            "צריך להפעיל את \"קיצורים: החור של המצלמה\" בנגישות.",
                            color = scheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                            Text("להפעיל")
                        }
                    }
                } else {
                    val set = Gesture.entries.count { NotchPrefs.action(ctx, it).type != ActionType.NONE }
                    val on = NotchPrefs.enabled(ctx)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // תווית מצב: "● פעיל · 4 תנועות מוגדרות"
                        Text(
                            if (on) "● פעיל · $set תנועות מוגדרות" else "○ כבוי",
                            Modifier
                                .clip(RoundedCornerShape(14.dp))
                                .background(if (on) Neon.Green.copy(alpha = 0.12f) else scheme.surfaceVariant)
                                .border(1.dp, if (on) Neon.Green.copy(alpha = 0.35f) else scheme.outline, RoundedCornerShape(14.dp))
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            color = if (on) Neon.Green else scheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Spacer(Modifier.weight(1f))
                        Switch(checked = on, onCheckedChange = {
                            NotchPrefs.setEnabled(ctx, it)
                            NotchOverlay.refresh()
                            refresh++
                        })
                    }
                }

                // ---------- תנועות: כרטיסים שתיים בשורה ----------
                Card {
                    Text("מה כל נגיעה בחור עושה", style = MaterialTheme.typography.titleMedium)
                    Gesture.entries.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { g ->
                                val a = NotchPrefs.action(ctx, g)
                                val none = a.type == ActionType.NONE
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .clip(RoundedCornerShape(16.dp))
                                        .background(Neon.Tile)
                                        .border(
                                            1.dp,
                                            if (none) Neon.Line else Neon.Green.copy(alpha = 0.35f),
                                            RoundedCornerShape(16.dp),
                                        )
                                        .clickable { picking = Target(g, null) }
                                        .padding(12.dp),
                                    verticalArrangement = Arrangement.spacedBy(3.dp),
                                ) {
                                    Text(g.label, style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant)
                                    Text(
                                        if (none) "🚫 כבוי" else a.title(),
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = if (none) scheme.onSurfaceVariant else Neon.GreenSoft,
                                        maxLines = 2,
                                    )
                                }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }

                // ---------- הטבעת ----------
                Card {
                    Text("הטבעת סביב המצלמה", style = MaterialTheme.typography.titleMedium)
                    SwitchLine("🔋 טבעת סוללה (מראה כמה סוללה נשארה)", NotchPrefs.ringOn(ctx)) {
                        NotchPrefs.setRingOn(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    // האפשרויות של הטבעת מופיעות רק כשהיא דלוקה
                    if (NotchPrefs.ringOn(ctx)) {
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SwitchLine("להראות רק בזמן טעינה", NotchPrefs.onlyCharging(ctx)) {
                                NotchPrefs.setOnlyCharging(ctx, it); NotchOverlay.refresh(); refresh++
                            }
                            SwitchLine("נקודה מסתובבת בזמן טעינה", NotchPrefs.chargeAnim(ctx)) {
                                NotchPrefs.setChargeAnim(ctx, it); NotchOverlay.refresh(); refresh++
                            }
                        }
                    }
                    HorizontalDivider(color = Neon.Line)
                    SwitchLine("💬 נורת התראות (מהבהבת בצבע של האפליקציה)", NotchPrefs.notifyLight(ctx)) {
                        NotchPrefs.setNotifyLight(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    if (NotchPrefs.notifyLight(ctx) && !Driving.listenerAllowed(ctx)) {
                        TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                            Text("לנורת ההתראות צריך גישה להתראות ←")
                        }
                    }
                    if (NotchPrefs.notifyLight(ctx)) {
                        // נכבית לבד אחרי זמן שהמסך דלוק; כשהמסך כבוי היא מחכה
                        Text(
                            "⏱️ נכבית לבד אחרי (רק כשהמסך דלוק):",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        Row(Modifier.padding(start = 8.dp)) {
                            listOf(10 to "10 שנ׳", 30 to "30 שנ׳", 60 to "דקה", 0 to "עד שקוראים").forEach { (secs, name) ->
                                val on = NotchPrefs.pulseSeconds(ctx) == secs
                                TextButton(onClick = { NotchPrefs.setPulseSeconds(ctx, secs); refresh++ }) {
                                    Text(name, color = if (on) Neon.GreenSoft else scheme.onSurfaceVariant, fontWeight = if (on) FontWeight.Bold else null)
                                }
                            }
                        }
                        SwitchLine("👆 הקשה על העיגול הדלוק מראה את ההודעה", NotchPrefs.ringTapMsg(ctx)) {
                            NotchPrefs.setRingTapMsg(ctx, it); NotchOverlay.refresh(); refresh++
                        }
                    }
                    HorizontalDivider(color = Neon.Line)
                    SwitchLine("🔊 עוצמת קול (הטבעת מתמלאת כשמשנים ווליום)", NotchPrefs.volumeRing(ctx)) {
                        NotchPrefs.setVolumeRing(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("🎵 התקדמות השיר (בזמן שמוזיקה מתנגנת)", NotchPrefs.musicRing(ctx)) {
                        NotchPrefs.setMusicRing(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    if (NotchPrefs.musicRing(ctx) && !Driving.listenerAllowed(ctx)) {
                        TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                            Text("לשיר צריך גישה להתראות ←")
                        }
                    }
                    SwitchLine("📞 שיחות (הבהוב בצלצול, וזמן השיחה)", NotchPrefs.callRing(ctx)) {
                        NotchPrefs.setCallRing(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                }

                // ---------- צבעים ----------
                Card {
                    Text("🎨 צבעים", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "לכל סוג התראה אפשר לבחור צבע מכל הקשת, בעיגול ובגלולה. הקש על שורה כדי לבחור.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    ColorLine("💬 הודעות", "msg", 0xFF25D366.toInt(), "לפי האפליקציה")
                    ColorLine("📞 שיחות", "call", 0xFF2AD49A.toInt())
                    ColorLine("🎵 מוזיקה", "music", 0xFFB388FF.toInt())
                    ColorLine("🧭 ניווט", "nav", 0xFF33CCFF.toInt())
                    ColorLine("⬇️ הורדות", "download", 0xFF4FB3FF.toInt())
                    ColorLine("📸 צילום מסך", "shot", 0xFF4FB3FF.toInt())
                    ColorLine("⚡ טעינה", "charge", 0xFF3ED18C.toInt())
                    ColorLine("🎧 אוזניות", "buds", 0xFFA0A8FF.toInt())
                    ColorLine("🔊 עוצמת קול", "volume", 0xFF4FB3FF.toInt())
                }

                // ---------- הגלולה החיה ----------
                Card {
                    Text("✨ גלולה חיה", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "הטבעת נמתחת לצדדים ומראה פרטים, והטקסט תמיד בצדדים של המצלמה ולא מאחוריה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    SwitchLine("להפעיל את הגלולה החיה", NotchPrefs.pill(ctx)) {
                        NotchPrefs.setPill(ctx, it); refresh++
                    }
                    // דוגמא: מראה את הגלולה עכשיו, בלי לחכות להודעה אמיתית
                    Text("👀 לראות דוגמא:", style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                        listOf("msg" to "💬 קצרה", "long" to "💬 ארוכה", "charge" to "⚡ מטען", "music" to "🎵 שיר").forEach { (type, name) ->
                            TextButton(onClick = {
                                if (!LivePill.demo(type)) Actions.toast(ctx, "קודם צריך להדליק את קיצורים בנגישות")
                            }) { Text(name) }
                        }
                    }
                    if (NotchPrefs.pill(ctx)) {
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SwitchLine("💬 הודעות (מי שלח ומה כתב)", NotchPrefs.pillMessages(ctx)) {
                                NotchPrefs.setPillMessages(ctx, it); refresh++
                            }
                            SwitchLine("📞 שיחות (ענה / דחה, וזמן השיחה)", NotchPrefs.pillCalls(ctx)) {
                                NotchPrefs.setPillCalls(ctx, it); refresh++
                            }
                            if (NotchPrefs.pillCalls(ctx) && android.os.Build.VERSION.SDK_INT >= 28 &&
                                ctx.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                TextButton(onClick = { shotPerm.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }) {
                                    Text("לכפתור ניתוק בגלולה צריך הרשאה ←")
                                }
                            }
                            SwitchLine("🎵 שיר חדש (עם התמונה של השיר)", NotchPrefs.pillMusic(ctx)) {
                                NotchPrefs.setPillMusic(ctx, it); refresh++
                            }
                            SwitchLine("💿 מוזיקה כל הזמן בעיגול (תמונת האלבום וגלי צליל)", NotchPrefs.pillMusicLive(ctx)) {
                                NotchPrefs.setPillMusicLive(ctx, it)
                                if (!it) LivePill.musicStopped()
                                refresh++
                            }
                            // הסמל של אפליקציית המוזיקה בשורת המצב: רק היא יכולה להסתיר אותו, אז פותחים ישר את המקום
                            TextButton(onClick = {
                                val pkg = NotchPrefs.mediaApp(ctx) ?: "com.spotify.music"
                                val ch = NotchPrefs.mediaChannel(ctx)
                                Actions.toast(ctx, "לכבות כאן את \"הפעלה\" / \"סמל בשורת המצב\". המוזיקה ממשיכה לעבוד")
                                val i = if (ch != null && android.os.Build.VERSION.SDK_INT >= 26) {
                                    Intent(android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                                        .putExtra(android.provider.Settings.EXTRA_CHANNEL_ID, ch)
                                } else {
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                                }
                                runCatching { ctx.startActivity(i) }.onFailure {
                                    Actions.toast(ctx, "לא הצלחתי לפתוח את ההגדרות")
                                }
                            }) { Text("🙈 להסתיר את סמל המוזיקה משורת המצב ←") }
                            SwitchLine("⚡ חיבור מטען", NotchPrefs.pillCharging(ctx)) {
                                NotchPrefs.setPillCharging(ctx, it); refresh++
                            }
                            SwitchLine("🧭 ניווט (וויז / גוגל מפות: הפנייה הבאה והמרחק)", NotchPrefs.pillNav(ctx)) {
                                NotchPrefs.setPillNav(ctx, it); refresh++
                            }
                            SwitchLine("🎧 אוזניות התחברו (שם וסוללה)", NotchPrefs.pillEarbuds(ctx)) {
                                NotchPrefs.setPillEarbuds(ctx, it); refresh++
                            }
                            SwitchLine("⬇️ הורדות (הטבעת מתמלאת, ובסוף \"הורד ✓\")", NotchPrefs.pillDownloads(ctx)) {
                                NotchPrefs.setPillDownloads(ctx, it); NotchOverlay.refresh(); refresh++
                            }
                            SwitchLine("📸 צילום מסך (תמונה קטנה ו\"נשמר\")", NotchPrefs.pillShots(ctx) && Screenshots.allowed(ctx)) {
                                NotchPrefs.setPillShots(ctx, it)
                                if (it && !Screenshots.allowed(ctx)) shotPerm.launch(Screenshots.permission)
                                refresh++
                            }
                        }
                        if (!Driving.listenerAllowed(ctx)) {
                            TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                                Text("להודעות, שיחות ושירים צריך גישה להתראות ←")
                            }
                        }
                        // ההתראה הקופצת של המערכת: אי אפשר להסתיר אותה מבחוץ, אבל אפשר לכבות אותה בכל אפליקציה
                        Text(
                            "רואים שתי התראות (של המערכת ושל הגלולה)? מכבים את \"חלון קופץ\" באפליקציה, ונשארת רק הגלולה.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        val openPopups = { pkg: String ->
                            Actions.toast(ctx, "התראות ← לכבות \"חלון קופץ\" / \"הצג כחלון קופץ\"")
                            try {
                                ctx.startActivity(
                                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                                )
                            } catch (_: Exception) {
                                Actions.toast(ctx, "לא הצלחתי לפתוח את ההגדרות")
                            }
                        }
                        val chatApps = listOf(
                            "com.whatsapp" to "וואטסאפ",
                            "com.whatsapp.w4b" to "וואטסאפ עסקי",
                            "org.telegram.messenger" to "טלגרם",
                            "com.samsung.android.messaging" to "הודעות",
                            "com.google.android.apps.messaging" to "הודעות",
                            "com.google.android.gm" to "ג'ימייל",
                            "com.facebook.orca" to "מסנג'ר",
                            "com.instagram.android" to "אינסטגרם",
                        ).filter { ctx.packageManager.getLaunchIntentForPackage(it.first) != null }
                        chatApps.forEach { (pkg, name) ->
                            TextButton(onClick = { openPopups(pkg) }) { Text("לכבות חלון קופץ של $name ←") }
                        }
                        TextButton(onClick = { pickingPopupApp = true }) { Text("אפליקציה אחרת… ←") }
                    }
                }

                // ---------- התנהגות ----------
                Card {
                    Text("התנהגות", style = MaterialTheme.typography.titleMedium)
                    SwitchLine("📳 רטט קטן בכל נגיעה בחור", NotchPrefs.haptic(ctx)) {
                        NotchPrefs.setHaptic(ctx, it); refresh++
                    }
                    SwitchLine("🙈 להסתיר במסך מלא (סרטון, משחק, תמונה)", NotchPrefs.hideFullscreen(ctx)) {
                        NotchPrefs.setHideFullscreen(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("🔒 לעבוד גם במסך הנעילה (טבעת, התראות וגלולה)", NotchPrefs.onLockScreen(ctx)) {
                        NotchPrefs.setOnLockScreen(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    // קיצורים כאפליקציית הטלפון: כל השיחות במסך הניאון. כבוי כברירת מחדל
                    SwitchLine("📞 מסך שיחה בניאון (קיצורים כאפליקציית הטלפון)", Calls.isDefault(ctx)) { on ->
                        if (on) {
                            // קודם תצוגה מקדימה של מסך השיחה, ומשם "להפעיל"
                            ctx.startActivity(Intent(ctx, CallActivity::class.java).putExtra(CallActivity.PREVIEW, true))
                        } else {
                            Actions.toast(ctx, "בחר שוב את \"טלפון\" של סמסונג באפליקציית השיחות")
                            Calls.openDefaultApps(ctx)
                        }
                        refresh++
                    }
                    Text(
                        "סמסונג לא עובד כשהוא לא אפליקציית הטלפון, אז לקיצורים יש חייגן משלו: מקלדת, שיחות אחרונות, אנשי קשר ומועדפים.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                    if (Calls.isDefault(ctx)) {
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // קיצור "טלפון" ווידג'ט מועדפים במסך הבית
                            SwitchLine("📱 קיצור \"טלפון\" ווידג'ט מועדפים במסך הבית", NotchPrefs.dialerHome(ctx)) { on ->
                                NotchPrefs.setDialerHome(ctx, on)
                                if (on) DialerHome.install(ctx.applicationContext) else DialerHome.remove(ctx.applicationContext)
                                refresh++
                            }
                            TextButton(onClick = { ctx.startActivity(Intent(ctx, DialActivity::class.java)) }) {
                                Text("לפתוח את החייגן ←")
                            }
                        }
                    }
                    // נגן: הקשה על העיגול כשמתנגן שיר
                    SwitchLine("🎵 נגן בהקשה על העיגול כשמתנגן שיר", NotchPrefs.musicTap(ctx)) {
                        NotchPrefs.setMusicTap(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    if (NotchPrefs.musicTap(ctx)) {
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SwitchLine("פס התקדמות וזמן", NotchPrefs.playerProgress(ctx)) {
                                NotchPrefs.setPlayerProgress(ctx, it); refresh++
                            }
                            SwitchLine("שיר קודם / הבא", NotchPrefs.playerSkip(ctx)) {
                                NotchPrefs.setPlayerSkip(ctx, it); refresh++
                            }
                            SwitchLine("תמונת האלבום", NotchPrefs.playerCover(ctx)) {
                                NotchPrefs.setPlayerCover(ctx, it); refresh++
                            }
                        }
                        if (!Driving.listenerAllowed(ctx)) {
                            TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                                Text("לנגן צריך גישה להתראות ←")
                            }
                        }
                    }
                    // אפליקציות שבהן קיצורים לא עובד בכלל: בלי טבעת, בלי נגיעות ובלי גלולה (שיחה נכנסת כן)
                    Text("🚫 כבוי באפליקציות האלה", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        "בתוכן אין טבעת, אין נגיעה בחור ואין גלולה (חוץ משיחה נכנסת).",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    NotchPrefs.offApps(ctx).forEach { (pkg, name) ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("📱 $name", Modifier.weight(1f))
                            TextButton(onClick = {
                                NotchPrefs.removeOffApp(ctx, pkg); NotchOverlay.refresh(); refresh++
                            }) { Text("להסיר") }
                        }
                    }
                    TextButton(onClick = { addingOffApp = true }) { Text("+ הוספת אפליקציה") }
                }

                // ---------- תנועות לפי אפליקציה ----------
                Card {
                    Text("תנועות לפי אפליקציה", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "בתוך אפליקציה מסוימת התנועות יכולות לעשות משהו אחר. למשל ביוטיוב הקשה = השהיה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    NotchPrefs.apps(ctx).forEach { (pkg, name) ->
                        val count = Gesture.entries.count { NotchPrefs.appAction(ctx, pkg, it) != null }
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { editingApp = pkg to name }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("📱 $name", Modifier.weight(1f))
                            Text(
                                if (count == 0) "כמו בכל מקום" else "$count תנועות משלה",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (count == 0) scheme.onSurfaceVariant else Neon.GreenSoft,
                            )
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null, tint = scheme.onSurfaceVariant)
                        }
                    }
                    TextButton(onClick = { addingApp = true }) { Text("+ הוספת אפליקציה") }
                }

                // ---------- גודל ומיקום (נפתח בלחיצה) ----------
                if (showPos) {
                    Card {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("📐 גודל ומיקום", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            TextButton(onClick = { showPos = false }) { Text("סגירה") }
                        }
                        Text(
                            "העיגול הוורוד למעלה מראה איפה הטבעת יושבת. מזיזים, והוא זז תוך כדי.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                        )
                        PrefSlider("גודל הטבעת", NotchPrefs.size(ctx), -6f..14f) { NotchPrefs.setSize(ctx, it) }
                        PrefSlider("עובי", NotchPrefs.thickness(ctx), 0.8f..4f) { NotchPrefs.setThickness(ctx, it) }
                        PrefSlider("ימינה / שמאלה", NotchPrefs.offsetX(ctx), -40f..40f) { NotchPrefs.setOffsetX(ctx, it) }
                        PrefSlider("למעלה / למטה", NotchPrefs.offsetY(ctx), -30f..30f) { NotchPrefs.setOffsetY(ctx, it) }
                        PrefSlider("אזור נגיעה", NotchPrefs.zoneExtra(ctx), 0f..40f) { NotchPrefs.setZoneExtra(ctx, it) }
                        SwitchLine("להראות את אזור הנגיעה", NotchPrefs.showZone(ctx)) {
                            NotchPrefs.setShowZone(ctx, it); NotchOverlay.refresh(); refresh++
                        }
                        TextButton(onClick = {
                            NotchPrefs.resetPosition(ctx)
                            NotchOverlay.refresh()
                            Actions.toast(ctx, "המיקום והגודל חזרו להתחלה")
                            refresh++
                        }) { Text("↺ איפוס מיקום וגודל") }
                    }
                }

                // ---------- כפתורים ----------
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NeonButton("📐", "גודל ומיקום", Modifier.weight(1f)) { showPos = !showPos }
                    NeonButton("⚙️", "אוטומציות", Modifier.weight(1f)) {
                        ctx.startActivity(Intent(ctx, AutomationsActivity::class.java))
                    }
                    NeonButton("🎙️", "קולי", Modifier.weight(1f)) {
                        ctx.startActivity(Intent(ctx, VoiceActivity::class.java))
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    // ---------- חלונות בחירה ----------
    // אפליקציה שבה קיצורים יהיה כבוי
    if (addingOffApp) {
        AppPicker(onDismiss = { addingOffApp = false }) { name, pkg ->
            addingOffApp = false
            NotchPrefs.addOffApp(ctx, pkg, name)
            NotchOverlay.refresh()
            refresh++
        }
    }
    // לכבות חלון קופץ של אפליקציה אחרת: פותחים את הגדרות ההתראות שלה
    if (pickingPopupApp) {
        AppPicker(onDismiss = { pickingPopupApp = false }) { _, pkg ->
            pickingPopupApp = false
            Actions.toast(ctx, "התראות ← לכבות \"חלון קופץ\" / \"הצג כחלון קופץ\"")
            try {
                ctx.startActivity(
                    Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                        .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg)
                )
            } catch (_: Exception) {
                Actions.toast(ctx, "לא הצלחתי לפתוח את ההגדרות")
            }
        }
    }
    // אפליקציה חדשה ל"תנועות לפי אפליקציה"
    if (addingApp) {
        AppPicker(onDismiss = { addingApp = false }) { name, pkg ->
            addingApp = false
            NotchPrefs.addApp(ctx, pkg, name)
            editingApp = pkg to name
            refresh++
        }
    }
    // התנועות של אפליקציה מסוימת
    editingApp?.let { (pkg, name) ->
        AppGesturesDialog(
            pkg = pkg,
            name = name,
            version = refresh,
            onDismiss = { editingApp = null },
            onPick = { g -> picking = Target(g, pkg) },
            onRemove = {
                NotchPrefs.removeApp(ctx, pkg)
                editingApp = null
                NotchOverlay.refresh()
                refresh++
            },
        )
    }
    picking?.let { t ->
        ActionPicker(
            t.g,
            appName = t.pkg?.let { p -> NotchPrefs.apps(ctx).firstOrNull { it.first == p }?.second },
            onDismiss = { picking = null },
            onDefault = {
                picking = null
                save(t, null)
            },
        ) { type ->
            picking = null
            when (type.arg) {
                ArgKind.NONE -> save(t, NotchAction(type))
                ArgKind.APP -> pickingApp = t
                ArgKind.CONTACT -> {
                    pendingContact = t to type
                    contactLauncher.launch(null)
                }
                ArgKind.URL -> pickingUrl = t
            }
        }
    }
    pickingApp?.let { t ->
        AppPicker(onDismiss = { pickingApp = null }) { name, pkg ->
            pickingApp = null
            save(t, NotchAction(ActionType.APP, pkg, name))
        }
    }
    pickingUrl?.let { t ->
        UrlDialog(onDismiss = { pickingUrl = null }) { url ->
            pickingUrl = null
            save(t, NotchAction(ActionType.URL, url, url.removePrefix("https://").removePrefix("http://")))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Neon.Card)
            .border(1.dp, Neon.Line, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) { content() }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** מחוון שהשינוי שלו נראה על הטבעת תוך כדי הזזה (בזמן אמת) */
@Composable
private fun PrefSlider(label: String, initial: Float, range: ClosedFloatingPointRange<Float>, onSave: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.8f))
        Slider(
            value = v,
            onValueChange = {
                v = it
                onSave(it)
                NotchOverlay.live()
            },
            valueRange = range,
            modifier = Modifier.weight(1.6f),
        )
    }
}

@Composable
private fun Tile(icon: ImageVector, label: String, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(onClick = onClick)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, contentDescription = null, tint = scheme.onPrimaryContainer) }
        Text(label, textAlign = TextAlign.Center)
    }
}

/** רשימת כל הפעולות */
@Composable
private fun ActionPicker(
    g: Gesture,
    appName: String?,
    onDismiss: () -> Unit,
    onDefault: () -> Unit,
    onPick: (ActionType) -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    if (appName != null) "$appName · ${g.label}: מה לעשות?" else "${g.label}: מה לעשות?",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    // באפליקציה מסוימת: אפשר לחזור לפעולה הרגילה
                    if (appName != null) {
                        item {
                            Text(
                                "↩️  כמו בכל מקום",
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { onDefault() }
                                    .padding(horizontal = 20.dp, vertical = 12.dp),
                                color = Neon.GreenSoft,
                            )
                        }
                    }
                    items(ActionType.entries) { t ->
                        Text(
                            t.emoji + "  " + t.label,
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(t) }
                                .padding(horizontal = 20.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** התנועות של אפליקציה אחת: מה שונה בה מהרגיל */
@Composable
private fun AppGesturesDialog(
    pkg: String,
    name: String,
    version: Int,
    onDismiss: () -> Unit,
    onPick: (Gesture) -> Unit,
    onRemove: () -> Unit,
) {
    val ctx = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("📱 $name", style = MaterialTheme.typography.titleMedium)
                Text(
                    "מה כל נגיעה עושה כש$name פתוחה",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                // version: כדי שהחלון יתעדכן אחרי כל שינוי
                key(version) {
                    Gesture.entries.forEach { g ->
                        val a = NotchPrefs.appAction(ctx, pkg, g)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { onPick(g) }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.label, Modifier.weight(0.9f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(
                                a?.title() ?: "↩️ כמו בכל מקום",
                                Modifier.weight(1.4f),
                                color = if (a != null) Neon.GreenSoft else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Row {
                    TextButton(onClick = onDismiss) { Text("סיום") }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onRemove) { Text("להסיר את האפליקציה", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

/** תנועה בכל מקום (pkg = null), או תנועה באפליקציה מסוימת */
private data class Target(val g: Gesture, val pkg: String?)

/** רשימת האפליקציות שבטלפון */
@Composable
private fun AppPicker(onDismiss: () -> Unit, onPick: (String, String) -> Unit) {
    val ctx = LocalContext.current
    val apps = remember {
        val pm = ctx.packageManager
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it.loadLabel(pm).toString() to it.activityInfo.packageName }
            .distinctBy { it.second }
            .sortedBy { it.first }
    }
    var filter by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("איזו אפליקציה לפתוח?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = filter,
                    onValueChange = { filter = it },
                    placeholder = { Text("חיפוש") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                LazyColumn(Modifier.heightIn(max = 420.dp)) {
                    items(apps.filter { filter.isBlank() || it.first.contains(filter, ignoreCase = true) }) { (name, pkg) ->
                        Text(
                            "📱  $name",
                            Modifier
                                .fillMaxWidth()
                                .clickable { onPick(name, pkg) }
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

/** כתובת של אתר */
@Composable
private fun UrlDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var url by remember { mutableStateOf("") }
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("איזה אתר לפתוח?", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    placeholder = { Text("ynet.co.il") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (url.isNotBlank()) onSave(url.trim()) }) { Text("שמירה") }
                    TextButton(onClick = onDismiss) { Text("ביטול") }
                }
            }
        }
    }
}

/** כפתור בסגנון הזוהר: סמל ומילה, מסגרת דקה */
@Composable
private fun NeonButton(emoji: String, label: String, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Neon.Tile)
            .border(1.dp, Neon.Line, RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(emoji)
        Text(label, color = Neon.GreenSoft, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
    }
}

/** שורת צבע: הקשה פותחת פס של כל הקשת; נגיעה או גרירה עליו בוחרת צבע */
@Composable
private fun ColorLine(label: String, type: String, default: Int, defaultName: String = "רגיל") {
    val ctx = LocalContext.current
    var open by remember { mutableStateOf(false) }
    var chosen by remember { mutableIntStateOf(NotchPrefs.customColor(ctx, type) ?: 0) }
    val shown = if (chosen != 0) chosen else default
    fun save(c: Int) {
        chosen = c
        NotchPrefs.setCustomColor(ctx, type, c)
        NotchOverlay.refresh()
    }
    fun preview() {
        when (type) {
            "msg" -> LivePill.demo("msg")
            "music" -> LivePill.demo("music")
            "charge" -> LivePill.demo("charge")
        }
    }
    Column {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(12.dp))
                .clickable { open = !open }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(label, Modifier.weight(1f))
            Text(
                if (chosen == 0) defaultName else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(8.dp))
            Box(
                Modifier
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(androidx.compose.ui.graphics.Color(shown))
                    .border(1.dp, androidx.compose.ui.graphics.Color.White.copy(alpha = 0.4f), CircleShape),
            )
        }
        if (open) {
            val hues = (0..12).map { androidx.compose.ui.graphics.Color.hsv(it * 30f % 360f, 0.75f, 1f) }
            fun pick(x: Float, w: Int) {
                val f = (x / w.coerceAtLeast(1)).coerceIn(0f, 1f)
                save(android.graphics.Color.HSVToColor(floatArrayOf(f * 360f % 360f, 0.75f, 1f)))
            }
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .clip(RoundedCornerShape(17.dp))
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(hues))
                    .pointerInput(Unit) {
                        detectTapGestures { pick(it.x, size.width); preview() }
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(onDragEnd = { preview() }) { ch, _ -> pick(ch.position.x, size.width) }
                    },
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = { save(android.graphics.Color.WHITE); preview() }) { Text("⚪ לבן") }
                TextButton(onClick = { save(0); preview() }) { Text("↩️ $defaultName") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { open = false }) { Text("סגור") }
            }
        }
    }
}
