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
    @Volatile var lessonId = ""                                   // lesson a book QR / photo should attach to ("" = make a new lesson)
    val drafts = java.util.concurrent.ConcurrentHashMap<String, List<String>>()   // answers typed so far, per exercise
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

/** Runs one screen step. If the server's reply has an unexpected shape, show a message instead of closing the app. */
internal inline fun MainActivity.safely(what: String, block: () -> Unit) {
    try { block() } catch (e: Exception) {
        android.util.Log.e("tutor", "$what: unexpected reply shape", e)
        toast("جواب معلم کامل نبود؛ دوباره امتحان کن 🔄")
    }
}

/** Every tutor request runs in the background; the result is handled on the UI thread. */
fun MainActivity.tutorCall(kid: Kid, body: JSONObject, then: (Int, JSONObject?) -> Unit) {
    body.put("kid_id", kid.id).put("day", Sync.today())
    thread {
        // Never let a bad/partial reply from the server crash the app.
        val (code, r) = try { Sync.tutor(prefs, body) } catch (e: Exception) { Pair(-1, null) }
        runOnUiThread {
            try { then(code, r) }
            catch (e: Exception) {
                android.util.Log.e("tutor", "unexpected reply shape", e)
                toast("جواب معلم کامل نبود؛ دوباره امتحان کن 🔄")
            }
        }
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
    goTo { tutorIntro(kid, subject) }
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

/** Questions with a text box each. Typed answers are kept (here and on the server) so an error never loses them. */
private fun MainActivity.questionCards(kid: Kid, subject: String, exId: String, qs: JSONArray, draft: JSONArray?): List<EditText> {
    val fields = ArrayList<EditText>()
    val saved = TutorState.drafts[exId] ?: draft?.let { d -> (0 until d.length()).map { d.getString(it) } } ?: emptyList()
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var pending: Runnable? = null
    for (i in 0 until qs.length()) {
        val c = ui.card()
        c.addView(ui.text("${fa(i + 1)}. ${qs.getString(i)}", 17f, Pal.INK, true))
        val f = field("جوابت", value = saved.getOrElse(i) { "" })
        fields.add(f)
        f.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val now = fields.map { it.text.toString() }
                TutorState.drafts[exId] = now
                pending?.let { handler.removeCallbacks(it) }
                pending = Runnable {
                    val body = JSONObject().put("action", "draft").put("subject", subject).put("exercise_id", exId).put("answers", JSONArray(now)).put("kid_id", kid.id).put("day", Sync.today())
                    thread { Sync.tutor(prefs, body) }          // best effort; the local copy is already safe
                }
                handler.postDelayed(pending!!, 1500)
            }
        })
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
    val fields = questionCards(kid, subject, exId, r.getJSONArray("questions"), r.optJSONArray("draft"))
    add(ui.pill("✅ ارسال جواب‌ها", Pal.GREEN) {
        val answers = JSONArray(fields.map { it.text.toString().trim() })
        tutorCall(kid, JSONObject().put("action", "assess_submit").put("subject", subject).put("exercise_id", exId).put("answers", answers)) { code, res ->
            if (code == 200 && res != null) { TutorState.drafts.remove(exId); tutorResultCard(kid, subject, res) }
            else toast(if (code == 429) "امروز دیگه بسه 🌙" else "ارسال نشد، ولی جواب‌هایت ذخیره است. دوباره بفرست 🔄")
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
    goTo { tutorHub(kid, subject, r) }
    TutorState.subject = subject; TutorState.lessonId = ""
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📚 $subject · سطح: ${r.optString("level")}", 22f, Pal.INK, true))
    if (r.optString("goal").isNotEmpty()) add(ui.text("🎯 هدف: ${r.optString("goal")}", 15f, Pal.MUTED))
    r.optJSONObject("open_practice")?.let { op ->
        add(ui.pill("▶ ادامه‌ی تمرین نیمه‌تمام", Pal.ORANGE) {
            tutorPracticeScreen(kid, subject, op.optString("lesson_id").takeIf { it.isNotEmpty() && it != "null" }, op, "")
        })
    }
    r.optJSONObject("last_lesson")?.let { ll ->
        add(ui.pill("📖 ادامه‌ی درس: ${ll.optString("title")} (${fa(ll.optInt("done"))} از ${fa(ll.optInt("total"))} بخش)", Pal.PURPLE) { tutorOpenLesson(kid, subject, ll.optString("id")) })
    }
    add(ui.pill("📅 برنامه‌ی امروز", Pal.PURPLE) { tutorPlan(kid, subject) })
    add(ui.pill("📖 درس‌های من (کتاب، فیلم، صوت)", Pal.BLUE) { tutorLessons(kid, subject) })
    add(ui.pill("💡 توضیح یک موضوع یا صفحه", Pal.ORANGE) { tutorExplain(kid, subject, "") })
    add(ui.pill("🎯 تمرین سریع", Pal.BLUE) { tutorPractice(kid, subject, "") })
    add(ui.pill("📜 سابقه‌ی من", Pal.MUTED) { tutorHistory(kid, subject) })
}

fun MainActivity.tutorPlan(kid: Kid, subject: String) = safely("tutorPlan") { tutorPlanUnsafe(kid, subject) }

private fun MainActivity.tutorPlanUnsafe(kid: Kid, subject: String) {
    goTo { tutorPlan(kid, subject) }
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

fun MainActivity.tutorPractice(kid: Kid, subject: String, topic: String, lessonId: String? = null, section: Int? = null) {
    goTo { tutorPractice(kid, subject, topic, lessonId, section) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("🎯 تمرین" + (if (topic.isEmpty()) "" else " · $topic"), 22f, Pal.INK, true))
    add(ui.text("⏳ سوال‌ها دارند آماده می‌شوند…", 15f, Pal.MUTED))
    val body = JSONObject().put("action", "practice").put("subject", subject).put("topic", topic)
    if (lessonId != null) body.put("lesson_id", lessonId)
    if (section != null) body.put("section", section)
    tutorCall(kid, body) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        tutorPracticeScreen(kid, subject, lessonId, r, topic)
    }
}

/** The questions screen. If sending fails, the typed answers stay on screen and in storage, and the button can be pressed again. */
fun MainActivity.tutorPracticeScreen(kid: Kid, subject: String, lessonId: String?, r: JSONObject, topic: String) = safely("tutorPracticeScreen") { tutorPracticeScreenUnsafe(kid, subject, lessonId, r, topic) }

private fun MainActivity.tutorPracticeScreenUnsafe(kid: Kid, subject: String, lessonId: String?, r: JSONObject, topic: String) {
    goTo { tutorPracticeScreen(kid, subject, lessonId, r, topic) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("🎯 تمرین" + (if (topic.isEmpty()) "" else " · $topic"), 22f, Pal.INK, true))
    if (r.optBoolean("resumed")) add(ui.text("این تمرین را قبلاً شروع کرده بودی؛ ادامه بده.", 14f, Pal.MUTED))
    val exId = r.getString("exercise_id")
    val fields = questionCards(kid, subject, exId, r.getJSONArray("questions"), r.optJSONArray("draft"))
    val lid = lessonId ?: r.optString("lesson_id").takeIf { it.isNotEmpty() && it != "null" }
    add(ui.pill("📤 تحویل بده", Pal.GREEN) {
        val answers = JSONArray(fields.map { it.text.toString().trim() })
        tutorCall(kid, JSONObject().put("action", "check").put("subject", subject).put("exercise_id", exId).put("answers", answers)) { c2, res ->
            if (c2 == 200 && res != null) { TutorState.drafts.remove(exId); tutorChecked(kid, subject, res, lid) }
            else toast(if (c2 == 409) "این تمرین قبلاً چک شده" else "معلم الان جواب نداد، ولی جواب‌هایت ذخیره است. دوباره «تحویل بده» را بزن 🔄")
        }
    })
}

fun MainActivity.tutorChecked(kid: Kid, subject: String, res: JSONObject, lessonId: String? = null) {
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
    if (lessonId != null) add(ui.pill("📖 برگشت به درس", Pal.GREEN) { tutorOpenLesson(kid, subject, lessonId) })
    else add(ui.pill("ادامه", Pal.GREEN) { tutorStart(kid, subject, "") })
}

/** What the kid did so far: recent exercises with scores, and lessons with progress. */
fun MainActivity.tutorHistory(kid: Kid, subject: String) = safely("tutorHistory") { tutorHistoryUnsafe(kid, subject) }

private fun MainActivity.tutorHistoryUnsafe(kid: Kid, subject: String) {
    goTo { tutorHistory(kid, subject) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📜 سابقه‌ی من · $subject", 22f, Pal.INK, true))
    tutorCall(kid, JSONObject().put("action", "history").put("subject", subject)) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        val lessons = r.getJSONArray("lessons")
        if (lessons.length() > 0) add(ui.title("📖 درس‌ها"))
        for (i in 0 until lessons.length()) {
            val l = lessons.getJSONObject(i)
            add(ui.card().apply {
                addView(ui.text(l.getString("title") + if (l.getString("status") == "done") " ✅" else "", 16f, Pal.INK, true))
                addView(ui.text("${fa(l.getInt("done"))} از ${fa(l.getInt("total"))} بخش تمام شد", 14f, Pal.MUTED))
            })
        }
        val items = r.getJSONArray("items")
        add(ui.title("🗓 کارهای اخیر"))
        if (items.length() == 0) add(ui.card().apply { addView(ui.text("هنوز چیزی انجام نداده‌ای.", 15f, Pal.MUTED)) })
        for (i in 0 until items.length()) {
            val it = items.getJSONObject(i)
            val kind = when (it.getString("kind")) { "assessment" -> "📝 تعیین سطح"; "practice" -> "🎯 تمرین"; else -> "💡 توضیح" }
            val tail = when {
                it.getString("status") == "open" -> "نیمه‌تمام ⏳"
                !it.isNull("score") -> "نمره: ${fa(it.getInt("score"))}"
                !it.isNull("understood") -> if (it.getBoolean("understood")) "فهمیدم ✅" else "نفهمیدم 🤔"
                else -> ""
            }
            add(ui.card().apply { addView(ui.text("$kind · ${it.getString("day")} · $tail" + (if (it.isNull("lesson")) "" else " · ${it.getString("lesson")}"), 14f, Pal.INK)) })
        }
    }
}

fun MainActivity.tutorExplain(kid: Kid, subject: String, prefill: String) {
    goTo { tutorExplain(kid, subject, prefill) }
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
    goTo { tutorExplain(kid, subject, text) }
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("⏳ معلم دارد تخته را آماده می‌کند…", 18f, Pal.MUTED, true))
    tutorCall(kid, JSONObject().put("action", "explain").put("subject", subject).put("text", text).put("simpler", simpler)) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        tutorBoard(kid, subject, r, text)
    }
}

/** Called after a photo of a book page was sent for explaining. */
fun MainActivity.tutorGotExplain(code: Int, r: JSONObject?) = safely("tutorGotExplain") { tutorGotExplainUnsafe(code, r) }

private fun MainActivity.tutorGotExplainUnsafe(code: Int, r: JSONObject?) {
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
fun MainActivity.tutorBoard(kid: Kid, subject: String, r: JSONObject, text: String) = safely("tutorBoard") { tutorBoardUnsafe(kid, subject, r, text) }

private fun MainActivity.tutorBoardUnsafe(kid: Kid, subject: String, r: JSONObject, text: String) {
    goTo { tutorBoard(kid, subject, r, text) }
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