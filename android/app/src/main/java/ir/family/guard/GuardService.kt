package ir.family.guard

import android.accessibilityservice.AccessibilityService
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.WindowManager
import android.widget.TextView
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.view.KeyEvent
import android.view.accessibility.AccessibilityWindowInfo
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Watches which app is in front. If the active kid is not allowed to use it
 * (not on the list, locked, time is up, tasks not done ...) it sends the phone to the home screen.
 * It also counts the time each kid spends in each app and syncs with the server every minute.
 */
class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private lateinit var prefs: Prefs
    private var cur: String? = null
    private var pip: String? = null          // an app playing in picture-in-picture (a small floating video)
    private var lastTick = 0L
    private var lastSync = 0L
    private var lastToast = 0L
    private var chip: TextView? = null
    private var stateStr = ""
    private var stateObj: State? = null

    /** Parsed state, re-parsed only when the stored JSON changes (we read it every second). */
    private fun state(): State? {
        val s = prefs.stateJson
        if (s != stateStr) { stateStr = s; stateObj = if (s.isEmpty()) null else StateParser.parse(s) }
        return stateObj
    }

    private val secTicker = object : Runnable {
        override fun run() {
            try { lookAtScreen() } catch (e: Exception) { Log.e("Guard", "screen", e) }
            try { updateChip() } catch (e: Exception) { Log.e("Guard", "chip", e) }
            h.postDelayed(this, 1000)
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            try { onTick() } catch (e: Exception) { Log.e("Guard", "tick", e) }
            h.postDelayed(this, 5000)
        }
    }

    override fun onServiceConnected() {
        prefs = Prefs(this)
        lastTick = SystemClock.elapsedRealtime()
        h.removeCallbacks(ticker); h.post(ticker)
        h.removeCallbacks(secTicker); h.post(secTicker)
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !this::prefs.isInitialized) return
        val pkg = e.packageName?.toString() ?: return
        // The notification shade and the keyboard pop up over the real app; they don't change what the kid is using.
        if (pkg in Env.overlays(this)) return
        cur = pkg
        enforce(pkg)
    }

    /**
     * What is really on screen, read every second from the window list. The "window changed" events alone are not
     * enough: after a system dialog (e.g. "open with…") or an ad closes over a stream, the phone often sends no new
     * event, so the guard kept thinking the dialog was in front — the countdown vanished and the time was not counted.
     */
    private fun lookAtScreen() {
        if (!prefs.configured) return
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (!screenOn) return
        val skip = Env.overlays(this)
        var front: String? = null; var floating: String? = null
        try {
            for (w in windows) {
                if (w.type != AccessibilityWindowInfo.TYPE_APPLICATION) continue
                val p = try { w.root?.packageName?.toString() } catch (e: Exception) { null } ?: continue
                if (p in skip) continue
                if (Build.VERSION.SDK_INT >= 26 && w.isInPictureInPictureMode) { if (floating == null) floating = p; continue }
                if (front == null && (w.isFocused || w.isActive)) front = p
            }
        } catch (e: Exception) {}
        if (front == null) front = try { rootInActiveWindow?.packageName?.toString()?.takeIf { it !in skip } } catch (e: Exception) { null }
        pip = floating?.takeIf { it != front }
        if (front != null && front != cur) { cur = front; enforce(front) }
        pip?.let { p -> if (!Policy.decide(p, Env.buildCtx(this, prefs, state())).allowed) pausePlayback() }   // a floating video can't be sent "home"
    }

    /** Pauses whatever video/music is playing (also in picture-in-picture), so a blocked stream does not keep going. */
    private fun pausePlayback() {
        try {
            val am = getSystemService(AUDIO_SERVICE) as AudioManager
            if (!am.isMusicActive) return
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PAUSE))
            am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_MEDIA_PAUSE))
        } catch (e: Exception) {}
    }

    private fun enforce(pkg: String) {
        if (!prefs.configured) return
        val dec = Policy.decide(pkg, Env.buildCtx(this, prefs, state()))
        if (!dec.allowed) {
            pausePlayback()                          // streams often keep playing in a floating window after "home"
            performGlobalAction(GLOBAL_ACTION_HOME)
            val now = SystemClock.elapsedRealtime()
            if (now - lastToast > 3000) { lastToast = now; Toast.makeText(this, dec.reason.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastTick) / 1000).coerceIn(0, 30); lastTick = now
        if (!prefs.configured) return
        val st = state()
        val pkg = cur
        val kid = st?.kids?.firstOrNull { it.id == prefs.activeKid }
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (kid != null && screenOn) {
            val c = Env.buildCtx(this, prefs, st)
            // the app in front, and a stream still playing in a floating window, both use up time
            for (p in listOfNotNull(pkg, pip).distinct()) {
                if (countable(p, kid, c) && Policy.decide(p, c).allowed) prefs.addUsage(Sync.today(), kid.id, p, dt)
            }
        }
        if (pkg != null && screenOn) enforce(pkg)   // re-check: the limit may have just been reached
        try { Reminder.maybeRemind(applicationContext, prefs, st) } catch (e: Exception) { Log.e("Guard", "reminder", e) }
        if (now - lastSync > 60_000) {
            lastSync = now
            io.execute { try { Sync.syncNow(applicationContext) } catch (e: Exception) { Log.e("Guard", "sync", e) } }
        }
    }

    /** Time in this app is counted (same test for counting and for showing the countdown, so they always agree). */
    private fun countable(pkg: String, kid: Kid, c: Policy.Ctx): Boolean =
        pkg != packageName && pkg !in c.essential && !c.parentUnlocked && !c.grace && kid.rules.any { it.pkg == pkg && !it.alwaysOn }

    /**
     * Live countdown floating over the app the kid is using (or over a floating video), so the remaining time is
     * always visible while time is being counted.
     */
    private fun updateChip() {
        if (!prefs.configured) return hideChip()
        val st = state()
        val kid = st?.kids?.firstOrNull { it.id == prefs.activeKid }
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (kid == null || !screenOn) return hideChip()
        val c = Env.buildCtx(this, prefs, st)
        val shown = { p: String? -> p != null && countable(p, kid, c) && kid.rules.firstOrNull { it.pkg == p }?.category != "edu" }
        val pkg = listOf(cur, pip).firstOrNull { shown(it) } ?: return hideChip()
        val rule = kid.rules.firstOrNull { it.pkg == pkg } ?: return hideChip()
        val unsaved = ((SystemClock.elapsedRealtime() - lastTick) / 1000).coerceIn(0, 5)   // seconds not yet written to usage
        val counted = kid.rules.filter { it.category != "edu" && !it.alwaysOn }.sumOf { c.usageSec[it.pkg] ?: 0L } + unsaved
        var left = (kid.dailyLimitMin + c.extraMin) * 60L - counted
        rule.dailyLimitMin?.let { left = minOf(left, (it + c.extraMin) * 60L - ((c.usageSec[pkg] ?: 0L) + unsaved)) }
        left = left.coerceAtLeast(0)
        val col = when { left > 600 -> "#22C55E"; left > 180 -> "#FF9F1C"; else -> "#EF4444" }
        showChip("⏳ " + fa("%02d:%02d".format(left / 60, left % 60)), Color.parseColor(col))
        if (left <= 0) enforce(pkg)
    }

    private fun showChip(text: String, bg: Int) {
        val d = resources.displayMetrics.density
        if (chip == null) {
            val t = TextView(this).apply {
                textSize = 16f; setTextColor(Color.WHITE); setTypeface(typeface, Typeface.BOLD)
                setPadding((12 * d).toInt(), (6 * d).toInt(), (12 * d).toInt(), (6 * d).toInt())
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply { gravity = Gravity.TOP or Gravity.END; x = (10 * d).toInt(); y = (52 * d).toInt() }
            try { (getSystemService(WINDOW_SERVICE) as WindowManager).addView(t, lp); chip = t } catch (e: Exception) { Log.e("Guard", "overlay", e); return }
        }
        chip?.text = text
        chip?.background = GradientDrawable().apply { setColor(bg); cornerRadius = 18 * d; alpha = 235 }
    }

    private fun hideChip() {
        chip?.let { try { (getSystemService(WINDOW_SERVICE) as WindowManager).removeView(it) } catch (e: Exception) {} }
        chip = null
    }

    override fun onInterrupt() {}
    override fun onDestroy() { hideChip(); h.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
}
