package com.kitzurim.app

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Size

/**
 * צילום מסך: כשנשמרת תמונה חדשה בתיקיית צילומי המסך, הגלולה מראה אותה קטנה עם "נשמר".
 * צריך הרשאה לתמונות (כדי לראות שנוסף צילום חדש).
 */
object Screenshots {
    private val main = Handler(Looper.getMainLooper())
    private var observer: ContentObserver? = null
    private var lastId = -1L

    val permission: String
        get() = if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_IMAGES else Manifest.permission.READ_EXTERNAL_STORAGE

    fun allowed(c: Context) = c.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    fun start(c: Context) {
        if (observer != null) return
        val o = object : ContentObserver(main) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                // הקובץ נכתב בכמה שלבים: מחכים רגע עד שהוא מוכן
                main.removeCallbacks(check)
                main.postDelayed(check, 700)
            }
        }
        observer = o
        runCatching { c.contentResolver.registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, o) }
    }

    fun stop(c: Context) {
        observer?.let { runCatching { c.contentResolver.unregisterContentObserver(it) } }
        observer = null
        main.removeCallbacks(check)
    }

    private val check = Runnable {
        val s = NotchOverlay.service ?: return@Runnable
        if (!NotchPrefs.pill(s) || !NotchPrefs.pillShots(s) || !allowed(s)) return@Runnable
        val shot = latest(s) ?: return@Runnable
        if (shot.first == lastId) return@Runnable
        lastId = shot.first
        val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, shot.first)
        val thumb: Bitmap? = runCatching {
            if (Build.VERSION.SDK_INT >= 29) s.contentResolver.loadThumbnail(uri, Size(160, 160), null)
            else @Suppress("DEPRECATION") MediaStore.Images.Thumbnails.getThumbnail(
                s.contentResolver, shot.first, MediaStore.Images.Thumbnails.MINI_KIND, null,
            )
        }.getOrNull()
        LivePill.showScreenshot(thumb, uri)
    }

    /** הצילום האחרון, אם נוסף ממש עכשיו (10 השניות האחרונות) */
    private fun latest(c: Context): Pair<Long, String>? = runCatching {
        val pathCol = if (Build.VERSION.SDK_INT >= 29) MediaStore.Images.Media.RELATIVE_PATH else @Suppress("DEPRECATION") MediaStore.Images.Media.DATA
        val since = System.currentTimeMillis() / 1000 - 10
        c.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            arrayOf(MediaStore.Images.Media._ID, pathCol, MediaStore.Images.Media.DISPLAY_NAME),
            "${MediaStore.Images.Media.DATE_ADDED} >= ?",
            arrayOf(since.toString()),
            "${MediaStore.Images.Media.DATE_ADDED} DESC",
        )?.use { cur ->
            while (cur.moveToNext()) {
                val path = (cur.getString(1) ?: "") + "/" + (cur.getString(2) ?: "")
                if (path.contains("screenshot", ignoreCase = true)) return@runCatching cur.getLong(0) to path
            }
            null
        }
    }.getOrNull()
}
