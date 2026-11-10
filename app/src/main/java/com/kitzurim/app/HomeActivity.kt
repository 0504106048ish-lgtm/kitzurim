package com.kitzurim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/** המסך הראשי: פס עליון עם מה שפעיל עכשיו, ומתחתיו אייקונים גדולים. (הלחצן הצדדי והעיגול יחוברו בהמשך) */
class HomeActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "בוקר טוב"
    in 12..16 -> "צהריים טובים"
    in 17..20 -> "ערב טוב"
    else -> "לילה טוב"
}

/** "יום שלישי, 10 בנובמבר" */
private fun today(): String = SimpleDateFormat("EEEE, d 'ב'MMMM", Locale("he")).format(Date())

/** מה פעיל עכשיו, לפס העליון */
private fun activeChips(ctx: Context): List<String> {
    val chips = mutableListOf<String>()
    if (AutoPrefs.drivingEnabled(ctx)) {
        chips += if (AutoPrefs.drivingActive(ctx)) "🚗 נוהג עכשיו" else "🚗 מענה בנהיגה"
    }
    if (AutoPrefs.nightCallsEnabled(ctx) && NightCalls.roleHeld(ctx)) chips += "📵 לילה"
    if (AutoPrefs.quietEnabled(ctx)) {
        val n = AutoPrefs.places(ctx).size
        chips += if (n == 1) "🤫 מקום שקט אחד" else "🤫 $n מקומות שקטים"
    }
    if (AutoPrefs.scheduleEnabled(ctx) && AutoPrefs.rules(ctx).isNotEmpty()) {
        chips += if (AutoPrefs.inScheduledQuiet(ctx)) "🕐 שעת שקט עכשיו" else "🕐 שעות שקט"
    }
    val alerts = PlaceAlerts.all(ctx).count { it.onArrive || it.onLeave }
    if (alerts > 0) chips += "🔔 $alerts התראות מקום"
    val reminders = AutoPrefs.reminders(ctx).size
    if (reminders > 0) chips += if (reminders == 1) "🛒 תזכורת אחת" else "🛒 $reminders תזכורות"
    return chips
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun HomeScreen(resumes: Int) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val openSettings = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }
    val openAutomations = { ctx.startActivity(Intent(ctx, AutomationsActivity::class.java)) }
    val scheme = MaterialTheme.colorScheme

    // מיקום הגלילה נשמר מחוץ לבנייה מחדש, כדי שאחרי לחיצה או חזרה תישאר איפה שהיית
    val scroll = rememberScrollState()

    key(resumes, refresh) {
        Surface(Modifier.fillMaxSize(), color = scheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(scroll)
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("קיצורים", Modifier.weight(1f), style = MaterialTheme.typography.headlineSmall)
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "הגדרות")
                    }
                }

                // ---------- הפס העליון ----------
                Column(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(26.dp))
                        .background(scheme.primary)
                        .clickable { openAutomations() }
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text("${greeting()} 👋", color = scheme.onPrimary, style = MaterialTheme.typography.titleLarge)
                    Text(today(), color = scheme.onPrimary.copy(alpha = 0.8f))
                    Spacer(Modifier.size(8.dp))
                    val chips = activeChips(ctx)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        (chips.ifEmpty { listOf("אין אוטומציות פעילות · להפעלה ←") }).forEach {
                            Text(
                                it,
                                Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(Color.White.copy(alpha = 0.2f))
                                    .padding(horizontal = 10.dp, vertical = 5.dp),
                                color = scheme.onPrimary,
                                fontSize = 13.sp,
                            )
                        }
                    }
                }

                // סוללה: אחוז מדויק, ובטעינה גם זמן עד מלא, זרם וחום
                BatteryCard()

                // מה קיצורים עשתה לבד היום
                TodayCard()

                // חסרים אישורים: פס אחד שמוביל למסך האישורים
                val missing = Perms.missingCount(ctx)
                if (missing > 0) {
                    Text(
                        "⚠️ חסרים $missing אישורים · לסדר בלחיצה ←",
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(scheme.errorContainer)
                            .clickable { ctx.startActivity(Intent(ctx, PermissionsActivity::class.java)) }
                            .padding(16.dp),
                        color = scheme.onErrorContainer,
                    )
                }

                // ---------- האייקונים, שלושה בשורה ----------
                val tiles: List<Triple<ImageVector, String, () -> Unit>> =
                    QuickAction.entries.map { action ->
                        val label = action.label
                        val onClick: () -> Unit = {
                            if (!Actions.run(ctx, action)) openSettings()
                            refresh++
                        }
                        Triple(action.icon, label, onClick)
                    } + Triple(Icons.Filled.AutoMode, "אוטומציות", openAutomations)

                tiles.chunked(3).forEach { row ->
                    Row(Modifier.fillMaxWidth()) {
                        row.forEach { (icon, label, onClick) ->
                            val lit = false
                            Tile(icon, label, lit, Modifier.weight(1f), onClick)
                        }
                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }

                Text(
                    "🎙️ לחיצה כפולה על הלחצן הצדדי = פקודה קולית · איך מחברים ←",
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { openSettings() }.padding(vertical = 10.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = scheme.primary,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** אייקון גדול עם שם מתחתיו, כמו במסך הבית של הטלפון */
@Composable
private fun Tile(icon: ImageVector, label: String, lit: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    Column(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(66.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(if (lit) scheme.primary else scheme.primaryContainer),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (lit) scheme.onPrimary else scheme.onPrimaryContainer,
                modifier = Modifier.size(30.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, maxLines = 1)
    }
}

/** "מה קרה היום": מה שקיצורים עשתה לבד מחצות (5 אחרונים, ולחיצה מראה את כולם) */
@Composable
private fun TodayCard() {
    val ctx = LocalContext.current
    val events = remember { ActivityLog.today(ctx) }
    var expanded by remember { mutableStateOf(false) }
    val scheme = MaterialTheme.colorScheme
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .clickable(enabled = events.size > 5) { expanded = !expanded }
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("מה קרה היום", style = MaterialTheme.typography.titleMedium)
        if (events.isEmpty()) {
            Text(
                "עוד לא קרה כלום. כשקיצורים תעשה משהו לבד (תענה בנהיגה, תשתיק, תזכיר), זה יופיע כאן.",
                style = MaterialTheme.typography.bodySmall,
                color = scheme.onSurfaceVariant,
            )
        } else {
            (if (expanded) events else events.take(5)).forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(e.emoji, Modifier.padding(end = 8.dp))
                    Text(e.text, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Text(e.clock(), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
                }
            }
            if (events.size > 5 && !expanded) {
                Text("ועוד ${events.size - 5} · לראות הכול", style = MaterialTheme.typography.bodySmall, color = scheme.primary)
            }
        }
    }
}
