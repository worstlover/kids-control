package ir.family.guard

import android.app.AlertDialog
import android.app.Dialog
import android.graphics.Color
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.MediaController
import android.widget.VideoView
import org.json.JSONObject
import kotlin.concurrent.thread

/** Same idea as tutorCall, but for the lessons endpoint. */
fun MainActivity.lessonCall(kid: Kid, subject: String, body: JSONObject, then: (Int, JSONObject?) -> Unit) {
    body.put("kid_id", kid.id).put("day", Sync.today()).put("subject", subject)
    thread {
        val (code, r) = Sync.tutorLesson(prefs, body)
        runOnUiThread {
            try { then(code, r) }
            catch (e: Exception) {
                android.util.Log.e("tutor", "lesson reply shape", e)
                toast("جواب درس کامل نبود؛ دوباره امتحان کن 🔄")
            }
        }
    }
}

fun MainActivity.tutorLessons(kid: Kid, subject: String) = safely("tutorLessons") { tutorLessonsUnsafe(kid, subject) }

private fun MainActivity.tutorLessonsUnsafe(kid: Kid, subject: String) {
    goTo { tutorLessons(kid, subject) }
    TutorState.lessonId = ""
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("📖 درس‌های من · $subject", 22f, Pal.INK, true))
    add(ui.pill("➕ درس جدید از کتاب", Pal.GREEN) { tutorNewLesson(kid, subject) })
    lessonCall(kid, subject, JSONObject().put("action", "list")) { code, r ->
        if (code != 200 || r == null) return@lessonCall tutorError(kid, subject, code)
        val arr = r.getJSONArray("lessons")
        if (arr.length() == 0) add(ui.card().apply { addView(ui.text("هنوز درسی نساخته‌ای. با عکس صفحه‌ی اول درس شروع کن.", 15f, Pal.MUTED)) })
        for (i in 0 until arr.length()) {
            val l = arr.getJSONObject(i)
            val secs = l.getJSONArray("sections")
            val done = (0 until secs.length()).count { secs.getJSONObject(it).getBoolean("done") }
            add(ui.card().apply {
                addView(ui.text(l.getString("title") + if (l.getString("status") == "done") " ✅" else "", 18f, Pal.INK, true))
                addView(ui.text("${fa(done)} از ${fa(secs.length())} بخش", 14f, Pal.MUTED))
                if (secs.length() > 0) addView(BarView(this@MainActivity).apply {
                    fraction = done / secs.length().toFloat(); barColor = Pal.GREEN; barColor2 = Pal.BLUE
                    layoutParams = LinearLayout.LayoutParams(-1, ui.dp(10)).apply { topMargin = ui.dp(6) }
                })
                addView(ui.pill("ادامه", Pal.PURPLE) { tutorLessonHub(kid, subject, l) })
            })
        }
    }
}

fun MainActivity.tutorNewLesson(kid: Kid, subject: String) {
    goTo { tutorNewLesson(kid, subject) }
    TutorState.subject = subject; TutorState.kidId = kid.id; TutorState.lessonId = ""
    box.removeAllViews()
    tutorBack(kid)
    add(ui.text("➕ درس جدید", 24f, Pal.INK, true))
    add(ui.card().apply {
        addView(ui.text("۱) از صفحه‌ی اول درس عکس بگیر؛ همان‌جا که نوشته این درس چه بخش‌هایی دارد. من عنوان و بخش‌ها را می‌خوانم.", 15f))
        addView(ui.text("۲) بعد می‌توانی QR کتاب را هم بگیری تا فیلم و صوت درس به همین درس اضافه شود.", 15f))
    })
    add(ui.pill("📷 عکس صفحه‌ی اول درس", Pal.ORANGE) { takePhoto("lessonpage", "") })
    add(ui.pill("🔳 فقط QR کتاب", Pal.BLUE) { takePhoto("qr", "") })
}

fun MainActivity.tutorOpenLesson(kid: Kid, subject: String, lessonId: String) {
    lessonCall(kid, subject, JSONObject().put("action", "get").put("lesson_id", lessonId)) { code, r ->
        if (code == 200 && r != null) tutorLessonHub(kid, subject, r.getJSONObject("lesson")) else tutorError(kid, subject, code)
    }
}

fun MainActivity.tutorLessonHub(kid: Kid, subject: String, lesson: JSONObject) = safely("tutorLessonHub") { tutorLessonHubUnsafe(kid, subject, lesson) }

private fun MainActivity.tutorLessonHubUnsafe(kid: Kid, subject: String, lesson: JSONObject) {
    goTo { tutorLessonHub(kid, subject, lesson) }
    TutorState.subject = subject; TutorState.kidId = kid.id; TutorState.lessonId = lesson.getString("id")
    box.removeAllViews()
    tutorBack(kid)
    val lid = lesson.getString("id")
    val secs = lesson.getJSONArray("sections")
    val pos = lesson.optInt("position", 0)
    add(ui.text("📖 ${lesson.getString("title")}", 24f, Pal.INK, true))

    if (secs.length() > 0) {
        add(ui.title("این درس چه بخش‌هایی دارد"))
        add(ui.card().apply {
            for (i in 0 until secs.length()) {
                val s = secs.getJSONObject(i)
                val icon = if (s.getBoolean("done")) "✅" else if (i == pos) "▶️" else "⚪"
                val extra = if (s.getInt("asked") > 0) "  (${fa(s.getInt("correct"))} از ${fa(s.getInt("asked"))} درست)" else ""
                addView(ui.text("$icon ${s.getString("title")}$extra", 16f, if (i == pos) Pal.PURPLE else Pal.INK, i == pos))
            }
        })
        val title = secs.getJSONObject(pos.coerceIn(0, secs.length() - 1)).getString("title")
        add(ui.pill("🎯 تمرین بخش «$title»", Pal.GREEN) { tutorPractice(kid, subject, title, lid, pos) })
    } else add(ui.card().apply { addView(ui.text("بخش‌ها هنوز مشخص نیست. عکس صفحه‌ی اول درس را بگیر.", 14f, Pal.MUTED)) })

    val media = lesson.optJSONArray("media")
    var hasAudio = false
    if (media != null && media.length() > 0) {
        add(ui.title("فیلم و صوت درس"))
        for (i in 0 until media.length()) {
            val m = media.getJSONObject(i)
            val url = m.getString("url")
            when (m.getString("type")) {
                "video" -> add(ui.pill("▶ فیلم درس", Pal.PURPLE) { playVideo(url) })
                "page" -> add(ui.pill("▶ فیلم درس (صفحه‌ی وب)", Pal.PURPLE) { playWeb(url) })
                "audio" -> { hasAudio = true; add(ui.pill("🎧 صوت درس", Pal.BLUE) { toggleAudio(url) }) }
            }
        }
    }
    if (hasAudio) add(ui.pill("✏️ دیکته از روی صوت درس", Pal.ORANGE) {
        lessonCall(kid, subject, JSONObject().put("action", "audio_dictation").put("lesson_id", lid)) { code, r ->
            val msg = when {
                code == 200 && r?.optString("status") == "review" -> "دیکته ساخته شد (${fa(r.optInt("phrase_count"))} جمله).\nبعد از تایید پدر و مادر شروع می‌شود."
                code == 200 && r?.optString("status") == "ready" -> "دیکته آماده است! از صفحه‌ی اصلی، بخش دیکته، شروعش کن."
                code == 200 -> r?.optString("feedback") ?: "صوت خوانده نشد."
                code == 429 -> "امروز به اندازه‌ی کافی دیکته‌ی جدید گرفتی؛ فردا دوباره."
                code == 403 -> "این فایل صوتی مجاز نیست؛ از پدر و مادر بخواه اجازه بدهند."
                code == 415 || code == 404 -> "این فایل برای دیکته مناسب نیست."
                else -> "الان نشد؛ کمی بعد دوباره امتحان کن."
            }
            AlertDialog.Builder(this).setMessage(msg).setPositiveButton("باشه", null).show()
            refresh()
        }
    })
    add(ui.pill("🔳 افزودن QR کتاب به این درس", Pal.MUTED) { takePhoto("qr", "") })
}

/** Result of making a lesson from a photo or QR; also used when a QR is attached. */
fun MainActivity.tutorGotLesson(sd: StatusDialog, code: Int, r: JSONObject?, attached: Boolean) = safely("tutorGotLesson") { tutorGotLessonUnsafe(sd, code, r, attached) }

private fun MainActivity.tutorGotLessonUnsafe(sd: StatusDialog, code: Int, r: JSONObject?, attached: Boolean) {
    val kid = Sync.loadState(prefs)?.kids?.firstOrNull { it.id == TutorState.kidId }
    val subject = TutorState.subject
    when {
        code == 200 && r != null && r.has("lesson") && kid != null -> {
            val l = r.getJSONObject("lesson")
            val body = if (attached) "فیلم و صوت اضافه شد: ${fa(r.optInt("added"))} مورد." else "${fa(l.getJSONArray("sections").length())} بخش پیدا شد."
            sd.result("📚", if (attached) "QR اضافه شد" else l.getString("title"), body, null, null, "برو به درس") { tutorLessonHub(kid, subject, l) }
        }
        code == 200 && r?.optString("status") == "retake" -> sd.result("📷", "صفحه واضح نبود", r.optString("feedback"), null, null, "دوباره می‌گیرم")
        code == 403 -> sd.result("🚫", "این لینک مجاز نیست", "آدرس «${r?.optString("host") ?: ""}» را پدر و مادر باید اجازه بدهند.", null, null)
        code == 400 -> sd.result("🤔", "این QR درست نیست", "QR کتاب درسی را بگیر.", null, null)
        code == 429 -> sd.result("🌙", "امروز دیگه بسه", "به سقف درس‌های جدید امروز رسیدی؛ فردا دوباره.", null, null)
        else -> sd.result("📶", "نشد", "اینترنت را چک کن و دوباره امتحان کن. درس‌هایی که قبلاً ساخته‌ای سر جایشان هستند.", null, null)
    }
}

// ---------------- players ----------------
object MediaHolder { var audio: MediaPlayer? = null; var url: String = "" }

fun MainActivity.stopMedia() { try { MediaHolder.audio?.stop(); MediaHolder.audio?.release() } catch (e: Exception) {}; MediaHolder.audio = null; MediaHolder.url = "" }

fun MainActivity.toggleAudio(url: String) {
    val cur = MediaHolder.audio
    if (cur != null && MediaHolder.url == url) { if (cur.isPlaying) { cur.pause(); toast("⏸ ایست") } else { cur.start(); toast("▶ ادامه") }; return }
    stopMedia()
    toast("🎧 در حال آماده شدن…")
    try {
        MediaHolder.audio = MediaPlayer().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
            setDataSource(url)
            setOnPreparedListener { it.start() }
            setOnErrorListener { _, _, _ -> toast("فایل صوتی باز نشد"); stopMedia(); true }
            setOnCompletionListener { stopMedia() }
            prepareAsync()
        }
        MediaHolder.url = url
    } catch (e: Exception) { toast("فایل صوتی باز نشد") }
}

fun MainActivity.playVideo(url: String) {
    val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val vv = VideoView(this)
    frame.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    val mc = MediaController(this); mc.setAnchorView(vv); vv.setMediaController(mc)
    frame.addView(ui.pill("✕ بستن", Pal.RED) { try { vv.stopPlayback() } catch (e: Exception) {}; dlg.dismiss() },
        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(24); marginEnd = ui.dp(16) })
    vv.setOnErrorListener { _, _, _ -> toast("فیلم باز نشد"); dlg.dismiss(); true }
    vv.setOnPreparedListener { it.start() }
    vv.setVideoURI(android.net.Uri.parse(url))
    dlg.setContentView(frame)
    dlg.setOnDismissListener { try { vv.stopPlayback() } catch (e: Exception) {} }
    dlg.show()
}

/** Pages like an embedded Aparat player. Navigation is kept on the page's own site. */
fun MainActivity.playWeb(url: String) {
    val host = android.net.Uri.parse(url).host ?: ""
    val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val frame = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
    val web = WebView(this)
    @Suppress("SetJavaScriptEnabled") web.settings.javaScriptEnabled = true
    web.settings.mediaPlaybackRequiresUserGesture = false
    web.webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean = req?.url?.host != host
    }
    frame.addView(web, FrameLayout.LayoutParams(-1, -1))
    frame.addView(ui.pill("✕ بستن", Pal.RED) { web.loadUrl("about:blank"); dlg.dismiss() },
        FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END).apply { topMargin = ui.dp(24); marginEnd = ui.dp(16) })
    dlg.setContentView(frame)
    dlg.setOnDismissListener { web.loadUrl("about:blank"); web.destroy() }
    web.loadUrl(url)
    dlg.show()
}
