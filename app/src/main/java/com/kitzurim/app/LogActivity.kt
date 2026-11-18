package com.kitzurim.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** מסך "מה קרה היום": מה קיצורים עשתה לבד, וזיהוי השיחות (🔎) — מה זוהה, ומה לא ולמה */
class LogActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.rgb(11, 13, 19)))
        setContent { KitzurimTheme { LogScreen(onBack = { finish() }) } }
    }
}

@Composable
private fun LogScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    var reload by remember { mutableIntStateOf(0) }
    val events = remember(reload) { ActivityLog.all(ctx) }
    Column(
        Modifier
            .fillMaxSize()
            .background(Color(0xFF0B0D13))
            .statusBarsPadding()
            .navigationBarsPadding()
            .padding(horizontal = 16.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("מה קרה היום", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.weight(1f))
            Text(
                "רענן",
                color = Color(0xFF2FD39A),
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { reload++ }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
            Text(
                "סגור",
                color = Color(0xFF8B93A0),
                fontSize = 14.sp,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { onBack() }.padding(horizontal = 10.dp, vertical = 6.dp),
            )
        }
        Text(
            "מה קיצורים עשתה לבד. השורות עם 🔎 הן זיהוי שיחות: מה זוהה, וכשלא זוהה, מה היה כתוב בחלון של Truecaller",
            color = Color(0xFF8B93A0),
            fontSize = 13.sp,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        if (events.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("עוד לא קרה כלום", color = Color(0xFF8B93A0), fontSize = 15.sp)
            }
            return
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            // תגית של יום כשהיום מתחלף (היום, אתמול...)
            val list = events
            items(list.size) { i ->
                val e = list[i]
                if (i == 0 || !sameDay(list[i - 1].time, e.time)) {
                    Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp), contentAlignment = Alignment.Center) {
                        Text(
                            dayLabel(e.time),
                            color = Color(0xFFCFD5DE),
                            fontSize = 11.5.sp,
                            modifier = Modifier
                                .clip(RoundedCornerShape(20.dp))
                                .background(Color.White.copy(alpha = 0.08f))
                                .padding(horizontal = 12.dp, vertical = 4.dp),
                        )
                    }
                }
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    Text(e.emoji, fontSize = 16.sp, modifier = Modifier.width(28.dp))
                    Text(e.text, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(e.clock(), color = Color(0xFF8B93A0), fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}
