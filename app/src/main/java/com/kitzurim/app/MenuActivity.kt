package com.kitzurim.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FlashlightOn
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhotoCamera
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** הכפתורים שבעיגול, לפי הסדר (מלמעלה, עם כיוון השעון) */
enum class QuickAction(val label: String, val icon: ImageVector) {
    CHAT("צ'אט", Icons.Filled.Forum),
    MESSAGE("הודעה מוכנה", Icons.AutoMirrored.Filled.Send),
    FLASH("פנס", Icons.Filled.FlashlightOn),
    NOTE("פתק", Icons.Filled.EditNote),
    CAMERA("מצלמה", Icons.Filled.PhotoCamera),
    HOME("הביתה", Icons.Filled.Home),
}

/** נפתח מהלחיצה הכפולה על הלחצן הצדדי: שקוף מעל המסך, עם הכפתורים בעיגול */
class MenuActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // פעם ראשונה: עוד אין מספר טלפון, אז פותחים קודם את ההגדרות
        if (Prefs.phone(this).isBlank()) {
            openSettings()
            return
        }
        setContent {
            KitzurimTheme {
                RadialMenu(
                    onAction = { runAction(it) },
                    onSettings = { openSettings() },
                    onDismiss = { close() },
                )
            }
        }
    }

    // לחיצה כפולה נוספת כשהתפריט פתוח: סוגרת אותו
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        close()
    }

    // יצאנו מהתפריט (נפתחה אפליקציה אחרת): לא משאירים אותו פתוח מאחור
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) finish()
    }

    private fun openSettings() {
        startActivity(Intent(this, SettingsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        close()
    }

    private fun close() {
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    private fun runAction(action: QuickAction) {
        when (action) {
            QuickAction.CHAT -> Actions.openWhatsApp(this, Prefs.phone(this), null)
            QuickAction.MESSAGE -> Actions.openWhatsApp(this, Prefs.phone(this), Prefs.message(this))
            QuickAction.FLASH -> Actions.toggleTorch(this)
            QuickAction.NOTE -> startActivity(
                Intent(this, NoteActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            QuickAction.CAMERA -> Actions.openCamera(this)
            QuickAction.HOME -> {
                // עוד אין כתובת בית: פותחים את ההגדרות כדי להקליד אותה
                if (Prefs.home(this).isBlank()) {
                    Actions.toast(this, "צריך להקליד כתובת בית בהגדרות")
                    openSettings()
                    return
                }
                Actions.navigateHome(this, Prefs.home(this))
            }
        }
        close()
    }
}

@Composable
fun RadialMenu(onAction: (QuickAction) -> Unit, onSettings: () -> Unit, onDismiss: () -> Unit) {
    val scrim = remember { Animatable(0f) }
    LaunchedEffect(Unit) { scrim.animateTo(1f, tween(200)) }
    val actions = QuickAction.entries

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f * scrim.value))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        actions.forEachIndexed { i, action ->
            // כל כפתור "קופץ" מהמרכז החוצה, קצת אחרי הקודם
            val p = remember { Animatable(0f) }
            LaunchedEffect(Unit) {
                delay(i * 35L)
                p.animateTo(1f, spring(dampingRatio = 0.62f, stiffness = 450f))
            }
            val angle = -PI / 2 + i * 2 * PI / actions.size
            val r = 118.dp * p.value
            Box(
                Modifier
                    .absoluteOffset(x = r * cos(angle).toFloat(), y = r * sin(angle).toFloat())
                    .graphicsLayer {
                        scaleX = p.value
                        scaleY = p.value
                        alpha = p.value.coerceIn(0f, 1f)
                    }
            ) {
                ActionBubble(action) { onAction(action) }
            }
        }

        // במרכז: גלגל שיניים להגדרות
        Box(
            Modifier
                .graphicsLayer { alpha = scrim.value }
                .size(46.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.85f))
                .clickable(onClick = onSettings),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Settings, contentDescription = "הגדרות", tint = MaterialTheme.colorScheme.onSurface)
        }
    }
}

@Composable
private fun ActionBubble(action: QuickAction, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(88.dp)) {
        Box(
            Modifier
                .size(62.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.primaryContainer)
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                action.icon,
                contentDescription = action.label,
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(28.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(action.label, color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center, maxLines = 1)
    }
}
