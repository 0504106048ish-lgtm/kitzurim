package com.kitzurim.app

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat

/**
 * פקודה קולית: נפתח חלון הדיבור של גוגל בעברית, ואז מבצעים את מה שאמרת.
 * דוגמאות: "שלח לאמא שאני מגיע בעוד עשר דקות", "נווט הביתה", "תדליק פנס",
 * "תרשום לקנות חלב", "תתקשר לדוד", "תזכיר לי לקנות חלב כשאני ליד שופרסל".
 */
class VoiceActivity : ComponentActivity() {
    private var pending: String? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        if (heard.isNullOrBlank()) finish() else handle(heard)
    }

    private val contactsPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val text = pending
        pending = null
        if (ok && text != null) handle(text) else {
            Actions.toast(this, "בלי גישה לאנשי הקשר אי אפשר למצוא למי לשלוח")
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "מה לעשות?")
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Actions.toast(this, "לא נמצא זיהוי דיבור בטלפון")
            finish()
        }
    }

    private fun needsContacts(): Boolean =
        ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED

    private fun handle(heard: String) {
        val cmd = VoiceCommands.parse(heard)
        if ((cmd is VoiceCommand.Send || cmd is VoiceCommand.Call) && needsContacts() && !cmd.toSelf()) {
            pending = heard
            contactsPermission.launch(Manifest.permission.READ_CONTACTS)
            return
        }
        when (cmd) {
            is VoiceCommand.Send -> {
                val target = if (cmd.toSelf()) Triple("עצמך", Prefs.phone(this), cmd.words.drop(1))
                else VoiceCommands.findContact(this, cmd.words)
                if (target == null) {
                    Actions.toast(this, "לא מצאתי איש קשר בשם \"${cmd.words.firstOrNull() ?: ""}\"")
                } else {
                    val text = VoiceCommands.cleanMessage(target.third)
                    Actions.toast(this, "שולח ל${target.first}")
                    Actions.openWhatsApp(this, target.second, text)
                }
            }
            is VoiceCommand.Call -> {
                val target = VoiceCommands.findContact(this, cmd.words)
                if (target == null) {
                    Actions.toast(this, "לא מצאתי איש קשר בשם \"${cmd.words.firstOrNull() ?: ""}\"")
                } else {
                    // פותח את החייגן עם המספר. השיחה עצמה מתחילה בלחיצה שלך
                    startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:" + target.second)))
                }
            }
            VoiceCommand.Home -> {
                if (!Actions.run(this, QuickAction.HOME)) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            is VoiceCommand.Navigate -> Actions.navigateTo(this, cmd.place)
            VoiceCommand.Torch -> Actions.toggleTorch(this)
            VoiceCommand.Camera -> Actions.openCamera(this)
            is VoiceCommand.Note -> {
                Prefs.setNotes(this, listOf(cmd.text) + Prefs.notes(this))
                Actions.toast(this, "📝 נשמר בפתקים: ${cmd.text}")
            }
            is VoiceCommand.Remind -> {
                if (cmd.place == null) {
                    Actions.toast(this, "צריך להגיד איפה, למשל: כשאני ליד הסופר")
                } else {
                    Reminders.addByName(applicationContext, cmd.text, cmd.place) { ok ->
                        Actions.toast(
                            applicationContext,
                            if (ok) "📍 אזכיר לך כשתגיע ל${cmd.place}" else "לא מצאתי את המקום \"${cmd.place}\"",
                        )
                    }
                }
            }
            is VoiceCommand.Unknown -> Actions.toast(this, "לא הבנתי: \"${cmd.heard}\"")
        }
        finish()
    }
}

sealed class VoiceCommand {
    /** words: מה שבא אחרי "שלח ל", בלי ה-ל (השם, ואחריו ההודעה) */
    data class Send(val words: List<String>) : VoiceCommand()
    data class Call(val words: List<String>) : VoiceCommand()
    data object Home : VoiceCommand()
    data class Navigate(val place: String) : VoiceCommand()
    data object Torch : VoiceCommand()
    data object Camera : VoiceCommand()
    data class Note(val text: String) : VoiceCommand()
    data class Remind(val text: String, val place: String?) : VoiceCommand()
    data class Unknown(val heard: String) : VoiceCommand()

    fun toSelf() = this is Send && words.firstOrNull() in listOf("עצמי", "עצמך")
}

object VoiceCommands {
    private val SEND = setOf("שלח", "תשלח", "שלחי", "תשלחי", "כתוב", "תכתוב", "תכתבי", "תגיד", "תגידי", "הודעה")
    private val CALL = setOf("תתקשר", "התקשר", "תתקשרי", "חייג", "תחייג", "תחייגי")
    private val NAV = setOf("נווט", "תנווט", "ניווט", "תנווטי", "סע", "קח", "תיקח")
    private val NOTE = setOf("תרשום", "רשום", "תרשמי", "פתק", "תזכור")
    private val REMIND = setOf("תזכיר", "תזכירי", "תזכורת", "הזכר")

    /** מילים שמתחילות ב-ש אבל ה-ש היא חלק מהמילה (לא "ש" של "שאני מגיע") */
    private val REAL_SHIN = setOf("שלום", "שבת", "שבוע", "שנה", "שעה", "שעתיים", "שתי", "שני", "שניים", "שלוש", "שש", "שבע", "שמונה", "שם", "שמח")

    fun parse(heard: String): VoiceCommand {
        val text = heard.trim().replace(Regex("[.,!?]"), "")
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return VoiceCommand.Unknown(heard)
        val first = words[0]
        val rest = words.drop(1)

        if (first in SEND && rest.isNotEmpty()) return VoiceCommand.Send(stripLamed(rest))
        if (first in CALL && rest.isNotEmpty()) return VoiceCommand.Call(stripLamed(rest))
        if (first in REMIND) return parseRemind(rest)
        if (first in NOTE && rest.isNotEmpty()) return VoiceCommand.Note(rest.joinToString(" "))
        if ("הביתה" in words || (first in NAV && rest.firstOrNull() in listOf("הביתה", "לבית"))) return VoiceCommand.Home
        if (first in NAV && rest.isNotEmpty()) {
            val place = stripLamed(rest).joinToString(" ")
            return VoiceCommand.Navigate(place)
        }
        if (words.any { it.contains("פנס") }) return VoiceCommand.Torch
        if (words.any { it.contains("מצלמה") || it == "תצלם" || it == "צלם" }) return VoiceCommand.Camera
        return VoiceCommand.Unknown(heard)
    }

    /** "לאמא" ← "אמא", "לדוד כהן" ← "דוד כהן" */
    private fun stripLamed(words: List<String>): List<String> {
        val w = words.toMutableList()
        if (w.firstOrNull() in listOf("ל", "אל")) w.removeAt(0)
        if (w.isNotEmpty() && w[0].length > 2 && w[0].startsWith("ל")) w[0] = w[0].drop(1)
        return w
    }

    /** "תזכיר לי לקנות חלב כשאני ליד שופרסל" ← טקסט "לקנות חלב", מקום "שופרסל" */
    private fun parseRemind(words: List<String>): VoiceCommand {
        val w = words.toMutableList()
        if (w.firstOrNull() == "לי") w.removeAt(0)
        val split = w.indexOfFirst { it.startsWith("כש") }
        if (split < 0) return VoiceCommand.Remind(w.joinToString(" "), null)
        val what = w.take(split).joinToString(" ")
        var where = w.drop(split + 1).filter { it !in listOf("אני", "ליד", "מגיע", "אגיע", "אהיה", "נמצא") }
        if (where.isNotEmpty() && where[0].length > 2 && (where[0].startsWith("ל") || where[0].startsWith("ב"))) {
            where = listOf(where[0].drop(1)) + where.drop(1)
        }
        // "כשאגיע לעבודה": המילה עם כש היא הפועל, ואז המקום אחריה
        return VoiceCommand.Remind(what, where.joinToString(" ").ifBlank { null })
    }

    /** "שאני מגיע" ← "אני מגיע" */
    fun cleanMessage(words: List<String>): String? {
        if (words.isEmpty()) return null
        val w = words.toMutableList()
        if (w[0].startsWith("ש") && w[0].length > 2 && w[0] !in REAL_SHIN) w[0] = w[0].drop(1)
        return w.joinToString(" ")
    }

    /**
     * מחפש איש קשר לפי המילים הראשונות (עד 3 מילים, הכי ארוך שנמצא).
     * מחזיר: שם, מספר טלפון, והמילים שנשארו (ההודעה).
     */
    fun findContact(ctx: Context, words: List<String>): Triple<String, String, List<String>>? {
        for (n in minOf(3, words.size) downTo 1) {
            val name = words.take(n).joinToString(" ")
            val found = try {
                ctx.contentResolver.query(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    arrayOf(
                        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                        ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ),
                    "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                    arrayOf("%$name%"),
                    null,
                )?.use { c -> if (c.moveToFirst()) c.getString(0) to c.getString(1) else null }
            } catch (_: Exception) {
                null
            }
            if (found != null) return Triple(found.first, found.second, words.drop(n))
        }
        return null
    }
}
