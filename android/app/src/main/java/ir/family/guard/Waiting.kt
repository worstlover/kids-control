package ir.family.guard

import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView

/** The last button the kid pressed (set by Ui.pill), so a request can show its progress right on that button. */
object Taps {
    @Volatile var view: TextView? = null
    @Volatile var at = 0L
}

/**
 * Shows the kid that an answer really went out and the teacher is working on it:
 * the pressed button turns into "📤 sending…" → "✅ received, thinking… 7s", and a banner at the bottom says the same.
 * Everything goes back when the reply arrives (or the screen is redrawn).
 */
object Waiting {
    private val h = Handler(Looper.getMainLooper())
    private var banner: TextView? = null
    private var btn: TextView? = null
    private var btnText: CharSequence? = null
    private var count = 0
    private var started = 0L
    @Volatile private var sent = false

    private val tick = object : Runnable {
        override fun run() {
            val s = ((System.currentTimeMillis() - started) / 1000).toInt()
            val dots = ".".repeat(s % 3 + 1)
            val line = when {
                !sent -> "📤 دارم می‌فرستم$dots"
                s < 15 -> "✅ رسید! معلم دارد فکر می‌کند$dots  ${fa(s)} ثانیه"
                s < 40 -> "⏳ معلم هنوز دارد فکر می‌کند$dots  ${fa(s)} ثانیه"
                else -> "🐢 امروز معلم شلوغ است، کمی بیشتر صبر کن$dots  ${fa(s)} ثانیه"
            }
            banner?.text = line
            btn?.let { b -> if (b.isAttachedToWindow) b.text = if (!sent) "📤 فرستادن$dots" else "✅ رسید · ${fa(s)} ثانیه$dots" }
            h.postDelayed(this, 500)
        }
    }

    /** Call on the UI thread when a request to the teacher starts. */
    fun begin(a: MainActivity) {
        count++
        if (count > 1) return
        started = System.currentTimeMillis(); sent = false
        val t = Taps.view
        if (t != null && System.currentTimeMillis() - Taps.at < 1500 && t.isAttachedToWindow) {
            btn = t; btnText = t.text; t.isEnabled = false; t.isClickable = false; t.alpha = 0.8f
        }
        Taps.view = null
        banner = TextView(a).apply {
            textSize = 15f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            background = a.ui.shape(Color.argb(235, 40, 44, 70), 18f)
            setPadding(a.ui.dp(16), a.ui.dp(12), a.ui.dp(16), a.ui.dp(12)); elevation = 20f
        }
        try {
            a.addContentView(banner, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM).apply {
                setMargins(a.ui.dp(14), 0, a.ui.dp(14), a.ui.dp(18))
            })
        } catch (e: Exception) { banner = null }
        h.removeCallbacks(tick); h.post(tick)
    }

    /** From any thread: the request body is fully uploaded. */
    fun uploaded() { sent = true }

    /** Call on the UI thread when the reply arrived (or failed). */
    fun end() {
        if (count == 0) return
        count--
        if (count > 0) return
        h.removeCallbacks(tick)
        banner?.let { b -> (b.parent as? ViewGroup)?.removeView(b) }
        banner = null
        btn?.let { b -> if (b.isAttachedToWindow) { b.text = btnText ?: ""; b.isEnabled = true; b.isClickable = true; b.alpha = 1f } }
        btn = null; btnText = null
    }
}

