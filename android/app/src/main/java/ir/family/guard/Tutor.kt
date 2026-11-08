package ir.family.guard

import android.content.Context
import android.graphics.Color
import android.speech.tts.TextToSpeech
import android.webkit.WebView
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.concurrent.thread

/** Which kid and subject the tutor is working with (survives screen redraws). */
object TutorState {
    @Volatile var subject = ""
    @Volatile var kidId = ""
}

/** Reads text aloud with the phone's text-to-speech engine, Persian voice if it has one. */
class Speaker(ctx: Context) {
    private var ready = false
    private var tts: TextToSpeech? = null
    init {
        tts = TextToSpeech(ctx) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                val r = tts?.setLanguage(Locale("fa", "IR"))
                if (r == TextToSpeech.LANG_MISSING_DATA || r == TextToSpeech.LANG_NOT_SUPPORTED) tts?.setLanguage(Locale.getDefault())
            }
        }
    }
    fun speak(text: String): Boolean {
        if (!ready || text.isBlank()) return false
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "tutor")
        return true
    }
    fun stop() { tts?.stop() }
    fun shutdown() { tts?.shutdown(); tts = null }
}

private val SUBJECTS = listOf("انگلیسی" to "english", "ریاضی" to "math", "فارسی" to "persian", "علوم" to "science")

/** Switch the tutor to a screen and remember it, so a redraw shows the same screen. */
fun MainActivity.goTo(screen: () -> Unit) { tutorView = screen; screen() }

fun MainActivity.tutorBack(kid: Kid) = add(ui.pill("← درس‌ها", Pal.MUTED) { goTo { tutorHome(kid) } })

/** Every tutor request runs in the background; the result is handled on the UI thread. */
fun MainActivity.tutorCall(kid: Kid, body: JSONObject, then: (Int, JSONObject?) -> Unit) {
    body.put("kid_id", kid.id).put("day", Sync.today())
    thread {
        val (code, r) = Sync.tutor(prefs, body)
        runOnUiThread { then(code, r) }
    }
}

fun MainActivity.tutorHome(kid: Kid) {
    TutorState.kidId = kid.id
    box.removeAllViews()
    add(ui.pill("← بازگشت", Pal.MUTED) { tutorView = null; render() })
    add(ui.text("🎓 معلم من", 26f, Pal.INK, true))
    add(ui.text("یک درس انتخاب کن. اول معلم سطحت را می‌سنجد، بعد برایت برنامه‌ی روزانه می‌نویسد.", 15f, Pal.MUTED))
    SUBJECTS.forEach { (label, key) -> add(ui.pill(label, Pal.PURPLE) { tutorIntro(kid, key) }) }
    val c = ui.card()
    val other = field("درس دیگر، مثلاً گیتار یا کدنویسی")
    c.addView(other)
    c.addView(ui.pill("شروع", Pal.GREEN) { val s = other.text.toString().trim(); if (s.isNotEmpty()) tutorIntro(kid, s) })
    add(c)
}

fun MainActivity.tutorIntro(kid: Kid, subject: String) {
    TutorState.subject = subject
    tutorView = { tutorIntro(kid, subject) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📚 $subject", 24f, Pal.INK, true))
    val c = ui.card()
    val goal = field("هدفت از این درس چیست؟ مثلاً: برای سفر بتوانم حرف بزنم")
    c.addView(goal)
    c.addView(ui.pill("▶ شروع", Pal.GREEN) { tutorStart(kid, subject, goal.text.toString().trim()) })
    add(c)
}

fun MainActivity.tutorStart(kid: Kid, subject: String, goal: String) {
    TutorState.subject = subject
    box.removeAllViews()
    tutorBack(kid)
    add(ui.card().apply { addView(ui.text("⏳ معلمت دارد آماده می‌شود…", 16f)) })
    tutorCall(kid, JSONObject().put("action", "profile").put("subject", subject).put("goal", goal)) { code, r ->
        when {
            code != 200 || r == null -> tutorError(kid, subject, code)
            r.optString("status") == "assess" -> tutorAssess(kid, subject, r)
            else -> tutorHub(kid, subject, r)
        }
    }
}

fun MainActivity.tutorError(kid: Kid, subject: String, code: Int) {
    box.removeAllViews()
    tutorBack(kid)
    val msg = when (code) {
        429 -> "امروز به سقف تمرین‌های معلم رسیدی؛ فردا دوباره 🌙"
        409 -> "اول باید سطحت را بسنجیم"
        502 -> "معلم الان در دسترس نیست؛ کمی بعد امتحان کن"
        else -> "ارتباط برقرار نشد؛ دوباره امتحان کن"
    }
    add(ui.card().apply { addView(ui.text(msg, 16f)) })
    add(ui.pill("🔄 دوباره", Pal.BLUE) { tutorStart(kid, subject, "") })
}

/** Questions with a text box each. Used for the level test and for practice. */
private fun MainActivity.questionCards(qs: JSONArray): List<EditText> {
    val fields = ArrayList<EditText>()
    for (i in 0 until qs.length()) {
        val c = ui.card()
        c.addView(ui.text("${fa(i + 1)}. ${qs.getString(i)}", 17f, Pal.INK, true))
        val f = field("جوابت")
        fields.add(f)
        c.addView(f)
        add(c)
    }
    return fields
}

fun MainActivity.tutorAssess(kid: Kid, subject: String, r: JSONObject) {
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📝 اول ببینیم از کجا شروع کنیم", 22f, Pal.INK, true))
    add(ui.text("به این سوال‌ها جواب بده. اشتباه کردن اشکالی ندارد؛ فقط برای این است که بفهمم کجا کمک لازم داری.", 15f, Pal.MUTED))
    val exId = r.getString("exercise_id")
    val fields = questionCards(r.getJSONArray("questions"))
    add(ui.pill("✅ ارسال جواب‌ها", Pal.GREEN) {
        val answers = JSONArray(fields.map { it.text.toString().trim() })
        tutorCall(kid, JSONObject().put("action", "assess_submit").put("subject", subject).put("exercise_id", exId).put("answers", answers)) { code, res ->
            if (code == 200 && res != null) tutorResultCard(kid, subject, res) else tutorError(kid, subject, code)
        }
    })
}

fun MainActivity.tutorResultCard(kid: Kid, subject: String, res: JSONObject) {
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("🎉 سطحت: ${res.optString("level")}", 26f, Pal.PURPLE, true))
    add(ui.card().apply { addView(ui.text(res.optString("feedback"), 17f)) })
    add(ui.pill("ادامه", Pal.GREEN) { tutorStart(kid, subject, "") })
}

fun MainActivity.tutorHub(kid: Kid, subject: String, r: JSONObject) {
    tutorView = { tutorHub(kid, subject, r) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📚 $subject · سطح: ${r.optString("level")}", 22f, Pal.INK, true))
    if (r.optString("goal").isNotEmpty()) add(ui.text("🎯 هدف: ${r.optString("goal")}", 15f, Pal.MUTED))
    add(ui.pill("📅 برنامه‌ی امروز", Pal.PURPLE) { tutorPlan(kid, subject) })
    add(ui.pill("📖 توضیح یک موضوع یا صفحه", Pal.ORANGE) { tutorExplain(kid, subject, "") })
    add(ui.pill("🎯 تمرین سریع", Pal.BLUE) { tutorPractice(kid, subject, "") })
}

fun MainActivity.tutorPlan(kid: Kid, subject: String) {
    tutorView = { tutorPlan(kid, subject) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📅 برنامه‌ی امروز", 22f, Pal.INK, true))
    tutorCall(kid, JSONObject().put("action", "plan").put("subject", subject)) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        val items = r.getJSONArray("items")
        for (i in 0 until items.length()) {
            val item = items.getJSONObject(i)
            val type = item.optString("type")
            add(ui.card().apply {
                addView(ui.text("${fa(i + 1)}. ${item.optString("title")}", 18f, Pal.INK, true))
                addView(ui.text("حدود ${fa(item.optInt("minutes", 5))} دقیقه", 14f, Pal.MUTED))
            })
            val label = if (type == "explain") "📖 بریم توضیح بگیریم" else "✏️ شروع"
            add(ui.pill(label, Pal.PURPLE) {
                if (type == "explain") tutorExplain(kid, subject, item.optString("title"))
                else tutorPractice(kid, subject, item.optString("title"))
            })
        }
    }
}

fun MainActivity.tutorPractice(kid: Kid, subject: String, topic: String) {
    tutorView = { tutorPractice(kid, subject, topic) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("🎯 تمرین" + (if (topic.isEmpty()) "" else " · $topic"), 22f, Pal.INK, true))
    add(ui.text("⏳ سوال‌ها دارند آماده می‌شوند…", 15f, Pal.MUTED))
    tutorCall(kid, JSONObject().put("action", "practice").put("subject", subject).put("topic", topic)) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        box.removeAllViews()
        tutorBack(kid)
        add(ui.text("🎯 تمرین" + (if (topic.isEmpty()) "" else " · $topic"), 22f, Pal.INK, true))
        val exId = r.getString("exercise_id")
        val fields = questionCards(r.getJSONArray("questions"))
        add(ui.pill("📤 تحویل بده", Pal.GREEN) {
            val answers = JSONArray(fields.map { it.text.toString().trim() })
            tutorCall(kid, JSONObject().put("action", "check").put("subject", subject).put("exercise_id", exId).put("answers", answers)) { c2, res ->
                if (c2 == 200 && res != null) tutorChecked(kid, subject, res) else tutorError(kid, subject, c2)
            }
        })
    }
}

fun MainActivity.tutorChecked(kid: Kid, subject: String, res: JSONObject) {
    box.removeAllViews()
    tutorBack(kid)
    val score = res.optInt("score")
    val total = res.optInt("total", 5)
    add(ui.text(if (score * 5 >= total * 4) "🏆 عالی! ${fa(score)} از ${fa(total)}" else "💪 ${fa(score)} از ${fa(total)}؛ داری خوب پیش می‌ری", 22f, Pal.PURPLE, true))
    if (score > 0) add(ui.chip("+${fa(score)} امتیاز 🪙", Pal.YELLOW, Pal.INK))
    val results = res.optJSONArray("results") ?: JSONArray()
    for (i in 0 until results.length()) {
        val rr = results.getJSONObject(i)
        val ok = rr.optBoolean("correct")
        val c = ui.card()
        c.addView(ui.text((if (ok) "✅ " else "❌ ") + "سوال ${fa(i + 1)}", 16f, if (ok) Pal.GREEN else Pal.RED, true))
        if (!ok) {
            if (rr.optString("hint").isNotEmpty()) c.addView(ui.text("💡 ${rr.optString("hint")}", 14f, Pal.MUTED))
            if (rr.has("correct_answer")) c.addView(ui.text("جواب درست: ${rr.optString("correct_answer")}", 14f, Pal.INK))
        }
        add(c)
    }
    add(ui.pill("ادامه", Pal.GREEN) { tutorStart(kid, subject, "") })
}

fun MainActivity.tutorExplain(kid: Kid, subject: String, prefill: String) {
    tutorView = { tutorExplain(kid, subject, prefill) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📖 چی را توضیح بدم؟", 22f, Pal.INK, true))
    add(ui.text("اسم موضوع را بنویس یا از کتاب عکس بگیر. معلم قدم‌به‌قدم روی تخته توضیح می‌دهد.", 15f, Pal.MUTED))
    val c = ui.card()
    val f = field("مثلاً: کسر چیست؟", value = prefill)
    c.addView(f)
    c.addView(ui.pill("🔊 توضیح بده", Pal.PURPLE) {
        val t = f.text.toString().trim()
        if (t.isNotEmpty()) tutorExplainCall(kid, subject, t, false)
    })
    add(c)
    add(ui.pill("📷 عکس صفحه‌ی کتاب", Pal.ORANGE) { TutorState.kidId = kid.id; takePhoto("tutor", "") })
}

fun MainActivity.tutorExplainCall(kid: Kid, subject: String, text: String, simpler: Boolean) {
    tutorView = { tutorExplain(kid, subject, text) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("⏳ معلم دارد تخته را آماده می‌کند…", 18f, Pal.MUTED, true))
    tutorCall(kid, JSONObject().put("action", "explain").put("subject", subject).put("text", text).put("simpler", simpler)) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        tutorBoard(kid, subject, r, text)
    }
}

/** Called after a photo of a book page was sent for explaining. */
fun MainActivity.tutorGotExplain(code: Int, r: JSONObject?) {
    val kid = Sync.loadState(prefs)?.kids?.firstOrNull { it.id == TutorState.kidId } ?: return toast("کودک پیدا نشد")
    val subject = TutorState.subject
    if (code != 200 || r == null) {
        goTo { tutorExplain(kid, subject, "") }
        return toast("عکس خوانده نشد؛ دوباره امتحان کن")
    }
    tutorBoard(kid, subject, r, "")
}

private fun boardHtml(svg: String): String = """<html><head><meta name="viewport" content="width=device-width,initial-scale=1"></head>
<body style="margin:0;background:#fff;height:100%;display:flex;align-items:center;justify-content:center;font-family:sans-serif">
<style>@keyframes pop{from{opacity:0;transform:scale(.85)}to{opacity:1;transform:scale(1)}}
.b{animation:pop .6s ease-out;width:100%;text-align:center} svg{max-width:100%;height:auto}</style>
<div class="b">$svg</div></body></html>"""

/** The whiteboard: one step at a time, a drawing, a sentence read aloud, and a check question. */
fun MainActivity.tutorBoard(kid: Kid, subject: String, r: JSONObject, text: String) {
    tutorView = { tutorBoard(kid, subject, r, text) }
    box.removeAllViews()
    tutorBack(kid)
    val steps = r.getJSONArray("steps")
    val exId = r.getString("exercise_id")
    add(ui.text("🖍 ${r.optString("title")}", 22f, Pal.INK, true))

    val web = WebView(this).apply {
        setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(240))
    }
    val counter = ui.text("", 14f, Pal.MUTED)
    val say = ui.text("", 18f, Pal.INK, true)
    val ask = ui.text("", 16f, Pal.PURPLE, true)
    val frame = ui.card()
    frame.addView(web); frame.addView(counter); frame.addView(say); frame.addView(ask)
    add(frame)

    var idx = 0
    fun show() {
        val s = steps.getJSONObject(idx)
        web.loadDataWithBaseURL(null, boardHtml(if (s.isNull("board")) "" else s.optString("board")), "text/html", "utf-8", null)
        counter.text = "قدم ${fa(idx + 1)} از ${fa(steps.length())}"
        say.text = s.optString("say")
        ask.text = if (s.isNull("ask") || s.optString("ask").isEmpty()) "" else "❓ ${s.optString("ask")}"
    }
    show()

    add(ui.pill("🔊 بشنو", Pal.ORANGE) { if (!speaker.speak(say.text.toString())) toast("صدای گوشی آماده نیست") })
    val nav = ui.row()
    nav.addView(ui.pill("⏮ قبلی", Pal.BLUE) { if (idx > 0) { idx--; show() } }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
    nav.addView(ui.pill("⏭ بعدی", Pal.BLUE) { if (idx < steps.length() - 1) { idx++; show() } }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) } })
    add(nav)

    add(ui.pill("✅ فهمیدم", Pal.GREEN) {
        tutorCall(kid, JSONObject().put("action", "explain_done").put("subject", subject).put("exercise_id", exId).put("understood", true)) { _, _ ->
            toast("عالی! 🎉")
            tutorStart(kid, subject, "")
        }
    })
    add(ui.pill("🤔 نفهمیدم، ساده‌تر بگو", Pal.RED) {
        tutorCall(kid, JSONObject().put("action", "explain_done").put("subject", subject).put("exercise_id", exId).put("understood", false)) { _, _ ->
            tutorExplainCall(kid, subject, text.ifEmpty { r.optString("title") }, true)
        }
    })
}
