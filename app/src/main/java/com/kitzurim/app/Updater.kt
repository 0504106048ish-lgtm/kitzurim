package com.kitzurim.app

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONArray
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * עדכונים: בודקים בגיטהאב אם יש גרסה חדשה יותר, מורידים את הקובץ ישר מהאפליקציה
 * ופותחים את ההתקנה של הטלפון (בלי לעבור דרך דף אינטרנט).
 */
object Updater {
    private const val API = "https://api.github.com/repos/0504106048ish-lgtm/kitzurim/releases?per_page=15"
    const val RELEASES_PAGE = "https://github.com/0504106048ish-lgtm/kitzurim/releases"

    /** גרסה שאפשר להוריד. notes = מה חדש בכל הגרסאות שאחרי הגרסה שמותקנת */
    class Release(val number: Int, val url: String, val size: Long, val date: String, val notes: List<Pair<Int, String>>)

    /** מספר הגרסה המותקנת (אותו מספר כמו בגיטהאב: v21 = 21) */
    fun installed(c: Context): Int = runCatching {
        val p = c.packageManager.getPackageInfo(c.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) p.longVersionCode.toInt() else @Suppress("DEPRECATION") p.versionCode
    }.getOrDefault(0)

    fun versionName(c: Context): String =
        runCatching { c.packageManager.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"

    /** הגרסה הכי חדשה בגיטהאב, או null אם אין חיבור. מחזיר גם אם היא לא חדשה (כדי להגיד "יש לך את האחרונה") */
    suspend fun latest(c: Context): Release? = withContext(Dispatchers.IO) {
        runCatching {
            val conn = URL(API).openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            val arr = JSONArray(text)
            val mine = installed(c)
            var best: Release? = null
            val notes = ArrayList<Pair<Int, String>>()
            for (i in 0 until arr.length()) {
                val r = arr.getJSONObject(i)
                if (r.optBoolean("draft") || r.optBoolean("prerelease")) continue
                val n = r.optString("tag_name").removePrefix("v").toIntOrNull() ?: continue
                if (n > mine) notes.add(n to r.optString("body").trim())
                val assets = r.optJSONArray("assets") ?: continue
                val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
                    .firstOrNull { it.optString("name").endsWith(".apk") } ?: continue
                if (best == null || n > best.number) {
                    best = Release(n, apk.optString("browser_download_url"), apk.optLong("size"), r.optString("published_at").take(10), emptyList())
                }
            }
            best?.let { Release(it.number, it.url, it.size, it.date, notes.sortedByDescending { p -> p.first }) }
        }.getOrNull()
    }

    private fun file(c: Context, n: Int) = File(c.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "Kitzurim-$n.apk")

    /**
     * מוריד את הגרסה ומעדכן את ההתקדמות (0 עד 1). בסוף פותח את ההתקנה.
     * מחזיר הודעת שגיאה, או null אם הכל הצליח
     */
    suspend fun downloadAndInstall(c: Context, r: Release, progress: (Float) -> Unit): String? {
        val f = file(c, r.number)
        // כבר הורד קודם (ולא נקטע): ישר להתקנה
        if (f.exists() && r.size > 0 && f.length() == r.size) {
            progress(1f)
            return install(c, f)
        }
        f.parentFile?.listFiles()?.forEach { if (it.name.endsWith(".apk")) it.delete() }
        val dm = c.getSystemService(DownloadManager::class.java) ?: return "אין מנהל הורדות בטלפון"
        val id = runCatching {
            dm.enqueue(
                DownloadManager.Request(Uri.parse(r.url))
                    .setTitle("קיצורים ${r.number}")
                    .setDescription("מוריד עדכון")
                    .setMimeType("application/vnd.android.package-archive")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                    .setDestinationInExternalFilesDir(c, Environment.DIRECTORY_DOWNLOADS, f.name),
            )
        }.getOrElse { return "לא הצלחתי להתחיל את ההורדה" }
        // בודקים כל רבע שנייה כמה ירד
        while (true) {
            delay(250)
            val q = dm.query(DownloadManager.Query().setFilterById(id)) ?: return "ההורדה נעלמה"
            q.use { cur ->
                if (!cur.moveToFirst()) return "ההורדה בוטלה"
                val status = cur.getInt(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val done = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val total = cur.getLong(cur.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)).takeIf { it > 0 } ?: r.size
                if (total > 0) progress((done.toFloat() / total).coerceIn(0f, 1f))
                when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> {
                        progress(1f)
                        return install(c, f)
                    }
                    DownloadManager.STATUS_FAILED -> return "ההורדה נכשלה. בדוק את החיבור לאינטרנט"
                }
            }
        }
    }

    /** פותח את מסך ההתקנה של הטלפון. בפעם הראשונה צריך לאשר לקיצורים להתקין */
    fun install(c: Context, f: File): String? {
        if (Build.VERSION.SDK_INT >= 26 && !c.packageManager.canRequestPackageInstalls()) {
            runCatching {
                c.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${c.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
            return "צריך לאשר פעם אחת \"להתקין אפליקציות\" לקיצורים, ואז ללחוץ שוב על העדכון"
        }
        val uri = FileProvider.getUriForFile(c, "${c.packageName}.files", f)
        return runCatching {
            c.startActivity(
                Intent(Intent.ACTION_VIEW)
                    .setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            null
        }.getOrElse { "לא הצלחתי לפתוח את ההתקנה" }
    }
}
