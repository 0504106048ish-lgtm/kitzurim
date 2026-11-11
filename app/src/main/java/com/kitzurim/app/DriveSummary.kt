package com.kitzurim.app

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

object DriveSummary {
    /** בסוף הנסיעה: התראה "בזמן הנסיעה: 4 הודעות מ-3 אנשים" שפותחת את הסיכום */
    fun show(ctx: Context) {
        if (!AutoPrefs.summaryEnabled(ctx)) return
        val msgs = AutoPrefs.driveMessages(ctx)
        if (msgs.isEmpty()) return
        val chats = msgs.map { it.chat }.distinct()
        val who = chats.take(3).joinToString(", ") + if (chats.size > 3) " ועוד" else ""
        Notify.post(
            ctx, 10,
            "🚗 בזמן הנסיעה: ${msgs.size} הודעות מ-${chats.size} צ'אטים",
            who,
            Intent(ctx, DriveSummaryActivity::class.java),
        )
    }
}

/** מסך הסיכום: ההודעות שהגיעו בנסיעה, לפי צ'אט, עם כפתור לפתיחת כל צ'אט */
class DriveSummaryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // שורת המצב: סמלים בהירים על הרקע הכהה
        neonBars()
        setContent { KitzurimTheme { DriveSummaryScreen { finish() } } }
    }
}

@Composable
fun DriveSummaryScreen(onDone: () -> Unit) {
    val ctx = LocalContext.current
    var msgs by remember { mutableStateOf(AutoPrefs.driveMessages(ctx)) }
    val byChat = msgs.groupBy { it.chat }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("מה פספסת בנסיעה", style = MaterialTheme.typography.headlineMedium)
            Text(
                if (msgs.isEmpty()) "לא הגיעו הודעות בנסיעה האחרונה"
                else "${msgs.size} הודעות מ-${byChat.size} צ'אטים",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            byChat.forEach { (chat, list) ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(chat, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            Text("${list.size}", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Medium)
                        }
                        list.forEach { m ->
                            Row {
                                Text(m.time, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("  " + m.text, Modifier.weight(1f))
                            }
                        }
                        val number = list.firstOrNull { it.number.isNotBlank() }?.number
                        OutlinedButton(onClick = {
                            if (number != null) {
                                Actions.openWhatsApp(ctx, number, null)
                            } else {
                                ctx.packageManager.getLaunchIntentForPackage("com.whatsapp")
                                    ?.let { ctx.startActivity(it) }
                            }
                        }) { Text(if (number != null) "פתיחת הצ'אט" else "פתיחת וואטסאפ") }
                    }
                }
            }
            if (msgs.isNotEmpty()) {
                TextButton(onClick = {
                    AutoPrefs.clearDriveMessages(ctx)
                    msgs = emptyList()
                    onDone()
                }) { Text("קראתי הכול, לנקות") }
            }
        }
    }
}
