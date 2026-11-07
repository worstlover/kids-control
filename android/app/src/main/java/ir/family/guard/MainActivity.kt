package ir.family.guard

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.media.ExifInterface
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.Settings
import android.text.InputType
import android.util.Base64
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.ByteArrayOutputStream
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var box: LinearLayout
    private var pendingLog: String? = null
    private var pendingKind = "task"          // task | book | dict
    private var dictSession: Pair<DictItem, Int>? = null
    private var player: AudioTrack? = null
    private val audioCache = HashMap<String, ByteArray>()
    private var sampleRate = 24000
    private var photoUri: Uri? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this)
        val sv = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(32)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        sv.addView(box); sv.setBackgroundColor(Color.rgb(246, 247, 251)); setContentView(sv)
    }

    override fun onResume() { super.onResume(); render(); refresh() }

    private fun refresh() {
        if (!prefs.configured) return
        thread { Sync.syncNow(applicationContext); runOnUiThread { if (!isFinishing) render() } }
    }

    // ---------- tiny view helpers ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun text(s: String, size: Float = 15f, bold: Boolean = false, color: Int = Color.rgb(29, 35, 48)) =
        TextView(this).apply { this.text = s; textSize = size; setTextColor(color); if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD); setPadding(0, dp(4), 0, dp(4)); textAlignment = View.TEXT_ALIGNMENT_VIEW_START }
    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }
    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(Color.WHITE)
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }
    private fun add(v: View) = box.addView(v)
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    private fun input(hint: String, pin: Boolean = false, value: String = "") = EditText(this).apply {
        this.hint = hint; setText(value); layoutDirection = View.LAYOUT_DIRECTION_LTR
        if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    private fun askPin(title: String, ok: (String) -> Unit) {
        val e = input("رمز", true)
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("تایید") { _, _ -> ok(e.text.toString()) }.setNegativeButton("بستن", null).show()
    }

    // ---------- screens ----------
    private fun render() {
        box.removeAllViews()
        when {
            !prefs.configured -> setup()
            else -> {
                val st = Sync.loadState(prefs)
                when {
                    st == null -> { add(text("در حال دریافت اطلاعات…", 16f)); add(button("تلاش دوباره") { refresh() }); parentButton() }
                    st.kids.none { it.id == prefs.activeKid } -> { dictSession = null; pickKid(st) }
                    dictSession != null -> dictationScreen()
                    else -> home(st, st.kids.first { it.id == prefs.activeKid })
                }
            }
        }
    }

    private fun setup() {
        add(text("اتصال به پنل والدین", 20f, true))
        val url = input("آدرس Supabase", value = prefs.sbUrl); val key = input("کلید anon", value = prefs.sbKey)
        val code = input("کد اتصال گوشی"); val pin = input("رمز والدین (۴ تا ۸ رقم)", true)
        listOf(url, key, code, pin).forEach { add(it) }
        add(button("اتصال") {
            if (!Regex("\\d{4,8}").matches(pin.text.toString())) return@button toast("رمز والدین باید ۴ تا ۸ رقم باشد")
            prefs.sbUrl = url.text.toString(); prefs.sbKey = key.text.toString(); prefs.token = code.text.toString().trim()
            prefs.parentPinHash = sha256(pin.text.toString())
            thread {
                val r = Sync.syncNow(applicationContext, true)
                runOnUiThread {
                    if (r == 200) { toast("وصل شد ✓"); render() }
                    else { prefs.token = ""; toast(if (r == 401) "کد اتصال اشتباه است" else "ارتباط با سرور برقرار نشد") ; render() }
                }
            }
        })
    }

    private fun pickKid(st: State) {
        add(text("کی هستی؟", 22f, true))
        if (st.kids.isEmpty()) add(text("هنوز فرزندی در پنل والدین ثبت نشده."))
        st.kids.forEach { k ->
            add(button(k.name) {
                if (k.pinHash == null) { prefs.activeKid = k.id; render() }
                else askPin("رمز ${k.name}") { p -> if (sha256(p) == k.pinHash) { prefs.activeKid = k.id; render() } else toast("رمز اشتباه است") }
            })
        }
        parentButton()
    }

    private fun home(st: State, kid: Kid) {
        add(text("سلام ${kid.name} 👋", 22f, true))
        val c = Env.buildCtx(this, prefs, st)
        val remain = Policy.remainingMinutes(kid, c)
        val head = card()
        head.addView(text("امتیاز تو: ${kid.balance}", 20f, true))
        head.addView(text("وقت گوشی باقی‌مانده امروز: $remain دقیقه"))
        if (prefs.lockAll) head.addView(text("⛔ گوشی فعلاً قفل است", 16f, true, Color.rgb(220, 38, 38)))
        add(head)
        if (!a11yEnabled()) { val w = card(); w.addView(text("⚠ محافظت فعال نیست؛ از بخش والدین فعالش کنید.", 14f, false, Color.rgb(180, 83, 9))); add(w) }

        add(text("تکالیف امروز", 18f, true))
        if (kid.tasks.isEmpty()) add(text("برای امروز کاری نداری 🎉"))
        kid.tasks.forEach { t ->
            val row = card()
            row.addView(text("${t.title}  (${t.points} امتیاز)", 16f, true))
            val label = mapOf("pending" to "انجام نشده", "submitted" to "منتظر تایید والدین", "graded" to "نمره گرفت؛ منتظر تایید", "approved" to "تایید شد ✓", "rejected" to "دوباره انجام بده")
            row.addView(text(label[t.status] ?: t.status))
            if (t.aiScore != null) row.addView(text("نمره: ${t.aiScore}   ${t.aiFeedback ?: ""}"))
            if (t.status == "pending" || t.status == "rejected") {
                row.addView(if (t.needsPhoto) button("📷 عکس تکلیف") { takePhoto("task", t.logId) } else button("انجام دادم") { markDone(t.logId) })
            }
            add(row)
        }

        add(text("دیکته", 18f, true))
        add(button("📖 دیکته‌ی جدید از روی کتاب") { takePhoto("book", kid.id) })
        kid.dictations.forEach { d ->
            val row = card()
            row.addView(text("${d.title}  (${d.points} امتیاز)", 16f, true))
            when (d.status) {
                "review" -> row.addView(text("منتظر تایید والدین"))
                "ready" -> row.addView(button("▶ شروع دیکته (${d.phraseCount} جمله)") { dictSession = Pair(d, 0); render() })
                "graded" -> row.addView(text(if (d.aiScore != null) "نمره: ${d.aiScore} — منتظر تایید والدین" else "منتظر نمره‌ی والدین"))
                else -> row.addView(text("نمره: ${d.aiScore ?: "-"}  ✓  امتیاز: ${d.pointsAwarded ?: 0}"))
            }
            if (d.aiFeedback != null && d.status != "ready") row.addView(text(d.aiFeedback))
            add(row)
        }

        add(text("برنامه‌های مجاز", 18f, true))
        val allowed = kid.rules.filter { !it.locked }
        if (allowed.isEmpty()) add(text("هنوز برنامه‌ای برایت مجاز نشده."))
        allowed.forEach { r ->
            val d = Policy.decide(r.pkg, c)
            val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(r.pkg, 0)).toString() } catch (e: Exception) { null } ?: return@forEach
            add(button(if (d.allowed) label else "🔒 $label — ${d.reason.message}") {
                if (d.allowed) packageManager.getLaunchIntentForPackage(r.pkg)?.let { startActivity(it) } else toast(d.reason.message)
            })
        }

        if (kid.wishlist.isNotEmpty()) add(text("آرزوهای من", 18f, true))
        kid.wishlist.forEach { w ->
            val row = card()
            row.addView(text(w.title, 16f, true))
            val need = w.pointsNeeded ?: 0L; val left = w.pointsLeft ?: 0L
            if (need > 0) {
                row.addView(ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = need.toInt(); progress = (need - left).coerceIn(0, need).toInt() })
                row.addView(text(if (left <= 0) "رسیدی! 🎉" else "$left امتیاز دیگر مانده"))
            } else row.addView(text("قیمت هنوز مشخص نشده"))
            add(row)
        }

        add(button("خروج (عوض کردن فرزند)") { prefs.activeKid = null; render() })
        parentButton()
    }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/") && s.contains("GuardService")
    }

    // ---------- parent mode ----------
    private fun parentButton() {
        val b = button("🔑 والدین") {
            if (prefs.parentPinHash.isEmpty()) { toast("رمز والدین ثبت نشده"); return@button }
            askPin("رمز والدین") { p -> if (sha256(p) == prefs.parentPinHash) { prefs.parentUntil = System.currentTimeMillis() + 10 * 60_000; parentMenu() } else toast("رمز اشتباه است") }
        }
        add(b)
    }

    private fun parentMenu() {
        val items = arrayOf(
            "باز کردن تنظیمات گوشی (۱۰ دقیقه)", "فعال‌سازی محافظت (دسترسی‌پذیری)", "فعال‌سازی مدیر دستگاه (ضد حذف)",
            if (prefs.lockAll) "باز کردن قفل گوشی" else "قفل کردن همه چیز", "اضافه کردن ۱۵ دقیقه", "تنظیم این برنامه به‌عنوان صفحه‌ی اصلی",
            "قطع اتصال و پاک کردن کد", "بستن",
        )
        AlertDialog.Builder(this).setTitle("حالت والدین").setItems(items) { _, i ->
            when (i) {
                0 -> startActivity(Intent(Settings.ACTION_SETTINGS))
                1 -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                2 -> startActivity(Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, ComponentName(this, AdminReceiver::class.java)))
                3 -> { prefs.lockAll = !prefs.lockAll; render() }
                4 -> { prefs.addExtra(Sync.today(), 15); toast("۱۵ دقیقه اضافه شد"); render() }
                5 -> startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
                6 -> { prefs.token = ""; prefs.activeKid = null; prefs.stateJson = ""; render() }
            }
        }.show()
    }

    // ---------- homework ----------
    private fun markDone(logId: String) {
        thread {
            val (code, res) = Sync.submit(prefs, logId, null)
            runOnUiThread { toast(if (code == 200 && res != null) "ثبت شد؛ منتظر تایید والدین" else "ثبت نشد، دوباره تلاش کن"); refresh() }
        }
    }

    private fun takePhoto(kind: String, id: String) {
        val v = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "hw_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return toast("دوربین در دسترس نیست")
        pendingLog = id; pendingKind = kind; photoUri = uri
        prefs.graceUntil = System.currentTimeMillis() + 3 * 60_000   // let the camera app open
        try { startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), 7) }
        catch (e: Exception) { contentResolver.delete(uri, null, null); toast("برنامه‌ی دوربین پیدا نشد") }
    }

    @Deprecated("simple camera flow")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req != 7) return
        prefs.graceUntil = 0
        val uri = photoUri; val id = pendingLog; val kind = pendingKind; photoUri = null; pendingLog = null
        if (uri == null || id == null) return
        if (res != RESULT_OK) { contentResolver.delete(uri, null, null); return }
        toast(if (kind == "book") "در حال خواندن صفحه… چند لحظه صبر کن" else "در حال نمره‌دادن… چند لحظه صبر کن")
        thread {
            val b64 = try { loadScaledB64(uri) } catch (e: Exception) { null }
            try { contentResolver.delete(uri, null, null) } catch (e: Exception) {}
            val (code, r) = when {
                b64 == null -> Pair(-2, null)
                kind == "book" -> Sync.dictCreate(prefs, id, b64)
                kind == "dict" -> Sync.submitDictation(prefs, id, b64)
                else -> Sync.submit(prefs, id, b64)
            }
            runOnUiThread {
                if (kind == "book") showBookResult(code, r) else { if (kind == "dict" && code == 200 && r?.optString("status") != "retake") dictSession = null; showResult(code, r) }
                refresh()
            }
        }
    }

    private fun showBookResult(code: Int, r: org.json.JSONObject?) {
        val msg = when {
            code == -2 -> "عکس خوانده نشد، دوباره بگیر"
            code == 429 -> "امروز به اندازه‌ی کافی دیکته‌ی جدید گرفتی؛ فردا دوباره."
            code != 200 || r == null -> "ارسال نشد، اینترنت را چک کن و دوباره امتحان کن"
            r.optString("status") == "retake" -> "صفحه واضح نبود 📷\n${r.optString("feedback")}"
            r.optString("status") == "review" -> "متن صفحه خوانده شد (${r.optInt("phrase_count")} جمله).\nبعد از اینکه پدر و مادر تایید کنند، دیکته شروع می‌شود."
            else -> "دیکته آماده است! (${r.optInt("phrase_count")} جمله) از فهرست دیکته‌ها شروعش کن."
        }
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("باشه", null).show()
    }

    // ---------- dictation: listen to a phrase, write it on paper, photograph the page ----------
    private fun dictationScreen() {
        val (d, idx) = dictSession!!
        val last = idx >= d.phraseCount - 1
        add(text("دیکته: ${d.title}", 22f, true))
        val c = card()
        c.addView(text("جمله‌ی ${idx + 1} از ${d.phraseCount}", 18f, true))
        c.addView(text("گوش بده و روی کاغذ بنویس. هر وقت خواستی دوباره بشنو."))
        add(c)
        add(button("🔊 بشنو") { playPhrase(d, idx) })
        if (!last) add(button("⏭ جمله‌ی بعد") { dictSession = Pair(d, idx + 1); render() })
        else add(button("✅ تمام شد؛ عکس دیکته‌ام را بفرست") { stopPlayer(); takePhoto("dict", d.id) })
        if (idx > 0) add(button("⏮ جمله‌ی قبل") { dictSession = Pair(d, idx - 1); render() })
        add(button("بازگشت") { stopPlayer(); dictSession = null; render() })
    }

    private fun stopPlayer() { try { player?.stop(); player?.release() } catch (e: Exception) {}; player = null }

    private fun playPhrase(d: DictItem, idx: Int) {
        stopPlayer()
        thread {
            val key = "${d.id}:$idx"
            var pcm = audioCache[key]
            if (pcm == null) {
                val (code, r) = Sync.dictAudio(prefs, d.id, idx)
                if (code != 200 || r == null) {
                    val msg = when (code) { 403 -> "هنوز والدین متن را تایید نکرده‌اند"; 429 -> "سهم دیکته‌ی امروزت تمام شد"; 409 -> "این دیکته تمام شده"; else -> "صدا در دسترس نیست، دوباره امتحان کن" }
                    runOnUiThread { toast(msg) }; return@thread
                }
                sampleRate = r.optInt("sample_rate", 24000)
                pcm = Base64.decode(r.getString("audio_b64"), Base64.DEFAULT)
                audioCache[key] = pcm
            }
            val bytes: ByteArray = pcm ?: return@thread
            runOnUiThread {
                try {
                    val t = AudioTrack.Builder()
                        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                        .setAudioFormat(AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(sampleRate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
                        .setBufferSizeInBytes(bytes.size).setTransferMode(AudioTrack.MODE_STATIC).build()
                    t.write(bytes, 0, bytes.size); t.play(); player = t
                } catch (e: Exception) { toast("پخش صدا ممکن نشد") }
            }
        }
    }

    override fun onPause() { super.onPause(); stopPlayer() }

    private fun showResult(code: Int, r: org.json.JSONObject?) {
        val msg = when {
            code == -2 -> "عکس خوانده نشد، دوباره بگیر"
            code == 429 -> "سهم دیکته‌ی امروزت تمام شد"
            code != 200 || r == null -> if (code == 409) "این تکلیف قبلاً تایید شده" else "ارسال نشد، اینترنت را چک کن و دوباره امتحان کن"
            r.optString("status") == "retake" -> "عکس واضح نیست 📷\n${r.optString("feedback")}"
            r.optString("status") == "submitted" -> "عکس ثبت شد؛ والدین نمره می‌دهند."
            else -> "نمره: ${r.optDouble("score")} از ۲۰\n${r.optString("feedback")}\n" + if (r.optString("status") == "approved") "امتیاز گرفتی: ${r.optInt("points")} 🎉" else "منتظر تایید والدین"
        }
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("باشه", null).show()
    }

    private fun loadScaledB64(uri: Uri): String {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
        var s = 1
        while (maxOf(o.outWidth, o.outHeight) / s > 2000) s *= 2
        var bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
        val orient = contentResolver.openInputStream(uri)!!.use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) }
        val deg = when (orient) { 6 -> 90f; 3 -> 180f; 8 -> 270f; else -> 0f }
        if (deg != 0f) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, Matrix().apply { postRotate(deg) }, true)
        var q = 80
        val out = ByteArrayOutputStream()
        do { out.reset(); bmp.compress(Bitmap.CompressFormat.JPEG, q, out); q -= 15 } while (out.size() > 4_000_000 && q > 20)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
