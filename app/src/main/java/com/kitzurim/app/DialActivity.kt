package com.kitzurim.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.telecom.TelecomManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

/**
 * מקלדת חיוג פשוטה. אנדרואיד דורש אותה מאפליקציית טלפון.
 * אנשי קשר והיסטוריה נשארים בטלפון של סמסונג: משם מחייגים כרגיל, וקיצורים מראה את מסך השיחה.
 */
class DialActivity : ComponentActivity() {
    private var pending: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        neonBars()
        val start = intent?.data?.schemeSpecificPart ?: ""
        setContent {
            KitzurimTheme {
                var number by remember { mutableStateOf(start) }
                Column(
                    Modifier
                        .fillMaxSize()
                        .background(Neon.Bg)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Spacer(Modifier.height(80.dp))
                    Text(
                        number.ifEmpty { "הקלד מספר" },
                        color = if (number.isEmpty()) Neon.Muted else Color.White,
                        fontSize = if (number.isEmpty()) 20.sp else 34.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.weight(1f))
                    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            listOf("123", "456", "789", "*0#").forEach { row ->
                                Row(Modifier.padding(vertical = 7.dp), horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                                    row.forEach { ch ->
                                        Box(
                                            Modifier
                                                .size(72.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(alpha = 0.08f))
                                                .clickable { number += ch },
                                            contentAlignment = Alignment.Center,
                                        ) { Text(ch.toString(), color = Color.White, fontSize = 30.sp) }
                                    }
                                }
                            }
                            Row(
                                Modifier.padding(top = 14.dp),
                                horizontalArrangement = Arrangement.spacedBy(22.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Spacer(Modifier.size(72.dp))
                                Box(
                                    Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(Neon.Green)
                                        .clickable { if (number.isNotBlank()) call(number) },
                                    contentAlignment = Alignment.Center,
                                ) { Text("✆︎", color = Color.Black, fontSize = 30.sp) }
                                Box(
                                    Modifier
                                        .size(72.dp)
                                        .clickable { number = number.dropLast(1) },
                                    contentAlignment = Alignment.Center,
                                ) { Text("⌫", color = Neon.Muted, fontSize = 26.sp) }
                            }
                        }
                    }
                    Spacer(Modifier.height(30.dp))
                }
            }
        }
    }

    private fun call(number: String) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pending = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE), 9)
            return
        }
        runCatching {
            getSystemService(TelecomManager::class.java)?.placeCall(Uri.fromParts("tel", number, null), Bundle())
        }
        finish()
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val n = pending ?: return
        pending = null
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) call(n)
    }
}
