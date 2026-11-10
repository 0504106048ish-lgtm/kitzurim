package com.kitzurim.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp

/** פתק מהיר: חלון קטן בתחתית המסך, המקלדת נפתחת מיד */
class NoteActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            KitzurimTheme {
                NoteScreen(
                    initialNotes = Prefs.notes(this),
                    onSave = { text ->
                        Prefs.setNotes(this, listOf(text) + Prefs.notes(this))
                        Actions.toast(this, "הפתק נשמר")
                        finish()
                    },
                    onSendToMe = { text ->
                        Actions.openWhatsApp(this, Prefs.phone(this), text)
                        finish()
                    },
                    onDelete = { index ->
                        val left = Prefs.notes(this).filterIndexed { i, _ -> i != index }
                        Prefs.setNotes(this, left)
                        left
                    },
                    onDismiss = { finish() },
                )
            }
        }
    }
}

@Composable
fun NoteScreen(
    initialNotes: List<String>,
    onSave: (String) -> Unit,
    onSendToMe: (String) -> Unit,
    onDelete: (Int) -> List<String>,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf(initialNotes) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) {
        focus.requestFocus()
        keyboard?.show()
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.5f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            )
            .systemBarsPadding()
            .imePadding(),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("פתק מהיר", style = MaterialTheme.typography.titleMedium)
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text("מה לזכור?") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { if (text.isNotBlank()) onSave(text.trim()) }) { Text("שמירה") }
                    OutlinedButton(onClick = { if (text.isNotBlank()) onSendToMe(text.trim()) }) {
                        Text("שליחה לעצמי")
                    }
                }
                if (notes.isNotEmpty()) {
                    HorizontalDivider()
                    Text(
                        "פתקים שמורים",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        notes.forEachIndexed { i, note ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(note, Modifier.weight(1f).padding(vertical = 6.dp))
                                IconButton(onClick = { notes = onDelete(i) }) {
                                    Icon(Icons.Filled.Delete, contentDescription = "מחיקה")
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
