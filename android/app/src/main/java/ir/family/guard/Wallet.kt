package ir.family.guard

import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONObject
import kotlin.concurrent.thread
import kotlin.math.ceil
import kotlin.math.roundToLong

/** The kid's wallet, kept between redraws: the last summary from the server and what is being typed on the spend screen. */
object WalletState {
    val summary = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, JSONObject>>()   // kidId -> (fetched at, summary)
    @Volatile var loading = false
    @Volatile var month = true                     // show the last 30 days (true) or everything (false)
    @Volatile var cat = "snack"
    @Volatile var title = ""
    @Volatile var amount = ""
}

/** Where points come from, in the kid's words. Keys are the server's ledger sources. */
private val EARN_LABELS = linkedMapOf(
    "tasks" to "✅ کارهای روزانه", "dictation" to "✏️ دیکته", "tutor" to "🎓 معلم و تمرین‌ها",
    "bonus" to "🌟 جایزه‌ی کم‌بازی", "gift" to "🎁 هدیه‌ی پدر و مادر", "invest_gift" to "📈 هدیه‌ی ۲۰٪ سرمایه‌گذاری",
)
private val SPEND_LABELS = linkedMapOf("snack" to "🍫 خوراکی", "fun" to "🧸 سرگرمی و اسباب‌بازی", "invest" to "📈 سرمایه‌گذاری")
private val SPEND_COLORS = mapOf("snack" to Pal.ORANGE, "fun" to Pal.PINK, "invest" to Pal.GREEN)

/** Points tab: balance, where the points came from, where they went, recent spends, and the spend button. */
fun MainActivity.walletSection(st: State, kid: Kid) {
    add(ui.title("🪙 کیف امتیاز من"))
    val holder = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    add(holder)
    val cached = WalletState.summary[kid.id]
    if (cached != null) fillWallet(holder, st, kid, cached.second)
    else holder.addView(ui.card().apply { addView(ui.text("⏳ دارم حساب امتیازهات رو می‌آرم…", 15f, Pal.MUTED)) })
    // Refresh in the background at most once a minute; the result is drawn into this holder only (no full redraw, so no loop).
    if ((cached == null || System.currentTimeMillis() - cached.first > 60_000) && !WalletState.loading) {
        WalletState.loading = true
        thread {
            val (code, r) = try { Sync.wallet(prefs, JSONObject().put("action", "summary").put("kid_id", kid.id)) } catch (e: Exception) { Pair(-1, null) }
            runOnUiThread {
                WalletState.loading = false
                if (isFinishing || isDestroyed) return@runOnUiThread
                if (code == 200 && r != null) {
                    WalletState.summary[kid.id] = Pair(System.currentTimeMillis(), r)
                    if (holder.isAttachedToWindow) try { fillWallet(holder, st, kid, r) } catch (e: Exception) { android.util.Log.e("wallet", "summary shape", e) }
                } else if (cached == null && holder.isAttachedToWindow) {
                    holder.removeAllViews()
                    holder.addView(ui.card().apply { addView(ui.text("حساب امتیازها الان نیامد؛ اینترنت را چک کن.", 15f, Pal.MUTED)) })
                }
            }
        }
    }
}

private fun MainActivity.barRow(label: String, value: Long, max: Long, color: Int, right: String): View {
    val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = ui.dp(8) } }
    val top = ui.row()
    top.addView(ui.text(label, 15f, Pal.INK).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
    top.addView(ui.text(right, 15f, Pal.INK, true))
    box.addView(top)
    box.addView(BarView(this).apply {
        fraction = if (max > 0) (value.toFloat() / max).coerceIn(0f, 1f) else 0f; barColor = color; barColor2 = color
        layoutParams = LinearLayout.LayoutParams(-1, ui.dp(12)).apply { topMargin = ui.dp(4) }
    })
    return box
}

private fun MainActivity.fillWallet(holder: LinearLayout, st: State, kid: Kid, r: JSONObject) {
    holder.removeAllViews()
    val rate = r.optDouble("toman_per_point", st.tomanPerPoint)
    val balance = r.optLong("balance", kid.balance)

    // ---- balance + spend button ----
    holder.addView(ui.card().apply {
        val top = ui.row()
        top.addView(ui.text("🪙 ${fa(balance)}", 30f, Pal.PURPLE, true).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
        top.addView(ui.text("≈ ${fa((balance * rate).roundToLong())} تومان", 15f, Pal.MUTED))
        addView(top)
        addView(ui.text("هر ${fa(rate.roundToLong())} تومان = یک امتیاز", 13f, Pal.MUTED))
        addView(ui.pill("💸 خرج کردن امتیاز", Pal.GREEN) { wishView = { spendScreen(kid) }; render() })
    })

    // ---- period switch ----
    val sw = ui.row()
    sw.addView(ui.pill("۳۰ روز اخیر", if (WalletState.month) Pal.PURPLE else Pal.MUTED) { WalletState.month = true; fillWallet(holder, st, kid, r) }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
    sw.addView(ui.pill("از اول تا حالا", if (!WalletState.month) Pal.PURPLE else Pal.MUTED) { WalletState.month = false; fillWallet(holder, st, kid, r) }
        .apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) } })
    holder.addView(sw)
    val src = if (WalletState.month) r.optJSONObject("month") ?: r else r

    // ---- where the points came from ----
    val earned = src.optJSONObject("earned") ?: JSONObject()
    val earnRows = EARN_LABELS.mapNotNull { (k, label) -> earned.optLong(k, 0).takeIf { it > 0 }?.let { Triple(k, label, it) } }
    val earnTotal = earnRows.sumOf { it.third }
    holder.addView(ui.card().apply {
        addView(ui.text("📥 از کجا امتیاز گرفتم؟", 18f, Pal.INK, true))
        if (earnRows.isEmpty()) addView(ui.text("هنوز امتیازی نگرفتی. کارهات رو انجام بده! 💪", 15f, Pal.MUTED))
        else {
            addView(ui.text("جمعاً ${fa(earnTotal)} امتیاز", 14f, Pal.MUTED))
            val max = earnRows.maxOf { it.third }
            earnRows.sortedByDescending { it.third }.forEach { (_, label, v) -> addView(barRow(label, v, max, Pal.BLUE, "+${fa(v)}")) }
        }
        val pen = src.optLong("penalties", 0)
        if (pen > 0) addView(ui.text("⚠️ جریمه‌ها: −${fa(pen)} امتیاز", 15f, Pal.RED, true).apply { setPadding(0, ui.dp(10), 0, 0) })
    })

    // ---- where the points went ----
    val spent = src.optJSONObject("spent") ?: JSONObject()
    val spentToman = src.optJSONObject("spent_toman") ?: JSONObject()
    val spendRows = SPEND_LABELS.mapNotNull { (k, label) -> spent.optLong(k, 0).takeIf { it > 0 }?.let { Triple(k, label, it) } }
    holder.addView(ui.card().apply {
        addView(ui.text("📤 امتیازهام کجا رفت؟", 18f, Pal.INK, true))
        if (spendRows.isEmpty()) addView(ui.text("هنوز چیزی خرج نکردی.", 15f, Pal.MUTED))
        else {
            val total = spendRows.sumOf { it.third }
            addView(ui.text("جمعاً ${fa(total)} امتیاز", 14f, Pal.MUTED))
            // one stacked bar: the share of each kind at a glance
            val stack = LinearLayout(this@fillWallet).apply { orientation = LinearLayout.HORIZONTAL; layoutParams = LinearLayout.LayoutParams(-1, ui.dp(18)).apply { topMargin = ui.dp(8) } }
            spendRows.forEach { (k, _, v) -> stack.addView(View(this@fillWallet).apply { setBackgroundColor(SPEND_COLORS[k] ?: Pal.MUTED); layoutParams = LinearLayout.LayoutParams(0, -1, v.toFloat()) }) }
            addView(stack)
            val max = spendRows.maxOf { it.third }
            spendRows.forEach { (k, label, v) ->
                val pct = (v * 100.0 / total).roundToLong()
                addView(barRow(label, v, max, SPEND_COLORS[k] ?: Pal.MUTED, "${fa(v)} امتیاز · ${fa(pct)}٪"))
                val t = spentToman.optLong(k, 0)
                if (t > 0) addView(ui.text("${fa(t)} تومان", 13f, Pal.MUTED))
            }
            val gift = earned.optLong("invest_gift", 0)
            if (gift > 0) addView(ui.text("🎁 برای سرمایه‌گذاری‌هات ${fa(gift)} امتیاز هدیه گرفتی", 14f, Pal.GREEN, true).apply { setPadding(0, ui.dp(8), 0, 0) })
        }
    })

    // ---- recent spends ----
    val spends = r.optJSONArray("spends")
    if (spends != null && spends.length() > 0) holder.addView(ui.card().apply {
        addView(ui.text("🧾 خرج‌های اخیر", 18f, Pal.INK, true))
        for (i in 0 until minOf(6, spends.length())) {
            val o = spends.optJSONObject(i) ?: continue
            val cat = o.optString("category")
            val gift = o.optLong("gift", 0)
            val line = "${SPEND_LABELS[cat] ?: "💸"} · ${o.optString("title")}"
            val row = ui.row().apply { setPadding(0, ui.dp(6), 0, 0) }
            row.addView(ui.text(line, 15f, Pal.INK).apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f) })
            row.addView(ui.text("−${fa(o.optLong("points"))}" + if (gift > 0) " (+${fa(gift)} هدیه)" else "", 15f, Pal.RED, true))
            addView(row)
            if (!o.isNull("amount_toman")) addView(ui.text("${fa(o.optLong("amount_toman"))} تومان", 12f, Pal.MUTED))
        }
    })
}

/** How much a spend costs, the same way the server counts it (rounded up; investment: parents add 20%). */
private fun cost(amountToman: Long, rate: Double, cat: String): Triple<Long, Long, Long> {
    val r = if (rate > 0) rate else 1000.0
    val points = maxOf(1L, ceil(amountToman / r).toLong())
    val gift = if (cat == "invest") (points * 0.2).roundToLong() else 0L
    return Triple(points, gift, points - gift)
}

/** Spend screen: what kind, for what, how much (toman); shows the points before confirming. */
fun MainActivity.spendScreen(kid: Kid) {
    box.removeAllViews()
    add(ui.pill("← امتیازها", Pal.MUTED) { wishView = null; kidTab = "points"; render() })
    add(ui.text("💸 خرج کردن امتیاز", 24f, Pal.INK, true))
    val st = Sync.loadState(prefs)
    val rate = WalletState.summary[kid.id]?.second?.optDouble("toman_per_point") ?: st?.tomanPerPoint ?: 1000.0
    val balance = WalletState.summary[kid.id]?.second?.optLong("balance") ?: kid.balance
    add(ui.text("الان ${fa(balance)} امتیاز داری · هر ${fa(rate.roundToLong())} تومان = یک امتیاز", 15f, Pal.MUTED))

    add(ui.title("برای چی می‌خوای خرج کنی؟"))
    SPEND_LABELS.forEach { (k, label) ->
        val sel = WalletState.cat == k
        add(ui.pill((if (sel) "● " else "○ ") + label, if (sel) (SPEND_COLORS[k] ?: Pal.PURPLE) else Pal.MUTED) { WalletState.cat = k; spendScreen(kid) })
    }
    if (WalletState.cat == "invest") add(ui.card().apply {
        addView(ui.text("📈 سرمایه‌گذاری یعنی پولت رو برای بعد نگه داری یا چیزی بخری که ارزشش می‌مونه (مثل حساب پس‌انداز یا سکه).", 14f, Pal.INK))
        addView(ui.text("🎁 پدر و مادرت ۲۰٪ هدیه می‌دن؛ پس از امتیازت فقط ۸۰٪ کم می‌شه.", 14f, Pal.GREEN, true))
    })

    val c = ui.card()
    val what = field(if (WalletState.cat == "invest") "مثلاً: پس‌انداز برای دوچرخه" else "مثلاً: بستنی، کارت بازی", value = WalletState.title)
    val amount = field("چقدر؟ (تومان)", value = WalletState.amount).apply { inputType = InputType.TYPE_CLASS_NUMBER }
    val preview = ui.text("", 16f, Pal.PURPLE, true)
    c.addView(what); c.addView(amount); c.addView(preview)
    add(c)

    fun parsedAmount(): Long = amount.text.toString().filter { it.isDigit() || it in '۰'..'۹' }
        .map { if (it in '۰'..'۹') '0' + (it - '۰') else it }.joinToString("").toLongOrNull() ?: 0L
    fun update() {
        WalletState.title = what.text.toString(); WalletState.amount = amount.text.toString()
        val a = parsedAmount()
        preview.text = if (a <= 0) "مبلغ رو بنویس تا ببینی چند امتیاز می‌شه" else {
            val (pts, gift, net) = cost(a, rate, WalletState.cat)
            val after = balance - net
            (if (gift > 0) "${fa(pts)} امتیاز − ${fa(gift)} هدیه = ${fa(net)} امتیاز از تو کم می‌شه" else "${fa(pts)} امتیاز از تو کم می‌شه") +
                "\n" + if (after >= 0) "بعدش ${fa(after)} امتیاز برات می‌مونه" else "⚠️ ${fa(-after)} امتیاز کم داری"
        }
    }
    val watcher = object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}
        override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) {}
        override fun afterTextChanged(s: android.text.Editable?) { update() }
    }
    what.addTextChangedListener(watcher); amount.addTextChangedListener(watcher)
    update()

    add(ui.pill("✅ ثبت خرج", Pal.GREEN) {
        val t = what.text.toString().trim()
        val a = parsedAmount()
        if (t.isEmpty()) return@pill toast("بنویس برای چی خرج می‌کنی")
        if (a < 100) return@pill toast("مبلغ رو به تومان بنویس")
        val (pts, gift, net) = cost(a, rate, WalletState.cat)
        if (net > balance) return@pill toast("امتیازت کافی نیست 🙂")
        val msg = "${SPEND_LABELS[WalletState.cat]}: $t\n${fa(a)} تومان = ${fa(pts)} امتیاز" + (if (gift > 0) "\n🎁 هدیه‌ی ۲۰٪: +${fa(gift)}" else "") + "\nاز امتیازت کم می‌شه: ${fa(net)}"
        AlertDialog.Builder(this).setTitle("مطمئنی؟").setMessage(msg)
            .setPositiveButton("بله، ثبت کن") { _, _ -> doSpend(kid, t, a) }
            .setNegativeButton("نه", null).show()
    })
    add(ui.text("خرجت همون لحظه توی پنل پدر و مادرت هم دیده می‌شه.", 13f, Pal.MUTED))
}

private fun MainActivity.doSpend(kid: Kid, title: String, amount: Long) {
    val body = JSONObject().put("action", "spend").put("kid_id", kid.id).put("category", WalletState.cat).put("title", title).put("amount_toman", amount)
    thread {
        val (code, r) = try { Sync.wallet(prefs, body) } catch (e: Exception) { Pair(-1, null) }
        runOnUiThread {
            when {
                code == 200 && r != null -> {
                    WalletState.title = ""; WalletState.amount = ""; WalletState.summary.remove(kid.id)
                    val gift = r.optLong("gift")
                    AlertDialog.Builder(this).setTitle("ثبت شد 🎉")
                        .setMessage("${fa(r.optLong("net"))} امتیاز کم شد" + (if (gift > 0) " (با ${fa(gift)} امتیاز هدیه)" else "") + ".\nالان ${fa(r.optLong("balance"))} امتیاز داری.")
                        .setPositiveButton("باشه", null).show()
                    wishView = null; kidTab = "points"; render(); refresh()
                }
                code == 409 -> toast("امتیازت کافی نیست 🙂")
                code == 429 -> toast("امروز خیلی خرج کردی؛ فردا دوباره")
                else -> toast("ثبت نشد؛ اینترنت را چک کن و دوباره امتحان کن")
            }
        }
    }
}
