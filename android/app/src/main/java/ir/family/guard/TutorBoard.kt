package ir.family.guard

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import android.widget.LinearLayout
import org.json.JSONArray
import org.json.JSONObject

/** Where the kid is on one blackboard lesson; survives redraws (sync, coming back from the mic or camera). */
class BoardProgress {
    var idx = 0
    val passed = HashSet<Int>()                               // steps whose question was answered right
    val tries = HashMap<Int, Int>()
    val notes = HashMap<Int, String>()                        // teacher's feedback under the question
    val again = HashMap<Int, Pair<String, String?>>()         // step explained another way after a wrong answer: (say, svg)
}

// ---------------- the blackboard page (runs inside a WebView with no network) ----------------
// No '$' anywhere in these strings: they are Kotlin raw strings.
private const val BOARD_HEAD = """<!DOCTYPE html><html><head><meta name="viewport" content="width=device-width,initial-scale=1"><style>
html,body{margin:0;height:100%;overflow:hidden;background:#5b3a1e;-webkit-user-select:none;user-select:none}
#f{position:absolute;inset:0;border-radius:14px;background:linear-gradient(135deg,#8a5a2b,#5b3a1e)}
#k{position:absolute;inset:9px 9px 20px 9px;border-radius:6px;box-shadow:inset 0 0 34px rgba(0,0,0,.75);
 background:radial-gradient(120% 90% at 22% 12%,rgba(255,255,255,.075),rgba(255,255,255,0) 55%),
 radial-gradient(90% 70% at 82% 88%,rgba(255,255,255,.05),rgba(255,255,255,0) 60%),
 repeating-linear-gradient(115deg,rgba(255,255,255,.014) 0 2px,rgba(0,0,0,0) 2px 8px),#1d2220}
#tray{position:absolute;left:9px;right:9px;bottom:5px;height:10px;border-radius:3px;background:#4a2e17;box-shadow:0 2px 3px rgba(0,0,0,.4)}
.ch{position:absolute;bottom:13px;height:5px;width:24px;border-radius:3px}
#b{position:absolute;inset:16px 18px 26px 18px;display:flex;align-items:center;justify-content:center;transition:opacity .45s,filter .45s}
#b svg{width:100%;height:100%;overflow:visible;filter:drop-shadow(0 0 1px rgba(255,255,255,.55))}
#b svg text{font-family:sans-serif;font-weight:bold}
#w{color:#F8F8F2;font:bold 22px/1.75 sans-serif;text-align:center;padding:6px 10px;direction:rtl;
 text-shadow:0 0 2px rgba(255,255,255,.45);clip-path:inset(0 0 0 100%);animation:write 1.8s steps(30) .2s forwards}
@keyframes write{to{clip-path:inset(0 0 0 0)}}
.gone{opacity:0;filter:blur(4px)}
#er{position:absolute;top:28%;right:-90px;font-size:58px;animation:sweep .5s linear forwards}
@keyframes sweep{to{right:110%}}
</style></head><body><div id="f"></div><div id="k"></div><div id="tray"></div>
<div class="ch" style="left:28px;background:#F8F8F2"></div><div class="ch" style="left:60px;background:#FFE066"></div><div class="ch" style="left:92px;background:#FF8FB1"></div>
<div id="b">"""

private const val BOARD_TAIL = """</div><script>
var CH='#F8F8F2';
function lum(c){var x=document.createElement('canvas').getContext('2d');x.fillStyle='#000';x.fillStyle=c;var v=x.fillStyle;
 if(v.charAt(0)!='#'){var m=v.match(/[\d.]+/g);if(!m)return 1;if(m.length>3&&+m[3]===0)return 0;return (0.299*m[0]+0.587*m[1]+0.114*m[2])/255;}
 return (0.299*parseInt(v.substr(1,2),16)+0.587*parseInt(v.substr(3,2),16)+0.114*parseInt(v.substr(5,2),16))/255;}
function dark(c){return !c||c=='none'||lum(c)<0.45;}
function prep(){
 var s=document.querySelector('#b svg');if(!s)return;
 s.removeAttribute('width');s.removeAttribute('height');s.setAttribute('preserveAspectRatio','xMidYMid meet');
 var vb=(s.getAttribute('viewBox')||'0 0 400 240').split(/[\s,]+/).map(Number);var area=(vb[2]||400)*(vb[3]||240);
 var els=s.querySelectorAll('rect,circle,ellipse,line,polyline,polygon,path,text'),list=[];
 for(var i=0;i<els.length;i++){var e=els[i],tag=e.tagName.toLowerCase();
  if(tag=='rect'&&(+e.getAttribute('width')||0)*(+e.getAttribute('height')||0)>area*0.8){e.parentNode.removeChild(e);continue;}
  var f=e.getAttribute('fill'),st=e.getAttribute('stroke');
  if(tag=='text'){if(dark(f))e.setAttribute('fill',CH);e.removeAttribute('stroke');}
  else{
   if(st&&st!='none'&&dark(st))e.setAttribute('stroke',CH);
   if(!st||st=='none')e.setAttribute('stroke',dark(f)?CH:f);
   if(tag=='line'||tag=='polyline'||dark(f))e.setAttribute('fill','none');else e.setAttribute('fill-opacity','0.3');
   if(parseFloat(e.getAttribute('stroke-width')||'0')<2.5)e.setAttribute('stroke-width','3');
   e.setAttribute('stroke-linecap','round');e.setAttribute('stroke-linejoin','round');}
  list.push(e);}
 var t=250;
 list.forEach(function(e){var tag=e.tagName.toLowerCase(),L=0;
  if(tag!='text'){try{L=e.getTotalLength();}catch(x){}}
  if(L>0&&e.animate){var d=Math.min(1300,Math.max(350,L*3));e.style.strokeDasharray=L+' '+L;
   e.animate([{strokeDashoffset:L},{strokeDashoffset:0}],{duration:d,delay:t,easing:'ease-in-out',fill:'backwards'});
   var fo=e.getAttribute('fill-opacity');
   if(fo)e.animate([{fillOpacity:0},{fillOpacity:+fo}],{duration:400,delay:t+d,fill:'backwards'});
   t+=d*0.8;}
  else if(e.animate){e.animate([{opacity:0},{opacity:1}],{duration:500,delay:t,fill:'backwards'});t+=tag=='text'?500:300;}});
}
function erase(){document.getElementById('b').className='gone';var r=document.createElement('div');r.id='er';r.textContent='🧽';document.body.appendChild(r);}
prep();
</script></body></html>"""

private fun escHtml(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

/** One step on the blackboard: the drawing (recoloured to chalk and drawn stroke by stroke), or the sentence written in chalk. */
internal fun boardHtml(svg: String?, text: String): String =
    BOARD_HEAD + (if (svg.isNullOrBlank()) "<div id=\"w\">" + escHtml(text) + "</div>" else svg) + BOARD_TAIL

/** WebView for the board: our own script runs, but it cannot load or open anything. The drawings are cleaned on the server too. */
private fun MainActivity.chalkBoard(): WebView = WebView(this).apply {
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    layoutParams = LinearLayout.LayoutParams(-1, ui.dp(250))
    @Suppress("SetJavaScriptEnabled") settings.javaScriptEnabled = true
    settings.blockNetworkLoads = true
    settings.allowFileAccess = false
    settings.allowContentAccess = false
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean = true
    }
}

/** Says it aloud unless the kid muted the teacher. The voice engine may still be starting: try once more a moment later. */
private fun MainActivity.speakSoon(text: String, anchor: View) {
    if (TutorState.muted) return
    if (!speaker.speak(text)) anchor.postDelayed({ if (!TutorState.muted) speaker.speak(text) }, 900)
}

// ---------------- voice answers ----------------
/** Asks the phone's speech recogniser for Persian text. Not every phone has one; then the kid just types. */
fun MainActivity.listen(onText: (String) -> Unit) {
    val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR")
        .putExtra(RecognizerIntent.EXTRA_PROMPT, "بگو؛ گوش می‌دهم 🎤")
    TutorState.onSpeech = onText
    prefs.graceUntil = System.currentTimeMillis() + 60_000          // let the recogniser open
    try { startActivityForResult(i, 11) }
    catch (e: Exception) { TutorState.onSpeech = null; prefs.graceUntil = 0; toast("این گوشی تشخیص صدا ندارد؛ لطفاً بنویس ✍️") }
}

internal fun MainActivity.onSpeechResult(res: Int, data: Intent?) {
    prefs.graceUntil = 0
    val cb = TutorState.onSpeech
    TutorState.onSpeech = null
    val t = if (res == Activity.RESULT_OK) data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull() else null
    if (t.isNullOrBlank()) { if (res == Activity.RESULT_OK) toast("صدایت را نشنیدم؛ دوباره بگو یا بنویس"); return }
    cb?.invoke(t)
}

/**
 * A text box with a 🎤 button. What is typed or said is kept under [key], so it survives the redraw
 * that happens when the kid comes back from the speech screen.
 */
internal fun MainActivity.micField(key: String, hint: String, lines: Int = 1): Pair<LinearLayout, EditText> {
    val f = field(hint, value = TutorState.drafts[key]?.firstOrNull() ?: "")
    if (lines > 1) f.minLines = lines
    f.addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { TutorState.drafts[key] = listOf(s?.toString() ?: "") }
    })
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    box.addView(f)
    box.addView(ui.pill("🎤 با صدا بگو", Pal.ORANGE) {
        listen { said ->
            val old = TutorState.drafts[key]?.firstOrNull().orEmpty().trim()
            val now = if (old.isEmpty()) said else "$old $said"
            TutorState.drafts[key] = listOf(now)
            f.setText(now)
        }
    })
    return Pair(box, f)
}

// ---------------- the board ----------------
fun MainActivity.tutorBoard(kid: Kid, subject: String, r: JSONObject, text: String) = safely("tutorBoard") { tutorBoardUnsafe(kid, subject, r, text) }

private fun MainActivity.tutorBoardUnsafe(kid: Kid, subject: String, r: JSONObject, text: String) {
    keepScreen { tutorBoard(kid, subject, r, text) }
    clearTutor()
    tutorBack(kid)
    val steps = r.getJSONArray("steps")
    val exId = r.getString("exercise_id")
    val n = steps.length()
    val prog = TutorState.boards.getOrPut(exId) { BoardProgress() }
    prog.idx = prog.idx.coerceIn(0, maxOf(0, n - 1))
    add(ui.text("🖍 ${r.optString("title")}", 22f, Pal.INK, true))

    val web = chalkBoard()
    val counter = ui.text("", 14f, Pal.MUTED)
    val say = ui.text("", 19f, Pal.INK, true)
    val frame = ui.card()
    frame.addView(web); frame.addView(counter); frame.addView(say)
    add(frame)

    val voice = ui.row()
    lateinit var muteBtn: android.widget.TextView
    voice.addView(ui.pill("🔊 دوباره بگو", Pal.ORANGE) { TutorState.muted = false; muteBtn.text = "🔈 صدا روشن"; speaker.speak(say.text.toString()) }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
    muteBtn = ui.pill(if (TutorState.muted) "🔇 صدا خاموش" else "🔈 صدا روشن", Pal.MUTED) {
        TutorState.muted = !TutorState.muted
        if (TutorState.muted) speaker.stop()
        muteBtn.text = if (TutorState.muted) "🔇 صدا خاموش" else "🔈 صدا روشن"
    }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) } }
    voice.addView(muteBtn)
    add(voice)

    val qCard = ui.card()
    add(qCard)
    val nav = ui.row()
    add(nav)
    val end = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    add(end)
    add(ui.pill("🤔 از اول ساده‌تر توضیح بده", Pal.RED) { tutorExplainCall(kid, subject, text.ifEmpty { r.optString("title") }, true) })

    fun hasAsk(i: Int): Boolean { val s = steps.getJSONObject(i); return !s.isNull("ask") && s.optString("ask").isNotEmpty() }
    fun open(i: Int) = !hasAsk(i) || i in prog.passed || (prog.tries[i] ?: 0) >= 3

    fun show(erase: Boolean) {
        val i = prog.idx
        val s = steps.getJSONObject(i)
        val alt = prog.again[i]
        val sayText = alt?.first ?: s.optString("say")
        val svg = if (alt != null) alt.second else if (s.isNull("board")) null else s.optString("board")
        val html = boardHtml(svg, sayText)
        if (erase) { web.evaluateJavascript("erase()", null); web.postDelayed({ web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null) }, 480) }
        else web.loadDataWithBaseURL("about:blank", html, "text/html", "utf-8", null)
        counter.text = "قدم ${fa(i + 1)} از ${fa(n)}   " + (0 until n).joinToString(" ") { if (it < i || (it == i && open(it))) "●" else "○" }
        say.text = (if (alt != null) "🔄 از یک راه دیگر: " else "") + sayText
        speakSoon(sayText, web)

        qCard.removeAllViews()
        if (hasAsk(i)) {
            qCard.visibility = View.VISIBLE
            qCard.addView(ui.text("❓ ${s.optString("ask")}", 17f, Pal.PURPLE, true))
            prog.notes[i]?.let { qCard.addView(ui.text(it, 15f, if (i in prog.passed) Pal.GREEN else Pal.INK)) }
            if (i in prog.passed) qCard.addView(ui.text("✅ این قدم را فهمیدی", 15f, Pal.GREEN, true))
            else if (!open(i)) {
                val (box2, f) = micField("board:$exId:$i", "جوابت را بنویس یا بگو")
                qCard.addView(box2)
                qCard.addView(ui.pill("📤 جواب بده", Pal.GREEN) {
                    val a = f.text.toString().trim()
                    if (a.isEmpty()) return@pill toast("اول جوابت را بنویس یا بگو 🙂")
                    tutorCall(kid, JSONObject().put("action", "step_answer").put("subject", subject).put("exercise_id", exId).put("step", i).put("answer", a)) { code, res ->
                        if (code != 200 || res == null) return@tutorCall toast("معلم الان جواب نداد؛ دوباره «جواب بده» را بزن 🔄")
                        prog.tries[i] = res.optInt("tries", (prog.tries[i] ?: 0) + 1)
                        val fb = res.optString("feedback")
                        if (res.optBoolean("correct")) {
                            prog.passed.add(i)
                            val pts = res.optInt("points")
                            prog.notes[i] = "🎉 $fb" + if (pts > 0) "  (+${fa(pts)} 🪙)" else ""
                            TutorState.drafts.remove("board:$exId:$i")
                        } else {
                            val sb = StringBuilder("🤔 ").append(fb)
                            if (res.has("correct_answer")) sb.append("\nجواب درست: ").append(res.optString("correct_answer"))
                            if (res.optBoolean("done")) sb.append("\nاشکالی ندارد؛ برو قدم بعد. آخر درس دوباره می‌پرسم.")
                            prog.notes[i] = sb.toString()
                            res.optJSONObject("reexplain")?.let { re -> prog.again[i] = Pair(re.optString("say"), if (re.isNull("board")) null else re.optString("board")) }
                        }
                        show(false)
                    }
                })
            }
        } else qCard.visibility = View.GONE

        nav.removeAllViews()
        nav.addView(ui.pill("⏮ قبلی", Pal.BLUE) { if (prog.idx > 0) { prog.idx--; show(true) } }
            .apply { alpha = if (i > 0) 1f else 0.4f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
        nav.addView(ui.pill("⏭ بعدی", Pal.BLUE) {
            if (!open(prog.idx)) toast("اول جواب سوال این قدم را بده 🙂")
            else if (prog.idx < n - 1) { prog.idx++; show(true) }
        }.apply { alpha = if (i < n - 1 && open(i)) 1f else 0.4f; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) } })

        end.removeAllViews()
        if (i == n - 1 && open(i)) {
            end.addView(ui.card().apply {
                addView(ui.text("👏 درس تمام شد! حالا ببینیم واقعاً جا افتاد یا نه:", 16f, Pal.INK, true))
                addView(ui.pill("🧑‍🏫 حالا تو به من یاد بده", Pal.PURPLE) { tutorTeachBack(kid, subject, r) })
                addView(ui.pill("🚪 آزمون خروج (۳ سوال)", Pal.GREEN) { tutorExitTicket(kid, subject, r) })
            })
        }
    }
    show(false)
}

// ---------------- "now you teach me" ----------------
fun MainActivity.tutorTeachBack(kid: Kid, subject: String, r: JSONObject) = safely("tutorTeachBack") { tutorTeachBackUnsafe(kid, subject, r) }

private fun MainActivity.tutorTeachBackUnsafe(kid: Kid, subject: String, r: JSONObject) {
    keepScreen { tutorTeachBack(kid, subject, r) }
    clearTutor()
    tutorBack(kid)
    val exId = r.getString("exercise_id")
    add(ui.text("🧑‍🏫 حالا تو معلم باش!", 24f, Pal.INK, true))
    add(ui.text("فرض کن دوستت «${r.optString("title")}» را بلد نیست. با زبان خودت برایش توضیح بده؛ لازم نیست جمله‌های من را تکرار کنی. مثال هم بزنی بهتر است.", 15f, Pal.MUTED))
    val (box2, f) = micField("teach:$exId", "مثلاً: ببین، این یعنی …", lines = 5)
    add(ui.card().apply { addView(box2) })
    val out = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    add(ui.pill("📤 بفرست", Pal.GREEN) {
        val t = f.text.toString().trim()
        if (t.length < 5) return@pill toast("کمی بیشتر توضیح بده 🙂")
        out.removeAllViews(); out.addView(ui.text("⏳ معلم دارد گوش می‌دهد…", 15f, Pal.MUTED))
        tutorCall(kid, JSONObject().put("action", "teach_back").put("subject", subject).put("exercise_id", exId).put("text", t), always = true) { code, res ->
            out.removeAllViews()
            if (code == 429) { out.addView(ui.text("سه بار امتحان کردی؛ برو سراغ آزمون خروج 👇", 15f, Pal.INK)); return@tutorCall }
            if (code != 200 || res == null) { toast("ارسال نشد، ولی نوشته‌ات ذخیره است. دوباره بفرست 🔄"); return@tutorCall }
            val score = res.optInt("score")
            val c = ui.card()
            c.addView(ui.text(if (res.optBoolean("passed")) "🌟 عالی یاد دادی! ${fa(score)} از ۱۰۰" else "💡 ${fa(score)} از ۱۰۰؛ چند نکته جا ماند", 19f, if (res.optBoolean("passed")) Pal.GREEN else Pal.ORANGE, true))
            c.addView(BarView(this).apply {
                fraction = score / 100f; barColor = Pal.GREEN; barColor2 = Pal.BLUE
                layoutParams = LinearLayout.LayoutParams(-1, ui.dp(10)).apply { topMargin = ui.dp(6) }
            })
            if (res.optInt("points") > 0) c.addView(ui.chip("+${fa(res.optInt("points"))} امتیاز 🪙", Pal.YELLOW, Pal.INK))
            val cov = res.optJSONArray("covered") ?: JSONArray()
            for (k in 0 until cov.length()) c.addView(ui.text("✅ ${cov.optString(k)}", 15f, Pal.INK))
            val mis = res.optJSONArray("missing") ?: JSONArray()
            for (k in 0 until mis.length()) c.addView(ui.text("⚠️ ${mis.optString(k)}", 15f, Pal.RED))
            if (res.optString("feedback").isNotEmpty()) c.addView(ui.text(res.optString("feedback"), 15f, Pal.MUTED))
            out.addView(c)
            if (res.optBoolean("passed")) TutorState.drafts.remove("teach:$exId")
            speakSoon(res.optString("feedback"), out)
        }
    })
    add(out)
    add(ui.pill("🚪 برو آزمون خروج", Pal.PURPLE) { tutorExitTicket(kid, subject, r) })
    add(ui.pill("🖍 برگرد پای تخته", Pal.MUTED) { tutorBoard(kid, subject, r, "") })
}

// ---------------- exit quiz and review ----------------
fun MainActivity.tutorExitTicket(kid: Kid, subject: String, r: JSONObject) {
    keepScreen { tutorExitTicket(kid, subject, r) }
    clearTutor()
    tutorBack(kid)
    add(ui.text("🚪 آزمون خروج", 22f, Pal.INK, true))
    add(ui.text("⏳ سه سوال دارد آماده می‌شود…", 15f, Pal.MUTED))
    tutorCall(kid, JSONObject().put("action", "exit_ticket").put("subject", subject).put("exercise_id", r.optString("exercise_id"))) { code, res ->
        if (code != 200 || res == null) return@tutorCall tutorError(kid, subject, code)
        tutorPracticeScreen(kid, subject, null, res, "🚪 آزمون خروج")
    }
}

fun MainActivity.tutorReview(kid: Kid, subject: String, topic: String = "") {
    keepScreen { tutorReview(kid, subject, topic) }
    clearTutor()
    tutorBack(kid)
    add(ui.text("🔁 مرور", 22f, Pal.INK, true))
    add(ui.text("⏳ دارم سوال‌های مرور را آماده می‌کنم…", 15f, Pal.MUTED))
    tutorCall(kid, JSONObject().put("action", "review").put("subject", subject)) { code, res ->
        if (code != 200 || res == null) return@tutorCall tutorError(kid, subject, code)
        if (res.optBoolean("none")) {
            clearTutor(); tutorBack(kid)
            add(ui.text("🔁 مرور", 22f, Pal.INK, true))
            add(ui.card().apply { addView(ui.text("امروز چیزی برای مرور نداری 🎉 هر مفهوم سر وقتش (۳ روز، یک هفته، سه هفته بعد) خودش اینجا می‌آید.", 16f)) })
            if (topic.isNotEmpty()) add(ui.pill("✏️ به‌جایش تمرین «$topic»", Pal.PURPLE) { tutorPractice(kid, subject, topic) })
            return@tutorCall
        }
        tutorPracticeScreen(kid, subject, null, res, "🔁 مرور")
    }
}

// ---------------- what was learned ----------------
private val STATUS_LABEL = mapOf("solid" to "⭐ تثبیت شد", "learned" to "✅ یاد گرفتم", "practicing" to "🔄 در حال تمرین", "new" to "🌱 تازه")

/** "today", "tomorrow", "3 days later"… for a review date. */
internal fun whenText(date: String?): String = try {
    if (date.isNullOrEmpty() || date == "null") "" else {
        val d = java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.parse(Sync.today()), java.time.LocalDate.parse(date))
        when { d <= 0 -> "امروز"; d == 1L -> "فردا"; d < 7 -> "${fa(d)} روز دیگر"; d < 30 -> "${fa(d / 7)} هفته‌ی دیگر"; else -> "${fa(d / 30)} ماه دیگر" }
    }
} catch (e: Exception) { "" }

private fun stageDots(stage: Int) = (0 until 4).joinToString("") { if (it < stage) "●" else "○" }

/** Under a graded check: what happened to each concept (moved up, back to practice, or "I will ask again"). */
internal fun MainActivity.conceptProgress(arr: JSONArray?) {
    if (arr == null || arr.length() == 0) return
    val c = ui.card()
    c.addView(ui.text("🧠 یادگیری‌ات", 17f, Pal.INK, true))
    for (i in 0 until arr.length()) {
        val o = arr.optJSONObject(i) ?: continue
        val name = o.optString("name")
        val next = whenText(o.optString("next_review"))
        val line = when {
            o.optInt("moved") > 0 && o.optString("status") == "solid" -> "⭐ «$name» دیگر کاملاً تثبیت شد!"
            o.optInt("moved") > 0 && o.optString("status") == "learned" -> "✅ «$name» را یاد گرفتی! مرور بعدی: $next"
            o.optInt("moved") > 0 -> "⬆️ «$name» یک پله محکم‌تر شد. مرور بعدی: $next"
            o.optInt("moved") < 0 -> "🔁 «$name» دوباره تمرین می‌خواهد؛ $next دوباره می‌پرسم"
            else -> "📌 «$name»: $next دوباره می‌پرسم تا مطمئن شوم یادت مانده"
        }
        c.addView(ui.text(line, 15f, Pal.INK))
    }
    add(c)
}

fun MainActivity.tutorConcepts(kid: Kid, subject: String) = safely("tutorConcepts") { tutorConceptsUnsafe(kid, subject) }

private fun MainActivity.tutorConceptsUnsafe(kid: Kid, subject: String) {
    keepScreen { tutorConcepts(kid, subject) }
    clearTutor()
    tutorBack(kid)
    add(ui.text("🧠 چیزهایی که یاد گرفتم · ${subjectLabel(subject)}", 22f, Pal.INK, true))
    add(ui.text("هر چیزی که یاد می‌گیری بعد از آزمون خروج، ۳ روز بعد، یک هفته بعد و سه هفته بعد دوباره پرسیده می‌شود. اگر هر بار یادت بود «✅ یاد گرفتم» و بعد «⭐ تثبیت شد» می‌شود.", 14f, Pal.MUTED))
    tutorCall(kid, JSONObject().put("action", "concepts").put("subject", subject)) { code, r ->
        if (code != 200 || r == null) return@tutorCall tutorError(kid, subject, code)
        if (r.optInt("due") > 0) add(ui.pill("🔁 مرور امروز (${fa(r.optInt("due"))})", Pal.ORANGE) { tutorReview(kid, subject) })
        val list = r.getJSONArray("concepts")
        if (list.length() == 0) { add(ui.card().apply { addView(ui.text("هنوز چیزی ثبت نشده. یک درس پای تخته بگیر و آزمون خروجش را بده.", 15f, Pal.MUTED)) }); return@tutorCall }
        for (group in listOf("solid", "learned", "practicing", "new")) {
            val items = (0 until list.length()).map { list.getJSONObject(it) }.filter { it.optString("status") == group }
            if (items.isEmpty()) continue
            add(ui.title("${STATUS_LABEL[group]} (${fa(items.size)})"))
            items.forEach { o ->
                add(ui.card().apply {
                    addView(ui.text("${o.optString("name")}   ${stageDots(o.optInt("stage"))}", 16f, Pal.INK, true))
                    val bits = ArrayList<String>()
                    if (o.optBoolean("due")) bits.add("⏰ امروز وقت مرورش است")
                    else whenText(o.optString("next_review")).takeIf { it.isNotEmpty() }?.let { bits.add("مرور بعدی: $it") }
                    if (o.optInt("attempts") > 0) bits.add("بار اول درست: ${fa(o.optInt("first_try_correct"))} از ${fa(o.optInt("attempts"))}")
                    if (o.optInt("hints") + o.optInt("simpler") > 0) bits.add("کمک گرفتی: ${fa(o.optInt("hints") + o.optInt("simpler"))} بار")
                    if (bits.isNotEmpty()) addView(ui.text(bits.joinToString(" · "), 13f, Pal.MUTED))
                })
            }
        }
    }
}

// ---------------- photo of a worked solution ----------------
/** Called after the kid photographed their solution on paper for an open practice / exit quiz / review. */
fun MainActivity.tutorGotSolution(code: Int, r: JSONObject?, exId: String) = safely("tutorGotSolution") {
    val kid = Sync.loadState(prefs)?.kids?.firstOrNull { it.id == TutorState.kidId } ?: return@safely toast("کودک پیدا نشد")
    when {
        code == 200 && r != null -> { TutorState.drafts.remove(exId); goTo { tutorChecked(kid, TutorState.subject, r, TutorState.solutionLesson) } }
        code == 409 -> toast("این تمرین قبلاً تحویل داده شده")
        code == 429 -> toast("امروز دیگه بسه 🌙")
        else -> toast("عکس فرستاده نشد؛ جواب‌هایت ذخیره است. دوباره امتحان کن 🔄")
    }
}
