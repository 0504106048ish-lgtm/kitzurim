package com.kitzurim.app

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** מה חדש בגרסה הזו (מתעדכן עם כל גרסה) */
private val WHATS_NEW = listOf(
    "🗂 תפריט ראשי חדש: אריחים לפי נושא, וחיפוש הגדרות",
    "▶ הדמיה ליד כל אפשרות בגלולה החיה",
    "💧 כל הגלולות בזכוכית כהה עם ברק",
    "💬 הודעה בשורה אחת שלא יורדת, והטקסט זז עד הסוף",
    "🎵 המסגרת של גלולת המוזיקה מתמלאת לפי השיר, וכחולה כשמשנים עוצמה",
    "📞 מסך שיחה בניאון עם סמלים בקו דק",
    "📞 הגלולה מתרחבת למסך השיחה ומתכווצת חזרה לחור",
    "📞 בזמן שיחה רק סימן קטן ליד המצלמה, והקשה עליו פותחת את השיחה",
    "📞 התראת שיחה עם השתק, רמקול ונתק",
    "🔄 עדכון ישר מהאפליקציה, בלי דף אינטרנט",
)

/** הגדרות ← עדכונים, מה חדש, ופרטים על האפליקציה */
@Composable
fun AboutSection() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val scheme = MaterialTheme.colorScheme
    val mine = remember { Updater.installed(ctx) }

    // ---------- עדכונים ----------
    var checking by remember { mutableStateOf(true) }
    var release by remember { mutableStateOf<Updater.Release?>(null) }
    var failed by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }
    var showOld by remember { mutableStateOf(false) }

    suspend fun check() {
        checking = true
        failed = false
        val r = Updater.latest(ctx)
        release = r
        failed = r == null
        checking = false
    }
    LaunchedEffect(Unit) { check() }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("🔄 עדכונים", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            val r = release
            when {
                checking -> Text("בודק אם יש גרסה חדשה…", color = scheme.onSurfaceVariant)
                failed -> {
                    Text("לא הצלחתי לבדוק. אין אינטרנט?", color = scheme.onSurfaceVariant)
                    TextButton(onClick = { scope.launch { check() } }) { Text("לבדוק שוב") }
                }
                r != null && r.number > mine -> {
                    Text("✨ גרסה ${r.number} מוכנה להורדה", fontWeight = FontWeight.Bold, color = Neon.Green)
                    Text(
                        "יש לך גרסה $mine · ${"%.1f".format(r.size / 1_048_576f)} מ״ב · ${r.date}",
                        style = MaterialTheme.typography.bodySmall,
                        color = scheme.onSurfaceVariant,
                    )
                    // מה חדש בגרסאות החדשות (מהכי חדשה)
                    r.notes.forEach { (n, body) ->
                        if (body.isNotBlank()) {
                            Text("גרסה $n", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                            body.split(" · ", "\n").map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Co-Authored") }.forEach {
                                Text("• $it", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                    if (downloading) {
                        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
                        Text(if (progress >= 1f) "פותח את ההתקנה…" else "מוריד… ${(progress * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                    } else {
                        Button(onClick = {
                            downloading = true
                            error = null
                            progress = 0f
                            scope.launch {
                                error = Updater.downloadAndInstall(ctx, r) { progress = it }
                                downloading = false
                            }
                        }, modifier = Modifier.fillMaxWidth()) { Text("⬇️ להוריד ולהתקין עכשיו") }
                    }
                    error?.let { Text(it, color = scheme.error, style = MaterialTheme.typography.bodySmall) }
                }
                else -> {
                    Text("✓ יש לך את הגרסה הכי חדשה ($mine)", color = Neon.Green)
                    TextButton(onClick = { scope.launch { check() } }) { Text("לבדוק שוב") }
                }
            }
        }
    }

    // ---------- מה חדש ----------
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("✨ מה חדש בגרסה $mine", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            WHATS_NEW.forEach { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }
    }

    // ---------- פרטים על האפליקציה ----------
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("ℹ️ על האפליקציה", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            val info = remember { runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0) }.getOrNull() }
            val fmt = remember { SimpleDateFormat("d.M.yyyy · HH:mm", Locale("he")) }
            val apkSize = remember { runCatching { File(ctx.applicationInfo.sourceDir).length() }.getOrDefault(0L) }
            fun yes(b: Boolean) = if (b) "✓ פעיל" else "✕ כבוי"

            InfoRow("גרסה", "$mine (${Updater.versionName(ctx)})")
            info?.let {
                InfoRow("הותקנה לראשונה", fmt.format(Date(it.firstInstallTime)))
                InfoRow("עודכנה לאחרונה", fmt.format(Date(it.lastUpdateTime)))
            }
            InfoRow("גודל", "${"%.1f".format(apkSize / 1_048_576f)} מ״ב")
            HorizontalDivider()
            Text("מצב", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            InfoRow("החור של המצלמה (נגישות)", yes(AutoSend.allowed(ctx)))
            InfoRow("גישה להתראות", yes(Driving.listenerAllowed(ctx)))
            InfoRow("אפליקציית הטלפון", yes(Calls.isDefault(ctx)))
            InfoRow("הגלולה החיה", yes(NotchPrefs.pill(ctx)))
            InfoRow("טבעת סוללה", yes(NotchPrefs.ringOn(ctx)))
            InfoRow("תנועות מוגדרות", "${Gesture.entries.count { NotchPrefs.action(ctx, it).type != ActionType.NONE }} מתוך ${Gesture.entries.size}")
            InfoRow("אפליקציות עם תנועות משלהן", "${NotchPrefs.apps(ctx).size}")
            InfoRow("אפליקציות שבהן קיצורים כבוי", "${NotchPrefs.offApps(ctx).size}")
            if (Build.VERSION.SDK_INT >= 26) InfoRow("התקנת עדכונים", yes(ctx.packageManager.canRequestPackageInstalls()))
            HorizontalDivider()
            Text("הטלפון", style = MaterialTheme.typography.labelLarge, color = scheme.onSurfaceVariant)
            InfoRow("דגם", "${Build.MANUFACTURER.replaceFirstChar { it.uppercase() }} ${Build.MODEL}")
            InfoRow("אנדרואיד", "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            InfoRow("מזהה האפליקציה", ctx.packageName)
            TextButton(onClick = {
                runCatching {
                    ctx.startActivity(
                        Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            }) { Text("פרטי האפליקציה בהגדרות הטלפון ←") }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}
