package com.kitzurim.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Switch
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // שורת המצב: סמלים בהירים על הרקע הכהה
        neonBars()
        setContent {
            KitzurimTheme {
                SettingsScreen(
                    initialPhone = Prefs.phone(this),
                    initialHome = Prefs.home(this),
                    onSave = { phone, home ->
                        Prefs.save(this, phone, home)
                        Actions.toast(this, "נשמר")
                        finish()
                    },
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    initialPhone: String,
    initialHome: String,
    onSave: (String, String) -> Unit,
) {
    var phone by remember { mutableStateOf(initialPhone) }
    var home by remember { mutableStateOf(initialHome) }
    var error by remember { mutableStateOf<String?>(null) }

    // המספר לא חובה. אם הקלדת, הוא צריך להיות מלא
    fun phoneOk(): Boolean {
        val ok = phone.isBlank() || Actions.normalizePhone(phone).length >= 11
        if (!ok) error = "צריך מספר טלפון מלא, למשל 050-1234567"
        return ok
    }

    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            Modifier
                .fillMaxSize()
                .systemBarsPadding()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text("קיצורים", style = MaterialTheme.typography.headlineMedium)
            Text("הגדרות", color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; error = null },
                label = { Text("מספר הוואטסאפ שלך") },
                placeholder = { Text("050-1234567") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "לא חובה. בשביל הפקודה הקולית \"שלח לעצמי...\"") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = home,
                onValueChange = { home = it },
                label = { Text("כתובת הבית") },
                placeholder = { Text("רחוב הרצל 10, ירושלים") },
                supportingText = { Text("הכפתור \"הביתה\" מנווט לכאן בוויז") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )

            Button(onClick = { if (phoneOk()) onSave(phone, home) }) { Text("שמירה") }

            val ctx = LocalContext.current
            OutlinedButton(
                onClick = { ctx.startActivity(Intent(ctx, AutomationsActivity::class.java)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("⚙️ אוטומציות: נהיגה, לילה, שקט לפי מקום") }

            // חיבור ללחצן הצדדי: לחיצה כפולה פותחת את הפקודה הקולית
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("🎙️ חיבור ללחצן הצדדי", fontWeight = FontWeight.Medium)
                    Text("ככה לחיצה כפולה על הלחצן הצדדי פותחת ישר את הפקודה הקולית:")
                    var voiceFirst by remember { mutableStateOf(Prefs.voiceFirst(ctx)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("1. להדליק: פתיחה ישר לפקודה קולית", Modifier.weight(1f))
                        Switch(checked = voiceFirst, onCheckedChange = {
                            voiceFirst = it
                            Prefs.setVoiceFirst(ctx, it)
                        })
                    }
                    Text("2. הגדרות הטלפון ← תכונות מתקדמות ← לחצן צד")
                    Text("3. מפעילים \"לחיצה כפולה\", בוחרים \"פתיחת אפליקציה\" ← \"קיצורים\"")
                    Text(
                        "מעכשיו גם האייקון של קיצורים פותח ישר את הדיבור. לאפליקציה עצמה: לוחצים \"חזרה\" בחלון הדיבור.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedButton(onClick = {
                        try {
                            ctx.startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
                        } catch (_: Exception) {
                        }
                    }) { Text("לפתוח את הגדרות הטלפון") }
                }
            }

        }
    }
}
