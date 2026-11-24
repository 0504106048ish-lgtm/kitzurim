package com.kitzurim.app

import android.content.Intent
import android.os.Bundle
import androidx.compose.animation.togetherWith
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/** המסך הראשי: החור של המצלמה. מה כל תנועה עושה, איך הטבעת נראית, וכיוון המיקום */
class HomeActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
        // העיגול הוורוד לכיוון מופיע רק בדף "עוד" (גודל ומיקום), ראו HomeScreen

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
    // המיקום בגלילה של כל דף נשמר מחוץ לבנייה מחדש (key למטה), כדי שהדלקת מתג לא תקפיץ לראש הדף
    val pageScroll = remember { mutableMapOf<String?, androidx.compose.foundation.ScrollState>() }
    val scheme = MaterialTheme.colorScheme

    // בחירת פעולה: לתנועה בכל מקום (pkg = null), או לתנועה באפליקציה מסוימת
    var picking by remember { mutableStateOf<Target?>(null) }
    var pickingApp by remember { mutableStateOf<Target?>(null) }
    var pickingUrl by remember { mutableStateOf<Target?>(null) }
    var addingApp by remember { mutableStateOf(false) }
    var pickingPopupApp by remember { mutableStateOf(false) }
    var addingOffApp by remember { mutableStateOf(false) }
    var editingApp by remember { mutableStateOf<Pair<String, String>?>(null) }
    // התפריט: null = מסך הבית עם האריחים; אחרת הדף של נושא אחד
    var openPage by remember { mutableStateOf<String?>(null) }
    // העיגול הוורוד (לכיוון המקום והגודל) רק בדף "עוד", שם מכוונים. בשאר הדפים רואים את הטבעת האמיתית,
    // כך שכשמכבים אותה היא באמת נעלמת
    androidx.compose.runtime.LaunchedEffect(openPage, resumes) { NotchOverlay.setPreview(openPage == "more") }
    var query by remember { mutableStateOf("") }
    androidx.activity.compose.BackHandler(enabled = openPage != null) { pageScroll.remove(openPage); openPage = null }
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
    // הקלטת שיחות: מבקשים את המיקרופון, ובודקים אם הטלפון בכלל מאפשר להקליט שיחות
    val recPerm = rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok ->
        // הכפתור מופיע תמיד אחרי האישור; אם הטלפון לא נותן את קול השיחה עצמו, מקליטים דרך שירות הנגישות
        CallRecorder.setEnabled(ctx, ok)
        if (ok) Actions.toast(ctx, CallRecorder.howText(ctx))
        refresh++
    }
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
            // מעבר בין דפים: הדף החדש נכנס בהחלקה מהצד ובהופעה רכה, והקודם זז קצת ונעלם.
            // בעברית "קדימה" זה שמאלה, ו"חזרה" ימינה
            androidx.compose.animation.AnimatedContent(
                targetState = openPage,
                transitionSpec = {
                    val ease = androidx.compose.animation.core.FastOutSlowInEasing
                    val forward = targetState != null
                    val inFrom = if (forward) -1 else 1
                    (
                        androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(340, easing = ease)) { it * inFrom / 3 } +
                            androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(260, delayMillis = 40)) +
                            androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(340, easing = ease), initialScale = 0.96f)
                        ).togetherWith(
                        androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(300, easing = ease)) { -it * inFrom / 5 } +
                            androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(180)) +
                            androidx.compose.animation.scaleOut(androidx.compose.animation.core.tween(300, easing = ease), targetScale = 0.97f),
                    )
                },
                label = "page",
            ) { page ->
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(pageScroll.getOrPut(page) { androidx.compose.foundation.ScrollState(0) })
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val current = HomePages.firstOrNull { it.id == page }
                if (current != null) {
                    // דף של נושא, כמו באייפון: "‹ קיצורים" קטן לחזרה, ומתחת שם הדף בגדול
                    Column {
                        Row(
                            Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { pageScroll.remove(openPage); openPage = null }
                                .padding(end = 10.dp, top = 4.dp, bottom = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // חץ חזרה (בעברית הוא מצביע ימינה)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "חזרה", tint = Neon.GreenSoft)
                            Text("קיצורים", color = Neon.GreenSoft, fontSize = 16.sp)
                        }
                        Text(
                            "${current.emoji} ${current.title}",
                            Modifier.padding(top = 4.dp, start = 4.dp),
                            fontSize = 30.sp,
                            fontWeight = FontWeight.ExtraBold,
                        )
                    }
                    // תצוגה חיה: רואים מיד את מה שמדליקים ומכבים בדף
                    LivePreview(current.id)
                } else Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("קיצורים", style = MaterialTheme.typography.headlineMedium)
                        Text("החור של המצלמה הוא כפתור", color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "הגדרות", tint = scheme.onSurfaceVariant)
                    }
                }

                // ---------- מצב (רק במסך הבית) ----------
                val serviceOn = AutoSend.allowed(ctx)
                if (page != null) {
                    // בדף של נושא לא מראים את המצב
                } else if (!serviceOn) {
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
                    // "חור חי": החור מדגים בעצמו מה הגלולה עושה, ומתחת המצב והמתג הראשי
                    LiveHoleHero(
                        on = on,
                        gestures = set,
                        pillOn = NotchPrefs.pill(ctx),
                        onToggle = {
                            NotchPrefs.setEnabled(ctx, it)
                            NotchOverlay.refresh()
                            refresh++
                        },
                        onOpen = { openPage = "pill" },
                    )
                    // מתגים מהירים: בלי להיכנס לדף
                    QuickToggles(ctx) { refresh++ }
                }

                // ---------- מסך הבית: חיפוש ואריחים ----------
                if (page == null) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("🔍 חיפוש הגדרה…") },
                        shape = RoundedCornerShape(16.dp),
                    )
                    if (query.isBlank()) {
                        // שני אריחים בשורה; כל אריח פותח דף קצר רק עם ההגדרות שלו
                        HomePages.chunked(2).forEach { pair ->
                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                pair.forEach { p ->
                                    Column(
                                        Modifier
                                            .weight(1f)
                                            .heightIn(min = 92.dp)
                                            .clip(RoundedCornerShape(18.dp))
                                            .background(Neon.Tile)
                                            .border(1.dp, Neon.Line, RoundedCornerShape(18.dp))
                                            .clickable { openPage = p.id }
                                            .padding(14.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        PageBadge(p.id)
                                        Spacer(Modifier.height(4.dp))
                                        Text(p.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                        Text(p.sub, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant, maxLines = 1)
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    } else {
                        // תוצאות חיפוש: כל הגדרה שמתאימה, ולחיצה פותחת את הדף שלה
                        val q = query.trim()
                        val hits = HomePages.flatMap { p -> p.items.filter { it.contains(q) || p.title.contains(q) }.map { p to it } }
                        if (hits.isEmpty()) {
                            Text("לא נמצא כלום עם \"$q\"", color = scheme.onSurfaceVariant)
                        }
                        hits.forEach { (p, item) ->
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(Neon.Tile)
                                    .clickable { openPage = p.id; query = "" }
                                    .padding(horizontal = 14.dp, vertical = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(item, Modifier.weight(1f))
                                Text("${p.emoji} ${p.title}", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                            }
                        }
                    }
                }

                // ---------- תנועות: כרטיסים שתיים בשורה ----------
                if (page == "touch") Card {
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
                if (page == "ring") Card {
                    Text("הטבעת סביב המצלמה", style = MaterialTheme.typography.titleMedium)
                    SwitchLine("🔋 טבעת סוללה (מראה כמה סוללה נשארה)", NotchPrefs.ringOn(ctx), demo = { NotchOverlay.demoRing("battery") }) {
                        NotchPrefs.setRingOn(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    // האפשרויות של הטבעת מופיעות רק כשהיא דלוקה
                    if (NotchPrefs.ringOn(ctx)) {
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            SwitchLine("להראות רק בזמן טעינה", NotchPrefs.onlyCharging(ctx), demo = { NotchOverlay.demoRing("charging") }) {
                                NotchPrefs.setOnlyCharging(ctx, it); NotchOverlay.refresh(); refresh++
                            }
                            SwitchLine("נקודה מסתובבת בזמן טעינה", NotchPrefs.chargeAnim(ctx), demo = { NotchOverlay.demoRing("spin") }) {
                                NotchPrefs.setChargeAnim(ctx, it); NotchOverlay.refresh(); refresh++
                            }
                        }
                    }
                    HorizontalDivider(color = Neon.Line)
                    SwitchLine("💬 נורת התראות (מהבהבת בצבע של האפליקציה)", NotchPrefs.notifyLight(ctx), demo = { NotchOverlay.demoRing("pulse") }) {
                        NotchPrefs.setNotifyLight(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    // אותו מתג כמו בגלולה החיה: גם כאן, ליד הנורה, כדי שיהיה קל למצוא
                    SwitchLine("✨ קצוות המסך זוהרים כשמגיעה הודעה", NotchPrefs.edgeGlow(ctx), demo = { LivePill.edgeGlow(0xFF25D366.toInt()) }) {
                        NotchPrefs.setEdgeGlow(ctx, it); refresh++
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
                        Text(
                            "כשמגיעה הודעה, העיגול סביב המצלמה נדלק בצבע של האפליקציה. פספסת את ההודעה? כשהמתג הזה דלוק, הקשה על העיגול הדלוק מראה אותה שוב.",
                            style = MaterialTheme.typography.bodySmall,
                            color = scheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 16.dp),
                        )
                        PillLine("👆 הקשה על העיגול הדלוק מראה שוב את ההודעה שפספסת", NotchPrefs.ringTapMsg(ctx), "long") {
                            NotchPrefs.setRingTapMsg(ctx, it); NotchOverlay.refresh(); refresh++
                        }
                    }
                    HorizontalDivider(color = Neon.Line)
                    SwitchLine("🔊 עוצמת קול (הטבעת מתמלאת כשמשנים ווליום)", NotchPrefs.volumeRing(ctx), demo = { NotchOverlay.demoRing("volume") }) {
                        NotchPrefs.setVolumeRing(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("🎵 התקדמות השיר (בזמן שמוזיקה מתנגנת)", NotchPrefs.musicRing(ctx), demo = { NotchOverlay.demoRing("music") }) {
                        NotchPrefs.setMusicRing(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    if (NotchPrefs.musicRing(ctx) && !Driving.listenerAllowed(ctx)) {
                        TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                            Text("לשיר צריך גישה להתראות ←")
                        }
                    }
                    SwitchLine("📞 שיחות (הבהוב בצלצול, וזמן השיחה)", NotchPrefs.callRing(ctx), demo = { NotchOverlay.demoRing("call") }) {
                        NotchPrefs.setCallRing(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                }

                // ---------- צבעים ----------
                if (page == "colors") Card {
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
                if (page == "pill") Card {
                    Text(
                        "הטבעת נמתחת לצדדים ומראה פרטים, והטקסט תמיד בצדדים של המצלמה ולא מאחוריה. ▶ ליד כל אפשרות מראה איך היא נראית, עכשיו למעלה.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    SwitchLine("להפעיל את הגלולה החיה", NotchPrefs.pill(ctx), demo = { LivePill.demo("msg") }) {
                        NotchPrefs.setPill(ctx, it); refresh++
                    }
                    if (NotchPrefs.pill(ctx)) {
                        SwitchLine("💧 טיפה נוטפת מהחור לפני שהגלולה נפתחת", NotchPrefs.pillDrip(ctx), demo = { LivePill.demo("msg") }) {
                            NotchPrefs.setPillDrip(ctx, it); refresh++
                        }
                        SwitchLine("⬛ מסגרת צבעונית לגלולה (כבוי: הגלולה שחורה לגמרי, בלי מסגרת, ונראית כמו המצלמה עצמה)", NotchPrefs.pillFrame(ctx), demo = { LivePill.demo("msg") }) {
                            NotchPrefs.setPillFrame(ctx, it); refresh++
                            LivePill.demo("msg")
                        }
                        Column(Modifier.padding(start = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            // מסודר בקבוצות קטנות: הודעות, שיחות, מוזיקה, טעינה, ועוד
                            PillGroup("💬 הודעות")
                            PillLine("💬 הודעות (מי שלח ומה כתב)", NotchPrefs.pillMessages(ctx), "long") {
                                NotchPrefs.setPillMessages(ctx, it); refresh++
                            }
                            if (NotchPrefs.pillMessages(ctx)) Column(Modifier.padding(start = 12.dp)) {
                                SwitchLine("🖼️ התמונה של מי ששלח (ותמונה קטנה ממה שנשלח)", NotchPrefs.pillPhoto(ctx), demo = { LivePill.demo("photo") }) {
                                    NotchPrefs.setPillPhoto(ctx, it); refresh++
                                }
                                if (NotchPrefs.pillPhoto(ctx)) SwitchLine("🎨 צבע המסגרת לפי התמונה", NotchPrefs.pillPhotoColor(ctx), demo = { LivePill.demo("photo") }) {
                                    NotchPrefs.setPillPhotoColor(ctx, it); refresh++
                                }
                                SwitchLine("✨ קצוות המסך זוהרים", NotchPrefs.edgeGlow(ctx), demo = { LivePill.edgeGlow(0xFF25D366.toInt()) }) {
                                    NotchPrefs.setEdgeGlow(ctx, it); refresh++
                                }
                            }
                            SwitchLine("🌙 הודעה כשהמסך כבוי (המסך נדלק לכמה שניות, שחור ובבהירות נמוכה, רק עם הגלולה והשעה, ונכבה לבד. לא בכיס ולא כשהטלפון הפוך)", NotchPrefs.aodMsg(ctx), demo = { Aod.demo(ctx) }) {
                                NotchPrefs.setAodMsg(ctx, it); refresh++
                            }
                            PillLine("🔑 קוד אימות (מועתק לבד, ומוצג בגדול)", NotchPrefs.pillCodes(ctx), "code") {
                                NotchPrefs.setPillCodes(ctx, it); refresh++
                            }
                            PillLine("📋 העתקת טקסט (מה הועתק, והקשה משתפת)", NotchPrefs.pillClip(ctx), "clip") {
                                NotchPrefs.setPillClip(ctx, it); refresh++
                            }
                            PillGroup("📞 שיחות")
                            PillLine("📞 שיחות (ענה / דחה, וזמן השיחה)", NotchPrefs.pillCalls(ctx), "call") {
                                NotchPrefs.setPillCalls(ctx, it); refresh++
                            }
                            if (NotchPrefs.pillCalls(ctx) && android.os.Build.VERSION.SDK_INT >= 28 &&
                                ctx.checkSelfPermission(android.Manifest.permission.ANSWER_PHONE_CALLS) != android.content.pm.PackageManager.PERMISSION_GRANTED
                            ) {
                                TextButton(onClick = { shotPerm.launch(android.Manifest.permission.ANSWER_PHONE_CALLS) }) {
                                    Text("לכפתור ניתוק בגלולה צריך הרשאה ←")
                                }
                            }
                            PillGroup("🎵 מוזיקה")
                            PillLine("🎵 שיר חדש (עם התמונה של השיר)", NotchPrefs.pillMusic(ctx), "song") {
                                NotchPrefs.setPillMusic(ctx, it); refresh++
                            }
                            PillLine("💿 מוזיקה כל הזמן בעיגול (תמונת האלבום וגלי צליל)", NotchPrefs.pillMusicLive(ctx), "live") {
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
                            PillGroup("🔋 טעינה")
                            PillLine("🪫 מי אכל את הסוללה (כשהסוללה יורדת ל-15%: האפליקציות שהכי היו בשימוש היום, עם כפתור לעצור את הראשונה ומצב חיסכון)", LowBattery.on(ctx), "lowbat") {
                                LowBattery.setOn(ctx, it); refresh++
                                if (it && !LowBattery.hasAccess(ctx)) { Actions.toast(ctx, "לאשר לקיצורים לראות זמני שימוש"); LowBattery.openAccess(ctx) }
                            }
                            SwitchLine("💧 טעינה נוזלית (טיפה מהמצלמה כשמחברים מטען)", NotchPrefs.chargeFx(ctx), demo = { Battery.read(ctx, null).let { b -> ChargeFx.play(ctx, b.percent, b.minutesToFull, force = true) } }) {
                                NotchPrefs.setChargeFx(ctx, it); refresh++
                            }
                            if (NotchPrefs.chargeFx(ctx)) {
                                // הסגנון: שורה של כפתורים קטנים. הקשה בוחרת ומראה מיד איך זה נראה
                                Row(Modifier.padding(start = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ChargeFx.STYLES.forEachIndexed { i, name ->
                                        val sel = NotchPrefs.chargeStyle(ctx) == i
                                        Surface(
                                            shape = RoundedCornerShape(14.dp),
                                            color = if (sel) scheme.primary else scheme.surfaceVariant,
                                            modifier = Modifier.clickable {
                                                NotchPrefs.setChargeStyle(ctx, i); refresh++
                                                val b = Battery.read(ctx, ctx.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
                                                if (!ChargeFx.play(ctx, b.percent, b.minutesToFull, force = true)) Actions.toast(ctx, "צריך שקיצורים תהיה דלוקה בנגישות")
                                            },
                                        ) {
                                            Text(
                                                (if (sel) "✓ " else "") + name,
                                                Modifier.padding(horizontal = 12.dp, vertical = 7.dp),
                                                style = MaterialTheme.typography.labelLarge,
                                                color = if (sel) scheme.onPrimary else scheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                            // בלי טעינה נוזלית (או כשהיא לא יכולה להופיע): הגלולה הרגילה
                            PillLine("⚡ גלולת מטען (כשהטעינה הנוזלית כבויה)", NotchPrefs.pillCharging(ctx), "charge") {
                                NotchPrefs.setPillCharging(ctx, it); refresh++
                            }
                            PillGroup("📱 עוד")
                            PillLine("🧭 ניווט (וויז / גוגל מפות: הפנייה הבאה והמרחק)", NotchPrefs.pillNav(ctx), "nav") {
                                NotchPrefs.setPillNav(ctx, it); refresh++
                            }
                            PillLine("🎧 אוזניות התחברו (שם וסוללה)", NotchPrefs.pillEarbuds(ctx), "buds") {
                                NotchPrefs.setPillEarbuds(ctx, it); refresh++
                            }
                            PillLine("⬇️ הורדות (הטבעת מתמלאת, ובסוף \"הורד ✓\")", NotchPrefs.pillDownloads(ctx), "download") {
                                NotchPrefs.setPillDownloads(ctx, it); NotchOverlay.refresh(); refresh++
                            }
                            PillLine("📸 צילום מסך (תמונה קטנה ו\"נשמר\")", NotchPrefs.pillShots(ctx) && Screenshots.allowed(ctx), "shot") {
                                NotchPrefs.setPillShots(ctx, it)
                                if (it && !Screenshots.allowed(ctx)) shotPerm.launch(Screenshots.permission)
                                refresh++
                            }
                            SwitchLine("🔦 פנס דולק (הקשה מכבה)", NotchPrefs.pillTorch(ctx), demo = { LivePill.demo("torch") }) {
                                NotchPrefs.setPillTorch(ctx, it); refresh++
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

                // ---------- נגיעות: רטט ----------
                if (page == "touch") Card {
                    SwitchLine("📳 רטט קטן בכל נגיעה בחור", NotchPrefs.haptic(ctx), demo = { LivePill.explain("📳", "כל נגיעה בחור של המצלמה עושה רטט קטן, כדי להרגיש שהיא נקלטה") }) {
                        NotchPrefs.setHaptic(ctx, it); refresh++
                    }
                }

                // ---------- אפליקציות ומסכים ----------
                if (page == "apps") Card {
                    SwitchLine("🙈 להסתיר במסך מלא (סרטון, משחק, תמונה)", NotchPrefs.hideFullscreen(ctx), demo = { LivePill.explain("🙈", "בסרטון או משחק במסך מלא, הגלולה והטבעת לא מופיעות ולא מפריעות") }) {
                        NotchPrefs.setHideFullscreen(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("🔒 לעבוד גם במסך הנעילה (טבעת, התראות וגלולה)", NotchPrefs.onLockScreen(ctx), demo = { LivePill.explain("🔒", "גם כשהטלפון נעול: הטבעת, ההתראות והגלולה מופיעות") }) {
                        NotchPrefs.setOnLockScreen(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("🙃 הופכים את הטלפון על המסך = שקט (מרימים: הצלצול חוזר, ורואים מה הגיע)", NotchPrefs.faceDown(ctx), demo = { LivePill.showFaceUp("בזמן שהיה הפוך: 2 הודעות מאבא, שיחה מאמא") }) { on ->
                        NotchPrefs.setFaceDown(ctx, on)
                        FaceDown.update(ctx)
                        // שקט מלא צריך הרשאת "נא לא להפריע" (בלעדיה: רק רטט)
                        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                        if (on && nm?.isNotificationPolicyAccessGranted != true) {
                            Actions.toast(ctx, "להדליק את קיצורים כאן, כדי שהטלפון ישתתק לגמרי (בלי זה: רק רטט)")
                            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        }
                        refresh++
                    }
                }

                // ---------- שיחות ----------
                if (page == "calls") Card {
                    // קיצורים כאפליקציית הטלפון: כל השיחות במסך הניאון. כבוי כברירת מחדל
                    SwitchLine("📞 מסך שיחה בניאון (קיצורים כאפליקציית הטלפון)", Calls.isDefault(ctx), demo = { LivePill.explain("📞", "כשקיצורים היא אפליקציית הטלפון: מסך השיחה בעיצוב ניאון, עם תמונה, הקלטה ועוד") }) { on ->
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
                            SwitchLine("📱 קיצור \"טלפון\" ווידג'ט מועדפים במסך הבית", NotchPrefs.dialerHome(ctx), demo = { LivePill.explain("📱", "סמל של החייגן של קיצורים במסך הבית, לפתיחה מהירה") }) { on ->
                                NotchPrefs.setDialerHome(ctx, on)
                                if (on) DialerHome.install(ctx.applicationContext) else DialerHome.remove(ctx.applicationContext)
                                refresh++
                            }
                            // סידור הכפתורים במסך השיחה: כל אחד לפי מה שנוח לו
                            Text(
                                "✏️ סידור הכפתורים במסך השיחה (רמקול, השתקה, מקלדת…)",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { ctx.startActivity(android.content.Intent(ctx, CallButtonsActivity::class.java)) }
                                    .padding(vertical = 10.dp),
                            )
                            // מה קרה היום: מה קיצורים עשתה לבד, וזיהוי השיחות (מה זוהה ומה לא)
                            Text(
                                "🔎 מה קרה היום (זיהוי שיחות ועוד)",
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { ctx.startActivity(android.content.Intent(ctx, LogActivity::class.java)) }
                                    .padding(vertical = 10.dp),
                            )
                            // ספאם לפי Truecaller: הצלצול משתתק לבד
                            SwitchLine("🚫 ספאם משתתק לבד: כש-Truecaller או Me מסמנות שיחה כספאם, הצלצול נעצר", NotchPrefs.spamSilence(ctx), demo = { LivePill.explain("🚫", "מספר שמסומן כספאם ב-Truecaller: הצלצול נעצר לבד") }) { on ->
                                NotchPrefs.setSpamSilence(ctx, on)
                                refresh++
                            }
                            // הקלטת שיחות: רק בטלפון שמאפשר (נבדק כשמדליקים)
                            SwitchLine("🎙️ כפתור \"הקלט\" בשיחה", CallRecorder.enabled(ctx), demo = { LivePill.explain("🎙️", "כפתור במסך השיחה שמקליט את השיחה (רק בטלפונים שמאפשרים)") }) { on ->
                                if (!on) {
                                    CallRecorder.setEnabled(ctx, false)
                                    refresh++
                                } else if (CallRecorder.permitted(ctx)) {
                                    CallRecorder.setEnabled(ctx, true)
                                    Actions.toast(ctx, CallRecorder.howText(ctx))
                                    refresh++
                                } else {
                                    recPerm.launch(android.Manifest.permission.RECORD_AUDIO)
                                }
                            }
                            TextButton(onClick = { ctx.startActivity(Intent(ctx, DialActivity::class.java)) }) {
                                Text("לפתוח את החייגן ←")
                            }
                        }
                    }
                }

                // ---------- חסימת שיחות וואטסאפ (בדף שיחות) ----------
                if (page == "calls") Card {
                    PillGroup("🚫 חסימת שיחות וואטסאפ")
                    SwitchLine("🚫 לחסום שיחות וואטסאפ (בשקט: אצלך לא מצלצל, ואצלו ממשיך לצלצל כאילו לא ענית. הוא לא יודע שנחסם. כבוי: כל השיחות מגיעות כרגיל)", WaBlock.on(ctx), demo = { LivePill.explain("🚫", "שיחת וואטסאפ חסומה: אצלך לא מצלצל, ואצלו ממשיך לצלצל כאילו לא ענית") }) { on ->
                        WaBlock.setOn(ctx, on); refresh++
                        // השתקה מלאה צריכה הרשאת "נא לא להפריע" (בלעדיה: רק הצלצול מושתק)
                        val nm = ctx.getSystemService(android.app.NotificationManager::class.java)
                        if (on && nm?.isNotificationPolicyAccessGranted != true) {
                            Actions.toast(ctx, "להדליק את קיצורים כאן, כדי שגם הרטט ייעצר")
                            runCatching { ctx.startActivity(Intent(android.provider.Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS)) }
                        }
                    }
                    if (WaBlock.on(ctx)) {
                        SwitchLine("❓ מספרים שלא שמורים אצלך", WaBlock.unknown(ctx), demo = { LivePill.explain("❓", "שיחת וואטסאפ ממספר שלא שמור אצלך: נחסמת בשקט") }) { WaBlock.setUnknown(ctx, it); refresh++ }
                        SwitchLine("⚠️ ספאם (לפי Truecaller)", WaBlock.spam(ctx), demo = { LivePill.explain("⚠️", "שיחת וואטסאפ ממספר ש-Truecaller סימנה כספאם: נחסמת בשקט") }) { WaBlock.setSpam(ctx, it); refresh++ }
                        // מי שחסמת בעצמך (מהכרטיס של השיחה)
                        val blocked = WaBlock.list(ctx).sorted()
                        PillGroup("🚫 חסומים (${blocked.size})")
                        if (blocked.isEmpty()) Text(
                            "כדי לחסום מישהו מסוים: כשהוא מתקשר בוואטסאפ, לוחצים \"חסום\" בכרטיס של השיחה.",
                            color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall,
                        )
                        blocked.forEach { who ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text("🚫 $who", Modifier.weight(1f))
                                Text("בטל חסימה", Modifier.clip(RoundedCornerShape(10.dp)).clickable { WaBlock.remove(ctx, who); refresh++ }.padding(8.dp), color = Neon.GreenSoft, fontSize = 13.sp)
                            }
                        }
                        // כל השיחות שנחסמו: מי ומתי. "לאפשר" = ממנו השיחות יגיעו כרגיל
                        val log = WaBlock.log(ctx)
                        PillGroup("📋 שיחות שנחסמו (${log.size})")
                        if (log.isEmpty()) Text("עוד לא נחסמה אף שיחה.", color = scheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                        val fmt = remember { java.text.SimpleDateFormat("d/M HH:mm", java.util.Locale.getDefault()) }
                        log.take(30).forEach { (t, who) ->
                            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Column(Modifier.weight(1f)) {
                                    Text(who)
                                    Text(fmt.format(java.util.Date(t)), color = Neon.Muted, fontSize = 12.sp)
                                }
                                if (who !in WaBlock.allowed(ctx)) Text(
                                    "לאפשר",
                                    Modifier.clip(RoundedCornerShape(10.dp)).clickable { WaBlock.allow(ctx, who); Actions.toast(ctx, "$who: השיחות יגיעו כרגיל"); refresh++ }.padding(8.dp),
                                    color = Neon.GreenSoft, fontSize = 13.sp,
                                ) else Text("מאופשר ✓", color = Neon.Muted, fontSize = 12.sp)
                            }
                        }
                        if (log.isNotEmpty()) TextButton(onClick = { WaBlock.clearLog(ctx); refresh++ }) { Text("🧹 לנקות את הרשימה") }
                    }
                }

                // ---------- מוזיקה ----------
                if (page == "music") Card {
                    // נגן: הקשה על העיגול כשמתנגן שיר
                    Text(
                        "כשמתנגן שיר, הקשה על העיגול שסביב המצלמה פותחת למעלה נגן קטן: עצירה והמשך, ומה שתבחר כאן למטה. ▶ מראה איך הוא נראה (צריך שיתנגן שיר).",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    PillLine("🎵 נגן קטן בהקשה על העיגול", NotchPrefs.musicTap(ctx), "player") {
                        NotchPrefs.setMusicTap(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    if (NotchPrefs.musicTap(ctx)) {
                        Column(Modifier.padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            PillLine("⏱️ פס התקדמות וזמן (בנגן: פס שמראה איפה השיר נמצא, וכמה זמן עבר)", NotchPrefs.playerProgress(ctx), "player_progress") {
                                NotchPrefs.setPlayerProgress(ctx, it); refresh++
                            }
                            PillLine("⏭️ שיר קודם ושיר הבא (בנגן: כפתורים לדלג בין שירים)", NotchPrefs.playerSkip(ctx), "player_skip") {
                                NotchPrefs.setPlayerSkip(ctx, it); refresh++
                            }
                            PillLine("🖼️ תמונת השיר (בנגן: התמונה של השיר או האלבום)", NotchPrefs.playerCover(ctx), "player_cover") {
                                NotchPrefs.setPlayerCover(ctx, it); refresh++
                            }
                        }
                        if (!Driving.listenerAllowed(ctx)) {
                            TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                                Text("לנגן צריך גישה להתראות ←")
                            }
                        }
                    }
                }

                // ---------- אפליקציות שבהן קיצורים כבוי ----------
                if (page == "apps") Card {
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
                if (page == "touch") Card {
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

                // ---------- אנדרואיד אוטו (בדף "עוד") ----------
                if (page == "more") Card {
                    Text("🚗 אנדרואיד אוטו", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "כשהטלפון מחובר לרכב, הודעות מוואטסאפ (וטלגרם, מסנג'ר ואינסטגרם) קופצות במסך הרכב, נקראות בקול, ואפשר לענות בקול. גם כשהחלון הקופץ של וואטסאפ כבוי בטלפון.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    SwitchLine("להעביר הודעות למסך הרכב", NotchPrefs.carMirror(ctx), demo = { LivePill.explain("🚗", "מחוברים לאנדרואיד אוטו: הודעות מוואטסאפ קופצות במסך הרכב ונקראות בקול") }) {
                        NotchPrefs.setCarMirror(ctx, it); refresh++
                    }
                    // SMS: בדרך כלל מגיעות לרכב לבד. אם כיבית גם להן את החלון הקופץ, מדליקים כאן
                    if (NotchPrefs.carMirror(ctx)) {
                        Text("מאילו אפליקציות:", style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                        NotchPrefs.CAR_APPS
                            .filter { (pkg, _) -> ctx.packageManager.getLaunchIntentForPackage(pkg) != null }
                            .forEach { (pkg, name) ->
                                SwitchLine(name, NotchPrefs.carApp(ctx, pkg)) { NotchPrefs.setCarApp(ctx, pkg, it); refresh++ }
                            }
                    }
                    Text(
                        "פעם אחת: אנדרואיד אוטו מציג רק אפליקציות מחנות Play. כדי שיציג גם את קיצורים:\n1. פותחים את הגדרות אנדרואיד אוטו (בכפתור כאן)\n2. לוחצים 10 פעמים על \"גרסה\" למטה, ומאשרים\n3. בתפריט ⋮ למעלה ← הגדרות מפתח ← מסמנים \"מקורות לא ידועים\"",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    TextButton(onClick = {
                        val pkg = "com.google.android.projection.gearhead"
                        val i = ctx.packageManager.getLaunchIntentForPackage(pkg)
                            ?: Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$pkg"))
                        runCatching { ctx.startActivity(i) }.onFailure { Actions.toast(ctx, "אנדרואיד אוטו לא מותקן") }
                    }) { Text("לפתוח את הגדרות אנדרואיד אוטו ←") }
                }

                // ---------- גודל ומיקום (בדף "עוד") ----------
                if (page == "more") {
                    Card {
                        Text("📐 גודל ומיקום", style = MaterialTheme.typography.titleMedium)
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
                        // רוחב הגלולה: כמה מקום להשאיר לשעה ולסוללה (הפוך: ימינה = רחבה יותר)
                        PrefSlider("רוחב הגלולה (טקסט ארוך)", 100f - NotchPrefs.pillMargin(ctx), 10f..96f) {
                            NotchPrefs.setPillMargin(ctx, 100f - it)
                            LivePill.demo("charge")
                        }
                        SwitchLine("להראות את אזור הנגיעה", NotchPrefs.showZone(ctx), demo = { LivePill.explain("👆", "עיגול דק מראה בדיוק איפה לגעת כדי שהחור של המצלמה יגיב") }) {
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

                // ---------- כפתורים (בדף "עוד") ----------
                if (page == "more") Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    // קבוצה בסגנון אייפון: רקע אחד מעוגל, שורות עם קו דק ביניהן
    val counter = remember { RowCounter() }
    counter.reset()
    androidx.compose.runtime.CompositionLocalProvider(LocalRowCounter provides counter) {
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF181C25))
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) { content() }
    }
}

/** סופר שורות בקבוצה: לשורה הראשונה אין קו מעליה */
private class RowCounter {
    private var n = 0
    fun reset() { n = 0 }
    fun next() = n++
}

private val LocalRowCounter = androidx.compose.runtime.compositionLocalOf { RowCounter() }

/** "🔋 טבעת סוללה (מראה כמה סוללה נשארה)" ← סמל, שם קצר, והסבר שנפתח ב־ⓘ */
private data class RowLabel(val icon: String?, val title: String, val info: String?)

private fun splitLabel(text: String): RowLabel {
    var t = text.trim()
    var icon: String? = null
    val first = t.substringBefore(' ')
    if (first != t && first.none { it.isLetterOrDigit() }) {
        icon = first
        t = t.substringAfter(' ').trim()
    }
    var info: String? = null
    if (t.endsWith(")") && t.contains(" (")) {
        info = t.substringAfter(" (").removeSuffix(")")
        t = t.substringBefore(" (")
    } else if (t.contains(": ") && t.substringBefore(": ").trim().contains(" ")) {
        info = t.substringAfter(": ")
        t = t.substringBefore(": ")
    } else if (t.contains(" = ") && t.length > 28) {
        info = t
        t = t.substringBefore(" (").take(28)
    }
    return RowLabel(icon, t, info?.replaceFirstChar { it.uppercase() })
}

/** צבע קבוע לכל סמל, לריבוע הצבעוני שמאחוריו */
private fun iconTint(icon: String): Color {
    val palette = listOf(0xFF2AD49A, 0xFF4C8DF6, 0xFFB18CFF, 0xFFFF6FAE, 0xFFFF7A59, 0xFFFFB84D, 0xFF38C6E8, 0xFF7E8BA3)
    return Color(palette[(icon.hashCode() and 0x7fffffff) % palette.size])
}

/** שורה בסגנון אייפון: סמל צבעוני, שם, ⓘ שפותח הסבר, ומה שבצד (מתג וכו') */
@Composable
private fun IosRow(text: String, trailing: @Composable () -> Unit) {
    val counter = LocalRowCounter.current
    val index = remember { counter.next() }
    val l = remember(text) { splitLabel(text) }
    var open by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        if (index > 0) Box(Modifier.fillMaxWidth().padding(start = if (l.icon != null) 42.dp else 0.dp).height(0.6.dp).background(Color(0x22FFFFFF)))
        // מרווח נוח בין שורות; המתג תמיד בקצה השמאלי, באותו מקום בכל השורות
        Row(Modifier.fillMaxWidth().heightIn(min = 60.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (l.icon != null) {
                Box(
                    Modifier.size(32.dp).clip(RoundedCornerShape(9.dp)).background(iconTint(l.icon).copy(alpha = 0.22f)),
                    contentAlignment = Alignment.Center,
                ) { Text(l.icon, fontSize = 16.sp) }
                Spacer(Modifier.width(12.dp))
            }
            // השם וה־ⓘ תופסים את כל המקום שנשאר, כך שמה שבצד (מתג, ▶) נדחף עד הקצה
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text(l.title, Modifier.weight(1f, fill = false), fontSize = 16.sp, color = Neon.Text)
            if (l.info != null) {
                Box(
                    Modifier
                        .padding(start = 6.dp)
                        .size(22.dp)
                        .clip(CircleShape)
                        .border(1.4.dp, if (open) Neon.GreenSoft else Neon.Muted, CircleShape)
                        .clickable { open = !open },
                    contentAlignment = Alignment.Center,
                ) { Text("i", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (open) Neon.GreenSoft else Neon.Muted) }
            }
            }
            Spacer(Modifier.width(8.dp))
            trailing()
        }
        androidx.compose.animation.AnimatedVisibility(open && l.info != null) {
            Text(
                l.info ?: "",
                Modifier
                    .fillMaxWidth()
                    .padding(start = if (l.icon != null) 42.dp else 0.dp, bottom = 10.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFF222837))
                    .padding(horizontal = 12.dp, vertical = 9.dp),
                fontSize = 13.sp,
                color = Neon.Muted,
            )
        }
    }
}

@Composable
private fun SwitchLine(text: String, checked: Boolean, demo: (() -> Unit)? = null, onChange: (Boolean) -> Unit) {
    IosRow(text) {
        // ▶ מראה עכשיו, על המסך, מה האפשרות הזאת עושה
        if (demo != null) DemoBtn(demo)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** כפתור ▶ ירוק קטן: הדגמה של האפשרות */
@Composable
private fun DemoBtn(onClick: () -> Unit) {
    val ctx = LocalContext.current
    Text(
        "▶",
        Modifier
            .padding(end = 6.dp)
            .clip(CircleShape)
            .background(Neon.Green.copy(alpha = 0.14f))
            .clickable {
                if (!NotchOverlay.isRunning()) Actions.toast(ctx, "קודם צריך להדליק את קיצורים בנגישות") else onClick()
            }
            .padding(horizontal = 11.dp, vertical = 6.dp),
        color = Neon.GreenSoft,
        fontSize = 13.sp,
    )
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

/** נושא בתפריט הראשי: אריח עם סמל, שם ושורה קטנה, והמילים שהחיפוש מוצא בו */
private class HomePage(val id: String, val emoji: String, val title: String, val sub: String, val items: List<String>)

private val HomePages = listOf(
    HomePage("touch", "👆", "נגיעות", "הקשה, כפולה, החלקה", listOf(
        "הקשה אחת", "הקשה כפולה", "לחיצה ארוכה", "החלקה", "רטט קטן בכל נגיעה", "תנועות לפי אפליקציה", "בהירות",
    )),
    HomePage("pill", "✨", "גלולה חיה", "התראות סביב המצלמה", listOf(
        "להפעיל את הגלולה החיה", "מי אכל את הסוללה", "סוללה חלשה", "טיפה נוטפת", "מסגרת לגלולה", "מסך כבוי", "תמיד מופעל", "מסך נעילה", "גלולה שחורה", "הודעות", "שיחות", "שיר חדש", "מוזיקה כל הזמן בעיגול", "סמל המוזיקה בשורת המצב",
        "חיבור מטען", "ניווט", "אוזניות", "הורדות", "צילום מסך", "קוד אימות", "העתקת טקסט", "חלון קופץ",
        "טעינה נוזלית", "פנס", "תמונת השולח", "צבע לפי התמונה", "קצוות זוהרים",
    )),
    HomePage("ring", "⭕", "טבעת", "סוללה ונורת התראות", listOf(
        "טבעת סוללה", "רק בזמן טעינה", "נקודה מסתובבת בזמן טעינה", "נורת התראות", "נכבית לבד אחרי",
        "הקשה על העיגול הדלוק", "עוצמת קול", "התקדמות השיר", "הבהוב בצלצול",
    )),
    HomePage("colors", "🎨", "צבעים", "צבע לכל סוג התראה", listOf(
        "צבע הודעות", "צבע שיחות", "צבע מוזיקה", "צבע ניווט", "צבע הורדות", "צבע צילום מסך", "צבע טעינה", "צבע אוזניות", "צבע עוצמת קול",
    )),
    HomePage("calls", "📞", "שיחות", "מסך ניאון וחייגן", listOf(
        "מסך שיחה בניאון", "אפליקציית הטלפון", "חייגן", "קיצור טלפון", "מועדפים", "חסימת שיחות וואטסאפ", "חסימה",
    )),
    HomePage("music", "🎵", "מוזיקה", "נגן בהקשה על העיגול", listOf(
        "נגן בהקשה על העיגול", "פס התקדמות וזמן", "שיר קודם / הבא", "תמונת האלבום",
    )),
    HomePage("apps", "📱", "אפליקציות ומסכים", "מסך מלא, נעילה, כבוי ב…", listOf(
        "להסתיר במסך מלא", "מסך הנעילה", "כבוי באפליקציות", "הפוך = שקט",
    )),
    HomePage("more", "⋯", "עוד", "רכב, גודל, אוטומציות, קולי", listOf(
        "אנדרואיד אוטו", "רכב", "מקורות לא ידועים",
        "גודל הטבעת", "עובי", "מיקום", "אזור נגיעה", "איפוס מיקום וגודל", "אוטומציות", "קולי",
    )),
)

/** כותרת קטנה של קבוצה בעמוד הגלולה (הודעות, שיחות, מוזיקה...) */
@Composable
private fun PillGroup(title: String) {
    // נושא חדש: רווח גדול, קו ברור, וכותרת בולטת בצבע. השורה הראשונה אחרי הכותרת בלי קו מעליה
    val counter = LocalRowCounter.current
    remember { counter.reset() }
    Column(Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 6.dp)) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0x33FFFFFF)))
        Text(
            title,
            Modifier.padding(top = 14.dp, start = 2.dp),
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold,
            color = Neon.GreenSoft,
        )
    }
}

/** מתג בגלולה החיה, ולידו ▶ שמראה עכשיו למעלה איך זה נראה */
@Composable
private fun PillLine(text: String, checked: Boolean, demo: String, onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    IosRow(text) {
        // ▶ מראה עכשיו למעלה, על המסך, איך זה נראה
        Text(
            "▶",
            Modifier
                .padding(end = 6.dp)
                .clip(CircleShape)
                .background(Neon.Green.copy(alpha = 0.14f))
                .clickable { if (!LivePill.demo(demo)) Actions.toast(ctx, "קודם צריך להדליק את קיצורים בנגישות") }
                .padding(horizontal = 11.dp, vertical = 6.dp),
            color = Neon.GreenSoft,
            fontSize = 13.sp,
        )
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
