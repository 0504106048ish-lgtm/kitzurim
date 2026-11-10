package com.kitzurim.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlin.math.abs
import kotlin.math.roundToInt

/** מצב הסוללה ברגע הזה */
data class BatteryInfo(
    val percent: Int,
    val charging: Boolean,
    val full: Boolean,
    val plug: String,
    val currentMa: Int?,
    val tempC: Double?,
    val minutesToFull: Long?,
)

object Battery {
    fun read(ctx: Context, sticky: Intent?): BatteryInfo {
        val i = sticky ?: ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val level = i?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = i?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        // האחוז המדויק שהטלפון מדווח (כמו בשורת המצב)
        val percent = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }
            ?: if (level >= 0 && scale > 0) (level * 100f / scale).roundToInt() else 0
        val status = i?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val charging = status == BatteryManager.BATTERY_STATUS_CHARGING
        val full = status == BatteryManager.BATTERY_STATUS_FULL
        val plug = when (i?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) {
            BatteryManager.BATTERY_PLUGGED_AC -> "מטען קיר"
            BatteryManager.BATTERY_PLUGGED_USB -> "USB"
            BatteryManager.BATTERY_PLUGGED_WIRELESS -> "אלחוטי"
            else -> ""
        }
        // זרם: סמסונג מדווחת במיקרו־אמפר או במילי־אמפר, לפי הדגם
        val current = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
            ?.takeIf { it != Int.MIN_VALUE && it != 0 }
            ?.let { if (abs(it) > 20_000) it / 1000 else it }
        val temp = i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }?.let { it / 10.0 }
        val toFull = if (Build.VERSION.SDK_INT >= 28 && charging) {
            bm?.computeChargeTimeRemaining()?.takeIf { it > 0 }?.let { it / 60_000 }
        } else {
            null
        }
        return BatteryInfo(percent, charging, full, plug, current, temp, toFull)
    }

    fun minutesText(m: Long): String = when {
        m < 60 -> "$m דק'"
        m % 60 == 0L -> "${m / 60} שע'"
        else -> "${m / 60} שע' ו-${m % 60} דק'"
    }
}

/** כרטיס הסוללה במסך הפתיחה: מתעדכן לבד כל עוד המסך פתוח */
@Composable
fun BatteryCard() {
    val ctx = LocalContext.current
    var info by remember { mutableStateOf(Battery.read(ctx, null)) }

    // כל שינוי בסוללה (אחוז, חיבור או ניתוק מטען)
    DisposableEffect(Unit) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                info = Battery.read(c, intent)
            }
        }
        ctx.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        onDispose { ctx.unregisterReceiver(receiver) }
    }
    // הזרם והזמן עד מלא משתנים כל הזמן: מרעננים כל 3 שניות
    LaunchedEffect(Unit) {
        while (true) {
            delay(3000)
            info = Battery.read(ctx, null)
        }
    }

    val scheme = MaterialTheme.colorScheme
    val color = when {
        info.charging || info.full -> scheme.primary
        info.percent <= 15 -> scheme.error
        else -> scheme.onSurface
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(scheme.surfaceContainerHigh)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (info.charging) "⚡" else "🔋",
                fontSize = 26.sp,
            )
            Text(
                "${info.percent}%",
                Modifier.padding(horizontal = 10.dp),
                fontSize = 34.sp,
                fontWeight = FontWeight.Medium,
                color = color,
            )
            Text(
                when {
                    info.full -> "מלאה"
                    info.charging -> "בטעינה" + if (info.plug.isNotBlank()) " · ${info.plug}" else ""
                    else -> "לא בטעינה"
                },
                color = scheme.onSurfaceVariant,
            )
        }
        // פס הסוללה
        Box(
            Modifier
                .fillMaxWidth()
                .height(10.dp)
                .clip(RoundedCornerShape(5.dp))
                .background(scheme.surfaceVariant),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(info.percent / 100f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(5.dp))
                    .background(color),
            )
        }
        // פרטים
        val details = buildList {
            if (info.charging) info.minutesToFull?.let { add("מלאה בעוד ${Battery.minutesText(it)}") }
            info.currentMa?.let { if (info.charging) add("${abs(it)} mA") }
            info.tempC?.let { add("${"%.1f".format(it)}°C") }
        }
        if (details.isNotEmpty()) {
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = scheme.onSurfaceVariant)
        }
    }
}
