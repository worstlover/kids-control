package ir.family.guard

import android.content.Context
import android.content.Intent
import android.telecom.TelecomManager
import android.view.inputmethod.InputMethodManager
import java.time.LocalTime

object Env {
    private val SYSTEM = setOf(
        "android", "com.android.systemui", "com.google.android.permissioncontroller", "com.android.permissioncontroller",
        "com.google.android.apps.nexuslauncher", "com.android.phone", "com.android.server.telecom", "com.android.emergency",
    )

    /** Packages that must never be blocked: launcher, dialer, keyboards, system UI. */
    fun essential(ctx: Context): Set<String> {
        val s = HashSet(SYSTEM)
        try {
            val home = ctx.packageManager.resolveActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            home?.activityInfo?.packageName?.let { s.add(it) }
        } catch (e: Exception) {}
        try { (ctx.getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage?.let { s.add(it) } } catch (e: Exception) {}
        try {
            (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.forEach { s.add(it.packageName) }
        } catch (e: Exception) {}
        return s
    }

    /** Windows that pop up over the real app (notification shade, keyboard) and must not change "what the kid is using". */
    fun overlays(ctx: Context): Set<String> {
        val s = HashSet<String>(); s.add("com.android.systemui")
        try { (ctx.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList.forEach { s.add(it.packageName) } } catch (e: Exception) {}
        return s
    }

    /** Settings and package installers: only reachable after the parent PIN. */
    fun isProtected(pkg: String): Boolean =
        pkg.startsWith("com.android.settings") || pkg == "com.google.android.packageinstaller" || pkg == "com.android.packageinstaller"

    fun buildCtx(ctx: Context, p: Prefs, st: State?): Policy.Ctx {
        val day = Sync.today()
        val kid = st?.kids?.firstOrNull { it.id == p.activeKid }
        val now = LocalTime.now()
        return Policy.Ctx(
            ownPkg = ctx.packageName, essential = essential(ctx), protectedPkgs = ::isProtected, kid = kid,
            lockAll = p.lockAll, parentUnlocked = p.parentUnlocked, grace = p.inGrace,
            minuteOfDay = now.hour * 60 + now.minute,
            usageSec = if (kid != null) p.usageFor(day, kid.id) else emptyMap(),
            extraMin = p.extraMin(day),
        )
    }
}
