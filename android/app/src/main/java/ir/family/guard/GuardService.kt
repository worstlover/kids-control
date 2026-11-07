package ir.family.guard

import android.accessibilityservice.AccessibilityService
import android.os.Handler
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
    private var lastTick = 0L
    private var lastSync = 0L
    private var lastToast = 0L

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
    }

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !this::prefs.isInitialized) return
        val pkg = e.packageName?.toString() ?: return
        // The notification shade and the keyboard pop up over the real app; they don't change what the kid is using.
        if (pkg in Env.overlays(this)) return
        cur = pkg
        enforce(pkg)
    }

    private fun enforce(pkg: String) {
        if (!prefs.configured) return
        val dec = Policy.decide(pkg, Env.buildCtx(this, prefs, Sync.loadState(prefs)))
        if (!dec.allowed) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val now = SystemClock.elapsedRealtime()
            if (now - lastToast > 3000) { lastToast = now; Toast.makeText(this, dec.reason.message, Toast.LENGTH_LONG).show() }
        }
    }

    private fun onTick() {
        val now = SystemClock.elapsedRealtime()
        val dt = ((now - lastTick) / 1000).coerceIn(0, 30); lastTick = now
        if (!prefs.configured) return
        val st = Sync.loadState(prefs)
        val pkg = cur
        val kid = st?.kids?.firstOrNull { it.id == prefs.activeKid }
        val screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive
        if (pkg != null && kid != null && screenOn) {
            val c = Env.buildCtx(this, prefs, st)
            val counted = pkg != packageName && pkg !in c.essential && !c.parentUnlocked && !c.grace && kid.rules.any { it.pkg == pkg }
            if (counted && Policy.decide(pkg, c).allowed) prefs.addUsage(Sync.today(), kid.id, pkg, dt)
        }
        if (pkg != null && screenOn) enforce(pkg)   // re-check: the limit may have just been reached
        if (now - lastSync > 60_000) {
            lastSync = now
            io.execute { try { Sync.syncNow(applicationContext) } catch (e: Exception) { Log.e("Guard", "sync", e) } }
        }
    }

    override fun onInterrupt() {}
    override fun onDestroy() { h.removeCallbacksAndMessages(null); io.shutdown(); super.onDestroy() }
}
