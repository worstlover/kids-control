package ir.family.guard

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

fun fa(s: String): String = buildString { for (ch in s) append(if (ch in '0'..'9') '۰' + (ch - '0') else ch) }
fun fa(n: Number): String = fa(n.toString())

object Pal {
    val BG_TOP = Color.parseColor("#E9E2FF"); val BG_BOT = Color.parseColor("#FFEFF6")
    val PURPLE = Color.parseColor("#7C4DFF"); val PINK = Color.parseColor("#FF5C93"); val ORANGE = Color.parseColor("#FF9F1C")
    val GREEN = Color.parseColor("#22C55E"); val BLUE = Color.parseColor("#3B82F6"); val YELLOW = Color.parseColor("#FFC93C")
    val RED = Color.parseColor("#EF4444"); val INK = Color.parseColor("#2B2D42"); val MUTED = Color.parseColor("#6B7280")
    val TRACK = Color.parseColor("#ECEAF6")
    val KIDS = intArrayOf(PURPLE, ORANGE, BLUE, GREEN, PINK)
    private val AVATARS = arrayOf("🦊", "🐼", "🐯", "🦄", "🐸", "🐵", "🦁", "🐶")
    fun avatar(id: String) = AVATARS[(id.hashCode() and 0x7fffffff) % AVATARS.size]
    fun kidColor(index: Int) = KIDS[index.coerceAtLeast(0) % KIDS.size]
}

/** Small toolkit so the whole screen can be built from code (no XML layouts needed). */
class Ui(val ctx: Context) {
    fun dp(v: Int) = (v * ctx.resources.displayMetrics.density).toInt()
    fun dpf(v: Float) = v * ctx.resources.displayMetrics.density

    fun shape(color: Int, radiusDp: Float): GradientDrawable = GradientDrawable().apply { setColor(color); cornerRadius = dpf(radiusDp) }
    fun oval(color: Int): GradientDrawable = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
    fun gradient(c1: Int, c2: Int, radiusDp: Float, o: GradientDrawable.Orientation = GradientDrawable.Orientation.TL_BR): GradientDrawable =
        GradientDrawable(o, intArrayOf(c1, c2)).apply { cornerRadius = dpf(radiusDp) }

    fun text(s: String, sizeSp: Float = 15f, color: Int = Pal.INK, bold: Boolean = false) = TextView(ctx).apply {
        text = s; textSize = sizeSp; setTextColor(color); if (bold) setTypeface(typeface, Typeface.BOLD)
        textAlignment = View.TEXT_ALIGNMENT_VIEW_START; setPadding(0, dp(2), 0, dp(2))
    }

    fun title(s: String) = text(s, 20f, Pal.INK, true).apply { setPadding(dp(4), dp(18), 0, dp(4)) }

    fun card(): LinearLayout = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL; background = shape(Color.WHITE, 22f); elevation = dpf(3f)
        setPadding(dp(16), dp(14), dp(16), dp(14))
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
    }

    fun row(): LinearLayout = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    /** Big colourful rounded button. */
    fun pill(label: String, color: Int, onClick: () -> Unit) = TextView(ctx).apply {
        text = label; textSize = 17f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
        background = gradient(color, darker(color), 26f, GradientDrawable.Orientation.TOP_BOTTOM); elevation = dpf(3f)
        setPadding(dp(18), dp(13), dp(18), dp(13)); isClickable = true
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) }
        setOnClickListener { onClick() }
    }

    fun chip(label: String, bg: Int, fg: Int = Color.WHITE) = TextView(ctx).apply {
        text = label; textSize = 13f; setTextColor(fg); setTypeface(typeface, Typeface.BOLD); background = shape(bg, 14f); setPadding(dp(10), dp(4), dp(10), dp(4))
    }

    fun space(h: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(-1, dp(h)) }

    fun darker(c: Int): Int { val hsv = FloatArray(3); Color.colorToHSV(c, hsv); hsv[2] *= 0.82f; return Color.HSVToColor(hsv) }
    fun lighter(c: Int, f: Float = 0.25f): Int = Color.rgb(
        (Color.red(c) + (255 - Color.red(c)) * f).toInt(), (Color.green(c) + (255 - Color.green(c)) * f).toInt(), (Color.blue(c) + (255 - Color.blue(c)) * f).toInt())
}

/** Circular countdown: the ring empties as the day's screen time is used up. */
class RingView(c: Context) : View(c) {
    var fraction = 1f; var ringColor = Pal.GREEN; var big = ""; var small = ""
    private val track = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; color = Pal.TRACK }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val tBig = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; typeface = Typeface.DEFAULT_BOLD; color = Pal.INK }
    private val tSmall = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Pal.MUTED }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val s = minOf(w, h); val sw = s * 0.11f
        track.strokeWidth = sw; arc.strokeWidth = sw; arc.color = ringColor
        val r = RectF((w - s) / 2 + sw / 2, (h - s) / 2 + sw / 2, (w + s) / 2 - sw / 2, (h + s) / 2 - sw / 2)
        cv.drawArc(r, 0f, 360f, false, track)
        if (fraction > 0.003f) cv.drawArc(r, -90f, 360f * fraction.coerceIn(0f, 1f), false, arc)
        tBig.textSize = s * 0.27f; tSmall.textSize = s * 0.11f
        val cy = h / 2 + tBig.textSize * 0.12f
        cv.drawText(big, w / 2, cy, tBig); cv.drawText(small, w / 2, cy + tSmall.textSize * 1.6f, tSmall)
    }
}

/** Thin rounded progress bar; can also run as an "in progress" sweep. */
class BarView(c: Context) : View(c) {
    var fraction = 0f; var barColor = Pal.GREEN; var barColor2 = Pal.GREEN
    var indeterminate = false
        set(v) { field = v; if (v) start() else stop(); invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private var phase = 0f
    private var anim: ValueAnimator? = null
    private fun start() { if (anim == null && isAttachedToWindow) anim = ValueAnimator.ofFloat(0f, 1f).apply { duration = 1300; repeatCount = ValueAnimator.INFINITE; addUpdateListener { phase = it.animatedValue as Float; invalidate() }; start() } }
    private fun stop() { anim?.cancel(); anim = null }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); if (indeterminate) start() }
    override fun onDetachedFromWindow() { stop(); super.onDetachedFromWindow() }
    override fun onDraw(cv: Canvas) {
        val w = width.toFloat(); val h = height.toFloat(); val r = h / 2
        p.shader = null; p.color = Pal.TRACK; cv.drawRoundRect(RectF(0f, 0f, w, h), r, r, p)
        val (x0, x1) = if (indeterminate) Pair((w + w * 0.35f) * phase - w * 0.35f, (w + w * 0.35f) * phase) else Pair(0f, w * fraction.coerceIn(0f, 1f))
        val l = maxOf(0f, x0); val rr = minOf(w, x1)
        if (rr - l > 1f) { p.shader = android.graphics.LinearGradient(l, 0f, rr, 0f, barColor, barColor2, android.graphics.Shader.TileMode.CLAMP); cv.drawRoundRect(RectF(l, 0f, rr, h), r, r, p) }
    }
}

/** Grouped bars: one colour per kid, one group per day. */
class BarChartView(c: Context) : View(c) {
    var series: List<List<Int>> = emptyList(); var colors: List<Int> = emptyList(); var labels: List<String> = emptyList()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val t = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER; color = Pal.MUTED }
    override fun onDraw(cv: Canvas) {
        val n = labels.size; if (n == 0 || series.isEmpty()) return
        val d = resources.displayMetrics.density
        val top = 18 * d; val labelH = 26 * d; val chartH = height - top - labelH
        val max = maxOf(1, series.maxOf { it.maxOrNull() ?: 0 })
        val groupW = width.toFloat() / n; val barW = groupW * 0.72f / series.size
        p.color = Pal.TRACK; cv.drawRect(0f, top + chartH, width.toFloat(), top + chartH + 2 * d, p)
        t.textSize = 11 * d
        for (i in 0 until n) {
            for (s in series.indices) {
                val v = series[s].getOrElse(i) { 0 }
                val x0 = i * groupW + groupW * 0.14f + s * barW
                if (v > 0) {
                    val bh = maxOf(8 * d, chartH * v / max)
                    p.color = colors.getOrElse(s) { Pal.PURPLE }
                    cv.drawRoundRect(RectF(x0 + 2 * d, top + chartH - bh, x0 + barW - 2 * d, top + chartH + 6 * d), 7 * d, 7 * d, p)
                    t.color = Pal.INK; cv.drawText(fa(v), x0 + barW / 2, top + chartH - bh - 4 * d, t)
                }
            }
            t.color = if (i == n - 1) Pal.PURPLE else Pal.MUTED; t.typeface = if (i == n - 1) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            cv.drawText(labels[i], i * groupW + groupW / 2, height - 6 * d, t)
        }
    }
}
