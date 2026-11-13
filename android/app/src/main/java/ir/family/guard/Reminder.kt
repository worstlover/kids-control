package ir.family.guard

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Base64
import android.util.Log
import android.widget.Toast
import org.json.JSONObject
import java.io.File
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.concurrent.thread

/**
 * Once an hour, outside the quiet hours, the phone says "<name> جان، کارهات منتظرتن. نمیای؟" when the active kid
 * still has tasks to do (not done yet, or sent back by a parent). The voice line is made once on the server (Gemini voice,
 * so it sounds right in Persian) and kept on the phone; the phone's own voice is the fallback.
 */
object Reminder {
    const val DEFAULT_QUIET = "23-10,14-18"

    /** "23-10,14-18" -> hour ranges, end not included, may wrap past midnight. Empty text = no quiet hours; bad text = null. */
    fun parse(spec: String?): List<Pair<Int, Int>>? {
        val s = (spec ?: "").map { if (it in '۰'..'۹') '0' + (it - '۰') else it }.joinToString("").replace(" ", "").replace('،', ',')
        if (s.isEmpty()) return emptyList()
        return s.split(',').filter { it.isNotEmpty() }.map { part ->
            val ab = part.split('-')
            if (ab.size != 2) return null
            val a = ab[0].substringBefore(':').toIntOrNull() ?: return null
            val b = ab[1].substringBefore(':').toIntOrNull() ?: return null
            if (a !in 0..24 || b !in 0..24) return null
            Pair(a % 24, b % 24)
        }
    }

    fun isQuiet(hour: Int, spec: String?): Boolean {
        val ranges = parse(spec) ?: parse(DEFAULT_QUIET)!!
        return ranges.any { (a, b) -> if (a == b) false else if (a < b) hour in a until b else hour >= a || hour < b }
    }

    /** Must match reminderText() on the server, so the saved voice line belongs to this name. */
    fun text(name: String) = "${name.trim().take(30)} جان، کارهات منتظرتن. نمیای؟"

    /** The decision, kept free of Android so it can be checked on a plain JVM. Returns the "day:hour" key to remember, or null. */
    fun due(kid: Kid?, now: ZonedDateTime, last: String): String? {
        if (kid == null || !kid.reminderOn) return null
        if (now.minute >= 10) return null                       // only near the top of the hour
        if (isQuiet(now.hour, kid.reminderQuiet)) return null
        val key = "${now.toLocalDate()}:${now.hour}:${kid.id}"
        if (key == last) return null
        return key
    }

    @Volatile private var busy = false

    /** Called from the guard service every few seconds; does nothing most of the time. */
    fun maybeRemind(ctx: Context, prefs: Prefs, st: State?) {
        if (busy || !prefs.configured) return
        val kid = st?.kids?.firstOrNull { it.id == prefs.activeKid }
        val key = due(kid, ZonedDateTime.now(TEHRAN), prefs.remindLast) ?: return
        prefs.remindLast = key                                  // once per hour, whatever happens next
        if (kid == null || Policy.pendingTasks(kid) == 0) return
        busy = true
        thread {
            try { play(ctx.applicationContext, prefs, kid) } catch (e: Exception) { Log.e("Reminder", "play", e) } finally { busy = false }
        }
    }

    private fun play(ctx: Context, prefs: Prefs, kid: Kid) {
        val line = text(kid.name)
        val main = Handler(Looper.getMainLooper())
        main.post { Toast.makeText(ctx, "⏰ $line", Toast.LENGTH_LONG).show() }
        val f = File(ctx.filesDir, "remind_" + sha256(line).take(16) + ".pcm")
        if (!f.exists()) {
            val (code, r) = Sync.post(prefs, "dictation-audio",
                JSONObject().put("token", prefs.token).put("kind", "reminder").put("kid_id", kid.id).put("day", Sync.today()), 60_000)
            val b64 = r?.optString("audio_b64").orEmpty()
            if (code == 200 && b64.isNotEmpty()) {
                try { f.writeBytes(Base64.decode(b64, Base64.DEFAULT)); prefs.reminderRate = r?.optInt("sample_rate", 24000) ?: 24000 } catch (e: Exception) { f.delete() }
            }
        }
        if (f.exists() && f.length() > 1000) playPcm(f.readBytes(), prefs.reminderRate) else main.post { speakWithPhone(ctx, line) }
    }

    private fun playPcm(bytes: ByteArray, rate: Int) {
        val t = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bytes.size).setTransferMode(AudioTrack.MODE_STATIC).build()
        try {
            t.write(bytes, 0, bytes.size); t.play()
            Thread.sleep(bytes.size * 1000L / (2L * rate) + 400)   // 16-bit mono: 2 bytes a sample
        } finally { try { t.stop() } catch (e: Exception) {}; t.release() }
    }

    /** No saved voice line and no internet: the phone's own voice (Persian if it has one). */
    private fun speakWithPhone(ctx: Context, line: String) {
        var tts: TextToSpeech? = null
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val r = tts?.setLanguage(Locale("fa", "IR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.getDefault())
                tts?.speak(line, TextToSpeech.QUEUE_FLUSH, null, "reminder")
            }
            Handler(Looper.getMainLooper()).postDelayed({ tts?.shutdown() }, 15_000)
        }
    }
}
