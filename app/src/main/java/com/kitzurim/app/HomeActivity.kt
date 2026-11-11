package com.kitzurim.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
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
        // שורת המצב: סמלים כהים על רקע בהיר (ובהירים במצב כהה), כדי שיהיה אפשר לקרוא אותה
        enableEdgeToEdge()
        // "פתיחה ישר לפקודה קולית": האייקון (והלחצן הצדדי) פותחים את הדיבור.
        // לאפליקציה עצמה מגיעים בלחיצה על "חזרה" בחלון הדיבור
        val fromShortcut = intent?.getStringExtra("open_home") == "true" ||
            intent?.getBooleanExtra("open_home", false) == true
        val fromLauncher = intent?.action == Intent.ACTION_MAIN
        if (savedInstanceState == null && fromLauncher && !fromShortcut && Prefs.voiceFirst(this)) {
            startActivity(Intent(this, VoiceActivity::class.java).putExtra(VoiceActivity.BACK_TO_HOME, true))
            finish()
            return
        }
        setContent { KitzurimTheme { HomeScreen(resumes.intValue) } }
    }
}

@Composable
fun HomeScreen(resumes: Int) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    val scheme = MaterialTheme.colorScheme

    // בחירת פעולה לתנועה
    var picking by remember { mutableStateOf<Gesture?>(null) }
    var pickingApp by remember { mutableStateOf<Gesture?>(null) }
    var pickingUrl by remember { mutableStateOf<Gesture?>(null) }
    var pendingContact by remember { mutableStateOf<Pair<Gesture, ActionType>?>(null) }

    fun save(g: Gesture, a: NotchAction) {
        NotchPrefs.setAction(ctx, g, a)
        NotchOverlay.refresh()
        Actions.toast(ctx, "${g.label}: ${a.title()}")
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
                        Text("קיצורים", style = MaterialTheme.typography.headlineSmall)
                        Text("החור של המצלמה הוא כפתור", color = scheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }) {
                        Icon(Icons.Filled.Settings, contentDescription = "הגדרות")
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
                            "צריך להפעיל את \"קיצורים: החור של המצלמה\" בנגישות. אם זה חסום: קודם \"אפשר הגדרות מוגבלות\".",
                            color = scheme.onErrorContainer,
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Button(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                            Text("להפעיל")
                        }
                    }
                } else {
                    Card {
                        SwitchLine("החור של המצלמה פעיל", NotchPrefs.enabled(ctx)) {
                            NotchPrefs.setEnabled(ctx, it)
                            NotchOverlay.refresh()
                            refresh++
                        }
                    }
                }

                // ---------- תנועות ----------
                Card {
                    Text("מה כל נגיעה בחור עושה", style = MaterialTheme.typography.titleMedium)
                    Gesture.entries.forEach { g ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .clickable { picking = g }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(g.label, Modifier.weight(0.9f), color = scheme.onSurfaceVariant)
                            Text(NotchPrefs.action(ctx, g).title(), Modifier.weight(1.4f), fontWeight = FontWeight.Medium)
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = null)
                        }
                        if (g != Gesture.entries.last()) HorizontalDivider()
                    }
                }

                // ---------- הטבעת ----------
                Card {
                    Text("הטבעת סביב המצלמה", style = MaterialTheme.typography.titleMedium)
                    SwitchLine("להראות טבעת", NotchPrefs.ringOn(ctx)) {
                        NotchPrefs.setRingOn(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("רק בזמן טעינה", NotchPrefs.onlyCharging(ctx)) {
                        NotchPrefs.setOnlyCharging(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("נקודה מסתובבת בטעינה", NotchPrefs.chargeAnim(ctx)) {
                        NotchPrefs.setChargeAnim(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    SwitchLine("נורת התראות (מהבהבת בצבע של האפליקציה)", NotchPrefs.notifyLight(ctx)) {
                        NotchPrefs.setNotifyLight(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                    if (NotchPrefs.notifyLight(ctx) && !Driving.listenerAllowed(ctx)) {
                        TextButton(onClick = { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }) {
                            Text("לנורת ההתראות צריך גישה להתראות ←")
                        }
                    }
                    PrefSlider("עובי", NotchPrefs.thickness(ctx), 0.8f..4f) { NotchPrefs.setThickness(ctx, it) }
                }

                // ---------- כיוון ----------
                Card {
                    Text("כיוון", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "אם הטבעת לא יושבת בדיוק על החור, מזיזים כאן. השינוי נראה מיד.",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    PrefSlider("גודל", NotchPrefs.size(ctx), -6f..14f) { NotchPrefs.setSize(ctx, it) }
                    PrefSlider("ימינה / שמאלה", NotchPrefs.offsetX(ctx), -40f..40f) { NotchPrefs.setOffsetX(ctx, it) }
                    PrefSlider("למעלה / למטה", NotchPrefs.offsetY(ctx), -30f..30f) { NotchPrefs.setOffsetY(ctx, it) }
                    SwitchLine("להראות את אזור הנגיעה", NotchPrefs.showZone(ctx)) {
                        NotchPrefs.setShowZone(ctx, it); NotchOverlay.refresh(); refresh++
                    }
                }

                // ---------- עוד ----------
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Tile(Icons.Filled.AutoMode, "אוטומציות", Modifier.weight(1f)) {
                        ctx.startActivity(Intent(ctx, AutomationsActivity::class.java))
                    }
                    Tile(Icons.Filled.Mic, "פקודה קולית", Modifier.weight(1f)) {
                        ctx.startActivity(Intent(ctx, VoiceActivity::class.java))
                    }
                }
                Spacer(Modifier.size(8.dp))
            }
        }
    }

    // ---------- חלונות בחירה ----------
    picking?.let { g ->
        ActionPicker(g, onDismiss = { picking = null }) { type ->
            picking = null
            when (type.arg) {
                ArgKind.NONE -> save(g, NotchAction(type))
                ArgKind.APP -> pickingApp = g
                ArgKind.CONTACT -> {
                    pendingContact = g to type
                    contactLauncher.launch(null)
                }
                ArgKind.URL -> pickingUrl = g
            }
        }
    }
    pickingApp?.let { g ->
        AppPicker(onDismiss = { pickingApp = null }) { name, pkg ->
            pickingApp = null
            save(g, NotchAction(ActionType.APP, pkg, name))
        }
    }
    pickingUrl?.let { g ->
        UrlDialog(onDismiss = { pickingUrl = null }) { url ->
            pickingUrl = null
            save(g, NotchAction(ActionType.URL, url, url.removePrefix("https://").removePrefix("http://")))
        }
    }
}

@Composable
private fun Card(content: @Composable () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
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

/** מחוון שהשינוי שלו נראה על הטבעת מיד כשמרימים את האצבע */
@Composable
private fun PrefSlider(label: String, initial: Float, range: ClosedFloatingPointRange<Float>, onSave: (Float) -> Unit) {
    var v by remember { mutableFloatStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(0.8f))
        Slider(
            value = v,
            onValueChange = { v = it },
            valueRange = range,
            onValueChangeFinished = {
                onSave(v)
                NotchOverlay.refresh()
            },
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
private fun ActionPicker(g: Gesture, onDismiss: () -> Unit, onPick: (ActionType) -> Unit) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.padding(vertical = 16.dp)) {
                Text(
                    "${g.label}: מה לעשות?",
                    Modifier.padding(horizontal = 20.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.titleMedium,
                )
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
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
