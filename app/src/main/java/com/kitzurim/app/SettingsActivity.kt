package com.kitzurim.app

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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KitzurimTheme {
                SettingsScreen(
                    initialPhone = Prefs.phone(this),
                    initialMessage = Prefs.message(this),
                    initialHome = Prefs.home(this),
                    onSave = { phone, message, home ->
                        Prefs.save(this, phone, message, home)
                        Actions.toast(this, "נשמר")
                        finish()
                    },
                    onTest = { phone, message -> Actions.openWhatsApp(this, phone, message) },
                )
            }
        }
    }
}

@Composable
fun SettingsScreen(
    initialPhone: String,
    initialMessage: String,
    initialHome: String,
    onSave: (String, String, String) -> Unit,
    onTest: (String, String) -> Unit,
) {
    var phone by remember { mutableStateOf(initialPhone) }
    var message by remember { mutableStateOf(initialMessage) }
    var home by remember { mutableStateOf(initialHome) }
    var error by remember { mutableStateOf<String?>(null) }

    fun phoneOk(): Boolean {
        val ok = Actions.normalizePhone(phone).length >= 11
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
            Text("הגדרות התפריט העגול", color = MaterialTheme.colorScheme.onSurfaceVariant)

            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it; error = null },
                label = { Text("מספר הוואטסאפ שלך") },
                placeholder = { Text("050-1234567") },
                singleLine = true,
                isError = error != null,
                supportingText = { Text(error ?: "הצ'אט הקבוע ייפתח עם המספר הזה") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = message,
                onValueChange = { message = it },
                label = { Text("ההודעה המוכנה") },
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

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(onClick = { if (phoneOk()) onSave(phone, message, home) }) { Text("שמירה") }
                OutlinedButton(onClick = { if (phoneOk()) onTest(phone, message) }) { Text("בדיקה בוואטסאפ") }
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("איך מחברים ללחצן הצדדי", fontWeight = FontWeight.Medium)
                    Text("1. הגדרות הטלפון ← תכונות מתקדמות ← לחצן צד")
                    Text("2. מפעילים \"לחיצה כפולה\" ובוחרים \"פתיחת אפליקציה\"")
                    Text("3. בוחרים ב\"קיצורים\"")
                    Text(
                        "מעכשיו, לחיצה כפולה על הלחצן הצדדי פותחת את העיגול. לחיצה על גלגל השיניים שבמרכז מחזירה לכאן.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}
