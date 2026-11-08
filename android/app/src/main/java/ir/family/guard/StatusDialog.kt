package ir.family.guard

import android.animation.ObjectAnimator
import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.view.Window
import android.animation.ValueAnimator
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Friendly progress card for AI work. The steps are REAL: preparing the photo, uploading it
 * (with a true percentage), then waiting for the AI's answer (with a seconds counter).
 */
class StatusDialog(private val act: Activity) {
    companion object { const val PENDING = 0; const val ACTIVE = 1; const val DONE = 2 }

    private val ui = Ui(act)
    private val dlg = Dialog(act)
    private val box = LinearLayout(act).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL; layoutDirection = android.view.View.LAYOUT_DIRECTION_RTL
        background = ui.shape(Color.WHITE, 28f); setPadding(ui.dp(22), ui.dp(24), ui.dp(22), ui.dp(22))
    }
    private val h = Handler(Looper.getMainLooper())
    private var bounce: ObjectAnimator? = null
    private var stepViews = listOf<TextView>()
    private var labels = listOf<String>()
    private var bar: BarView? = null
    private var waitStart = 0L
    private var waitStep = -1
    private val ticker = object : Runnable {
        override fun run() {
            if (waitStep in stepViews.indices) {
                val s = (System.currentTimeMillis() - waitStart) / 1000
                stepViews[waitStep].text = "⏳ ${labels[waitStep]}…  ${fa(s)} ثانیه"
            }
            h.postDelayed(this, 1000)
        }
    }

    init {
        dlg.requestWindowFeature(Window.FEATURE_NO_TITLE)
        dlg.setContentView(box); dlg.setCancelable(false)
        dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        dlg.window?.setLayout((act.resources.displayMetrics.widthPixels * 0.9).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    private fun head(emoji: String, title: String, animate: Boolean) {
        box.removeAllViews(); bounce?.cancel()
        val e = TextView(act).apply { text = emoji; textSize = 52f; gravity = Gravity.CENTER }
        box.addView(e); box.addView(ui.text(title, 20f, Pal.INK, true).apply { gravity = Gravity.CENTER; textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER })
        if (animate) bounce = ObjectAnimator.ofFloat(e, "translationY", 0f, -ui.dpf(12f), 0f).apply { duration = 900; repeatCount = ValueAnimator.INFINITE; start() }
    }

    fun show() { if (!act.isFinishing) dlg.show() }
    fun dismiss() { h.removeCallbacks(ticker); bounce?.cancel(); bar?.indeterminate = false; try { dlg.dismiss() } catch (e: Exception) {} }

    fun steps(emoji: String, title: String, stepLabels: List<String>) {
        head(emoji, title, true); labels = stepLabels
        box.addView(ui.space(10))
        bar = BarView(act).apply { barColor = Pal.PURPLE; barColor2 = Pal.PINK; layoutParams = LinearLayout.LayoutParams(-1, ui.dp(10)); indeterminate = false }
        box.addView(bar)
        stepViews = stepLabels.map { l -> ui.text("⚪ $l", 16f, Pal.MUTED).also { box.addView(it) } }
    }

    fun setStep(i: Int, state: Int, extra: String = "") {
        val v = stepViews.getOrNull(i) ?: return
        when (state) {
            DONE -> { v.text = "✅ ${labels[i]}"; v.setTextColor(Pal.GREEN) }
            ACTIVE -> { v.text = "⏳ ${labels[i]}… $extra"; v.setTextColor(Pal.INK) }
            else -> { v.text = "⚪ ${labels[i]}"; v.setTextColor(Pal.MUTED) }
        }
    }

    fun uploadProgress(pct: Int) {
        bar?.indeterminate = false; bar?.fraction = pct / 100f; bar?.invalidate()
        setStep(1, ACTIVE, "٪${fa(pct)}")
    }

    /** The photo is with the AI now; we only know it is working, so show a sweep and a seconds counter. */
    fun waitingForAi(stepIndex: Int) {
        setStep(stepIndex - 1, DONE); bar?.indeterminate = true
        waitStep = stepIndex; waitStart = System.currentTimeMillis(); h.removeCallbacks(ticker); h.post(ticker)
    }

    fun result(emoji: String, title: String, body: String, stars: Int?, chip: String?, button: String = "باشه", onClose: () -> Unit = {}) {
        h.removeCallbacks(ticker); waitStep = -1; bar?.indeterminate = false
        head(emoji, title, false)
        if (stars != null) box.addView(ui.text("★".repeat(stars) + "☆".repeat(5 - stars), 34f, Pal.YELLOW, true).apply { textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER })
        if (body.isNotBlank()) box.addView(ui.text(body, 16f).apply { textAlignment = android.view.View.TEXT_ALIGNMENT_CENTER; gravity = Gravity.CENTER; setPadding(0, ui.dp(8), 0, ui.dp(4)) })
        if (chip != null) box.addView(ui.chip(chip, Pal.GREEN).apply { (layoutParams as? LinearLayout.LayoutParams)?.topMargin = ui.dp(8) })
        box.addView(ui.pill(button, Pal.PURPLE) { dismiss(); onClose() })
    }
}
