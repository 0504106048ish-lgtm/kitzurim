package com.kitzurim.app

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** הצבעים של קיצורים: כהה וזוהר, כמו הטבעת סביב המצלמה */
object Neon {
    val Bg = Color(0xFF0B0E14)
    val Card = Color(0xFF141924)
    val Tile = Color(0xFF1A2030)
    val Line = Color(0xFF1F2633)
    val Green = Color(0xFF2AD49A)
    val GreenSoft = Color(0xFF7EF0C0)
    val Text = Color(0xFFE8EDF5)
    val Muted = Color(0xFF8B95A7)
}

private val NeonScheme = darkColorScheme(
    primary = Neon.Green,
    onPrimary = Neon.Bg,
    primaryContainer = Neon.Tile,
    onPrimaryContainer = Neon.GreenSoft,
    secondary = Neon.GreenSoft,
    onSecondary = Neon.Bg,
    secondaryContainer = Neon.Tile,
    onSecondaryContainer = Neon.GreenSoft,
    tertiary = Color(0xFF4FB3FF),
    tertiaryContainer = Color(0xFF14283D),
    onTertiaryContainer = Color(0xFFA8D6FF),
    background = Neon.Bg,
    onBackground = Neon.Text,
    surface = Neon.Bg,
    onSurface = Neon.Text,
    surfaceVariant = Neon.Tile,
    onSurfaceVariant = Neon.Muted,
    surfaceContainerLowest = Neon.Bg,
    surfaceContainerLow = Neon.Card,
    surfaceContainer = Neon.Card,
    surfaceContainerHigh = Neon.Card,
    surfaceContainerHighest = Neon.Card,
    outline = Color(0xFF2A3142),
    outlineVariant = Neon.Line,
    error = Color(0xFFFF6B6B),
    errorContainer = Color(0xFF3A1A1F),
    onErrorContainer = Color(0xFFFFB4B4),
)

/** כל המסכים של קיצורים: כהה וזוהר (תמיד, גם כשהטלפון במצב בהיר) */
@Composable
fun KitzurimTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = NeonScheme, content = content)
}

/** שורת המצב ושורת הניווט: שקופות עם סמלים בהירים, על הרקע הכהה */
fun ComponentActivity.neonBars() {
    enableEdgeToEdge(
        statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
    )
}
