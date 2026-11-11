package com.kitzurim.app

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/** כל האישורים שהאפליקציה צריכה, ומה חסר */
object Perms {
    private fun granted(ctx: Context, p: String) =
        ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    /** ההרשאות שקופצות בחלון רגיל (אפשר לבקש את כולן יחד) */
    fun runtime(): List<String> = buildList {
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.CALL_PHONE)
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT >= 31) add(Manifest.permission.BLUETOOTH_CONNECT)
    }

    fun runtimeMissing(ctx: Context) = runtime().filter { !granted(ctx, it) }
    fun bgLocationMissing(ctx: Context) =
        Build.VERSION.SDK_INT >= 29 && !granted(ctx, Manifest.permission.ACCESS_BACKGROUND_LOCATION)
    fun batteryMissing(ctx: Context) =
        ctx.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(ctx.packageName) != true
    fun dndMissing(ctx: Context) = !QuietPlaces.dndAllowed(ctx)
    fun listenerMissing(ctx: Context) = !Driving.listenerAllowed(ctx)
    fun accessibilityMissing(ctx: Context) = !AutoSend.allowed(ctx)

    /** כמה אישורים חסרים (בשביל הפס במסך הפתיחה) */
    fun missingCount(ctx: Context): Int = listOf(
        runtimeMissing(ctx).isNotEmpty(),
        bgLocationMissing(ctx),
        batteryMissing(ctx),
        dndMissing(ctx),
        listenerMissing(ctx),
        accessibilityMissing(ctx),
    ).count { it }

    /** פותח ישר את המתג של קיצורים בגישה להתראות (ולא את כל הרשימה) */
    fun listenerIntent(ctx: Context): Intent {
        val me = ComponentName(ctx, ReplyListener::class.java).flattenToString()
        return if (Build.VERSION.SDK_INT >= 30) {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, me)
        } else {
            Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        }
    }

    /** פותח ישר את "קיצורים: שליחה אוטומטית" בנגישות (ולא את כל הרשימה) */
    fun accessibilityIntent(ctx: Context): Intent {
        val me = ComponentName(ctx, AutoSendService::class.java).flattenToString()
        return Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS")
            .putExtra(Intent.EXTRA_COMPONENT_NAME, me)
    }
}

/** מסך האישורים: הכול במקום אחד, עם כפתור "לאשר הכול" שעובר אחד אחרי השני */
class PermissionsActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // שורת המצב: סמלים כהים על רקע בהיר (ובהירים במצב כהה), כדי שיהיה אפשר לקרוא אותה
        enableEdgeToEdge()
        setContent { KitzurimTheme { PermissionsScreen(resumes.intValue) { finish() } } }
    }
}

/** צעד אחד ברשימת האישורים */
private class PermStep(
    val id: String,
    val title: String,
    val why: String,
    val missing: () -> Boolean,
    val launch: () -> Unit,
)

private fun openSafely(ctx: Context, intent: Intent, fallback: Intent? = null) {
    try {
        ctx.startActivity(intent)
    } catch (_: Exception) {
        try {
            if (fallback != null) ctx.startActivity(fallback)
        } catch (_: Exception) {
            Actions.toast(ctx, "לא הצלחתי לפתוח את ההגדרות")
        }
    }
}

@Composable
fun PermissionsScreen(resumes: Int, onDone: () -> Unit) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val scroll = rememberScrollState()
    var wizard by rememberSaveable { mutableStateOf(false) }
    var tried by rememberSaveable { mutableStateOf("") }
    // "הגדרות מוגבלות" אי אפשר לבדוק אם שוחררו, אז זוכרים שכבר עברת שם
    var unlockedVisited by rememberSaveable { mutableStateOf(false) }

    val multi = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }
    val single = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refresh++ }
    val appDetails = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))

    val steps = listOf(
        PermStep(
            "unlock", "שחרור \"הגדרות מוגבלות\"",
            "כי קיצורים לא הותקנה מחנות Play. במסך שנפתח: ⋮ למעלה ← \"אפשר הגדרות מוגבלות\", ואז חזרה.",
            { !unlockedVisited && (Perms.listenerMissing(ctx) || Perms.accessibilityMissing(ctx)) },
        ) {
            unlockedVisited = true
            Actions.toast(ctx, "⋮ למעלה ← \"אפשר הגדרות מוגבלות\" ← חזרה")
            openSafely(ctx, appDetails)
        },
        PermStep(
            "runtime", "אנשי קשר, שיחות, מיקום, התראות ובלוטות'",
            "חלונות קטנים קופצים אחד אחרי השני. בכל אחד: \"אפשר\".",
            { Perms.runtimeMissing(ctx).isNotEmpty() },
        ) { multi.launch(Perms.runtimeMissing(ctx).toTypedArray()) },
        PermStep(
            "bgloc", "מיקום \"כל הזמן\"",
            "בשביל שקט לפי מקום ותזכורות. לבחור \"לאפשר כל הזמן\" ולחזור.",
            {
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION) ==
                    PackageManager.PERMISSION_GRANTED && Perms.bgLocationMissing(ctx)
            },
        ) {
            Actions.toast(ctx, "לבחור \"לאפשר כל הזמן\" ולחזור")
            single.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
        },
        PermStep(
            "battery", "ריצה ברקע",
            "כדי שסמסונג לא תעצור את האוטומציות. בחלון שקופץ: \"אפשר\".",
            { Perms.batteryMissing(ctx) },
        ) {
            openSafely(
                ctx,
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + ctx.packageName)),
            )
        },
        PermStep(
            "dnd", "\"נא לא להפריע\"",
            "כדי להעביר לשקט מלא ולא רק לרטט. להפעיל את \"קיצורים\" ולחזור.",
            { Perms.dndMissing(ctx) },
        ) {
            Actions.toast(ctx, "להפעיל את \"קיצורים\" ולחזור")
            openSafely(ctx, Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS))
        },
        PermStep(
            "listener", "גישה להתראות",
            "בשביל מענה בנהיגה וסיכום הנסיעה. להפעיל את המתג ולחזור.",
            { Perms.listenerMissing(ctx) },
        ) {
            Actions.toast(ctx, "להפעיל את המתג ולחזור")
            openSafely(ctx, Perms.listenerIntent(ctx), Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        },
        PermStep(
            "accessibility", "החור של המצלמה",
            "הטבעת, הנגיעות בחור, ושליחה אוטומטית בוואטסאפ. להפעיל את המתג, לאשר ולחזור.",
            { Perms.accessibilityMissing(ctx) },
        ) {
            Actions.toast(ctx, "להפעיל את \"קיצורים: החור של המצלמה\" ולחזור")
            openSafely(ctx, Perms.accessibilityIntent(ctx), Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        },
    )

    fun nextStep() {
        val done = tried.split(",").filter { it.isNotBlank() }
        val step = steps.firstOrNull { it.missing() && it.id !in done }
        if (step == null) {
            wizard = false
            tried = ""
            refresh++
        } else {
            tried = (done + step.id).joinToString(",")
            step.launch()
        }
    }

    // חזרנו לאפליקציה (מאישור או ממסך הגדרות): ממשיכים לבא
    LaunchedEffect(resumes) { if (wizard) nextStep() }

    key(resumes, refresh) {
        // "שחרור" לא נספר: הוא רק צעד הכנה
        val real = steps.filter { it.id != "unlock" }
        val missing = real.filter { it.missing() }
        val doneCount = real.size - missing.size
        val visible = steps.filter { it.missing() }

        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(scroll)
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Text("אישורים", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "$doneCount מתוך ${real.size} מאושרים",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                LinearProgressIndicator(
                    progress = { doneCount.toFloat() / real.size },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                )

                if (missing.isEmpty()) {
                    Column(
                        Modifier.fillMaxWidth().padding(vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text("✓", fontSize = 64.sp, color = MaterialTheme.colorScheme.primary)
                        Text("הכול מאושר", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            "כל האוטומציות יכולות לעבוד עכשיו",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                        Button(onClick = onDone) { Text("סיום") }
                    }
                } else {
                    Button(
                        onClick = {
                            wizard = true
                            tried = ""
                            nextStep()
                        },
                        modifier = Modifier.fillMaxWidth().height(56.dp),
                    ) { Text("✓ לאשר הכול", fontSize = 18.sp) }
                    Text(
                        "כל אישור נפתח בתורו. מאשרים, לוחצים \"חזרה\", והבא נפתח לבד.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    // רק מה שחסר. מה שאושר נעלם מהרשימה
                    visible.forEachIndexed { i, step ->
                        Card(Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .size(32.dp)
                                        .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        "${i + 1}",
                                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                                        fontWeight = FontWeight.Medium,
                                    )
                                }
                                Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                    Text(step.title, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        step.why,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                TextButton(onClick = { step.launch() }) { Text("לאשר") }
                            }
                        }
                    }

                    // אם סמסונג עדיין חוסמת
                    if (Perms.listenerMissing(ctx) || Perms.accessibilityMissing(ctx)) {
                        Text(
                            "עדיין כתוב \"האפליקציה לא תקבל גישה\"? הגדרות ← אבטחה ופרטיות ← \"חסימה אוטומטית\" ← לכבות, לאשר כאן, ואז אפשר להדליק שוב.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}
