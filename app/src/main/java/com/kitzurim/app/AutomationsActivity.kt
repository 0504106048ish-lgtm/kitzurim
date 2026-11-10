package com.kitzurim.app

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import android.app.Activity
import androidx.activity.result.contract.ActivityResultContract
import android.app.TimePickerDialog
import android.provider.ContactsContract
import java.util.UUID
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat

/** מסך האוטומציות: מצב נהיגה, שיחות לא מוכרות בלילה, שקט לפי מקום */
class AutomationsActivity : ComponentActivity() {
    // כשחוזרים מההגדרות של הטלפון (אחרי אישור הרשאה), המסך מתעדכן
    private val resumes = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KitzurimTheme { AutomationsScreen(resumes.intValue) } }
    }
}

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun open(ctx: Context, intent: Intent) {
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        Actions.toast(ctx, "לא הצלחתי לפתוח את ההגדרות")
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun AutomationsScreen(resumes: Int) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }

    var showCars by remember { mutableStateOf(false) }
    var reply by remember { mutableStateOf(AutoPrefs.replyText(ctx)) }
    var placeName by remember { mutableStateOf("") }
    var placeAddress by remember { mutableStateOf("") }
    var placeRadius by remember { mutableStateOf(200f) }
    // התראות מקום
    var alName by remember { mutableStateOf("") }
    var alAddress by remember { mutableStateOf("") }
    var alText by remember { mutableStateOf("") }
    var alArrive by remember { mutableStateOf(true) }
    var alLeave by remember { mutableStateOf(false) }
    var alRadius by remember { mutableStateOf(200f) }
    var alError by remember { mutableStateOf<String?>(null) }
    var alBusy by remember { mutableStateOf(false) }
    var placeError by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }

    val btLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        if (it) showCars = true
        refresh++
    }
    val locLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val roleLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (NightCalls.roleHeld(ctx)) AutoPrefs.setNightCallsEnabled(ctx, true)
        refresh++
    }
    // בחירת איש קשר שלא יקבל מענה אוטומטי. נשמרים גם השם וגם המספר,
    // כדי לזהות אותו גם כשהשם בוואטסאפ שונה מאנשי הקשר (בלי הרשאה: הטלפון נותן רק את מי שבחרת)
    val contactLauncher = rememberLauncherForActivityResult(PickPhone()) { uri ->
        val picked = uri?.let {
            try {
                ctx.contentResolver.query(
                    it,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
            } catch (_: Exception) {
                null
            }
        }
        if (picked != null) AutoPrefs.addExcluded(ctx, picked.first, picked.second)
        refresh++
    }
    // תשובה מיוחדת: כותבים את הטקסט, ואז בוחרים למי
    var customText by remember { mutableStateOf("") }
    var customError by remember { mutableStateOf<String?>(null) }
    val customLauncher = rememberLauncherForActivityResult(PickPhone()) { uri ->
        val picked = uri?.let {
            try {
                ctx.contentResolver.query(
                    it,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    null, null, null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
            } catch (_: Exception) {
                null
            }
        }
        if (picked != null) {
            AutoPrefs.addCustomReply(
                ctx,
                CustomReply(picked.first.trim(), picked.second.filter { it.isDigit() }, customText.trim()),
            )
            customText = ""
            Actions.toast(ctx, "נשמר: ${picked.first} יקבל תשובה מיוחדת")
        }
        refresh++
    }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    var remText by remember { mutableStateOf("") }
    var remPlace by remember { mutableStateOf("") }
    var remError by remember { mutableStateOf<String?>(null) }
    var remBusy by remember { mutableStateOf(false) }

    // איזו תיקייה פתוחה ("" = רשימת התיקיות). כפתור "חזרה" של הטלפון חוזר לרשימה
    var folder by rememberSaveable { mutableStateOf("") }
    BackHandler(enabled = folder.isNotEmpty()) { folder = "" }

    // מיקום הגלילה נשמר מחוץ לבנייה מחדש של המסך, כדי שאחרי לחיצה או חזרה תישאר איפה שהיית.
    // לרשימת התיקיות ולתיקייה הפתוחה יש גלילה נפרדת; תיקייה חדשה נפתחת מלמעלה
    val listScroll = rememberScrollState()
    val folderScroll = rememberScrollState()
    LaunchedEffect(folder) { if (folder.isNotEmpty()) folderScroll.scrollTo(0) }

    /** בוחרים שעת התחלה ואז שעת סיום, ונוצר טווח שקט לכל ימי השבוע */
    fun addRule() {
        TimePickerDialog(ctx, { _, h1, m1 ->
            TimePickerDialog(ctx, { _, h2, m2 ->
                AutoPrefs.addRule(
                    ctx,
                    QuietRule(UUID.randomUUID().toString(), h1 * 60 + m1, h2 * 60 + m2, QuietRule.ALL_DAYS),
                )
                ScheduledQuiet.apply(ctx)
                refresh++
            }, (h1 + 1) % 24, m1, true).apply { setTitle("עד איזו שעה?") }.show()
        }, 8, 0, true).apply { setTitle("מאיזו שעה?") }.show()
    }


    // ההגדרות וההרשאות נקראות מחדש אחרי כל שינוי, ואחרי חזרה מההגדרות של הטלפון
    key(resumes, refresh) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(if (folder.isEmpty()) listScroll else folderScroll)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val current = FOLDERS.firstOrNull { it.id == folder }
            if (current == null) {
                Text("אוטומציות", style = MaterialTheme.typography.headlineMedium)
                Text("דברים שקורים לבד, בלי ללחוץ על כלום", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { folder = "" }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "חזרה")
                    }
                    Text(current.emoji + " " + current.title, style = MaterialTheme.typography.headlineSmall)
                }
            }

            // ---------- ההרשאות הכלליות: מופיעות רק כשחסר משהו, ונעלמות כשמאשרים ----------
            val missingCount = Perms.missingCount(ctx)
            if (current == null && missingCount > 0) {
                Section("⚠️ חסרים $missingCount אישורים") {
                    Text(
                        "כדי שהאוטומציות יעבדו. במסך האישורים הכול מסודר, עם כפתור אחד שעובר על הכול.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Button(
                        onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text("✓ לסדר את האישורים") }
                }
            }

            // ---------- רשימת התיקיות ----------
            if (current == null) {
                FOLDERS.forEach { f ->
                    FolderRow(f.emoji, f.title, folderStatus(ctx, f.id)) { folder = f.id }
                }
            }

            if (folder == "drive") {
            // ---------- מצב נהיגה ----------
            Section(
                "🚗 מענה אוטומטי בנהיגה",
                checked = AutoPrefs.drivingEnabled(ctx),
                onCheck = { AutoPrefs.setDrivingEnabled(ctx, it); refresh++ },
            ) {
                Text(
                    "כשהטלפון מתחבר לבלוטות' של הרכב, מי שכותב לך בוואטסאפ מקבל תשובה אחת. בקבוצות לא עונים.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Status(Driving.listenerAllowed(ctx), "", "גישה להתראות (לקרוא הודעות וואטסאפ)") {
                    open(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                }
                if (!Driving.listenerAllowed(ctx)) RestrictedHelp(ctx)

                val car = AutoPrefs.carName(ctx)
                Text(if (car.isBlank()) "הרכב: עוד לא נבחר" else "הרכב: $car", fontWeight = FontWeight.Medium)
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 31 && !granted(ctx, Manifest.permission.BLUETOOTH_CONNECT)) {
                        btLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                    } else {
                        showCars = !showCars
                    }
                }) { Text(if (car.isBlank()) "בחירת הרכב" else "החלפת הרכב") }
                if (showCars) {
                    val devices = Driving.pairedDevices(ctx)
                    if (devices.isEmpty()) Text("לא נמצאו מכשירי בלוטות' מחוברים")
                    devices.forEach { (name, address) ->
                        Text(
                            name,
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    AutoPrefs.setCar(ctx, name, address)
                                    showCars = false
                                    refresh++
                                }
                                .padding(vertical = 10.dp),
                        )
                        HorizontalDivider()
                    }
                }

                OutlinedTextField(
                    value = reply,
                    onValueChange = { reply = it; AutoPrefs.setReplyText(ctx, it) },
                    label = { Text("התשובה האוטומטית") },
                    supportingText = { Text("מה שכל אחד מקבל (חוץ ממי שיש לו תשובה מיוחדת)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                // תשובה מיוחדת לאנשים מסוימים
                HorizontalDivider()
                Text("תשובה מיוחדת לאנשים מסוימים", fontWeight = FontWeight.Medium)
                AutoPrefs.customReplies(ctx).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("💌 " + r.name, fontWeight = FontWeight.Medium)
                            Text(
                                "\"${r.text}\"",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { AutoPrefs.removeCustomReply(ctx, r); refresh++ }) {
                            Icon(Icons.Filled.Delete, contentDescription = "מחיקה")
                        }
                    }
                }
                OutlinedTextField(
                    value = customText,
                    onValueChange = { customText = it; customError = null },
                    label = { Text("תשובה מיוחדת") },
                    placeholder = { Text("אני בדרך הביתה ❤️") },
                    isError = customError != null,
                    supportingText = { Text(customError ?: "כותבים את התשובה, ואז בוחרים למי") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(onClick = {
                    if (customText.isBlank()) {
                        customError = "קודם לכתוב את התשובה"
                    } else {
                        customLauncher.launch(null)
                    }
                }) { Text("בחירת איש קשר") }
                SwitchRow("אני נוהג עכשיו (הפעלה ידנית)", AutoPrefs.drivingActive(ctx)) {
                    AutoPrefs.setDrivingActive(ctx, it)
                    if (!it) DriveSummary.show(ctx)
                    refresh++
                }
                SwitchRow("סיכום ההודעות בסוף הנסיעה", AutoPrefs.summaryEnabled(ctx)) {
                    AutoPrefs.setSummaryEnabled(ctx, it)
                    refresh++
                }
                val inDrive = AutoPrefs.driveMessages(ctx).size
                TextButton(onClick = { ctx.startActivity(Intent(ctx, DriveSummaryActivity::class.java)) }) {
                    Text(if (inDrive > 0) "📨 הסיכום של הנסיעה האחרונה ($inDrive הודעות)" else "📨 הסיכום של הנסיעה האחרונה")
                }

                // אנשים שלא מקבלים מענה
                HorizontalDivider()
                Text("לא עונים אוטומטית ל:", fontWeight = FontWeight.Medium)
                val excluded = AutoPrefs.excluded(ctx)
                if (excluded.isEmpty()) {
                    Text(
                        "כולם מקבלים מענה",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                excluded.forEach { entry ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🙅 " + Excluded.name(entry), Modifier.weight(1f))
                        IconButton(onClick = { AutoPrefs.removeExcluded(ctx, entry); refresh++ }) {
                            Icon(Icons.Filled.Delete, contentDescription = "הסרה")
                        }
                    }
                }
                OutlinedButton(onClick = { contactLauncher.launch(null) }) { Text("הוספת איש קשר") }

                // מי קיבל תשובה
                HorizontalDivider()
                Text("מי קיבל תשובה אוטומטית", fontWeight = FontWeight.Medium)
                val log = AutoPrefs.replyLog(ctx)
                if (log.isEmpty()) {
                    Text(
                        "עוד אף אחד",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    log.forEach { Text("💬 $it", style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { AutoPrefs.clearReplyLog(ctx); refresh++ }) { Text("ניקוי הרשימה") }
                }
            }
            }

            if (folder == "quiet") {
            // ---------- שקט לפי שעות קבועות ----------
            Section(
                "🕐 שקט לפי שעות",
                checked = AutoPrefs.scheduleEnabled(ctx),
                onCheck = {
                    AutoPrefs.setScheduleEnabled(ctx, it)
                    ScheduledQuiet.apply(ctx)
                    refresh++
                },
            ) {
                Text(
                    "בשעות שקבעת הטלפון עובר לשקט, ובסופן הצלצול חוזר למה שהיה. אפשר לבחור באילו ימים.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Status(QuietPlaces.dndAllowed(ctx), "שקט מלא: מאושר", "שקט מלא: לא מאושר (בינתיים רק רטט)") {
                    open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }
                AutoPrefs.rules(ctx).forEach { rule ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(ScheduledQuiet.describe(rule), Modifier.weight(1f), fontWeight = FontWeight.Medium)
                            IconButton(onClick = {
                                AutoPrefs.removeRule(ctx, rule.id)
                                ScheduledQuiet.apply(ctx)
                                refresh++
                            }) { Icon(Icons.Filled.Delete, contentDescription = "מחיקה") }
                        }
                        // הימים: לחיצה מדליקה או מכבה יום
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            ScheduledQuiet.DAY_LETTERS.forEachIndexed { i, letter ->
                                FilterChip(
                                    selected = rule.hasDay(i),
                                    onClick = {
                                        AutoPrefs.updateRule(ctx, rule.toggleDay(i))
                                        ScheduledQuiet.apply(ctx)
                                        refresh++
                                    },
                                    label = { Text(letter) },
                                )
                            }
                        }
                    }
                    HorizontalDivider()
                }
                OutlinedButton(onClick = { addRule() }) { Text("הוספת שעות שקט") }
            }
            }

            if (folder == "remind") {
            // ---------- תזכורת לפי מקום ----------
            Section("🛒 תזכורות לפי מקום") {
                Text(
                    "התזכורת קופצת כשאתה מגיע למקום, ואז נמחקת. אפשר גם בקול: \"תזכיר לי לקנות חלב כשאני ליד שופרסל\".",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fineOk = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                val bgLocOk = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                if (!fineOk || !bgLocOk) {
                    Status(false, "", "מיקום \"לאפשר כל הזמן\"") {
                        if (!fineOk) {
                            locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                        } else if (Build.VERSION.SDK_INT >= 29) {
                            locLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                }
                AutoPrefs.reminders(ctx).forEach { r ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(r.text, fontWeight = FontWeight.Medium)
                            Text(
                                "📍 " + r.place,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { Reminders.remove(ctx, r.id); refresh++ }) {
                            Icon(Icons.Filled.Delete, contentDescription = "מחיקה")
                        }
                    }
                }
                OutlinedTextField(
                    value = remText,
                    onValueChange = { remText = it; remError = null },
                    label = { Text("מה להזכיר") },
                    placeholder = { Text("לקנות חלב") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = remPlace,
                    onValueChange = { remPlace = it; remError = null },
                    label = { Text("איפה") },
                    placeholder = { Text("שופרסל רמות, או שם של מקום ששמרת") },
                    singleLine = true,
                    isError = remError != null,
                    supportingText = { remError?.let { Text(it) } },
                    modifier = Modifier.fillMaxWidth(),
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        when {
                            remText.isBlank() -> remError = "צריך לכתוב מה להזכיר"
                            remPlace.isBlank() -> remError = "צריך לכתוב איפה"
                            remBusy -> {}
                            else -> {
                                remBusy = true
                                Reminders.addByName(ctx, remText.trim(), remPlace.trim()) { ok ->
                                    remBusy = false
                                    if (ok) {
                                        remText = ""
                                        remPlace = ""
                                        Actions.toast(ctx, "התזכורת נשמרה")
                                    } else {
                                        remError = "לא מצאתי את המקום. לנסות לכתוב גם את העיר"
                                    }
                                    refresh++
                                }
                            }
                        }
                    }) { Text(if (remBusy) "מחפש..." else "הוספה") }
                    OutlinedButton(onClick = {
                        when {
                            remText.isBlank() -> remError = "צריך לכתוב מה להזכיר"
                            !fineOk -> remError = "קודם לאשר מיקום"
                            remBusy -> {}
                            else -> {
                                remBusy = true
                                val name = remPlace.trim().ifBlank { "המקום ששמרת" }
                                Reminders.addHere(ctx, remText.trim(), name) { ok ->
                                    remBusy = false
                                    if (ok) {
                                        remText = ""
                                        remPlace = ""
                                        Actions.toast(ctx, "התזכורת נשמרה למקום הזה")
                                    } else {
                                        remError = "לא הצלחתי למצוא את המיקום שלך"
                                    }
                                    refresh++
                                }
                            }
                        }
                    }) { Text("במקום שבו אני עכשיו") }
                }
            }
            }

            if (folder == "alerts") {
            // ---------- התראה בהגעה למקום וביציאה ממנו ----------
            Section("🔔 התראות מקום") {
                Text(
                    "התראה בכל פעם שמגיעים למקום או עוזבים אותו. בשונה מתזכורת, היא לא נמחקת אחרי פעם אחת.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fineA = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                val bgA = Build.VERSION.SDK_INT < 29 || granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                Status(fineA && bgA && Notify.allowed(ctx), "", "מיקום \"כל הזמן\" והתראות") {
                    ctx.startActivity(Intent(ctx, PermissionsActivity::class.java))
                }

                val alerts = PlaceAlerts.all(ctx)
                alerts.forEach { a ->
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("📍 " + a.name, fontWeight = FontWeight.Medium)
                                Text(
                                    PlaceAlerts.describe(a) + if (a.text.isNotBlank()) " · \"${a.text}\"" else "",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                            IconButton(onClick = { PlaceAlerts.remove(ctx, a.id); refresh++ }) {
                                Icon(Icons.Filled.Delete, contentDescription = "מחיקה")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            FilterChip(
                                selected = a.onArrive,
                                onClick = { PlaceAlerts.update(ctx, a.copy(onArrive = !a.onArrive)); refresh++ },
                                label = { Text("בהגעה") },
                            )
                            FilterChip(
                                selected = a.onLeave,
                                onClick = { PlaceAlerts.update(ctx, a.copy(onLeave = !a.onLeave)); refresh++ },
                                label = { Text("ביציאה") },
                            )
                        }
                    }
                    HorizontalDivider()
                }

                Text("הוספת מקום", fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = alName,
                    onValueChange = { alName = it; alError = null },
                    label = { Text("שם המקום") },
                    placeholder = { Text("העבודה") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = alAddress,
                    onValueChange = { alAddress = it; alError = null },
                    label = { Text("כתובת או שם של מקום") },
                    placeholder = { Text("רחוב הרצל 10, ירושלים") },
                    singleLine = true,
                    isError = alError != null,
                    supportingText = { Text(alError ?: "או להשאיר ריק וללחוץ \"המקום שבו אני עכשיו\"") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = alText,
                    onValueChange = { alText = it },
                    label = { Text("מה לכתוב בהתראה (לא חובה)") },
                    placeholder = { Text("לבדוק שנעלתי את הרכב") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("מתי להתריע", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = alArrive, onClick = { alArrive = !alArrive }, label = { Text("כשאני מגיע") })
                    FilterChip(selected = alLeave, onClick = { alLeave = !alLeave }, label = { Text("כשאני עוזב") })
                }
                Text("גודל האזור", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100f to "קטן · 100 מ'", 200f to "בינוני · 200 מ'", 500f to "גדול · 500 מ'").forEach { (r, label) ->
                        FilterChip(selected = alRadius == r, onClick = { alRadius = r }, label = { Text(label) })
                    }
                }
                val onAlertAdded: (Boolean) -> Unit = { ok ->
                    alBusy = false
                    if (ok) {
                        Actions.toast(ctx, "🔔 ${alName.trim()} נשמר")
                        alName = ""
                        alAddress = ""
                        alText = ""
                    } else {
                        alError = if (alAddress.isNotBlank()) {
                            "לא מצאתי את הכתובת. לנסות לכתוב גם את העיר"
                        } else {
                            "לא הצלחתי למצוא את המיקום שלך. לבדוק שהמיקום בטלפון דולק"
                        }
                    }
                    refresh++
                }
                fun alertCheck(): Boolean {
                    alError = when {
                        alName.isBlank() -> "צריך לתת שם למקום"
                        !alArrive && !alLeave -> "לבחור מתי להתריע: בהגעה, ביציאה או שניהם"
                        else -> null
                    }
                    return alError == null && !alBusy
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        if (alertCheck()) {
                            if (alAddress.isBlank()) {
                                alError = "צריך לכתוב כתובת או שם של מקום"
                            } else {
                                alBusy = true
                                PlaceAlerts.addByAddress(
                                    ctx, alName.trim(), alAddress.trim(), alRadius, alArrive, alLeave, alText.trim(), onAlertAdded,
                                )
                            }
                        }
                    }) { Text(if (alBusy) "מחפש..." else "הוספה לפי הכתובת") }
                    OutlinedButton(onClick = {
                        if (alertCheck()) {
                            if (!fineA) {
                                alError = "קודם לאשר מיקום"
                            } else {
                                alBusy = true
                                PlaceAlerts.addHere(ctx, alName.trim(), alRadius, alArrive, alLeave, alText.trim(), onAlertAdded)
                            }
                        }
                    }) { Text("המקום שבו אני עכשיו") }
                }
            }
            }

            if (folder == "night") {
            // ---------- שיחות לא מוכרות בלילה ----------
            Section(
                "📵 מספרים לא מוכרים בלילה",
                checked = AutoPrefs.nightCallsEnabled(ctx) && NightCalls.roleHeld(ctx),
                onCheck = { on ->
                    if (on && !NightCalls.roleHeld(ctx)) {
                        if (Build.VERSION.SDK_INT >= 29) {
                            val rm = ctx.getSystemService(android.app.role.RoleManager::class.java)
                            rm?.createRequestRoleIntent(android.app.role.RoleManager.ROLE_CALL_SCREENING)
                                ?.let { roleLauncher.launch(it) }
                        } else {
                            Actions.toast(ctx, "הטלפון הזה ישן מדי בשביל זה")
                        }
                    } else {
                        AutoPrefs.setNightCallsEnabled(ctx, on)
                        refresh++
                    }
                },
            ) {
                Text(
                    "בין 23:00 ל־07:00, שיחה ממספר שלא שמור אצלך נדחית בשקט ונרשמת כאן. אנשי קשר מצלצלים כרגיל.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val rejected = AutoPrefs.rejectedCalls(ctx)
                if (rejected.isNotEmpty()) {
                    Text("שיחות שנדחו", fontWeight = FontWeight.Medium)
                    rejected.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                    TextButton(onClick = { AutoPrefs.clearRejected(ctx); refresh++ }) { Text("ניקוי הרשימה") }
                }
            }
            }

            if (folder == "quiet") {
            // ---------- שקט לפי מקום ----------
            Section(
                "🤫 שקט לפי מקום",
                checked = AutoPrefs.quietEnabled(ctx),
                onCheck = {
                    AutoPrefs.setQuietEnabled(ctx, it)
                    QuietPlaces.register(ctx)
                    refresh++
                },
            ) {
                Text(
                    "כשאתה מגיע למקום ששמרת, הטלפון עובר לשקט. כשאתה יוצא, הצלצול חוזר למה שהיה.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val fine = granted(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
                Status(fine, "מיקום: מאושר", "מיקום: לא מאושר") {
                    locLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION)
                }
                if (Build.VERSION.SDK_INT >= 29) {
                    val bg = granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    Status(bg, "מיקום ברקע: מאושר", "מיקום ברקע: צריך לבחור \"לאפשר כל הזמן\"") {
                        if (!fine) {
                            Actions.toast(ctx, "קודם לאשר מיקום")
                        } else {
                            locLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                        }
                    }
                }
                Status(QuietPlaces.dndAllowed(ctx), "שקט מלא: מאושר", "שקט מלא: לא מאושר (בינתיים רק רטט)") {
                    open(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
                }

                // המקומות ששמרת (כמה שרוצים)
                val places = AutoPrefs.places(ctx)
                if (places.isNotEmpty()) {
                    Text("המקומות שלך (${places.size})", fontWeight = FontWeight.Medium)
                }
                places.forEach { p ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("📍 " + p.name)
                            Text(
                                "ברדיוס ${p.radius.toInt()} מטר",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = {
                            AutoPrefs.removePlace(ctx, p.id)
                            QuietPlaces.register(ctx)
                            refresh++
                        }) { Icon(Icons.Filled.Delete, contentDescription = "מחיקה") }
                    }
                }

                // הוספת מקום חדש
                HorizontalDivider()
                Text("הוספת מקום", fontWeight = FontWeight.Medium)
                OutlinedTextField(
                    value = placeName,
                    onValueChange = { placeName = it; placeError = null },
                    label = { Text("שם המקום") },
                    placeholder = { Text("בית כנסת") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = placeAddress,
                    onValueChange = { placeAddress = it; placeError = null },
                    label = { Text("כתובת או שם של מקום") },
                    placeholder = { Text("רחוב רבי עקיבא 10, בני ברק") },
                    singleLine = true,
                    isError = placeError != null,
                    supportingText = { Text(placeError ?: "או להשאיר ריק וללחוץ \"המקום שבו אני עכשיו\"") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("גודל האזור", style = MaterialTheme.typography.bodySmall)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(100f to "קטן · 100 מ'", 200f to "בינוני · 200 מ'", 500f to "גדול · 500 מ'").forEach { (r, label) ->
                        FilterChip(
                            selected = placeRadius == r,
                            onClick = { placeRadius = r },
                            label = { Text(label) },
                        )
                    }
                }
                val onPlaceAdded: (Boolean) -> Unit = { ok ->
                    adding = false
                    if (ok) {
                        Actions.toast(ctx, "📍 ${placeName.trim()} נשמר")
                        placeName = ""
                        placeAddress = ""
                    } else {
                        placeError = if (placeAddress.isNotBlank()) {
                            "לא מצאתי את הכתובת. לנסות לכתוב גם את העיר"
                        } else {
                            "לא הצלחתי למצוא את המיקום שלך. לבדוק שהמיקום בטלפון דולק"
                        }
                    }
                    refresh++
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        when {
                            placeName.isBlank() -> placeError = "צריך לתת שם למקום"
                            placeAddress.isBlank() -> placeError = "צריך לכתוב כתובת או שם של מקום"
                            adding -> {}
                            else -> {
                                adding = true
                                QuietPlaces.addByAddress(ctx, placeName.trim(), placeAddress.trim(), placeRadius, onPlaceAdded)
                            }
                        }
                    }) { Text(if (adding) "מחפש..." else "הוספה לפי הכתובת") }
                    OutlinedButton(onClick = {
                        when {
                            placeName.isBlank() -> placeError = "צריך לתת שם למקום"
                            !fine -> placeError = "קודם לאשר מיקום"
                            adding -> {}
                            else -> {
                                adding = true
                                QuietPlaces.addCurrentPlace(ctx, placeName.trim(), placeRadius, onPlaceAdded)
                            }
                        }
                    }) { Text("המקום שבו אני עכשיו") }
                }
            }
            }
        }
    }
    }
}

@Composable
private fun Section(
    title: String,
    checked: Boolean? = null,
    onCheck: (Boolean) -> Unit = {},
    content: @Composable () -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                if (checked != null) Switch(checked = checked, onCheckedChange = onCheck)
            }
            content()
        }
    }
}

@Composable
private fun SwitchRow(text: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheck)
    }
}

/** שורת הרשאה שחסרה, עם כפתור "לאשר". כשההרשאה מאושרת, השורה נעלמת */
@Suppress("UNUSED_PARAMETER")
@Composable
private fun Status(ok: Boolean, okText: String, needText: String, fix: () -> Unit) {
    if (ok) return
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = MaterialTheme.colorScheme.error)
        Text(needText, Modifier.weight(1f).padding(horizontal = 8.dp))
        TextButton(onClick = fix) { Text("לאשר") }
    }
}

/** תיקייה במסך האוטומציות */
private data class Folder(val id: String, val emoji: String, val title: String)

private val FOLDERS = listOf(
    Folder("drive", "🚗", "נהיגה"),
    Folder("quiet", "🤫", "שקט (לפי מקום ולפי שעות)"),
    Folder("night", "📵", "שיחות בלילה"),
    Folder("remind", "🛒", "תזכורות לפי מקום"),
    Folder("alerts", "🔔", "התראות בהגעה וביציאה"),
)

/** שורת המצב שמתחת לשם התיקייה */
private fun folderStatus(ctx: Context, id: String): String = when (id) {
    "drive" -> when {
        !AutoPrefs.drivingEnabled(ctx) -> "כבוי"
        AutoPrefs.drivingActive(ctx) -> "פעיל · נוהג עכשיו"
        AutoPrefs.carName(ctx).isBlank() -> "פעיל · עוד לא נבחר רכב"
        else -> "פעיל · " + AutoPrefs.carName(ctx)
    }
    "quiet" -> {
        val parts = mutableListOf<String>()
        if (AutoPrefs.quietEnabled(ctx)) parts += "${AutoPrefs.places(ctx).size} מקומות"
        if (AutoPrefs.scheduleEnabled(ctx)) parts += "${AutoPrefs.rules(ctx).size} טווחי שעות"
        if (parts.isEmpty()) "כבוי" else "פעיל · " + parts.joinToString(" · ")
    }
    "night" -> if (AutoPrefs.nightCallsEnabled(ctx) && NightCalls.roleHeld(ctx)) "פעיל · 23:00–07:00" else "כבוי"
    "remind" -> AutoPrefs.reminders(ctx).size.let { if (it == 0) "אין תזכורות" else "$it תזכורות" }
    "alerts" -> PlaceAlerts.all(ctx).size.let { if (it == 0) "אין התראות" else "פעיל · $it מקומות" }
    else -> ""
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FolderRow(emoji: String, title: String, status: String, onClick: () -> Unit) {
    val on = status.startsWith("פעיל") || status.endsWith("תזכורות") && !status.startsWith("אין")
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
        }
    }
}

/** מה לעשות כשאנדרואיד או סמסונג חוסמים את האישור ("האפליקציה לא תקבל גישה") */
@Composable
private fun RestrictedHelp(ctx: Context) {
    Text(
        "אם כתוב \"האפליקציה לא תקבל גישה\" או שהאפשרות אפורה:\n" +
            "1. הגדרות ← אפליקציות ← קיצורים ← ⋮ למעלה ← \"אפשר הגדרות מוגבלות\"\n" +
            "2. אם אין ⋮: הגדרות ← אבטחה ופרטיות ← \"חסימה אוטומטית\" ← לכבות, לאשר, ואז אפשר להדליק שוב",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = {
        open(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName)))
    }) { Text("לפתוח את פרטי האפליקציה") }
}

/** בחירת מספר טלפון מאנשי הקשר (מחזיר גם את השם וגם את המספר, בלי הרשאה לכל אנשי הקשר) */
class PickPhone : ActivityResultContract<Unit?, Uri?>() {
    override fun createIntent(context: Context, input: Unit?) =
        Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI)

    override fun parseResult(resultCode: Int, intent: Intent?): Uri? =
        if (resultCode == Activity.RESULT_OK) intent?.data else null
}

