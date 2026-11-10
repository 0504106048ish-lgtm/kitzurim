package com.kitzurim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import java.util.Calendar

/** המסך הראשי: נכנסים לאפליקציה ובוחרים פעולה. (הלחצן הצדדי והעיגול יחוברו בהמשך) */
class HomeActivity : ComponentActivity() {
    private val resumes = mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes.intValue++
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { KitzurimTheme { HomeScreen(resumes.intValue) } }
    }
}

private fun describe(a: QuickAction, torchOn: Boolean): String = when (a) {
    QuickAction.CHAT -> "הצ'אט עם עצמך בוואטסאפ"
    QuickAction.MESSAGE -> "הודעה כתובה מראש"
    QuickAction.FLASH -> if (torchOn) "דולק עכשיו" else "הדלקה וכיבוי"
    QuickAction.NOTE -> "לרשום משהו מהר"
    QuickAction.CAMERA -> "צילום מהיר"
    QuickAction.HOME -> "ניווט בוויז"
}

private fun greeting(): String = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
    in 5..11 -> "בוקר טוב"
    in 12..16 -> "צהריים טובים"
    in 17..20 -> "ערב טוב"
    else -> "לילה טוב"
}

private fun activeAutomations(ctx: Context): Int = listOf(
    AutoPrefs.drivingEnabled(ctx),
    AutoPrefs.nightCallsEnabled(ctx) && NightCalls.roleHeld(ctx),
    AutoPrefs.quietEnabled(ctx),
).count { it }

@Composable
fun HomeScreen(resumes: Int) {
    val ctx = LocalContext.current
    var refresh by remember { mutableIntStateOf(0) }
    val openSettings = { ctx.startActivity(Intent(ctx, SettingsActivity::class.java)) }

    key(resumes, refresh) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(greeting(), color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("קיצורים", style = MaterialTheme.typography.headlineMedium)
                    }
                    IconButton(onClick = openSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "הגדרות")
                    }
                }

                // פעם ראשונה: עוד אין מספר וואטסאפ
                if (Prefs.phone(ctx).isBlank()) {
                    Card(
                        onClick = openSettings,
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text(
                            "להתחלה: להקליד את מספר הוואטסאפ שלך ←",
                            Modifier.padding(16.dp),
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                }

                // הפעולות, שתיים בשורה
                QuickAction.entries.chunked(2).forEach { pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        pair.forEach { action ->
                            val highlighted = action == QuickAction.FLASH && Torch.on
                            ActionCard(
                                icon = action.icon,
                                title = if (highlighted) "פנס דולק" else action.label,
                                subtitle = describe(action, Torch.on),
                                highlighted = highlighted,
                                modifier = Modifier.weight(1f),
                            ) {
                                if (!Actions.run(ctx, action)) openSettings()
                                refresh++
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }

                // אוטומציות
                val active = activeAutomations(ctx)
                ActionCard(
                    icon = Icons.Filled.AutoMode,
                    title = "אוטומציות",
                    subtitle = if (active == 0) "נהיגה, לילה, שקט לפי מקום" else "$active פעילות עכשיו",
                    highlighted = active > 0,
                    modifier = Modifier.fillMaxWidth(),
                ) { ctx.startActivity(Intent(ctx, AutomationsActivity::class.java)) }

                Text(
                    "בהמשך: פתיחה מהירה בלחיצה כפולה על הלחצן הצדדי",
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun ActionCard(
    icon: ImageVector,
    title: String,
    subtitle: String,
    highlighted: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (highlighted) scheme.primaryContainer else scheme.surfaceContainerHigh,
        ),
        modifier = modifier,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                Modifier
                    .size(46.dp)
                    .background(if (highlighted) scheme.primary else scheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = if (highlighted) scheme.onPrimary else scheme.onPrimaryContainer,
                )
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
            }
        }
    }
}
