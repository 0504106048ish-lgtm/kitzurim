package com.kitzurim.app

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * הקלטת שיחות: רק בטלפונים שמאפשרים לקיצורים להקליט את הקול של השיחה (שני הצדדים).
 * בודקים פעם אחת כשמדליקים את המתג; בטלפון שלא מאפשר, הכפתור "הקלט" פשוט לא מופיע.
 * ההקלטות נשמרות בתיקייה "הקלטות/קיצורים" בטלפון, עם השם והתאריך.
 */
object CallRecorder {
    /** האם מקליטים עכשיו (המסך של השיחה מתעדכן לפי זה) */
    var recording by mutableStateOf(false)
        private set

    private var rec: MediaRecorder? = null
    private var pfd: ParcelFileDescriptor? = null
    private var uri: Uri? = null

    private fun sp(c: Context) = c.getSharedPreferences("call_rec", Context.MODE_PRIVATE)

    /** המתג בהגדרות דלוק, והטלפון עבר את הבדיקה */
    fun enabled(c: Context) = sp(c).getBoolean("on", false) && permitted(c)

    fun setEnabled(c: Context, v: Boolean) = sp(c).edit().putBoolean("on", v).apply()

    fun permitted(c: Context) = c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    /** האם הטלפון נותן לקיצורים את הקול של השיחה (אם לא, אנדרואיד חוסם, ואין טעם להראות כפתור) */
    fun supported(c: Context): Boolean {
        if (!permitted(c)) return false
        return runCatching {
            val buf = AudioRecord.getMinBufferSize(16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (buf <= 0) return false
            @Suppress("MissingPermission")
            val r = AudioRecord(MediaRecorder.AudioSource.VOICE_CALL, 16000, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buf * 2)
            val ok = r.state == AudioRecord.STATE_INITIALIZED
            r.release()
            ok
        }.getOrDefault(false)
    }

    /** הסבר קצר איך תוקלט השיחה בטלפון הזה */
    fun howText(c: Context): String =
        if (supported(c)) "🎙️ בשיחה יופיע כפתור \"הקלט\": שני הצדדים יוקלטו"
        else "🎙️ בשיחה יופיע כפתור \"הקלט\". הטלפון לא נותן את קול השיחה ישירות, אז מקליטים דרך המיקרופון: הצד השני נשמע הכי טוב ברמקול"

    fun toggle(c: Context, who: String) {
        if (recording) stop(c) else start(c, who)
    }

    fun start(c: Context, who: String) {
        if (recording || !permitted(c)) return
        val safe = who.replace(Regex("""[\\/:*?"<>|]"""), "").replace("⁦", "").replace("⁩", "").trim().ifEmpty { "שיחה" }
        val stamp = SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.getDefault()).format(Date())
        val ok = runCatching {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, "$safe $stamp.m4a")
                put(MediaStore.MediaColumns.MIME_TYPE, "audio/mp4")
                if (Build.VERSION.SDK_INT >= 29) {
                    val dir = if (Build.VERSION.SDK_INT >= 31) Environment.DIRECTORY_RECORDINGS else Environment.DIRECTORY_MUSIC
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "$dir/קיצורים")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
            }
            val u = c.contentResolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values) ?: error("no file")
            uri = u
            val fd = c.contentResolver.openFileDescriptor(u, "w") ?: error("no fd")
            pfd = fd
            // הדרך הטובה (קול השיחה עצמו, שני הצדדים) רק אם הטלפון מאפשר. אחרת, כמו אפליקציות הקלטת שיחות:
            // מקליטים דרך שירות הנגישות של קיצורים (לו אנדרואיד נותן להקליט גם בזמן שיחה). הצד השני נשמע הכי טוב ברמקול
            val direct = supported(c)
            val owner: Context = if (direct) c else (NotchOverlay.service ?: c)
            @Suppress("DEPRECATION")
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(owner) else MediaRecorder()
            r.setAudioSource(if (direct) MediaRecorder.AudioSource.VOICE_CALL else MediaRecorder.AudioSource.VOICE_RECOGNITION)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            r.setAudioSamplingRate(44100)
            r.setAudioEncodingBitRate(96000)
            r.setOutputFile(fd.fileDescriptor)
            r.prepare()
            r.start()
            rec = r
        }.isSuccess
        if (ok) {
            recording = true
            Actions.toast(c, "🔴 מקליט את השיחה")
        } else {
            cleanup(c, keep = false)
            Actions.toast(c, "הטלפון לא נתן להקליט את השיחה")
        }
    }

    fun stop(c: Context) {
        if (!recording && rec == null) return
        val saved = runCatching { rec?.stop() }.isSuccess
        runCatching { rec?.release() }
        rec = null
        recording = false
        cleanup(c, keep = saved)
        if (saved) {
            Actions.toast(c, "ההקלטה נשמרה בהקלטות ← קיצורים")
            ActivityLog.add(c, "🎙️", "הקלטת שיחה נשמרה")
        }
    }

    private fun cleanup(c: Context, keep: Boolean) {
        runCatching { pfd?.close() }
        pfd = null
        val u = uri ?: return
        uri = null
        runCatching {
            if (!keep) {
                c.contentResolver.delete(u, null, null)
            } else if (Build.VERSION.SDK_INT >= 29) {
                c.contentResolver.update(u, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            }
        }
    }
}
