package ir.family.guard

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.VideoView

/** Splits a lesson video/audio into parts of about two minutes. Pure, so it can be tested on a plain JVM. */
object Parts {
    const val TARGET_MS = 120_000

    /** Start times (ms) of each part; one part if the length is unknown (live streams). */
    fun starts(durationMs: Int, target: Int = TARGET_MS): List<Int> {
        if (durationMs <= 0) return listOf(0)
        val n = maxOf(1, Math.round(durationMs.toDouble() / target).toInt())
        return (0 until n).map { (durationMs.toLong() * it / n).toInt() }
    }

    fun endOf(starts: List<Int>, i: Int, durationMs: Int): Int = if (i + 1 < starts.size) starts[i + 1] else durationMs
}

/** Lets the kid pick when a page had several videos/audios; one item plays at once. */
fun MainActivity.playMediaList(items: List<MediaItem>, title: String, pageUrl: String? = null) {
    if (items.isEmpty()) { if (pageUrl != null) playWeb(pageUrl) else toast("فیلم یا صدایی پیدا نشد"); return }
    if (items.size == 1) { playMedia(items[0], title); return }
    var v = 0; var a = 0
    val labels = items.map {
        val name = when (it.type) { "audio" -> "🎧 صدای ${fa(++a)}"; else -> "🎬 فیلم ${fa(++v)}" }
        if (it.title.isNotBlank()) "$name: ${it.title}" else name
    }
    AlertDialog.Builder(this).setTitle("کدام را ببینیم؟").setItems(labels.toTypedArray()) { _, i -> playMedia(items[i], title) }.show()
}

fun MainActivity.playMedia(m: MediaItem, title: String) = if (m.type == "page") playWeb(m.url) else playParts(m.url, m.type == "audio", title)

/**
 * Plays a lesson video or audio part by part (about 2 minutes each): it stops at the end of each part so the
 * kid can repeat it or go on. Remembers the part for next time.
 */
fun MainActivity.playParts(url: String, audio: Boolean, title: String) {
    stopMedia()
    val store = getSharedPreferences("media_parts", Context.MODE_PRIVATE)
    val dlg = Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(18, 22, 36)); setPadding(ui.dp(16), ui.dp(24), ui.dp(16), ui.dp(16)) }
    val head = ui.text(title.ifBlank { if (audio) "صوت درس" else "فیلم درس" }, 19f, Color.WHITE, true)
    val partLabel = ui.text("در حال آماده شدن… ⏳", 16f, Color.rgb(255, 214, 102), true)
    root.addView(head); root.addView(partLabel)

    val stage = FrameLayout(this)
    val vv = VideoView(this)
    stage.addView(vv, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    if (audio) stage.addView(TextView(this).apply { text = "🎧"; textSize = 90f; gravity = Gravity.CENTER }, FrameLayout.LayoutParams(-1, -1, Gravity.CENTER))
    root.addView(stage, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = ui.dp(10); bottomMargin = ui.dp(10) })

    val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply { max = 1000 }
    root.addView(bar, LinearLayout.LayoutParams(-1, ui.dp(14)))
    val status = ui.text("", 15f, Color.WHITE)
    root.addView(status)

    val h = Handler(Looper.getMainLooper())
    var starts = listOf(0); var dur = 0; var part = 0; var ready = false; var stoppedAtEnd = false

    fun showPart() {
        partLabel.text = if (starts.size > 1) "بخش ${fa(part + 1)} از ${fa(starts.size)}" else if (audio) "صوت درس" else "فیلم درس"
    }
    fun go(i: Int) {
        if (!ready) return
        part = i.coerceIn(0, starts.size - 1); stoppedAtEnd = false
        store.edit().putInt(url, part).apply()
        showPart(); status.text = "▶ در حال پخش…"
        vv.seekTo(starts[part]); vv.start()
    }
    val tick = object : Runnable {
        override fun run() {
            if (ready && dur > 0) {
                val pos = vv.currentPosition
                val s = starts[part]; val e = Parts.endOf(starts, part, dur)
                bar.progress = if (e > s) ((pos - s).coerceAtLeast(0) * 1000L / (e - s)).toInt().coerceIn(0, 1000) else 0
                if (vv.isPlaying && starts.size > 1 && pos >= e - 250 && part + 1 < starts.size) {
                    vv.pause(); stoppedAtEnd = true
                    status.text = "بخش ${fa(part + 1)} تمام شد 👏\nدوباره ببین یا برو بخش بعد."
                }
            }
            h.postDelayed(this, 250)
        }
    }

    val row1 = ui.row()
    fun btn(label: String, color: Int, f: () -> Unit) = ui.pill(label, color, f).apply {
        layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(4); marginEnd = ui.dp(4); topMargin = ui.dp(8) }
    }
    row1.addView(btn("⏮ قبلی", Pal.MUTED) { go(part - 1) })
    row1.addView(btn("⏯", Pal.BLUE) {
        if (!ready) return@btn
        if (vv.isPlaying) { vv.pause(); status.text = "⏸ ایست" }
        else if (stoppedAtEnd) go(part + 1)
        else { vv.start(); status.text = "▶ در حال پخش…" }
    })
    row1.addView(btn("بعدی ⏭", Pal.GREEN) { go(part + 1) })
    root.addView(row1)
    root.addView(ui.pill("🔁 دوباره همین بخش", Pal.ORANGE) { go(part) })
    root.addView(ui.pill("✕ بستن", Pal.RED) { dlg.dismiss() })

    vv.setOnPreparedListener { mp ->
        dur = mp.duration.takeIf { it > 0 } ?: 0
        starts = Parts.starts(dur)
        ready = true
        val saved = store.getInt(url, 0).coerceIn(0, starts.size - 1)
        go(saved)
        if (saved > 0) status.text = "▶ از بخش ${fa(saved + 1)} ادامه می‌دهیم (دفعه‌ی قبل اینجا بودی)"
    }
    vv.setOnCompletionListener {
        stoppedAtEnd = false
        store.edit().remove(url).apply()
        status.text = "آفرین! تا آخر دیدی 🎉"
        bar.progress = 1000
    }
    vv.setOnErrorListener { _, _, _ ->
        status.text = "پخش نشد 😕 اینترنت را چک کن و دوباره امتحان کن."
        partLabel.text = ""
        true
    }
    vv.setVideoURI(android.net.Uri.parse(url))
    h.post(tick)
    dlg.setContentView(root)
    dlg.setOnDismissListener { h.removeCallbacksAndMessages(null); try { vv.stopPlayback() } catch (e: Exception) {} }
    dlg.show()
}
