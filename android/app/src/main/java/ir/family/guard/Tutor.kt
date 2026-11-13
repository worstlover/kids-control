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
    @Volatile var track = SCHOOL                                  // SCHOOL lesson (textbook) or FREE class (after-school)
    @Volatile var gen = 0                                         // bumped on every screen change; late replies for an old screen are dropped
    @Volatile var kidId = ""
    @Volatile var lessonId = ""                                   // lesson a book QR / photo should attach to ("" = make a new lesson)
    val drafts = java.util.concurrent.ConcurrentHashMap<String, List<String>>()   // answers typed so far, per exercise
    val boards = java.util.concurrent.ConcurrentHashMap<String, BoardProgress>()  // where the kid is on each blackboard lesson
    @Volatile var muted = false                                   // teacher's voice off
    @Volatile var solutionLesson: String? = null                  // lesson to go back to after a photo of a worked solution
    @Volatile var onSpeech: ((String) -> Unit)? = null            // where the text from the 🎤 button goes
    val sectionPick = java.util.concurrent.ConcurrentHashMap<String, Int>()   // lesson id -> section the kid tapped
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
                tts?.setSpeechRate(0.9f)                          // a little slower: it is a teacher talking to a kid
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

const val SCHOOL = "school"
const val FREE = "free"

/** School lessons follow the textbook (photo of the page, book QR). Keys are what the server stores. */
private val SCHOOL_SUBJECTS = listOf("🔤 انگلیسی" to "english", "➗ ریاضی" to "math", "📝 فارسی" to "persian", "🔬 علوم" to "science")
/** Free (after-school) classes: the tutor follows the kid's goal, not the school book. Kept apart from school lessons on the server. */
private val FREE_CLASSES = listOf(
    "🗣 زبان انگلیسی (آموزشگاه)" to "english", "💻 برنامه‌نویسی" to "برنامه‌نویسی", "♟ شطرنج" to "شطرنج",
    "🎸 موسیقی" to "موسیقی", "🎨 نقاشی" to "نقاشی", "🧠 محاسبات ذهنی" to "محاسبات ذهنی",
)

fun isFree() = TutorState.track == FREE

/** Name shown to the kid for a subject key, e.g. "math" -> "➗ ریاضی", "شطرنج" (free) -> "♟ شطرنج". */
fun subjectLabel(key: String, track: String = TutorState.track): String =
    (if (track == FREE) FREE_CLASSES else SCHOOL_SUBJECTS).firstOrNull { it.second == key }?.first ?: key

/** "🏫 مدرسه · ➗ ریاضی" or "🎨 کلاس آزاد · ♟ شطرنج" — so the kid always sees which kind of class this is. */
fun subjectTitle(key: String) = (if (isFree()) "🎨 کلاس آزاد · " else "🏫 مدرسه · ") + subjectLabel(key)

/**
 * Remember the screen that is being drawn, so a redraw (render() after a sync, onResume…) shows it again.
 * It does NOT draw. Screens call this on themselves.
 * (Calling goTo here was the force close: goTo draws the screen, which called goTo again, forever -> StackOverflowError.)
 */
fun MainActivity.keepScreen(screen: () -> Unit) { tutorView = screen }

/** Jump to ANOTHER screen: remember it and draw it. A screen must never goTo itself (use keepScreen). */
fun MainActivity.goTo(screen: () -> Unit) { tutorView = screen; screen() }

/** Start drawing a new tutor screen. Replies still on their way for the previous screen are ignored. */
fun MainActivity.clearTutor() { TutorState.gen++; speaker.stop(); box.removeAllViews() }

fun MainActivity.tutorBack(kid: Kid) = add(ui.pill("← درس‌ها", Pal.MUTED) { goTo { tutorHome(kid) } })

/** Runs one screen step. If the server's reply has an unexpected shape, show a message instead of closing the app. */
internal inline fun MainActivity.safely(what: String, block: () -> Unit) {
    try { block() } catch (e: Exception) {
        android.util.Log.e("tutor", "$what: unexpected reply shape", e)
        toast("جواب معلم کامل نبود؛ دوباره امتحان کن 🔄")
    }
}

/**
 * Every tutor request runs in the background; the result is handled on the UI thread.
 * A reply that comes back after the kid moved to another screen is dropped (it used to be drawn on top of the new screen).
 * [always] = the reply should still be shown if the kid is anywhere in the tutor (results of "send answers" and the like).
 */
fun MainActivity.tutorCall(kid: Kid, body: JSONObject, always: Boolean = false, then: (Int, JSONObject?) -> Unit) {
    body.put("kid_id", kid.id).put("day", Sync.today()).put("track", TutorState.track)
    val gen = TutorState.gen
    thread {
        // Never let a bad/partial reply from the server crash the app.
        val (code, r) = try { Sync.tutor(prefs, body) } catch (e: Exception) { Pair(-1, null) }
        runOnUiThread { deliver(gen, always) { then(code, r) } }
    }
}

/** Runs a reply handler only if it still belongs on screen, and never lets it close the app. */
internal fun MainActivity.deliver(gen: Int, always: Boolean, block: () -> Unit) {
    if (isFinishing || isDestroyed) return
    if (tutorView == null) return                          // kid left the tutor; don't draw on the home screen
    if (!always && gen != TutorState.gen) return           // an older screen's reply
    try { block() }
    catch (e: Exception) {
        android.util.Log.e("tutor", "unexpected reply shape", e)
        toast("جواب معلم کامل نبود؛ دوباره امتحان کن 🔄")
    }
}

fun MainActivity.tutorHome(kid: Kid) {
    TutorState.kidId = kid.id
    tutorView = { tutorHome(kid) }
    clearTutor()
    add(ui.pill("← بازگشت", Pal.MUTED) { tutorView = null; render() })
    add(ui.text("🎓 معلم من", 26f, Pal.INK, true))
    add(ui.text("اول معلم سطحت را می‌سنجد، بعد برایت برنامه‌ی روزانه می‌نویسد. درس‌های مدرسه و کلاس‌های آزاد جدا از هم پیش می‌روند.", 15f, Pal.MUTED))

    val school = trackSection(kid, SCHOOL, "🏫 درس‌های مدرسه", "طبق کتاب درسی؛ با عکس صفحه و QR کتاب", Pal.BLUE,
        SCHOOL_SUBJECTS, "درس دیگر مدرسه، مثلاً عربی یا مطالعات")
    val free = trackSection(kid, FREE, "🎨 کلاس‌های آزاد", "بیرون از مدرسه: زبان، کدنویسی، موسیقی، شطرنج…؛ طبق هدف خودت", Pal.ORANGE,
        FREE_CLASSES, "کلاس دیگر، مثلاً گیتار یا رباتیک")

    // Classes the kid already started (with their level) — best effort, the screen works without it.
    tutorCall(kid, JSONObject().put("action", "subjects")) { code, r ->
        if (code != 200 || r == null) return@tutorCall
        school.fill(r.optJSONArray("school"))
        free.fill(r.optJSONArray("free"))
    }
}

/** One of the two blocks on the tutor home: preset subjects, the kid's own ones (added when the server answers), and a "start another" box. */
private class TrackSection(val track: String, val presets: Map<String, android.widget.TextView>, val mine: LinearLayout, val onPick: (String) -> Unit, val color: Int, val ui: Ui) {
    fun fill(arr: JSONArray?) {
        if (arr == null) return
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val key = o.optString("subject").takeIf { it.isNotEmpty() } ?: continue
            val level = if (o.isNull("level")) "" else o.optString("level")
            val tail = if (level.isEmpty()) "  ⏳ تعیین سطح" else "  · سطح: $level"
            val preset = presets[key]
            if (preset != null) preset.text = subjectLabel(key, track) + tail
            else mine.addView(ui.pill(subjectLabel(key, track) + tail, color) { onPick(key) })
        }
    }
}

private fun MainActivity.trackSection(kid: Kid, track: String, title: String, sub: String, color: Int,
                                      presets: List<Pair<String, String>>, hint: String): TrackSection {
    val pick: (String) -> Unit = { key -> TutorState.track = track; if (track == SCHOOL) tutorStart(kid, key, "") else tutorIntro(kid, key) }
    add(ui.title(title))
    add(ui.text(sub, 14f, Pal.MUTED))
    val pills = LinkedHashMap<String, android.widget.TextView>()
    presets.forEach { (label, key) -> val p = ui.pill(label, color) { pick(key) }; pills[key] = p; add(p) }
    val mine = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    add(mine)
    val c = ui.card()
    val other = field(hint)
    c.addView(other)
    c.addView(ui.pill("شروع", Pal.GREEN) { val s = other.text.toString().trim().lowercase(); if (s.isNotEmpty()) pick(s) })
    add(c)
    return TrackSection(track, pills, mine, pick, color, ui)
}

fun MainActivity.tutorIntro(kid: Kid, subject: String) {
    TutorState.subject = subject
    keepScreen { tutorIntro(kid, subject) }
    clearTutor()
    tutorBack(kid)
    add(ui.text(subjectTitle(subject), 24f, Pal.INK, true))
    val c = ui.card()
    val goal = field(if (isFree()) "هدفت از این کلاس چیست؟ مثلاً: بتوانم یک آهنگ ساده بزنم" else "هدفت از این درس چیست؟ مثلاً: امتحان این ترم را خوب بدهم")
    c.addView(goal)
    c.addView(ui.pill("▶ شروع", Pal.GREEN) { tutorStart(kid, subject, goal.text.toString().trim()) })
    add(c)
}

fun MainActivity.tutorStart(kid: Kid, subject: String, goal: String) {
    TutorState.subject = subject
    keepScreen { tutorStart(kid, subject, "") }            // a redraw asks again (cheap: the server reuses the open level test)
    clearTutor()
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
    clearTutor()
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
private val QUESTION_TYPES = mapOf("apply" to "🔧 به‌کار ببر", "find_error" to "🔍 اشتباه را پیدا کن", "why" to "❓ چرا؟")

private fun MainActivity.questionCards(kid: Kid, subject: String, exId: String, qs: JSONArray, draft: JSONArray?, types: JSONArray? = null): List<EditText> {
    val fields = ArrayList<EditText>()
    val saved = TutorState.drafts[exId] ?: draft?.let { d -> (0 until d.length()).map { d.getString(it) } } ?: emptyList()
    val handler = android.os.Handler(android.os.Looper.getMainLooper())
    var pending: Runnable? = null
    for (i in 0 until qs.length()) {
        val c = ui.card()
        QUESTION_TYPES[types?.optString(i)]?.let { c.addView(ui.chip(it, Pal.PURPLE)) }
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
                    val body = JSONObject().put("action", "draft").put("subject", subject).put("exercise_id", exId).put("answers", JSONArray(now)).put("kid_id", kid.id).put("day", Sync.today()).put("track", TutorState.track)
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
    clearTutor()
    tutorBack(kid)
    add(ui.text("📝 اول ببینیم از کجا شروع کنیم", 22f, Pal.INK, true))
    add(ui.text("به این سوال‌ها جواب بده. اشتباه کردن اشکالی ندارد؛ فقط برای این است که بفهمم کجا کمک لازم داری.", 15f, Pal.MUTED))
    val exId = r.getString("exercise_id")
    val fields = questionCards(kid, subject, exId, r.getJSONArray("questions"), r.optJSONArray("draft"))
    add(ui.pill("✅ ارسال جواب‌ها", Pal.GREEN) {
        val answers = JSONArray(fields.map { it.text.toString().trim() })
        tutorCall(kid, JSONObject().put("action", "assess_submit").put("subject", subject).put("exercise_id", exId).put("answers", answers), always = true) { code, res ->
            if (code == 200 && res != null) { TutorState.drafts.remove(exId); tutorResultCard(kid, subject, res) }
            else toast(if (code == 429) "امروز دیگه بسه 🌙" else "ارسال نشد، ولی جواب‌هایت ذخیره است. دوباره بفرست 🔄")
        }
    })
}

fun MainActivity.tutorResultCard(kid: Kid, subject: String, res: JSONObject) {
    keepScreen { tutorResultCard(kid, subject, res) }
    clearTutor()
    tutorBack(kid)
    add(ui.text("🎉 سطحت: ${res.optString("level")}", 26f, Pal.PURPLE, true))
    add(ui.card().apply { addView(ui.text(res.optString("feedback"), 17f)) })
    add(ui.pill("ادامه", Pal.GREEN) { tutorStart(kid, subject, "") })
}

fun MainActivity.tutorHub(kid: Kid, subject: String, r: JSONObject) {
    keepScreen { tutorStart(kid, subject, "") }            // a redraw re-reads the profile, so "continue" buttons are never stale
    TutorState.subject = subject; TutorState.lessonId = ""
    clearTutor()
    tutorBack(kid)
    val level = r.optString("level").takeIf { it.isNotEmpty() && it != "null" }
    add(ui.text(subjectTitle(subject) + (if (level != null) " · سطح: $level" else ""), 22f, Pal.INK, true))
    if (r.optString("goal").isNotEmpty() && r.optString("goal") != "null") add(ui.text("🎯 هدف: ${r.optString("goal")}", 15f, Pal.MUTED))
    // School: the book comes first. Photograph the lesson (or its QR), learn it from the book, then answer questions.
    if (!isFree()) {
        val hasLessons = r.optInt("lessons_count") > 0
        add(ui.card().apply {
            addView(ui.text(if (hasLessons) "📚 درس امروزت از روی کتاب" else "📚 اول درس امروز را از کتاب بگیر", 18f, Pal.INK, true))
            addView(ui.text("از صفحه‌ی اول درس عکس بگیر یا QR کتاب را بزن؛ معلم همان را درس می‌دهد و بعد از همان سوال می‌پرسد.", 14f, Pal.MUTED))
            r.optJSONObject("last_lesson")?.let { ll ->
                addView(ui.pill("▶ ادامه‌ی «${ll.optString("title")}» (${fa(ll.optInt("done"))} از ${fa(ll.optInt("total"))} بخش)", Pal.PURPLE) { tutorOpenLesson(kid, subject, ll.optString("id")) })
            }
            addView(ui.pill("📷 عکس صفحه‌ی اول درس جدید", Pal.ORANGE) { TutorState.subject = subject; TutorState.kidId = kid.id; TutorState.lessonId = ""; takePhoto("lessonpage", "") })
            addView(ui.pill("🔳 QR کتاب", Pal.BLUE) { TutorState.subject = subject; TutorState.kidId = kid.id; TutorState.lessonId = ""; takePhoto("qr", "") })
            if (hasLessons) addView(ui.pill("📖 همه‌ی درس‌هایم", Pal.MUTED) { tutorLessons(kid, subject) })
        })
        add(ui.text("بیشتر:", 14f, Pal.MUTED))
    }
    r.optJSONObject("open_practice")?.let { op ->
        add(ui.pill("▶ ادامه‌ی تمرین نیمه‌تمام", Pal.ORANGE) {
            tutorPracticeScreen(kid, subject, op.optString("lesson_id").takeIf { it.isNotEmpty() && it != "null" }, op,
                if (op.optBoolean("exit")) "🚪 آزمون خروج" else if (op.optBoolean("review")) "🔁 مرور" else "")
        })
    }
    if (isFree()) r.optJSONObject("last_lesson")?.let { ll ->
        add(ui.pill("📖 ادامه‌ی درس: ${ll.optString("title")} (${fa(ll.optInt("done"))} از ${fa(ll.optInt("total"))} بخش)", Pal.PURPLE) { tutorOpenLesson(kid, subject, ll.optString("id")) })
    }
    val due = r.optInt("reviews_due")
    if (due > 0) add(ui.pill("🔁 مرور امروز: ${fa(due)} مفهوم (تا یادت نرود)", Pal.ORANGE) { tutorReview(kid, subject) })
    add(ui.pill("📅 برنامه‌ی امروز", Pal.PURPLE) { tutorPlan(kid, subject) })
    if (isFree()) add(ui.pill("📖 جلسه‌های من (از روی کتاب یا جزوه‌ی کلاس)", Pal.BLUE) { tutorLessons(kid, subject) })
    add(ui.pill("💡 توضیح یک موضوع یا صفحه", Pal.ORANGE) { tutorExplain(kid, subject, "") })
    add(ui.pill("🎯 تمرین سریع", Pal.BLUE) { tutorPractice(kid, subject, "") })
    val cc = r.optJSONObject("concept_counts")
    val known = (cc?.optInt("learned") ?: 0) + (cc?.optInt("solid") ?: 0)
    add(ui.pill("🧠 چیزهایی که یاد گرفتم" + (if (cc != null) " (${fa(known)} یاد گرفته · ${fa(cc.optInt("practicing"))} در حال تمرین)" else ""), Pal.GREEN) { tutorConcepts(kid, subject) })
    add(ui.pill("📜 سابقه‌ی من", Pal.MUTED) { tutorHistory(kid, subject) })
}

fun MainActivity.tutorPlan(kid: Kid, subject: String) = safely("tutorPlan") { tutorPlanUnsafe(kid, subject) }

private fun MainActivity.tutorPlanUnsafe(kid: Kid, subject: String) {
    keepScreen { tutorPlan(kid, subject) }
    clearTutor()
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
            val label = when (type) { "explain" -> "🖍 بریم پای تخته"; "review" -> "🔁 مرور"; else -> "✏️ شروع" }
            add(ui.pill(label, Pal.PURPLE) {
                when (type) {
                    "explain" -> tutorExplainCall(kid, subject, item.optString("title"), false)
                    "review" -> tutorReview(kid, subject, item.optString("title"))
                    else -> tutorPractice(kid, subject, item.optString("title"))
                }
            })
        }
    }
}

fun MainActivity.tutorPractice(kid: Kid, subject: String, topic: String, lessonId: String? = null, section: Int? = null, fresh: Boolean = false) {
    keepScreen { tutorPractice(kid, subject, topic, lessonId, section, false) }     // a redraw resumes the same set, never asks for another
    clearTutor()
    tutorBack(kid)
    add(ui.text("🎯 تمرین" + (if (topic.isEmpty()) "" else " · $topic"), 22f, Pal.INK, true))
    add(ui.text("⏳ سوال‌ها دارند آماده می‌شوند…", 15f, Pal.MUTED))
    val body = JSONObject().put("action", "practice").put("subject", subject).put("topic", topic).put("fresh", fresh)
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
    keepScreen { tutorPracticeScreen(kid, subject, lessonId, r, topic) }
    clearTutor()
    tutorBack(kid)
    val heading = if (topic.startsWith("🚪") || topic.startsWith("🔁")) topic else "🎯 تمرین" + (if (topic.isEmpty()) "" else " · $topic")
    add(ui.text(heading, 22f, Pal.INK, true))
    if (r.optBoolean("exit")) add(ui.text("سه سوال کوتاه که نشان می‌دهد واقعاً فهمیدی، نه فقط حفظ کردی. جواب درست بار اول، امتیاز دوبرابر دارد.", 15f, Pal.MUTED))
    if (r.optBoolean("review")) add(ui.text("این‌ها را قبلاً یاد گرفتی؛ ببینیم هنوز یادت هست؟ اگر یادت بود، یک پله محکم‌تر می‌شود.", 15f, Pal.MUTED))
    if (r.optBoolean("resumed")) add(ui.text("این تمرین را قبلاً شروع کرده بودی؛ ادامه بده.", 14f, Pal.MUTED))
    else if (r.optBoolean("reused")) add(ui.text("♻️ این سوال‌ها از قبل ذخیره شده بودند. سوال تازه می‌خوای؟ از صفحه‌ی درس «سوال‌های تازه» را بزن.", 14f, Pal.MUTED))
    val exId = r.getString("exercise_id")
    val fields = questionCards(kid, subject, exId, r.getJSONArray("questions"), r.optJSONArray("draft"), r.optJSONArray("types"))
    val lid = lessonId ?: r.optString("lesson_id").takeIf { it.isNotEmpty() && it != "null" }
    add(ui.pill("📷 راه‌حلم را روی کاغذ نوشتم (عکس بگیر)", Pal.BLUE) {
        // Typed answers (if any) go too; the teacher reads the rest from the photo and checks each step.
        TutorState.drafts[exId] = fields.map { it.text.toString() }
        TutorState.kidId = kid.id; TutorState.subject = subject; TutorState.solutionLesson = lid
        takePhoto("solution", exId)
    })
    add(ui.pill("📤 تحویل بده", Pal.GREEN) {
        val answers = JSONArray(fields.map { it.text.toString().trim() })
        tutorCall(kid, JSONObject().put("action", "check").put("subject", subject).put("exercise_id", exId).put("answers", answers), always = true) { c2, res ->
            if (c2 == 200 && res != null) { TutorState.drafts.remove(exId); tutorChecked(kid, subject, res, lid) }
            else toast(if (c2 == 409) "این تمرین قبلاً چک شده" else "معلم الان جواب نداد، ولی جواب‌هایت ذخیره است. دوباره «تحویل بده» را بزن 🔄")
        }
    })
}

fun MainActivity.tutorChecked(kid: Kid, subject: String, res: JSONObject, lessonId: String? = null) {
    keepScreen { tutorChecked(kid, subject, res, lessonId) }
    clearTutor()
    tutorBack(kid)
    val score = res.optInt("score")
    val total = res.optInt("total", 5)
    add(ui.text(if (score * 5 >= total * 4) "🏆 عالی! ${fa(score)} از ${fa(total)}" else "💪 ${fa(score)} از ${fa(total)}؛ داری خوب پیش می‌ری", 22f, Pal.PURPLE, true))
    val points = if (res.has("points")) res.optInt("points") else score
    if (points > 0) add(ui.chip("+${fa(points)} امتیاز 🪙", Pal.YELLOW, Pal.INK))
    when (res.optString("level_changed")) {
        "up" -> add(ui.card().apply { addView(ui.text("🚀 سطحت رفت بالا: ${res.optString("level")}", 18f, Pal.GREEN, true)) })
        "down" -> add(ui.card().apply { addView(ui.text("سطح را کمی ساده‌تر کردم تا محکم‌تر جلو برویم: ${res.optString("level")}", 16f, Pal.INK)) })
    }
    conceptProgress(res.optJSONArray("concepts"))
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
    keepScreen { tutorHistory(kid, subject) }
    clearTutor()
    tutorBack(kid)
    add(ui.text("📜 سابقه‌ی من · ${subjectLabel(subject)}", 22f, Pal.INK, true))
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
            val kind = when {
                it.optString("mode") == "exit" -> "🚪 آزمون خروج"
                it.optString("mode") == "review" -> "🔁 مرور"
                else -> when (it.getString("kind")) { "assessment" -> "📝 تعیین سطح"; "practice" -> "🎯 تمرین"; else -> "🖍 تخته" }
            } + (if (it.isNull("title") || it.optString("title").isEmpty()) "" else " «${it.optString("title")}»")
            val tail = when {
                !it.isNull("teach") -> "یاد دادی: ${fa(it.getInt("teach"))} از ۱۰۰"
                it.getString("status") == "open" && it.getString("kind") != "explain" -> "نیمه‌تمام ⏳"
                !it.isNull("score") -> "نمره: ${fa(it.getInt("score"))}" + (if (it.isNull("total")) "" else " از ${fa(it.getInt("total"))}")
                !it.isNull("understood") -> if (it.getBoolean("understood")) "فهمیدم ✅" else "نفهمیدم 🤔"
                else -> ""
            }
            add(ui.card().apply { addView(ui.text("$kind · ${it.getString("day")} · $tail" + (if (it.isNull("lesson")) "" else " · ${it.getString("lesson")}"), 14f, Pal.INK)) })
        }
    }
}

fun MainActivity.tutorExplain(kid: Kid, subject: String, prefill: String) {
    keepScreen { tutorExplain(kid, subject, prefill) }
    clearTutor()
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
    add(ui.pill(if (isFree()) "📷 عکس کتاب یا جزوه‌ی کلاس" else "📷 عکس صفحه‌ی کتاب", Pal.ORANGE) { TutorState.kidId = kid.id; takePhoto("tutor", "") })
}

fun MainActivity.tutorExplainCall(kid: Kid, subject: String, text: String, simpler: Boolean) {
    keepScreen { tutorExplain(kid, subject, text) }
    clearTutor()
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
