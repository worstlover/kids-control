package ir.family.guard

import android.app.Activity
import java.io.File
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.BitmapDrawable
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.drawable.GradientDrawable
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
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDate
import kotlin.concurrent.thread

class MainActivity : Activity() {
    internal lateinit var prefs: Prefs
    internal lateinit var ui: Ui
    internal lateinit var box: LinearLayout
    internal var tutorView: (() -> Unit)? = null
    internal var wishView: (() -> Unit)? = null
    internal var shopWeb: android.webkit.WebView? = null
    internal var kidTab = "today"              // which bottom tab the kid is on
    internal lateinit var navBar: LinearLayout  // bottom tab bar (always below the scroll area)
    internal lateinit var speaker: Speaker
    internal lateinit var scroll: ScrollView
    private var pendingId: String? = null
    private var pendingKind = "task"          // task | book | dict
    private var photoUri: Uri? = null
    private var dictSession: Pair<DictItem, Int>? = null
    private var player: AudioTrack? = null
    private val audioCache = HashMap<String, ByteArray>()
    private var sampleRate = 24000
    private var speakBtn: TextView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        prefs = Prefs(this); ui = Ui(this); speaker = Speaker(this)
        val sv = ScrollView(this)
        box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(40)); layoutDirection = View.LAYOUT_DIRECTION_RTL }
        sv.addView(box)
        scroll = sv
        navBar = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        root.addView(sv, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(navBar, LinearLayout.LayoutParams(-1, -2))
        applyBackground()
        setContentView(root)
    }

    override fun onResume() { super.onResume(); render(); refresh() }
    override fun onPause() { super.onPause(); stopPlayer(); speaker.stop(); stopMedia() }
    override fun onDestroy() { speaker.shutdown(); super.onDestroy() }

    internal fun refresh() {
        if (!prefs.configured) return
        thread { Sync.syncNow(applicationContext); runOnUiThread { if (!isFinishing) render() } }
    }

    /** Kid's own picture behind the screen, with a light veil so cards stay readable; default gradient otherwise. */
    internal fun applyBackground() {
        val grad = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.BG_TOP, Pal.BG_BOT))
        val bmp = if (prefs.bgPath.isNotEmpty()) decodeScaled(prefs.bgPath) else null
        scroll.background = if (bmp == null) grad
            else LayerDrawable(arrayOf(BitmapDrawable(resources, bmp), ColorDrawable(Color.parseColor("#B3FFFFFF"))))
    }

    internal fun pickBackground() {
        try { startActivityForResult(Intent(Intent.ACTION_GET_CONTENT).setType("image/*"), 9) }
        catch (e: Exception) { toast("برنامه‌ی گالری پیدا نشد") }
    }

    internal fun onBackgroundPicked(res: Int, data: Intent?) {
        val uri = if (res == RESULT_OK) data?.data else null
        if (uri == null) return
        thread {
            val path = saveBackground(this, uri)
            runOnUiThread {
                if (path != null) { prefs.bgPath = path; applyBackground(); render(); toast("عکس پس‌زمینه عوض شد ✓") }
                else toast("این عکس خوانده نشد")
            }
        }
    }

    internal fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
    internal fun add(v: View) = box.addView(v)
    internal fun field(hint: String, pin: Boolean = false, value: String = "") = EditText(this).apply {
        this.hint = hint; setText(value); layoutDirection = View.LAYOUT_DIRECTION_LTR; textSize = 16f
        background = ui.shape(Color.parseColor("#F3F4F8"), 16f); setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
        if (pin) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
    }
    private fun askPin(title: String, ok: (String) -> Unit) {
        val e = EditText(this).apply { hint = "رمز"; inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD; gravity = Gravity.CENTER; textSize = 24f }
        AlertDialog.Builder(this).setTitle(title).setView(e).setPositiveButton("تایید") { _, _ -> ok(e.text.toString()) }.setNegativeButton("بستن", null).show()
    }

    // ================= screens =================
    internal fun render() {
        applyBackground()
        TutorState.gen++                       // tutor replies still on their way belong to the old screen
        box.removeAllViews()
        if (!prefs.configured) return setup()
        val st = Sync.loadState(prefs)
        val active = st?.kids?.firstOrNull { it.id == prefs.activeKid }
        navBar.visibility = if (active != null && dictSession == null) View.VISIBLE else View.GONE
        if (active != null) tabBar(active)
        when {
            st == null -> {
                add(ui.text("⏳ در حال دریافت اطلاعات…", 20f, Pal.INK, true))
                add(ui.pill("🔄 تلاش دوباره", Pal.BLUE) { refresh() }); parentButton()
            }
            st.kids.none { it.id == prefs.activeKid } -> { dictSession = null; pickKid(st) }
            wishView != null -> wishView!!.invoke()
            tutorView != null -> tutorView!!.invoke()
            dictSession != null -> dictationScreen()
            else -> home(st, st.kids.first { it.id == prefs.activeKid })
        }
    }

    private fun setup() {
        add(ui.text("🔗 اتصال به پنل والدین", 24f, Pal.INK, true))
        val c = ui.card()
        val url = field("آدرس Supabase", value = prefs.sbUrl); val key = field("کلید", value = prefs.sbKey)
        val code = field("کد اتصال گوشی"); val pin = field("رمز والدین (۴ تا ۸ رقم)", true)
        listOf(url, key, code, pin).forEach { c.addView(it) }
        add(c)
        add(ui.pill("✅ اتصال", Pal.GREEN) {
            if (!Regex("\\d{4,8}").matches(pin.text.toString())) return@pill toast("رمز والدین باید ۴ تا ۸ رقم باشد")
            prefs.sbUrl = url.text.toString(); prefs.sbKey = key.text.toString(); prefs.token = code.text.toString().trim()
            prefs.parentPinHash = sha256(pin.text.toString())
            thread {
                val r = Sync.syncNow(applicationContext, true)
                runOnUiThread {
                    if (r == 200) { toast("وصل شد ✓"); render() }
                    else { prefs.token = ""; toast(if (r == 401) "کد اتصال اشتباه است" else "ارتباط با سرور برقرار نشد"); render() }
                }
            }
        })
    }

    private fun pickKid(st: State) {
        add(ui.text("کی هستی؟ 👋", 30f, Pal.INK, true).apply { setPadding(ui.dp(4), ui.dp(30), 0, ui.dp(10)) })
        if (st.kids.isEmpty()) add(ui.text("هنوز فرزندی در پنل والدین ثبت نشده."))
        st.kids.chunked(2).forEach { pair ->
            val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(12) } }
            pair.forEach { k ->
                val col = Pal.kidColor(st.kids.indexOf(k))
                val tile = LinearLayout(this).apply {
                    orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; isClickable = true; elevation = ui.dpf(4f)
                    background = ui.gradient(ui.lighter(col, 0.15f), col, 28f); setPadding(ui.dp(10), ui.dp(24), ui.dp(10), ui.dp(24))
                    layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6); marginEnd = ui.dp(6) }
                    setOnClickListener {
                        if (k.pinHash == null) { prefs.activeKid = k.id; render() }
                        else askPin("رمز ${k.name}") { p -> if (sha256(p) == k.pinHash) { prefs.activeKid = k.id; render() } else toast("رمز اشتباه است") }
                    }
                }
                tile.addView(TextView(this).apply { text = Pal.avatar(k.id); textSize = 54f; gravity = Gravity.CENTER })
                tile.addView(TextView(this).apply { text = k.name; textSize = 22f; setTextColor(Color.WHITE); setTypeface(typeface, android.graphics.Typeface.BOLD); gravity = Gravity.CENTER })
                r.addView(tile)
            }
            if (pair.size == 1) r.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) })
            add(r)
        }
        add(ui.space(20)); parentButton()
    }

    private fun home(st: State, kid: Kid) {
        val kidIdx = st.kids.indexOf(kid)
        val c = Env.buildCtx(this, prefs, st)
        val remain = Policy.remainingMinutes(kid, c)
        val total = kid.dailyLimitMin + c.extraMin

        // ---- header ----
        val hdr = ui.row().apply {
            background = ui.gradient(Pal.PURPLE, Pal.PINK, 28f); elevation = ui.dpf(4f); setPadding(ui.dp(16), ui.dp(16), ui.dp(16), ui.dp(16))
            layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) }
        }
        hdr.addView(TextView(this).apply { text = Pal.avatar(kid.id); textSize = 34f; gravity = Gravity.CENTER; background = ui.oval(Color.WHITE); layoutParams = LinearLayout.LayoutParams(ui.dp(66), ui.dp(66)) })
        val names = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(12); marginEnd = ui.dp(8) } }
        names.addView(ui.text("سلام ${kid.name}! 👋", 22f, Color.WHITE, true))
        names.addView(ui.text("امروز چه کارهای باحالی داری؟", 14f, Color.parseColor("#F5E9FF")))
        hdr.addView(names)
        hdr.addView(ui.chip("🪙 ${fa(kid.balance)}", Pal.YELLOW, Pal.INK).apply { textSize = 20f; setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8)) })
        add(hdr)
        if (prefs.lockAll) add(ui.card().apply { addView(ui.text("⛔ گوشی فعلاً قفل است", 18f, Pal.RED, true)) })
        if (!a11yEnabled()) add(ui.card().apply { addView(ui.text("⚠ محافظت فعال نیست؛ از بخش والدین فعالش کنید.", 14f, Color.parseColor("#B45309"))) })

        if (kidTab == "today") {
        if (kid.penalties.isNotEmpty()) add(ui.card().apply {
            addView(ui.text("⚠️ امروز امتیاز کم شد", 17f, Pal.RED, true))
            kid.penalties.forEach { addView(ui.text("−${fa(it.points)}  ${it.title}", 15f, Pal.INK)) }
        })

        // ---- countdown ring ----
        val ringCard = ui.card(); val rr = ui.row()
        val ring = RingView(this).apply {
            fraction = if (total > 0) remain / total.toFloat() else 0f
            ringColor = when { remain * 100 > total * 40 -> Pal.GREEN; remain * 100 > total * 15 -> Pal.ORANGE; else -> Pal.RED }
            big = fa(remain); small = "دقیقه"; layoutParams = LinearLayout.LayoutParams(ui.dp(150), ui.dp(150))
        }
        rr.addView(ring)
        val rt = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(14) } }
        rt.addView(ui.text("⏳ وقت گوشی امروز", 18f, Pal.INK, true))
        rt.addView(ui.text(if (remain <= 0) "تموم شد! فردا دوباره 🌙" else "از ${fa(total)} دقیقه، ${fa(remain)} دقیقه مونده", 15f, Pal.MUTED))
        rt.addView(ui.text("هر چی کمتر بازی کنی، امتیاز بیشتر می‌گیری 🌟", 13f, Pal.PURPLE))
        rr.addView(rt); ringCard.addView(rr); add(ringCard)

        }
        if (kidTab == "tasks") {
        // ---- tasks ----
        val done = kid.tasks.count { it.status == "approved" }
        add(ui.title("🎯 کارهای امروز"))
        if (kid.tasks.isEmpty()) add(ui.card().apply { addView(ui.text("برای امروز کاری نداری 🎉", 16f)) })
        else {
            add(ui.card().apply {
                addView(ui.text("${fa(done)} از ${fa(kid.tasks.size)} کار انجام شد", 16f, Pal.INK, true))
                addView(BarView(this@MainActivity).apply { fraction = done / kid.tasks.size.toFloat(); barColor = Pal.GREEN; barColor2 = Pal.BLUE; layoutParams = LinearLayout.LayoutParams(-1, ui.dp(12)).apply { topMargin = ui.dp(8) } })
            })
        }
        kid.tasks.forEach { t -> add(taskCard(t)) }

        // ---- dictation ----
        add(ui.title("📖 دیکته"))
        add(ui.pill("📸 دیکته‌ی جدید از روی کتاب", Pal.ORANGE) { takePhoto("book", kid.id) })
        kid.dictations.forEach { d ->
            val card = ui.card()
            val top = ui.row(); top.addView(ui.text("✏️ ${d.title}", 17f, Pal.INK, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) }); top.addView(ui.chip("⭐ ${fa(d.points)}", Pal.YELLOW, Pal.INK)); card.addView(top)
            when (d.status) {
                "review" -> card.addView(ui.text("⏳ منتظر تایید والدین", 14f, Pal.MUTED))
                "ready" -> card.addView(ui.pill("▶ شروع دیکته (${fa(d.phraseCount)} جمله)", Pal.PURPLE) { dictSession = Pair(d, 0); render() })
                "graded" -> card.addView(ui.text(if (d.aiScore != null) "نمره: ${fa(d.aiScore)} — منتظر تایید امتیاز ⏳" else "منتظر نمره‌ی والدین ⏳", 15f))
                else -> card.addView(ui.text("✅ نمره: ${d.aiScore?.let { fa(it) } ?: "-"}   امتیاز: ${fa(d.pointsAwarded ?: 0)}", 15f, Pal.GREEN, true))
            }
            if (d.aiFeedback != null && d.status != "ready") card.addView(ui.text(d.aiFeedback, 14f, Pal.MUTED))
            add(card)
        }

        }
        if (kidTab == "points") {
        // ---- wallet: where points came from, where they went, spending ----
        walletSection(st, kid)
        // ---- competition charts ----
        if (st.kids.size > 1 || kid.weekPoints.isNotEmpty()) chartCards(st, kidIdx)

        }
        if (kidTab == "apps") {
        // ---- apps ----
        add(ui.title("🎮 برنامه‌های من"))
        val allowed = kid.rules.filter { !it.locked || it.alwaysOn }
        if (allowed.isEmpty()) add(ui.card().apply { addView(ui.text("هنوز برنامه‌ای برایت باز نشده.", 15f, Pal.MUTED)) })
        else add(ui.card().apply {
            allowed.chunked(4).forEach { rowApps ->
                val r = LinearLayout(this@MainActivity).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) } }
                rowApps.forEach { rule -> appCell(rule, c)?.let { r.addView(it) } }
                repeat(4 - rowApps.size) { r.addView(View(this@MainActivity).apply { layoutParams = LinearLayout.LayoutParams(0, 1, 1f) }) }
                addView(r)
            }
        })

        }
        if (kidTab == "points") wishlistSection(st, kid)
        add(ui.space(14))
        val foot = ui.row()
        foot.addView(ui.pill("👋 عوض کردن", Pal.BLUE) { prefs.activeKid = null; render() }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6); topMargin = ui.dp(10) } })
        foot.addView(parentPill().apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6); topMargin = ui.dp(10) } })
        add(foot)
    }

    private fun taskCard(t: TaskItem): View {
        val card = ui.card(); val top = ui.row()
        val (emoji, label, col) = when (t.status) {
            "approved" -> Triple("✅", "انجام شد", Pal.GREEN)
            "submitted" -> Triple("⏳", "منتظر تایید", Pal.BLUE)
            "graded" -> Triple("⏳", "منتظر تایید امتیاز", Pal.BLUE)
            "rejected" -> Triple("🔁", "دوباره انجام بده", Pal.RED)
            else -> Triple("🕓", "هنوز نه", Pal.ORANGE)
        }
        top.addView(TextView(this).apply { text = emoji; textSize = 26f; setPadding(0, 0, ui.dp(10), 0) })
        top.addView(ui.text(t.title, 17f, Pal.INK, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        top.addView(ui.chip("⭐ ${fa(t.points)}", Pal.YELLOW, Pal.INK))
        card.addView(top)
        card.addView(ui.chip(label, col).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(6) }.also { it.layoutParams = LinearLayout.LayoutParams(-2, -2).apply { topMargin = ui.dp(6) } })
        if (t.aiScore != null) card.addView(ui.text("نمره: ${fa(t.aiScore)} از ۲۰   ${t.aiFeedback ?: ""}", 14f, Pal.MUTED))
        if (t.status == "pending" || t.status == "rejected") {
            card.addView(if (t.needsPhoto) ui.pill("📷 عکس تکلیف", Pal.PURPLE) { takePhoto("task", t.logId) } else ui.pill("🙌 انجام دادم", Pal.GREEN) { markDone(t.logId) })
        }
        return card
    }

    private fun appCell(rule: Rule, c: Policy.Ctx): View? {
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(rule.pkg, 0)).toString() } catch (e: Exception) { return null }
        val icon = try { packageManager.getApplicationIcon(rule.pkg) } catch (e: Exception) { null }
        val d = Policy.decide(rule.pkg, c)
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(0, -2, 1f); isClickable = true
            addView(ImageView(this@MainActivity).apply { setImageDrawable(icon); alpha = if (d.allowed) 1f else 0.35f; layoutParams = LinearLayout.LayoutParams(ui.dp(54), ui.dp(54)) })
            addView(TextView(this@MainActivity).apply { text = if (d.allowed) label else "🔒 $label"; textSize = 12f; setTextColor(Pal.INK); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; gravity = Gravity.CENTER })
            setOnClickListener { if (d.allowed) packageManager.getLaunchIntentForPackage(rule.pkg)?.let { startActivity(it) } else toast(d.reason.message) }
        }
    }

    // ---- simple charts that kids can read: points per day and screen time ----
    private fun chartCards(st: State, kidIdx: Int) {
        val kids = st.kids
        add(ui.title("🏆 امتیازهای این هفته"))
        val card = ui.card()
        val legend = ui.row()
        val totals = kids.map { it.weekPoints.sum() }
        val best = totals.maxOrNull() ?: 0
        kids.forEachIndexed { i, k ->
            val crown = if (best > 0 && totals[i] == best && totals.count { it == best } == 1) "🥇 " else ""
            legend.addView(ui.chip("${Pal.avatar(k.id)} ${k.name}: $crown${fa(totals[i])}", Pal.kidColor(i)).apply { layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) } })
        }
        card.addView(legend)
        val chart = BarChartView(this).apply {
            series = kids.map { it.weekPoints.ifEmpty { List(7) { 0 } } }; colors = kids.indices.map { Pal.kidColor(it) }
            labels = dayLabels(st); layoutParams = LinearLayout.LayoutParams(-1, ui.dp(190)).apply { topMargin = ui.dp(10) }
        }
        card.addView(chart); add(card)

        if (kids.size > 1) {
            add(ui.title("⏱ امروز چقدر گوشی دیدیم؟"))
            val sc = ui.card()
            val least = kids.minOf { it.screenMinToday }
            kids.forEachIndexed { i, k ->
                val r = ui.row().apply { layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(6) } }
                r.addView(ui.text("${Pal.avatar(k.id)} ${k.name}", 15f, Pal.INK, true).apply { layoutParams = LinearLayout.LayoutParams(ui.dp(96), -2) })
                r.addView(BarView(this).apply { fraction = if (k.dailyLimitMin > 0) k.screenMinToday / k.dailyLimitMin.toFloat() else 0f; barColor = Pal.kidColor(i); barColor2 = ui.lighter(Pal.kidColor(i), 0.3f); layoutParams = LinearLayout.LayoutParams(0, ui.dp(14), 1f).apply { marginStart = ui.dp(6); marginEnd = ui.dp(8) } })
                r.addView(ui.text(fa(k.screenMinToday) + " د" + if (k.screenMinToday == least && kids.count { it.screenMinToday == least } == 1) " 🌟" else "", 14f, Pal.MUTED))
                sc.addView(r)
            }
            sc.addView(ui.text("کمتر گوشی دیدن = ستاره 🌟", 13f, Pal.PURPLE)); add(sc)
        }
    }

    private fun dayLabels(st: State): List<String> {
        val names = mapOf(java.time.DayOfWeek.SATURDAY to "ش", java.time.DayOfWeek.SUNDAY to "ی", java.time.DayOfWeek.MONDAY to "د", java.time.DayOfWeek.TUESDAY to "س",
            java.time.DayOfWeek.WEDNESDAY to "چ", java.time.DayOfWeek.THURSDAY to "پ", java.time.DayOfWeek.FRIDAY to "ج")
        return st.weekDays.ifEmpty { (6 downTo 0).map { LocalDate.now(TEHRAN).minusDays(it.toLong()).toString() } }.map { names[LocalDate.parse(it).dayOfWeek] ?: "" }
    }

    private fun a11yEnabled(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/") && s.contains("GuardService")
    }

    /** Kid sets a first PIN, or changes it by typing the current one. */
    private fun changeKidPin(kid: Kid) {
        val hasPin = kid.pinHash != null
        val oldField = field("رمز فعلی", true)
        val n1 = field("رمز جدید (۴ تا ۸ رقم)", true)
        val n2 = field("رمز جدید را دوباره بنویس", true)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(18), ui.dp(8), ui.dp(18), 0) }
        if (hasPin) box.addView(oldField)
        box.addView(n1); box.addView(n2)
        AlertDialog.Builder(this).setTitle(if (hasPin) "🔑 عوض کردن رمز" else "🔑 ساخت رمز").setView(box)
            .setPositiveButton("ذخیره") { _, _ ->
                val old = oldField.text.toString(); val a = n1.text.toString(); val b = n2.text.toString()
                if (!Regex("\\d{4,8}").matches(a)) return@setPositiveButton toast("رمز باید ۴ تا ۸ رقم باشد")
                if (a != b) return@setPositiveButton toast("دو رمز جدید یکی نیستند")
                if (hasPin && sha256(old) != kid.pinHash) return@setPositiveButton toast("رمز فعلی اشتباه است")
                thread {
                    val (code, _) = Sync.kidPin(prefs, kid.id, if (hasPin) old else null, a)
                    runOnUiThread {
                        when (code) {
                            200 -> { toast("رمزت ذخیره شد ✓"); refresh() }
                            403 -> toast("رمز فعلی اشتباه است")
                            else -> toast("ارتباط برقرار نشد، دوباره امتحان کن")
                        }
                    }
                }
            }
            .setNegativeButton("انصراف", null).show()
    }

    // ================= parent mode =================
    private fun parentPill() = ui.pill("🔑 والدین", Pal.MUTED) { parentLogin() }
    private fun parentButton() = add(parentPill())

    /** Fixed bottom tab bar for the kid: today / tasks / tutor / apps / points. */
    private fun tabBar(kid: Kid) {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = ui.dpf(8f)
            setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6))
        }
        val tabs = listOf("today" to "📋 امروز", "tasks" to "🎯 کارها", "tutor" to "🎓 معلم", "apps" to "🎮 برنامه", "points" to "🪙 امتیاز")
        tabs.forEach { (key, label) ->
            val sel = if (key == "tutor") tutorView != null else (key == kidTab && tutorView == null)
            bar.addView(TextView(this).apply {
                text = label; textSize = 12f; gravity = Gravity.CENTER
                setTypeface(typeface, if (sel) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (sel) Color.WHITE else Color.parseColor("#1F2937"))
                background = ui.shape(if (sel) Color.parseColor("#7C3AED") else Color.TRANSPARENT, 16f)
                setPadding(0, ui.dp(10), 0, ui.dp(10))
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(2); marginEnd = ui.dp(2) }
                setOnClickListener {
                    if (key == "tutor") { TutorState.kidId = kid.id; tutorView = { tutorHome(kid) }; render() }
                    else { tutorView = null; wishView = null; kidTab = key; render() }
                }
            })
        }
        navBar.removeAllViews(); navBar.addView(bar)
    }

    private fun parentLogin() {
        if (prefs.parentPinHash.isEmpty()) return toast("رمز والدین ثبت نشده")
        askPin("رمز والدین") { p -> if (sha256(p) == prefs.parentPinHash) { prefs.parentUntil = System.currentTimeMillis() + 10 * 60_000; parentMenu() } else toast("رمز اشتباه است") }
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

    // ================= homework / book photos =================
    private fun markDone(logId: String) {
        thread {
            val (code, res) = Sync.submit(prefs, logId, null)
            runOnUiThread { toast(if (code == 200 && res != null) "ثبت شد؛ منتظر تایید والدین 🙌" else "ثبت نشد، دوباره تلاش کن"); refresh() }
        }
    }

    internal fun takePhoto(kind: String, id: String) {
        val v = ContentValues().apply { put(MediaStore.Images.Media.DISPLAY_NAME, "hw_${System.currentTimeMillis()}.jpg"); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg") }
        val uri = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, v) ?: return toast("دوربین در دسترس نیست")
        pendingId = id; pendingKind = kind; photoUri = uri
        prefs.graceUntil = System.currentTimeMillis() + 3 * 60_000   // let the camera app open
        try { startActivityForResult(Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, uri), 7) }
        catch (e: Exception) { contentResolver.delete(uri, null, null); toast("برنامه‌ی دوربین پیدا نشد") }
    }

    @Deprecated("simple camera flow")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 9) { onBackgroundPicked(res, data); return }
        if (req == 11) { onSpeechResult(res, data); return }
        if (req != 7) return
        prefs.graceUntil = 0
        val uri = photoUri; val id = pendingId; val kind = pendingKind; photoUri = null; pendingId = null
        if (uri == null || id == null) return
        if (res != RESULT_OK) { contentResolver.delete(uri, null, null); return }
        if (kind == "qr") { handleQrPhoto(uri); return }

        val sd = StatusDialog(this)
        val isBook = kind == "book" || kind == "lessonpage" || kind == "lessonadd"
        sd.steps(if (isBook) "📖" else "🤖", if (isBook) "دارم صفحه‌ی کتاب را می‌خوانم" else "هوش مصنوعی داره تکلیفت رو نمره می‌ده",
            listOf("آماده کردن عکس", "فرستادن عکس", if (isBook) "خواندن متن توسط هوش مصنوعی" else "بررسی توسط هوش مصنوعی"))
        sd.show(); sd.setStep(0, StatusDialog.ACTIVE)
        thread {
            val b64 = try { loadScaledB64(uri) } catch (e: Exception) { null }
            try { contentResolver.delete(uri, null, null) } catch (e: Exception) {}
            if (b64 == null) { runOnUiThread { sd.result("😕", "عکس خوانده نشد", "دوباره عکس بگیر.", null, null) }; return@thread }
            runOnUiThread { sd.setStep(0, StatusDialog.DONE); sd.uploadProgress(0) }
            var aiShown = false
            val progress: (Int) -> Unit = { p -> runOnUiThread { if (p >= 100) { if (!aiShown) { aiShown = true; sd.waitingForAi(2) } } else sd.uploadProgress(p) } }
            val (code, r) = when (kind) {
                "book" -> Sync.dictCreate(prefs, id, b64, progress)
                "dict" -> Sync.submitDictation(prefs, id, b64, progress)
                "lessonpage" -> Sync.tutorLesson(prefs, JSONObject().put("action", "create").put("kid_id", TutorState.kidId).put("subject", TutorState.subject).put("track", TutorState.track).put("day", Sync.today()).put("image_b64", b64).put("media_type", "image/jpeg"), 90_000, progress)
                "lessonadd" -> Sync.tutorLesson(prefs, JSONObject().put("action", "add_page").put("kid_id", TutorState.kidId).put("subject", TutorState.subject).put("track", TutorState.track).put("day", Sync.today()).put("lesson_id", id).put("image_b64", b64).put("media_type", "image/jpeg"), 90_000, progress)
                "solution" -> Sync.tutor(prefs, JSONObject().put("action", "check").put("kid_id", TutorState.kidId).put("subject", TutorState.subject).put("track", TutorState.track)
                    .put("day", Sync.today()).put("exercise_id", id).put("answers", org.json.JSONArray(TutorState.drafts[id] ?: emptyList<String>()))
                    .put("image_b64", b64).put("media_type", "image/jpeg"))
                "tutor" -> Sync.tutor(prefs, org.json.JSONObject().put("action", "explain").put("kid_id", TutorState.kidId).put("subject", TutorState.subject).put("track", TutorState.track).put("day", Sync.today()).put("image_b64", b64).put("media_type", "image/jpeg"))
                else -> Sync.submit(prefs, id, b64, progress)
            }
            runOnUiThread {
                if (kind == "dict" && code == 200 && r?.optString("status") != "retake") dictSession = null
                if (kind == "lessonpage") tutorGotLesson(sd, code, r, false)
                else if (kind == "lessonadd") tutorGotLesson(sd, code, r, false, page = true)
                else if (kind == "tutor") { sd.dismiss(); tutorGotExplain(code, r) }
                else if (kind == "solution") { sd.dismiss(); tutorGotSolution(code, r, id) }
                else if (kind == "book") bookResult(sd, code, r) else gradeResult(sd, code, r)
                refresh()
            }
        }
    }

    /** Reads the QR on the phone (no internet needed), then asks the server to build or extend the lesson from its link. */
    private fun handleQrPhoto(uri: Uri) {
        val sd = StatusDialog(this)
        sd.steps("🔳", "دارم QR کتاب را می‌خوانم", listOf("خواندن QR", "فرستادن لینک", "گرفتن درس از سایت"))
        sd.show(); sd.setStep(0, StatusDialog.ACTIVE)
        thread {
            val url: String? = try {
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, o) }
                var s = 1
                while (maxOf(o.outWidth, o.outHeight) / s > 2400) s *= 2
                val bmp = contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = s }) }!!
                val px = IntArray(bmp.width * bmp.height); bmp.getPixels(px, 0, bmp.width, 0, 0, bmp.width, bmp.height)
                Qr.decode(px, bmp.width, bmp.height)
            } catch (e: Exception) { null }
            try { contentResolver.delete(uri, null, null) } catch (e: Exception) {}
            if (url == null) { runOnUiThread { sd.result("🔳", "QR پیدا نشد", "نزدیک‌تر و واضح‌تر عکس بگیر؛ QR کامل و صاف توی عکس باشد.", null, null, "دوباره می‌گیرم") }; return@thread }
            val attach = TutorState.lessonId.isNotEmpty()
            runOnUiThread { sd.setStep(0, StatusDialog.DONE); sd.waitingForAi(2) }
            val body = JSONObject().put("kid_id", TutorState.kidId).put("subject", TutorState.subject).put("track", TutorState.track).put("day", Sync.today()).put("qr_url", url)
            if (attach) body.put("action", "attach_qr").put("lesson_id", TutorState.lessonId) else body.put("action", "create")
            val (code, r) = Sync.tutorLesson(prefs, body)
            runOnUiThread { tutorGotLesson(sd, code, r, attach); refresh() }
        }
    }

    private fun gradeResult(sd: StatusDialog, code: Int, r: org.json.JSONObject?) {
        when {
            code == 429 -> sd.result("🌙", "امروز دیگه بسه", "سهم دیکته‌ی امروزت تمام شد؛ فردا دوباره.", null, null)
            code == 409 -> sd.result("✅", "این قبلاً تایید شده", "", null, null)
            code != 200 || r == null -> sd.result("📶", "ارسال نشد", "اینترنت را چک کن و دوباره امتحان کن.", null, null)
            r.optString("status") == "retake" -> sd.result("📷", "عکس واضح نبود", r.optString("feedback"), null, null, "دوباره می‌گیرم")
            r.optString("status") == "submitted" -> sd.result("📨", "فرستاده شد!", "پدر و مادر نمره می‌دهند.", null, null)
            else -> {
                val score = r.optDouble("score"); val stars = Math.round(score / 4.0).toInt().coerceIn(0, 5)
                val title = when { score >= 18 -> "🏆 عالی بود!"; score >= 14 -> "🎉 آفرین!"; score >= 10 -> "💪 خوبه، بهتر هم می‌شه"; else -> "🌱 دوباره تلاش می‌کنیم" }
                val chip = if (r.optString("status") == "approved") "+${fa(r.optInt("points"))} امتیاز 🪙" else "منتظر تایید والدین ⏳"
                sd.result(if (score >= 14) "🎉" else "😊", title, "نمره: ${fa(score)} از ۲۰\n${r.optString("feedback")}", stars, chip)
            }
        }
    }

    private fun bookResult(sd: StatusDialog, code: Int, r: org.json.JSONObject?) {
        when {
            code == 429 -> sd.result("🌙", "امروز دیگه بسه", "امروز به اندازه‌ی کافی دیکته‌ی جدید گرفتی؛ فردا دوباره.", null, null)
            code != 200 || r == null -> sd.result("📶", "ارسال نشد", "اینترنت را چک کن و دوباره امتحان کن.", null, null)
            r.optString("status") == "retake" -> sd.result("📷", "صفحه واضح نبود", r.optString("feedback"), null, null, "دوباره می‌گیرم")
            r.optString("status") == "review" -> sd.result("📖", "صفحه خوانده شد!", "${fa(r.optInt("phrase_count"))} جمله پیدا شد.\nبعد از تایید پدر و مادر، دیکته شروع می‌شود.", null, null)
            else -> sd.result("🎉", "دیکته آماده‌ست!", "${fa(r.optInt("phrase_count"))} جمله. از فهرست دیکته‌ها شروعش کن.", null, null)
        }
    }

    // ================= dictation: listen, write on paper, photograph =================
    private fun dictationScreen() {
        val (d, idx) = dictSession!!
        val last = idx >= d.phraseCount - 1
        add(ui.text("✏️ دیکته: ${d.title}", 24f, Pal.INK, true).apply { setPadding(ui.dp(4), ui.dp(20), 0, ui.dp(6)) })
        val c = ui.card()
        c.addView(ui.text("جمله‌ی ${fa(idx + 1)} از ${fa(d.phraseCount)}", 18f, Pal.PURPLE, true).apply { gravity = Gravity.CENTER; textAlignment = View.TEXT_ALIGNMENT_CENTER })
        val dots = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) } }
        repeat(d.phraseCount) { i ->
            dots.addView(View(this).apply { background = ui.oval(if (i <= idx) Pal.PURPLE else Pal.TRACK); layoutParams = LinearLayout.LayoutParams(ui.dp(12), ui.dp(12)).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4) } })
        }
        c.addView(dots)
        c.addView(ui.text("گوش بده و روی کاغذ بنویس.\nهر وقت خواستی دوباره بشنو.", 15f, Pal.MUTED).apply { textAlignment = View.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER; setPadding(0, ui.dp(10), 0, ui.dp(10)) })
        val big = TextView(this).apply {
            text = "🔊"; textSize = 56f; gravity = Gravity.CENTER; isClickable = true; elevation = ui.dpf(6f)
            background = ui.oval(Pal.ORANGE); layoutParams = LinearLayout.LayoutParams(ui.dp(132), ui.dp(132)).apply { gravity = Gravity.CENTER_HORIZONTAL; topMargin = ui.dp(6); bottomMargin = ui.dp(10) }
            setOnClickListener { playPhrase(d, idx) }
        }
        speakBtn = big; c.addView(big); add(c)
        if (!last) add(ui.pill("⏭ جمله‌ی بعد", Pal.PURPLE) { stopPlayer(); dictSession = Pair(d, idx + 1); render() })
        else add(ui.pill("✅ تمام شد؛ عکس دیکته‌ام را بفرست", Pal.GREEN) { stopPlayer(); takePhoto("dict", d.id) })
        if (idx > 0) add(ui.pill("⏮ جمله‌ی قبل", Pal.BLUE) { stopPlayer(); dictSession = Pair(d, idx - 1); render() })
        add(ui.pill("↩ بازگشت", Pal.MUTED) { stopPlayer(); dictSession = null; render() })
    }

    private fun stopPlayer() { try { player?.stop(); player?.release() } catch (e: Exception) {}; player = null; try { segPlayer?.stop(); segPlayer?.release() } catch (e: Exception) {}; segPlayer = null }

    private var segPlayer: android.media.MediaPlayer? = null

    private fun playSegment(url: String, start: Double, end: Double, fallback: () -> Unit) {
        val ui2 = android.os.Handler(android.os.Looper.getMainLooper())
        try {
            segPlayer = android.media.MediaPlayer().apply {
                setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                setDataSource(url)
                setOnPreparedListener { it.seekTo((start * 1000).toInt()) }
                setOnSeekCompleteListener { mp -> mp.start(); speakBtn?.text = "🔊"; ui2.postDelayed({ try { if (mp.isPlaying) mp.pause() } catch (e: Exception) {} }, ((end - start) * 1000).toLong() + 300) }
                setOnErrorListener { _, _, _ -> fallback(); true }
                prepareAsync()
            }
        } catch (e: Exception) { fallback() }
    }

    private fun playPhrase(d: DictItem, idx: Int) {
        stopPlayer()
        if (d.audioUrl != null && idx < d.segments.size) {        // original lesson recording, one phrase at a time
            speakBtn?.text = "⏳"
            playSegment(d.audioUrl, d.segments[idx].first, d.segments[idx].second) { runOnUiThread { speakBtn?.text = "🔊"; playPhraseTts(d, idx) } }
            return
        }
        playPhraseTts(d, idx)
    }

    private fun playPhraseTts(d: DictItem, idx: Int) {
        stopPlayer()
        speakBtn?.text = "⏳"
        thread {
            val key = "${d.id}:$idx"
            var pcm = audioCache[key]
            if (pcm == null) {
                val (code, r) = Sync.dictAudio(prefs, d.id, idx)
                if (code != 200 || r == null) {
                    val msg = when (code) { 403 -> "هنوز والدین متن را تایید نکرده‌اند"; 429 -> "سهم دیکته‌ی امروزت تمام شد"; 409 -> "این دیکته تمام شده"; else -> "صدا در دسترس نیست، دوباره امتحان کن" }
                    runOnUiThread { speakBtn?.text = "🔊"; toast(msg) }; return@thread
                }
                sampleRate = r.optInt("sample_rate", 24000)
                pcm = Base64.decode(r.getString("audio_b64"), Base64.DEFAULT)
                audioCache[key] = pcm
            }
            val bytes: ByteArray = pcm ?: return@thread
            runOnUiThread {
                speakBtn?.text = "🔊"
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