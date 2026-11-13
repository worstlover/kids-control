package ir.family.parent

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.app.TimePickerDialog
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import kotlin.concurrent.thread

private val DAYS = listOf("یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه")

class ParentActivity : Activity() {
    private lateinit var api: Api
    private lateinit var ui: Ui
    private lateinit var content: LinearLayout
    private lateinit var kidBar: LinearLayout
    private var kids = JSONArray()
    private var kidId = ""
    private var tab = "today"

    // task form state (kept between redraws)
    private var fEdit: String? = null
    private var fTitle = ""
    private var fType = "daily"
    private val fWeek = mutableSetOf<Int>()
    private var fMonth = 1
    private var fEvery = 2
    private var fOnce = ""
    private var fStart = ""
    private var fEnd = ""
    private var fTime = ""
    private var fPoints = "5"
    private var fPenalty = "0"
    private var fPhoto = false
    private var eTitle: EditText? = null
    private var eMonth: EditText? = null
    private var eEvery: EditText? = null
    private var ePoints: EditText? = null
    private var ePenalty: EditText? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        api = Api(this)
        ui = Ui(this)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(Pal.BG_TOP, Pal.BG_BOT))
        }
        setContentView(root)
        if (api.loggedIn) buildMain(root) else loginScreen(root)
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    /** Runs a network call off the UI thread; errors are shown as a message. */
    private fun async(work: () -> Unit) = thread {
        try { work() } catch (e: Exception) { runOnUiThread { toast(e.message?.take(140) ?: "خطا") } }
    }

    private fun input(hint: String, value: String = "", numeric: Boolean = false) = EditText(this).apply {
        this.hint = hint
        setText(value)
        textSize = 16f
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        if (numeric) inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_SIGNED
        background = ui.shape(Color.parseColor("#F3F4F8"), 14f)
        setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) }
    }

    private fun chipRow(views: List<View>): View {
        val h = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        views.forEach { h.addView(it, LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(6); topMargin = ui.dp(6) }) }
        return HorizontalScrollView(this).apply { addView(h) }
    }

    private fun currentKid(): JSONObject? = (0 until kids.length()).map { kids.getJSONObject(it) }.firstOrNull { it.getString("id") == kidId }

    // ================= login =================
    private fun loginScreen(root: LinearLayout) {
        root.removeAllViews()
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(22), ui.dp(60), ui.dp(22), ui.dp(22)) }
        root.addView(ScrollView(this).apply { addView(box) })
        box.addView(ui.text("👨‍👩‍👧", 56f))
        box.addView(ui.title("ورود والدین"))
        val email = input("ایمیل")
        val pw = input("رمز").apply { inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD }
        box.addView(email); box.addView(pw)
        box.addView(ui.pill("ورود", Pal.PURPLE) {
            val e = email.text.toString().trim()
            val p = pw.text.toString()
            if (e.isEmpty() || p.isEmpty()) return@pill toast("ایمیل و رمز را بنویس")
            thread {
                val ok = try { api.login(e, p) } catch (x: Exception) { runOnUiThread { toast("ارتباط با سرور برقرار نشد") }; return@thread }
                runOnUiThread { if (ok) buildMain(root) else toast("ایمیل یا رمز اشتباه است") }
            }
        })
    }

    // ================= main frame: kid chips + tab content + bottom bar =================
    private fun buildMain(root: LinearLayout) {
        root.removeAllViews()
        kidBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setPadding(ui.dp(10), ui.dp(12), ui.dp(10), ui.dp(4)) }
        root.addView(HorizontalScrollView(this).apply { addView(kidBar) })
        val scroll = ScrollView(this)
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(ui.dp(14), ui.dp(6), ui.dp(14), ui.dp(30)) }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val nav = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; setBackgroundColor(Color.WHITE); elevation = ui.dpf(8f); setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(6)) }
        listOf("today" to "📋 امروز", "tasks" to "🎯 کارها", "points" to "🪙 امتیاز", "settings" to "⚙️ تنظیمات").forEach { (k, label) ->
            val on = k == tab
            nav.addView(ui.chip(label, if (on) Pal.PURPLE else Pal.TRACK, if (on) Color.WHITE else Pal.INK).apply {
                gravity = Gravity.CENTER; textSize = 13f
                layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(3); marginEnd = ui.dp(3) }
                setOnClickListener { tab = k; buildMain(root) }
            })
        }
        root.addView(nav)
        loadKids(root)
    }

    private fun loadKids(root: LinearLayout) = async {
        val list = api.get("kids?active=eq.true&select=id,name,auto_penalty,reminder_on,reminder_quiet&order=name")
        runOnUiThread {
            kids = list
            if (kidId.isEmpty() || currentKid() == null) kidId = if (list.length() > 0) list.getJSONObject(0).getString("id") else ""
            kidBar.removeAllViews()
            for (i in 0 until list.length()) {
                val k = list.getJSONObject(i)
                val id = k.getString("id")
                val on = id == kidId
                kidBar.addView(ui.chip(k.getString("name"), if (on) Pal.PURPLE else Pal.TRACK, if (on) Color.WHITE else Pal.INK).apply {
                    textSize = 16f; setPadding(ui.dp(14), ui.dp(8), ui.dp(14), ui.dp(8))
                    layoutParams = LinearLayout.LayoutParams(-2, -2).apply { marginEnd = ui.dp(8) }
                    setOnClickListener { kidId = id; buildMain(root) }
                })
            }
            if (kidId.isEmpty()) {
                content.removeAllViews()
                content.addView(ui.text("هنوز فرزندی ثبت نشده. اول از پنل وب فرزند اضافه کن.", 16f))
            } else render()
        }
    }

    private fun render() {
        when (tab) {
            "today" -> today()
            "tasks" -> tasks()
            "points" -> points()
            "settings" -> settings()
        }
    }

    // ================= today: grouped by what the parent has to do =================
    private fun sectionHead(text: String, color: Int): View = ui.text(text, 18f, color, true).apply { setPadding(ui.dp(4), ui.dp(18), 0, ui.dp(4)) }

    private fun countChip(label: String, n: Int, color: Int): View = ui.chip("$label ${fa(n)}", if (n > 0) color else Pal.TRACK, if (n > 0) Color.WHITE else Pal.MUTED)

    private fun today() {
        content.removeAllViews()
        content.addView(ui.title("📋 امروز"))
        val day = Api.today()
        async {
            val logs = api.get("task_logs?select=id,status,points_awarded,ai_score,ai_feedback,tasks(title,points,due_time,needs_photo)&kid_id=eq.$kidId&due_date=eq.$day")
            val bal = api.get("kid_balance?kid_id=eq.$kidId").optJSONObject(0)?.optLong("points", 0) ?: 0L
            val todayRows = api.get("points_ledger?kid_id=eq.$kidId&created_at=gte.${day}T00:00:00%2B03:30&select=delta")
            val dicts = api.get("dictations?kid_id=eq.$kidId&status=in.(review,graded)&select=id,title,status,ai_score,ai_feedback,points,points_awarded")
            val pens = api.get("penalty_rules?kid_id=eq.$kidId&active=eq.true&select=id,title,points")
            val done = api.get("penalty_logs?kid_id=eq.$kidId&day=eq.$day&select=rule_id")
            val doneIds = (0 until done.length()).map { done.getJSONObject(it).getString("rule_id") }.toSet()
            val all = (0 until logs.length()).map { logs.getJSONObject(it) }
            val waiting = all.filter { it.getString("status") in setOf("submitted", "graded") }
            val todo = all.filter { it.getString("status") == "pending" }
            val redo = all.filter { it.getString("status") == "rejected" }
            val ok = all.filter { it.getString("status") == "approved" }
            val dWait = (0 until dicts.length()).map { dicts.getJSONObject(it) }
            val gained = (0 until todayRows.length()).sumOf { maxOf(0, todayRows.getJSONObject(it).getInt("delta")) }
            runOnUiThread {
                content.addView(ui.card().apply {
                    val r = ui.row()
                    r.addView(LinearLayout(this@ParentActivity).apply {
                        orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
                        addView(ui.text("موجودی امتیاز", 14f, Pal.MUTED, true))
                        addView(ui.text(fa(bal), 36f, Pal.PURPLE, true))
                    })
                    r.addView(ui.text("امروز +${fa(gained)}", 16f, Pal.GREEN, true))
                    addView(r)
                    addView(chipRow(listOf(
                        countChip("⏳ منتظر تایید", waiting.size + dWait.count { it.getString("status") == "graded" }, Pal.ORANGE),
                        countChip("🕓 انجام‌نشده", todo.size, Pal.BLUE),
                        countChip("🔁 رد شده", redo.size, Pal.RED),
                        countChip("✅ تایید شده", ok.size, Pal.GREEN),
                    )))
                })
                if (all.isEmpty() && dWait.isEmpty()) content.addView(ui.text("برای امروز کاری ثبت نشده.", 15f, Pal.MUTED))

                // 1) what needs the parent now
                val gradedDicts = dWait.filter { it.getString("status") == "graded" }
                if (waiting.isNotEmpty() || gradedDicts.isNotEmpty()) {
                    content.addView(sectionHead("⏳ منتظر تایید شما (${fa(waiting.size + gradedDicts.size)})", Pal.ORANGE))
                    if (waiting.size > 1) content.addView(ui.pill("✅ تایید همه‌ی کارهای منتظر", Pal.GREEN) { approveAll(waiting) })
                    waiting.forEach { content.addView(logCard(it)) }
                    gradedDicts.forEach { content.addView(dictCard(it)) }
                }
                dWait.filter { it.getString("status") == "review" }.takeIf { it.isNotEmpty() }?.let { rv ->
                    content.addView(ui.card().apply {
                        addView(ui.text("📖 ${fa(rv.size)} دیکته منتظر تایید متن است", 15f, Pal.INK, true))
                        addView(ui.text("متن را در پنل وب (تب «دیکته») با کتاب مقایسه و تایید کن: " + rv.joinToString("، ") { it.getString("title") }, 13f, Pal.MUTED))
                    })
                }
                // 2) not done yet
                if (todo.isNotEmpty()) {
                    content.addView(sectionHead("🕓 هنوز انجام نداده (${fa(todo.size)})", Pal.BLUE))
                    todo.forEach { content.addView(logCard(it)) }
                }
                // 3) sent back
                if (redo.isNotEmpty()) {
                    content.addView(sectionHead("🔁 رد شده؛ باید دوباره انجام دهد (${fa(redo.size)})", Pal.RED))
                    redo.forEach { content.addView(logCard(it)) }
                }
                // 4) done
                if (ok.isNotEmpty()) {
                    val pts = ok.sumOf { if (it.isNull("points_awarded")) 0 else it.getInt("points_awarded") }
                    content.addView(sectionHead("✅ تایید شده (${fa(ok.size)}) · +${fa(pts)} امتیاز", Pal.GREEN))
                    ok.forEach { content.addView(logCard(it)) }
                }

                if (pens.length() > 0) {
                    content.addView(sectionHead("⚠️ جریمه‌ی امروز", Pal.RED))
                    for (i in 0 until pens.length()) {
                        val p = pens.getJSONObject(i)
                        content.addView(ui.card().apply {
                            addView(ui.text("${p.getString("title")}  −${fa(p.getInt("points"))}", 16f, Pal.INK, true))
                            if (doneIds.contains(p.getString("id"))) addView(ui.text("امروز ثبت شد", 14f, Pal.MUTED))
                            else addView(ui.pill("ثبت جریمه", Pal.RED) { applyPenalty(p) })
                        })
                    }
                }
            }
        }
    }

    private fun logCard(log: JSONObject): View {
        val t = log.getJSONObject("tasks")
        val status = log.getString("status")
        val (label, color) = when (status) {
            "pending" -> Pair("🕓 انجام نشده", Pal.BLUE)
            "submitted" -> Pair("⏳ انجام داده؛ منتظر تایید شما", Pal.ORANGE)
            "graded" -> Pair("⏳ هوش مصنوعی نمره داد؛ منتظر تایید امتیاز", Pal.ORANGE)
            "approved" -> Pair("✅ تایید شد", Pal.GREEN)
            "rejected" -> Pair("🔁 رد شد؛ باید دوباره انجام دهد", Pal.RED)
            else -> Pair(status, Pal.MUTED)
        }
        val card = ui.card()
        val top = ui.row()
        top.addView(ui.text(t.getString("title"), 17f, Pal.INK, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        val pts = if (status == "approved" && !log.isNull("points_awarded")) log.getInt("points_awarded") else t.getInt("points")
        top.addView(ui.chip("⭐ ${fa(pts)}", Pal.YELLOW, Pal.INK))
        card.addView(top)
        card.addView(ui.text(label + (if (!t.isNull("due_time") && status == "pending") " · تا ساعت ${fa(t.getString("due_time"))}" else "") +
            (if (t.optBoolean("needs_photo") && status == "pending") " · با عکس" else ""), 14f, color, true))
        if (!log.isNull("ai_score")) card.addView(ui.text("نمره‌ی هوش مصنوعی: ${fa(log.getDouble("ai_score").toString().removeSuffix(".0"))} از ۲۰", 14f, Pal.INK))
        if (!log.isNull("ai_feedback")) card.addView(ui.text(log.getString("ai_feedback"), 13f, Pal.MUTED))
        val row = ui.row()
        when (status) {
            "submitted", "graded" -> {
                row.addView(ui.pill("✅ تایید", Pal.GREEN) { approve(log) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
                row.addView(ui.pill("❌ رد", Pal.RED) { reject(log) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            }
            "pending", "rejected" -> row.addView(ui.pill("✅ انجام داد (تایید دستی)", Pal.GREEN) { approve(log) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            "approved" -> row.addView(ui.pill("↩ لغو تایید", Pal.MUTED) { reject(log) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        }
        card.addView(row)
        return card
    }

    private fun dictCard(d: JSONObject): View = ui.card().apply {
        val top = ui.row()
        top.addView(ui.text("📖 دیکته: ${d.getString("title")}", 17f, Pal.INK, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        top.addView(ui.chip("⭐ ${fa(if (d.isNull("points_awarded")) d.optInt("points") else d.getInt("points_awarded"))}", Pal.YELLOW, Pal.INK))
        addView(top)
        addView(ui.text("⏳ نوشت و نمره گرفت؛ منتظر تایید امتیاز", 14f, Pal.ORANGE, true))
        if (!d.isNull("ai_score")) addView(ui.text("نمره: ${fa(d.getDouble("ai_score").toString().removeSuffix(".0"))} از ۲۰", 14f, Pal.INK))
        if (!d.isNull("ai_feedback")) addView(ui.text(d.getString("ai_feedback"), 13f, Pal.MUTED))
        val row = ui.row()
        row.addView(ui.pill("✅ تایید و امتیاز", Pal.GREEN) { approveDict(d) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
        row.addView(ui.pill("✍️ دوباره بنویسد", Pal.RED) { rejectDict(d) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        addView(row)
    }

    private fun approveDict(d: JSONObject) = async {
        val pts = if (d.isNull("points_awarded")) d.optInt("points") else d.getInt("points_awarded")
        val changed = api.write("PATCH", "dictations?id=eq.${d.getString("id")}&status=eq.graded", JSONObject().put("status", "done").put("points_awarded", pts))
        if (changed != null && pts > 0) api.write("POST", "points_ledger", JSONObject().put("kid_id", kidId).put("delta", pts).put("reason", "task")
            .put("ref_id", d.getString("id")).put("note", "دیکته: " + d.getString("title")))
        runOnUiThread { toast("تایید شد ✓"); render() }
    }

    private fun rejectDict(d: JSONObject) = async {
        api.send("PATCH", "dictations?id=eq.${d.getString("id")}", JSONObject().put("status", "ready").put("done_day", JSONObject.NULL)
            .put("ai_score", JSONObject.NULL).put("ai_feedback", JSONObject.NULL).put("points_awarded", JSONObject.NULL).put("answer_photo_path", JSONObject.NULL))
        runOnUiThread { toast("دوباره می‌نویسد"); render() }
    }

    private fun approveAll(list: List<JSONObject>) {
        AlertDialog.Builder(this).setMessage("${fa(list.size)} کار تایید و امتیازشان داده شود؟")
            .setPositiveButton("تایید همه") { _, _ -> async { list.forEach { approveNow(it) }; runOnUiThread { toast("همه تایید شدند ✓"); render() } } }
            .setNegativeButton("انصراف", null).show()
    }

    private fun approve(log: JSONObject) = async { approveNow(log); runOnUiThread { toast("تایید شد ✓"); render() } }

    /** Approve one task and give its points (the status filter stops a double click from paying twice). */
    private fun approveNow(log: JSONObject) {
        val t = log.getJSONObject("tasks")
        val pts = if (log.isNull("points_awarded")) t.getInt("points") else log.getInt("points_awarded")
        val changed = api.write("PATCH", "task_logs?id=eq.${log.getString("id")}&status=neq.approved", JSONObject().put("status", "approved").put("points_awarded", pts))
        if (changed != null && pts > 0) api.write("POST", "points_ledger", JSONObject().put("kid_id", kidId).put("delta", pts).put("reason", "task")
            .put("ref_id", log.getString("id")).put("note", t.getString("title")))
    }

    private fun reject(log: JSONObject) = async {
        val t = log.getJSONObject("tasks")
        if (log.getString("status") == "approved" && !log.isNull("points_awarded") && log.getInt("points_awarded") > 0)
            api.write("POST", "points_ledger", JSONObject().put("kid_id", kidId).put("delta", -log.getInt("points_awarded")).put("reason", "penalty")
                .put("ref_id", log.getString("id")).put("note", "لغو: " + t.getString("title")))
        api.send("PATCH", "task_logs?id=eq.${log.getString("id")}", JSONObject().put("status", "rejected").put("points_awarded", JSONObject.NULL))
        runOnUiThread { toast("ثبت شد"); render() }
    }

    private fun applyPenalty(p: JSONObject) = async {
        val day = Api.today()
        api.write("POST", "penalty_logs", JSONObject().put("kid_id", kidId).put("rule_id", p.getString("id")).put("day", day).put("points", p.getInt("points")))
        api.write("POST", "points_ledger", JSONObject().put("kid_id", kidId).put("delta", -p.getInt("points")).put("reason", "penalty")
            .put("note", "جریمه: " + p.getString("title")))
        runOnUiThread { toast("جریمه ثبت شد"); render() }
    }

    // ================= tasks: list, copy, and the full form =================
    private fun scheduleText(t: JSONObject): String {
        val s = t.optJSONObject("schedule")
        val type = s?.optString("type")?.takeIf { it.isNotEmpty() }
        if (s == null || type == null) return when (t.optString("kind")) {
            "weekly" -> (0 until (t.optJSONArray("weekdays")?.length() ?: 0)).joinToString("، ") { DAYS[t.getJSONArray("weekdays").getInt(it)] }
            "once" -> "یک‌بار"
            else -> "هر روز"
        }
        return when (type) {
            "weekdays" -> (0 until s.getJSONArray("weekdays").length()).joinToString("، ") { DAYS[s.getJSONArray("weekdays").getInt(it)] }
            "monthly" -> "روز ${fa(s.optInt("month_day"))} هر ماه"
            "interval" -> "هر ${fa(s.optInt("every_n"))} روز"
            "once" -> "یک‌بار در ${s.optString("date")}"
            else -> "هر روز"
        }
    }

    private fun tasks() {
        content.removeAllViews()
        content.addView(ui.title("🎯 کارها"))
        content.addView(ui.pill("➕ کار جدید", Pal.GREEN) { loadForm(null) })
        async {
            val list = api.get("tasks?kid_id=eq.$kidId&active=eq.true&select=*")
            val others = (0 until kids.length()).map { kids.getJSONObject(it) }.filter { it.getString("id") != kidId }
            runOnUiThread {
                if (list.length() == 0) content.addView(ui.text("هنوز کاری نیست.", 15f, Pal.MUTED))
                val items = (0 until list.length()).map { list.getJSONObject(it) }
                val groups = items.groupBy { groupOf(it) }.toSortedMap(compareBy { it.first })
                for ((g, inGroup) in groups) {
                    content.addView(sectionHead("${g.second} (${fa(inGroup.size)})", Pal.PURPLE))
                    for (t in inGroup.sortedWith(compareBy({ it.isNull("due_time") }, { it.optString("due_time") }, { it.getString("title") }))) content.addView(ui.card().apply {
                        addView(ui.text(t.getString("title"), 17f, Pal.INK, true))
                        addView(ui.text(scheduleText(t) + (if (t.isNull("due_time")) "" else " · تا ساعت ${t.getString("due_time")}") +
                            " · ${fa(t.getInt("points"))} امتیاز" + (if (t.getInt("penalty_points") > 0) " · جریمه ${fa(t.getInt("penalty_points"))}" else ""), 14f, Pal.MUTED))
                        val row = ui.row()
                        row.addView(ui.pill("✏️ ویرایش", Pal.BLUE) { loadForm(t) }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
                        row.addView(ui.pill("🗑 حذف", Pal.RED) { async { api.send("PATCH", "tasks?id=eq.${t.getString("id")}", JSONObject().put("active", false)); runOnUiThread { render() } } }
                            .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                        addView(row)
                        if (others.isNotEmpty()) {
                            addView(ui.text("کپی به:", 13f, Pal.MUTED))
                            addView(chipRow(others.map { o -> ui.chip("📋 ${o.getString("name")}", Pal.TRACK, Pal.INK).apply {
                                setOnClickListener { copyTask(t, o.getString("id")) } } }))
                        }
                    })
                }
                if (others.isNotEmpty() && list.length() > 0) content.addView(ui.card().apply {
                    addView(ui.text("کپی همه‌ی کارها", 16f, Pal.INK, true))
                    addView(chipRow(others.map { o -> ui.chip("همه به ${o.getString("name")}", Pal.PURPLE, Color.WHITE).apply {
                        setOnClickListener { copyAll(list, o.getString("id")) } } }))
                })
            }
        }
    }

    /** Which group a task is listed under: by how often it repeats. */
    private fun groupOf(t: JSONObject): Pair<Int, String> {
        val type = t.optJSONObject("schedule")?.optString("type")?.takeIf { it.isNotEmpty() }
            ?: when (t.optString("kind")) { "weekly" -> "weekdays"; "once" -> "once"; else -> "daily" }
        return when (type) {
            "weekdays" -> Pair(1, "🗓 روزهای مشخص هفته")
            "interval" -> Pair(2, "🔁 هر چند روز یک‌بار")
            "monthly" -> Pair(3, "📆 ماهانه")
            "once" -> Pair(4, "1️⃣ یک‌بار")
            else -> Pair(0, "📅 هر روز")
        }
    }

    private fun copyTask(t: JSONObject, toKid: String) = async {
        val o = JSONObject(t.toString())
        o.remove("id"); o.remove("created_at"); o.put("kid_id", toKid)
        api.write("POST", "tasks", o)
        runOnUiThread { toast("کپی شد ✓") }
    }

    private fun copyAll(list: JSONArray, toKid: String) = async {
        val rows = JSONArray()
        for (i in 0 until list.length()) {
            val o = JSONObject(list.getJSONObject(i).toString())
            o.remove("id"); o.remove("created_at"); o.put("kid_id", toKid)
            rows.put(o)
        }
        if (rows.length() > 0) api.send("POST", "tasks", rows, "return=minimal")
        runOnUiThread { toast("${fa(rows.length())} کار کپی شد ✓") }
    }

    private fun loadForm(t: JSONObject?) {
        fEdit = t?.getString("id")
        fTitle = t?.optString("title") ?: ""
        val s = t?.optJSONObject("schedule")
        fType = s?.optString("type")?.takeIf { it.isNotEmpty() } ?: when (t?.optString("kind")) {
            "weekly" -> "weekdays"; "once" -> "once"; else -> "daily" }
        fWeek.clear()
        val wd = s?.optJSONArray("weekdays") ?: t?.optJSONArray("weekdays")
        if (wd != null) for (i in 0 until wd.length()) fWeek.add(wd.getInt(i))
        fMonth = s?.optInt("month_day", 1) ?: 1
        fEvery = s?.optInt("every_n", 2) ?: 2
        fOnce = s?.optString("date") ?: ""
        fStart = s?.optString("start")?.takeIf { it != "null" && it.isNotEmpty() } ?: ""
        fEnd = s?.optString("end")?.takeIf { it != "null" && it.isNotEmpty() } ?: ""
        fTime = t?.optString("due_time")?.takeIf { it != "null" && it.isNotEmpty() } ?: ""
        fPoints = (t?.optInt("points", 5) ?: 5).toString()
        fPenalty = (t?.optInt("penalty_points", 0) ?: 0).toString()
        fPhoto = t?.optBoolean("needs_photo", false) ?: false
        form()
    }

    /** Copies what is typed into the form before redrawing it. */
    private fun captureForm() {
        eTitle?.let { fTitle = it.text.toString().trim() }
        eMonth?.let { fMonth = it.text.toString().toIntOrNull() ?: fMonth }
        eEvery?.let { fEvery = it.text.toString().toIntOrNull() ?: fEvery }
        ePoints?.let { fPoints = it.text.toString() }
        ePenalty?.let { fPenalty = it.text.toString() }
    }

    private fun pickDate(initial: String, onPicked: (String) -> Unit) {
        val c = Calendar.getInstance()
        if (initial.length == 10) c.set(initial.substring(0, 4).toInt(), initial.substring(5, 7).toInt() - 1, initial.substring(8, 10).toInt())
        DatePickerDialog(this, { _, y, m, d -> onPicked("%04d-%02d-%02d".format(y, m + 1, d)) },
            c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show()
    }

    private fun form() {
        content.removeAllViews()
        content.addView(ui.title(if (fEdit == null) "➕ کار جدید" else "✏️ ویرایش کار"))
        val card = ui.card()
        val title = input("عنوان کار، مثلاً تکالیف مدرسه", fTitle); eTitle = title
        card.addView(title)

        card.addView(ui.text("هر وقت انجام می‌شود:", 14f, Pal.MUTED, true))
        val types = listOf("daily" to "هر روز", "weekdays" to "روزهای هفته", "monthly" to "ماهانه", "interval" to "هر چند روز", "once" to "یک‌بار")
        card.addView(chipRow(types.map { (k, l) ->
            ui.chip(l, if (fType == k) Pal.PURPLE else Pal.TRACK, if (fType == k) Color.WHITE else Pal.INK).apply {
                setOnClickListener { captureForm(); fType = k; form() }
            }
        }))

        when (fType) {
            "weekdays" -> card.addView(chipRow(DAYS.mapIndexed { i, n ->
                ui.chip(n, if (fWeek.contains(i)) Pal.PURPLE else Pal.TRACK, if (fWeek.contains(i)) Color.WHITE else Pal.INK).apply {
                    setOnClickListener { if (!fWeek.add(i)) fWeek.remove(i); form() }
                }
            }))
            "monthly" -> {
                val e = input("روز ماه", fMonth.toString(), true); eMonth = e
                card.addView(ui.text("روز ماه (اگر ماه کمتر داشت، آخرین روز)", 13f, Pal.MUTED)); card.addView(e)
            }
            "interval" -> {
                val e = input("هر چند روز", fEvery.toString(), true); eEvery = e
                card.addView(ui.text("هر چند روز یک‌بار؟ (مثلاً ۳)", 13f, Pal.MUTED)); card.addView(e)
            }
            "once" -> card.addView(ui.pill("📅 ${fOnce.ifEmpty { "انتخاب تاریخ" }}", Pal.BLUE) { pickDate(fOnce) { fOnce = it; form() } })
        }

        if (fType != "once") {
            card.addView(chipRow(listOf(
                ui.chip("از: ${fStart.ifEmpty { "امروز" }}", Pal.TRACK, Pal.INK).apply { setOnClickListener { pickDate(fStart) { fStart = it; form() } } },
                ui.chip("تا: ${fEnd.ifEmpty { "بی‌پایان" }}", Pal.TRACK, Pal.INK).apply { setOnClickListener { pickDate(fEnd) { fEnd = it; form() } } },
                ui.chip("✕ پاک کردن پایان", Pal.TRACK, Pal.RED).apply { setOnClickListener { fEnd = ""; form() } },
            )))
        }

        card.addView(chipRow(listOf(
            ui.chip("🕒 ساعت: ${fTime.ifEmpty { "بدون ساعت" }}", Pal.TRACK, Pal.INK).apply {
                setOnClickListener {
                    val c = Calendar.getInstance()
                    val h = if (fTime.length == 5) fTime.substring(0, 2).toInt() else c.get(Calendar.HOUR_OF_DAY)
                    val mi = if (fTime.length == 5) fTime.substring(3, 5).toInt() else 0
                    TimePickerDialog(this@ParentActivity, { _, hh, mm -> fTime = "%02d:%02d".format(hh, mm); form() }, h, mi, true).show()
                }
            },
            ui.chip("✕ بدون ساعت", Pal.TRACK, Pal.RED).apply { setOnClickListener { fTime = ""; form() } },
        )))

        val pts = input("امتیاز پاداش", fPoints, true); ePoints = pts
        val pen = input("جریمه اگر انجام نشد (امتیاز)", fPenalty, true); ePenalty = pen
        card.addView(ui.text("امتیاز پاداش", 13f, Pal.MUTED)); card.addView(pts)
        card.addView(ui.text("جریمه اگر انجام نشد (فقط با «جریمه‌ی خودکار» روشن)", 13f, Pal.MUTED)); card.addView(pen)

        val photo = CheckBox(this).apply { text = "نیاز به عکس (نمره‌دهی هوش مصنوعی)"; isChecked = fPhoto; setOnCheckedChangeListener { _, on -> fPhoto = on } }
        card.addView(photo)
        card.addView(ui.pill(if (fEdit == null) "ذخیره کار" else "ذخیره تغییرها", Pal.GREEN) { captureForm(); saveTask() })
        if (fEdit != null) card.addView(ui.pill("انصراف", Pal.MUTED) { fEdit = null; tab = "tasks"; render() })
        content.addView(card)
    }

    private fun saveTask() {
        if (fTitle.isBlank()) return toast("عنوان کار را بنویس")
        val s = JSONObject().put("type", fType)
        when (fType) {
            "weekdays" -> { if (fWeek.isEmpty()) return toast("حداقل یک روز هفته را انتخاب کن"); s.put("weekdays", JSONArray(fWeek.sorted())) }
            "monthly" -> { if (fMonth !in 1..31) return toast("روز ماه باید بین ۱ و ۳۱ باشد"); s.put("month_day", fMonth) }
            "interval" -> { if (fEvery < 2) return toast("عدد بزرگ‌تر از ۱ بنویس"); s.put("every_n", fEvery) }
            "once" -> { if (fOnce.isEmpty()) return toast("تاریخ را انتخاب کن"); s.put("date", fOnce) }
        }
        if (fType == "once") {
            s.put("start", JSONObject.NULL); s.put("end", JSONObject.NULL)
        } else {
            s.put("start", if (fStart.isNotEmpty()) fStart else if (fType == "interval") Api.today() else JSONObject.NULL)
            s.put("end", if (fEnd.isNotEmpty()) fEnd else JSONObject.NULL)
        }
        val kind = when (fType) { "weekdays" -> "weekly"; "once" -> "once"; else -> "daily" }
        val row = JSONObject()
            .put("title", fTitle).put("kind", kind).put("schedule", s)
            .put("weekdays", if (fType == "weekdays") JSONArray(fWeek.sorted()) else JSONObject.NULL)
            .put("points", fPoints.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            .put("penalty_points", fPenalty.toIntOrNull()?.coerceAtLeast(0) ?: 0)
            .put("due_time", if (fTime.isNotEmpty()) fTime else JSONObject.NULL)
            .put("needs_photo", fPhoto).put("active", true)
        val editing = fEdit
        async {
            if (editing != null) api.write("PATCH", "tasks?id=eq.$editing", row)
            else api.write("POST", "tasks", row.put("kid_id", kidId))
            runOnUiThread {
                toast("ذخیره شد ✓")
                fEdit = null; fTitle = ""; fWeek.clear(); fType = "daily"; fOnce = ""; fStart = ""; fEnd = ""; fTime = ""; fPoints = "5"; fPenalty = "0"; fPhoto = false
                tab = "tasks"; render()
            }
        }
    }

    // ================= points: balance, where points came from / went, filtered history =================
    private var pFilter = "all"

    /** Same groups as the kid's wallet on the server (ledgerSource). */
    private fun sourceOf(r: JSONObject): String {
        val note = if (r.isNull("note")) "" else r.getString("note")
        val d = r.getInt("delta")
        return when (r.getString("reason")) {
            "task" -> when {
                note.startsWith("دیکته") -> "dictation"
                Regex("^(تمرین معلم|کلاس آزاد|آزمون خروج|جواب روی تخته|یاد دادن)").containsMatchIn(note) -> "tutor"
                else -> "tasks"
            }
            "screen_bonus" -> "bonus"
            "manual" -> if (d >= 0) "gift" else "penalty"
            "penalty" -> "penalty"
            "payout" -> "payout"
            "invest_gift" -> "invest_gift"
            "spend" -> if (r.isNull("category")) "spend" else r.getString("category")
            else -> "other"
        }
    }

    private val SOURCE_LABEL = mapOf(
        "tasks" to "✅ کارها", "dictation" to "✏️ دیکته", "tutor" to "🎓 معلم", "bonus" to "🌟 جایزه‌ی کم‌بازی", "gift" to "🎁 هدیه‌ی دستی",
        "invest_gift" to "📈 هدیه‌ی سرمایه‌گذاری", "penalty" to "⚠️ جریمه", "payout" to "💰 تبدیل به پول",
        "snack" to "🍫 خوراکی", "fun" to "🧸 سرگرمی و اسباب‌بازی", "invest" to "📈 سرمایه‌گذاری", "spend" to "💸 خرج", "other" to "•",
    )

    private fun bar(label: String, v: Int, max: Int, color: Int): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(0, ui.dp(6), 0, 0)
        val r = ui.row()
        r.addView(ui.text(label, 14f, Pal.INK).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        r.addView(ui.text(fa(v), 14f, Pal.INK, true))
        addView(r)
        addView(BarView(this@ParentActivity).apply { fraction = if (max > 0) v / max.toFloat() else 0f; barColor = color; barColor2 = color
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(10)).apply { topMargin = ui.dp(3) } })
    }

    private fun points() {
        content.removeAllViews()
        content.addView(ui.title("🪙 امتیاز"))
        async {
            val bal = api.get("kid_balance?kid_id=eq.$kidId").optJSONObject(0)?.optLong("points", 0) ?: 0L
            val rate = api.get("settings?id=eq.1&select=toman_per_point").optJSONObject(0)?.optDouble("toman_per_point", 1000.0) ?: 1000.0
            val ledger = api.get("points_ledger?kid_id=eq.$kidId&order=created_at.desc&limit=300&select=delta,note,reason,category,amount_toman,ref_id,created_at")
            val rows = (0 until ledger.length()).map { ledger.getJSONObject(it) }
            runOnUiThread {
                content.addView(ui.card().apply {
                    addView(ui.text("موجودی", 14f, Pal.MUTED, true))
                    addView(ui.text(fa(bal), 40f, Pal.PURPLE, true))
                    addView(ui.text("≈ ${fa((bal * rate).toLong())} تومان", 15f, Pal.MUTED))
                })

                // last 30 days at a glance
                val since = java.time.LocalDate.now(TEHRAN).minusDays(30).toString()
                val recent = rows.filter { it.getString("created_at") >= since }
                val earned = recent.filter { it.getInt("delta") > 0 && it.getString("reason") != "spend" }.groupBy { sourceOf(it) }.mapValues { e -> e.value.sumOf { it.getInt("delta") } }
                val spent = recent.filter { it.getString("reason") == "spend" }.groupBy { sourceOf(it) }.mapValues { e -> e.value.sumOf { -it.getInt("delta") } }
                val spentToman = recent.filter { it.getString("reason") == "spend" }.groupBy { sourceOf(it) }.mapValues { e -> e.value.sumOf { it.optLong("amount_toman", 0) } }
                val lost = recent.filter { sourceOf(it) == "penalty" }.sumOf { -it.getInt("delta") }
                content.addView(ui.card().apply {
                    addView(ui.text("۳۰ روز اخیر", 16f, Pal.INK, true))
                    addView(ui.text("📥 کسب‌شده: +${fa(earned.values.sum())}", 15f, Pal.GREEN, true))
                    val mx = earned.values.maxOrNull() ?: 0
                    earned.entries.sortedByDescending { it.value }.forEach { addView(bar(SOURCE_LABEL[it.key] ?: it.key, it.value, mx, Pal.BLUE)) }
                    if (lost > 0) addView(ui.text("⚠️ جریمه‌ها: −${fa(lost)}", 15f, Pal.RED, true).apply { setPadding(0, ui.dp(10), 0, 0) })
                    if (spent.isNotEmpty()) {
                        addView(ui.text("📤 خرج‌های بچه: −${fa(spent.values.sum())} (${fa(spentToman.values.sum())} تومان)", 15f, Pal.ORANGE, true).apply { setPadding(0, ui.dp(10), 0, 0) })
                        val ms = spent.values.maxOrNull() ?: 0
                        spent.entries.sortedByDescending { it.value }.forEach { addView(bar((SOURCE_LABEL[it.key] ?: it.key) + " · ${fa(spentToman[it.key] ?: 0)} تومان", it.value, ms, Pal.ORANGE)) }
                    }
                })

                val manual = ui.card()
                manual.addView(ui.text("امتیاز دستی", 16f, Pal.INK, true))
                val amt = input("مثلاً +۵ یا −۳", "", true)
                val why = input("دلیل (اختیاری)")
                manual.addView(amt); manual.addView(why)
                manual.addView(ui.pill("ثبت", Pal.GREEN) {
                    val d = amt.text.toString().toIntOrNull() ?: return@pill toast("عدد بنویس")
                    async {
                        api.write("POST", "points_ledger", JSONObject().put("kid_id", kidId).put("delta", d)
                            .put("reason", if (d > 0) "manual" else "penalty")
                            .put("note", if (why.text.isBlank()) JSONObject.NULL else why.text.toString()))
                        runOnUiThread { render() }
                    }
                })
                content.addView(manual)
                content.addView(ui.pill("🎁 بستن ماه و تبدیل به پول", Pal.ORANGE) {
                    if (bal <= 0) toast("امتیازی برای بستن نیست") else confirmClose(bal, rate)
                })

                // history, filtered
                content.addView(ui.title("حرکت‌های امتیاز"))
                val filters = listOf("all" to "همه", "earn" to "📥 کسب", "lose" to "⚠️ جریمه", "spend" to "📤 خرج بچه", "payout" to "💰 تبدیل به پول")
                content.addView(chipRow(filters.map { (k, l) -> ui.chip(l, if (pFilter == k) Pal.PURPLE else Pal.TRACK, if (pFilter == k) Color.WHITE else Pal.INK).apply { setOnClickListener { pFilter = k; render() } } }))
                val shown = rows.filter { r ->
                    val src = sourceOf(r)
                    when (pFilter) {
                        "earn" -> r.getInt("delta") > 0 && r.getString("reason") != "spend" && src != "payout"
                        "lose" -> src == "penalty"
                        "spend" -> r.getString("reason") == "spend"
                        "payout" -> src == "payout"
                        else -> r.getString("reason") != "invest_gift"      // the gift is shown on its spend row
                    }
                }.take(60)
                val giftOf = rows.filter { it.getString("reason") == "invest_gift" && !it.isNull("ref_id") }.associate { it.getString("ref_id") to it.getInt("delta") }
                if (shown.isEmpty()) content.addView(ui.text("چیزی نیست.", 15f, Pal.MUTED))
                shown.forEach { r ->
                    val d = r.getInt("delta")
                    val src = sourceOf(r)
                    content.addView(ui.card().apply {
                        addView(ui.row().apply {
                            addView(ui.text((SOURCE_LABEL[src] ?: "") + "  " + (if (r.isNull("note")) "" else r.getString("note")), 15f, Pal.INK).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
                            addView(ui.text((if (d > 0) "+" else "−") + fa(Math.abs(d)), 16f, if (d > 0) Pal.GREEN else Pal.RED, true))
                        })
                        val date = r.getString("created_at").take(10)
                        val extra = if (r.getString("reason") == "spend") " · ${fa(r.optLong("amount_toman", 0))} تومان" + (giftOf[r.optString("ref_id")]?.let { " · هدیه‌ی شما +${fa(it)}" } ?: "") else ""
                        addView(ui.text(fa(date) + extra, 12f, Pal.MUTED))
                        if (r.getString("reason") == "spend" && !r.isNull("ref_id")) addView(ui.pill("↩ برگرداندن این خرج", Pal.MUTED) { undoSpend(r.getString("ref_id")) })
                    })
                }
            }
        }
    }

    private fun undoSpend(ref: String) {
        AlertDialog.Builder(this).setMessage("این خرج برگردانده شود؟ امتیازش (و هدیه‌ی سرمایه‌گذاری‌اش) به حالت قبل برمی‌گردد.")
            .setPositiveButton("برگرداندن") { _, _ ->
                async { api.send("DELETE", "points_ledger?ref_id=eq.$ref&reason=in.(spend,invest_gift)"); runOnUiThread { toast("برگردانده شد"); render() } }
            }.setNegativeButton("انصراف", null).show()
    }

    private fun confirmClose(bal: Long, rate: Double) {
        val spent = input("جایزه چی خریده شد؟")
        AlertDialog.Builder(this).setTitle("بستن ماه")
            .setMessage("${fa(bal)} امتیاز = ${fa((bal * rate).toLong())} تومان. امتیاز صفر می‌شود.")
            .setView(spent)
            .setPositiveButton("بستن ماه") { _, _ ->
                async {
                    val month = Api.today().substring(0, 7)
                    api.write("POST", "month_closings", JSONObject().put("kid_id", kidId).put("month", month)
                        .put("points_earned", bal).put("toman_per_point", rate)
                        .put("spent_on", if (spent.text.isBlank()) JSONObject.NULL else spent.text.toString()))
                    api.write("POST", "points_ledger", JSONObject().put("kid_id", kidId).put("delta", -bal).put("reason", "payout").put("note", "بستن ماه $month"))
                    runOnUiThread { toast("ماه بسته شد ✓"); render() }
                }
            }
            .setNegativeButton("انصراف", null).show()
    }

    // ================= settings =================
    private fun settings() {
        content.removeAllViews()
        content.addView(ui.title("⚙️ تنظیمات"))
        async {
            val rate = api.get("settings?id=eq.1&select=toman_per_point").optJSONObject(0)?.optDouble("toman_per_point", 1000.0) ?: 1000.0
            runOnUiThread {
                val c = ui.card()
                c.addView(ui.text("هر امتیاز چند تومان؟", 16f, Pal.INK, true))
                val r = input("", rate.toLong().toString(), true)
                c.addView(r)
                c.addView(ui.pill("ذخیره ضریب", Pal.PURPLE) {
                    val v = r.text.toString().toDoubleOrNull() ?: return@pill toast("عدد بنویس")
                    async { api.send("PATCH", "settings?id=eq.1", JSONObject().put("toman_per_point", v)); runOnUiThread { toast("ذخیره شد ✓") } }
                })
                content.addView(c)

                currentKid()?.let { kid ->
                    val a = ui.card()
                    a.addView(ui.text("جریمه‌ی خودکار ${kid.getString("name")}", 16f, Pal.INK, true))
                    a.addView(ui.text("اگر کاری که جریمه دارد دیروز انجام نشده، امتیاز جریمه‌اش کم می‌شود.", 13f, Pal.MUTED))
                    a.addView(CheckBox(this).apply {
                        text = "فعال باشد"
                        isChecked = kid.optBoolean("auto_penalty", false)
                        setOnCheckedChangeListener { _, on -> async { api.send("PATCH", "kids?id=eq.$kidId", JSONObject().put("auto_penalty", on)) } }
                    })
                    content.addView(a)

                    val rm = ui.card()
                    rm.addView(ui.text("🔔 یادآوری صوتی ${kid.getString("name")}", 16f, Pal.INK, true))
                    rm.addView(ui.text("اگر کار انجام‌نشده دارد، گوشی‌اش هر ساعت (اول ساعت) می‌گوید: «${kid.getString("name")} جان، کارهات منتظرتن. نمیای؟»", 13f, Pal.MUTED))
                    rm.addView(CheckBox(this).apply {
                        text = "روشن باشد"
                        isChecked = kid.optBoolean("reminder_on", true)
                        setOnCheckedChangeListener { _, on -> async { api.send("PATCH", "kids?id=eq.$kidId", JSONObject().put("reminder_on", on)); kid.put("reminder_on", on) } }
                    })
                    rm.addView(ui.text("ساعت‌های استراحت (بدون یادآوری)، مثلاً 23-10,14-18", 13f, Pal.MUTED))
                    val q = input("23-10,14-18", kid.optString("reminder_quiet", "23-10,14-18"))
                    rm.addView(q)
                    rm.addView(ui.pill("ذخیره‌ی ساعت‌ها", Pal.PURPLE) {
                        val v = q.text.toString().trim()
                        if (!Regex("^(\\d{1,2}-\\d{1,2})(,\\d{1,2}-\\d{1,2})*$").matches(v) && v.isNotEmpty()) return@pill toast("به شکل 23-10,14-18 بنویس")
                        async { api.send("PATCH", "kids?id=eq.$kidId", JSONObject().put("reminder_quiet", v)); kid.put("reminder_quiet", v); runOnUiThread { toast("ذخیره شد ✓") } }
                    })
                    content.addView(rm)
                }

                content.addView(ui.pill("🚪 خروج از حساب", Pal.MUTED) { api.logout(); recreate() })
            }
        }
    }
}
