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
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/**
 * פקודה קולית: נפתח חלון הדיבור של גוגל בעברית, ואז מבצעים את מה שאמרת.
 * דוגמאות: "שלח לאמא שאני מגיע בעוד עשר דקות", "נווט הביתה",
 * "תתקשר לדוד", "תזכיר לי לקנות חלב כשאני ליד שופרסל".
 */
class VoiceActivity : ComponentActivity() {
    companion object {
        /** נפתח מהאייקון (מצב "פתיחה ישר לפקודה קולית"): "חזרה" בחלון הדיבור פותח את האפליקציה */
        const val BACK_TO_HOME = "back_to_home"
    }

    private var pending: String? = null

    /** שאלת המשך: מה שאמרת עכשיו הוא תשובה ("למי?", "מה לכתוב?") ולא פקודה חדשה */
    private var askPrefix: String? = null
    private var askTextFor: Pair<String, String>? = null

    private val speech = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        val heard = res.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        val prefix = askPrefix
        val textFor = askTextFor
        askPrefix = null
        askTextFor = null
        when {
            // "פתיחה ישר לפקודה קולית": לחיצה על "חזרה" בחלון הדיבור פותחת את האפליקציה עצמה
            heard.isNullOrBlank() && prefix == null && textFor == null &&
                intent?.getBooleanExtra(BACK_TO_HOME, false) == true -> {
                startActivity(Intent(this, HomeActivity::class.java).putExtra("open_home", true))
                finish()
            }
            heard.isNullOrBlank() -> finish()
            // "מה לכתוב לאליהו?" ← "שאני מגיע"
            textFor != null -> {
                val text = VoiceCommands.cleanMessage(heard.trim().split(Regex("\\s+")))
                if (text.isNullOrBlank()) finish() else startSendCountdown(textFor.first, textFor.second, text)
            }
            // "למי לשלוח?" ← "לאליהו שאני מגיע": ממשיכים כאילו אמרת "שלח לאליהו שאני מגיע"
            prefix != null -> handle("$prefix $heard")
            else -> handle(heard)
        }
    }

    /** פותח שוב את חלון הדיבור עם שאלה */
    private fun listen(prompt: String) {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "he-IL")
            .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt)
        try {
            speech.launch(intent)
        } catch (_: ActivityNotFoundException) {
            Actions.toast(this, "לא נמצא זיהוי דיבור בטלפון")
            finish()
        }
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
        listen("מה לעשות?")
    }

    private var pendingCall: Pair<String, String>? = null

    private val callPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        val target = pendingCall
        pendingCall = null
        when {
            target == null -> finish()
            ok -> startCountdown(target.first, target.second)
            else -> {
                // בלי הרשאה: פותחים את החייגן עם המספר, ואתה לוחץ "חייג"
                dial(target.second)
                finish()
            }
        }
    }

    private fun canCall() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    private fun dial(number: String) {
        startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")))
    }

    /** "מתקשר לדוד… בעוד 3": אפשר לבטל אם הזיהוי טעה, ואחרי 3 שניות השיחה מתחילה לבד */
    private fun startCountdown(name: String, number: String) {
        setContent {
            KitzurimTheme {
                Countdown(
                    emoji = "📞",
                    title = "מתקשר ל$name",
                    detail = number,
                    onCancel = { finish() },
                    onCall = {
                        try {
                            startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")))
                        } catch (_: SecurityException) {
                            dial(number)
                        }
                        finish()
                    },
                )
            }
        }
    }

    /** "שולח לאמא: אני מגיע… בעוד 3": אחרי 3 שניות הצ'אט נפתח, ושירות הנגישות לוחץ "שלח" */
    private fun startSendCountdown(name: String, number: String, text: String) {
        setContent {
            KitzurimTheme {
                Countdown(
                    emoji = "💬",
                    title = "שולח ל$name",
                    detail = "\"$text\"",
                    onCancel = { finish() },
                    onCall = {
                        if (AutoSend.allowed(this)) {
                            AutoSend.arm(this)
                        } else {
                            Actions.toast(this, "כדי שזה יישלח לבד: לאשר \"שליחה אוטומטית\" במסך האוטומציות")
                        }
                        Actions.openWhatsApp(this, number, text)
                        finish()
                    },
                )
            }
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
                // "שלח וואטסאפ" בלי למי: שואלים
                if (cmd.words.isEmpty()) {
                    askPrefix = "שלח"
                    listen("למי לשלוח וואטסאפ?")
                    return
                }
                if (cmd.toSelf() && Prefs.phone(this).isBlank()) {
                    Actions.toast(this, "כדי לשלוח לעצמך, צריך להקליד את המספר שלך בהגדרות")
                    startActivity(Intent(this, SettingsActivity::class.java))
                    finish()
                    return
                }
                val target = if (cmd.toSelf()) Triple("עצמך", Prefs.phone(this), cmd.words.drop(1))
                else VoiceCommands.findContact(this, cmd.words)
                if (target == null) {
                    Actions.toast(this, "לא מצאתי איש קשר בשם \"${cmd.words.firstOrNull() ?: ""}\"")
                } else {
                    val text = VoiceCommands.cleanMessage(target.third)
                    if (text.isNullOrBlank()) {
                        // "שלח לאליהו" בלי הודעה: שואלים מה לכתוב
                        askTextFor = target.first to target.second
                        listen("מה לכתוב ל${target.first}?")
                    } else {
                        startSendCountdown(target.first, target.second, text)
                    }
                    return
                }
            }
            is VoiceCommand.Call -> {
                // "תתקשר" בלי למי: שואלים
                if (cmd.words.isEmpty()) {
                    askPrefix = "תתקשר"
                    listen("למי להתקשר?")
                    return
                }
                val target = VoiceCommands.findContact(this, cmd.words)
                if (target == null) {
                    Actions.toast(this, "לא מצאתי איש קשר בשם \"${cmd.words.firstOrNull() ?: ""}\"")
                } else if (canCall()) {
                    startCountdown(target.first, target.second)
                    return
                } else {
                    // פעם ראשונה: מבקשים הרשאה לבצע שיחות
                    pendingCall = target.first to target.second
                    callPermission.launch(Manifest.permission.CALL_PHONE)
                    return
                }
            }
            VoiceCommand.Home -> {
                if (!Actions.run(this, QuickAction.HOME)) {
                    startActivity(Intent(this, SettingsActivity::class.java))
                }
            }
            is VoiceCommand.Navigate -> Actions.navigateTo(this, cmd.place)
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
            is VoiceCommand.Unknown -> Actions.toast(
                this,
                "לא הבנתי \"${cmd.heard}\". אפשר להגיד למשל: שלח לאמא שאני מגיע, תתקשר לדוד, נווט הביתה",
            )
        }
        finish()
    }
}

sealed class VoiceCommand {
    /** words: מה שבא אחרי "שלח" (השם, ואחריו ההודעה), בלי מילים כמו "וואטסאפ" ו"הודעה" */
    data class Send(val words: List<String>) : VoiceCommand()
    data class Call(val words: List<String>) : VoiceCommand()
    data object Home : VoiceCommand()
    data class Navigate(val place: String) : VoiceCommand()
    data class Remind(val text: String, val place: String?) : VoiceCommand()
    data class Unknown(val heard: String) : VoiceCommand()

    fun toSelf() = this is Send && words.firstOrNull() in listOf("עצמי", "לעצמי", "עצמך", "לעצמך")
}

object VoiceCommands {
    private val SEND = setOf("שלח", "תשלח", "שלחי", "תשלחי", "כתוב", "תכתוב", "תכתבי", "תגיד", "תגידי", "הודעה")
    private val CALL = setOf("תתקשר", "התקשר", "תתקשרי", "חייג", "תחייג", "תחייגי")
    private val NAV = setOf("נווט", "תנווט", "ניווט", "תנווטי", "סע", "קח", "תיקח")
    private val REMIND = setOf("תזכיר", "תזכירי", "תזכורת", "הזכר")

    /** מילים שהן לא חלק מהשם ולא מההודעה: "תשלח וואטסאפ לאליהו", "תשלח הודעה לאליהו" */
    private val FILLER = setOf(
        "וואטסאפ", "ווטסאפ", "ואטסאפ", "וואטספ", "whatsapp", "בוואטסאפ", "בווטסאפ", "בואטסאפ",
        "הודעה", "הודעת", "הודעות", "מסר", "טלפון", "בטלפון", "שיחה", "ל", "אל", "את",
    )

    /** מילים שמתחילות ב-ש אבל ה-ש היא חלק מהמילה (לא "ש" של "שאני מגיע") */
    private val REAL_SHIN = setOf("שלום", "שבת", "שבוע", "שנה", "שעה", "שעתיים", "שתי", "שני", "שניים", "שלוש", "שש", "שבע", "שמונה", "שם", "שמח")

    private fun isFiller(w: String) = w.lowercase() in FILLER

    private fun dropFillers(words: List<String>) = words.dropWhile { isFiller(it) }

    fun parse(heard: String): VoiceCommand {
        val text = heard.trim().replace(Regex("[.,!?]"), "")
        val words = text.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return VoiceCommand.Unknown(heard)
        val first = words[0]
        val rest = words.drop(1)

        // גם בלי שם ("שלח וואטסאפ"): אז שואלים למי
        if (first in SEND) return VoiceCommand.Send(dropFillers(rest))
        if (first in CALL) return VoiceCommand.Call(dropFillers(rest))
        // "וואטסאפ לאליהו" בלי "שלח"
        if (first.contains("טסאפ") || first.lowercase() == "whatsapp") return VoiceCommand.Send(dropFillers(rest))
        if (first in REMIND) return parseRemind(rest)
        if ("הביתה" in words || (first in NAV && rest.firstOrNull() in listOf("הביתה", "לבית"))) return VoiceCommand.Home
        if (first in NAV && rest.isNotEmpty()) {
            val place = stripLamed(rest).joinToString(" ")
            return VoiceCommand.Navigate(place)
        }
        return VoiceCommand.Unknown(heard)
    }

    /** "לקניון מלחה" ← "קניון מלחה" */
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
        return VoiceCommand.Remind(what, where.joinToString(" ").ifBlank { null })
    }

    /** "שאני מגיע" ← "אני מגיע". מילים כמו "בוואטסאפ" בהתחלה לא נכנסות להודעה */
    fun cleanMessage(words: List<String>): String? {
        val w = dropFillers(words).toMutableList()
        if (w.isEmpty()) return null
        if (w[0].startsWith("ש") && w[0].length > 2 && w[0] !in REAL_SHIN) w[0] = w[0].drop(1)
        return w.joinToString(" ")
    }

    /**
     * כמה טוב השם שאמרת מתאים לשם באנשי הקשר:
     * 4 = בדיוק אותו שם, 3 = השם מתחיל בו ("אליהו כהן"), 2 = מילה שלמה בתוך השם ("משה אליהו"),
     * 1 = רק חלק ממילה ("אליהוו"). 0 = לא מתאים
     */
    private fun score(contact: String, said: String): Int {
        val c = contact.trim().lowercase()
        val s = said.trim().lowercase()
        if (s.isEmpty()) return 0
        return when {
            c == s -> 4
            c.startsWith("$s ") -> 3
            c.split(Regex("\\s+")).joinToString(" ").let { " $it ".contains(" $s ") } -> 2
            s.length >= 3 && c.contains(s) -> 1
            else -> 0
        }
    }

    /**
     * מחפש איש קשר לפי המילים הראשונות (עד 3 מילים).
     * מעדיף התאמה מלאה של שם, ומנסה את המילה הראשונה גם בלי "ל" ("לאליהו" ← "אליהו") וגם איתה ("לאה").
     * מחזיר: שם, מספר טלפון, והמילים שנשארו (ההודעה).
     */
    fun findContact(ctx: Context, words: List<String>): Triple<String, String, List<String>>? {
        val contacts: List<Pair<String, String>> = try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                ),
                null, null, null,
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val name = c.getString(0)
                        val number = c.getString(1)
                        if (name != null && number != null) add(name to number)
                    }
                }
            } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
        if (contacts.isEmpty() || words.isEmpty()) return null

        var weak: Triple<String, String, List<String>>? = null
        for (n in minOf(3, words.size) downTo 1) {
            val said = words.take(n).joinToString(" ")
            val variants = buildList {
                if (said.length > 2 && said.startsWith("ל")) add(said.drop(1))
                add(said)
            }
            for (v in variants) {
                val best = contacts.maxByOrNull { score(it.first, v) } ?: continue
                val s = score(best.first, v)
                if (s >= 2) return Triple(best.first, best.second, words.drop(n))
                if (s == 1 && weak == null) weak = Triple(best.first, best.second, words.drop(n))
            }
        }
        return weak
    }
}

/** ספירה לאחור לפני פעולה: אפשר לבטל אם הזיהוי טעה, או ללחוץ "עכשיו" */
@Composable
fun Countdown(emoji: String, title: String, detail: String, onCancel: () -> Unit, onCall: () -> Unit) {
    var seconds by remember { mutableIntStateOf(3) }
    var done by remember { mutableStateOf(false) }
    val callOnce = {
        if (!done) {
            done = true
            onCall()
        }
    }
    LaunchedEffect(Unit) {
        while (seconds > 0) {
            delay(1000)
            seconds--
        }
        callOnce()
    }
    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.6f))
            .systemBarsPadding(),
        contentAlignment = Alignment.Center,
    ) {
        Surface(shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.surface, modifier = Modifier.padding(24.dp)) {
            Column(
                Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(emoji, fontSize = 40.sp)
                Text(title, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                Text(detail, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Text(
                    "בעוד $seconds",
                    style = MaterialTheme.typography.displaySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedButton(onClick = { done = true; onCancel() }) { Text("ביטול") }
                    Button(onClick = { callOnce() }) { Text("עכשיו") }
                }
            }
        }
    }
}
