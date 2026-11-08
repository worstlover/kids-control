import ir.family.guard.*
import ir.family.guard.Policy.Reason.*

var n = 0
fun check(name: String, cond: Boolean) { if (!cond) { println("FAIL: $name"); kotlin.system.exitProcess(1) }; println("  ✓ ${++n}. $name") }

fun rule(pkg: String, cat: String = "other", limit: Int? = null, locked: Boolean = false, from: String? = null, to: String? = null, tasks: Boolean = false, always: Boolean = false) =
    Rule(pkg, locked, cat, limit, from, to, tasks, always)
fun task(status: String) = TaskItem("id", "t", 5, false, status, null, null)
fun kid(rules: List<Rule>, tasks: List<TaskItem> = emptyList(), limit: Int = 60) = Kid("k", "علی", null, limit, 0, tasks, emptyList(), rules)
fun ctx(kid: Kid?, usage: Map<String, Long> = emptyMap(), lockAll: Boolean = false, parent: Boolean = false, grace: Boolean = false, minute: Int = 12 * 60, extra: Int = 0) =
    Policy.Ctx("me", setOf("launcher", "systemui"), { it.startsWith("com.android.settings") }, kid, lockAll, parent, grace, minute, usage, extra)
fun d(pkg: String, c: Policy.Ctx) = Policy.decide(pkg, c).reason

fun main() {
    val k = kid(listOf(rule("game", "game", limit = 30), rule("edu", "edu"), rule("chat", locked = true)))
    check("essential/own always allowed, even with no kid", d("launcher", ctx(null)) == OK && d("me", ctx(null)) == OK)
    check("no kid selected -> blocked", d("game", ctx(null)) == NO_KID)
    check("app without rule -> not allowed", d("tiktok", ctx(k)) == NOT_ALLOWED)
    check("allowed app ok", d("game", ctx(k)) == OK)
    check("locked rule blocks", d("chat", ctx(k)) == APP_LOCKED)
    check("lock-all blocks even allowed app, not launcher", d("game", ctx(k, lockAll = true)) == LOCKED_ALL && d("launcher", ctx(k, lockAll = true)) == OK)
    check("settings blocked for kid, even with grace", d("com.android.settings", ctx(k)) == PARENT_ONLY && d("com.android.settings", ctx(k, grace = true)) == PARENT_ONLY)
    check("parent unlock opens everything", d("com.android.settings", ctx(k, parent = true)) == OK && d("tiktok", ctx(null, parent = true)) == OK)
    check("grace (camera) allows unknown app", d("camera", ctx(k, grace = true)) == OK)
    check("per-app limit: 29 min ok, 30 min blocked", d("game", ctx(k, mapOf("game" to 29 * 60L))) == OK && d("game", ctx(k, mapOf("game" to 30 * 60L))) == APP_LIMIT)
    check("parent extra minutes lift the per-app limit", d("game", ctx(k, mapOf("game" to 30 * 60L), extra = 15)) == OK)
    check("daily total limit blocks non-edu", d("game", ctx(kid(listOf(rule("game", "game"), rule("video")), limit = 60), mapOf("video" to 60 * 60L))) == DAILY_LIMIT)
    val k2 = kid(listOf(rule("game", "game"), rule("edu", "edu")), limit = 60)
    check("edu apps neither count nor get blocked by the daily limit", d("edu", ctx(k2, mapOf("game" to 99 * 60L, "edu" to 500 * 60L))) == OK && d("game", ctx(k2, mapOf("edu" to 500 * 60L))) == OK)
    check("remaining minutes", Policy.remainingMinutes(k2, ctx(k2, mapOf("game" to 25 * 60L, "edu" to 900L))) == 35)
    check("remaining never negative", Policy.remainingMinutes(k2, ctx(k2, mapOf("game" to 999 * 60L))) == 0)
    val kt = kid(listOf(rule("game", "game", tasks = true)), tasks = listOf(task("approved"), task("pending")))
    check("lock-until-tasks: pending blocks", d("game", ctx(kt)) == TASKS_FIRST)
    check("submitted/graded/approved count as done", d("game", ctx(kid(listOf(rule("game", tasks = true)), tasks = listOf(task("submitted"), task("graded"), task("approved"))))) == OK)
    check("rejected task counts as pending again", d("game", ctx(kid(listOf(rule("game", tasks = true)), tasks = listOf(task("rejected"))))) == TASKS_FIRST)
    val kw = kid(listOf(rule("game", from = "16:00", to = "18:30")))
    check("time window", d("game", ctx(kw, minute = 15 * 60 + 59)) == OUTSIDE_HOURS && d("game", ctx(kw, minute = 16 * 60)) == OK && d("game", ctx(kw, minute = 18 * 60 + 29)) == OK && d("game", ctx(kw, minute = 18 * 60 + 30)) == OUTSIDE_HOURS)
    val kn = kid(listOf(rule("game", from = "21:00:00", to = "06:30:00")))
    check("overnight window wraps midnight", d("game", ctx(kn, minute = 23 * 60)) == OK && d("game", ctx(kn, minute = 5 * 60)) == OK && d("game", ctx(kn, minute = 12 * 60)) == OUTSIDE_HOURS)
    check("toMinutes parsing", Policy.toMinutes("07:05:00") == 425 && Policy.toMinutes(null) == null && Policy.toMinutes("x") == null)
    // always-available apps (calls, SMS): ignore every restriction, but never the parent-only settings
    val ka = kid(listOf(rule("phone", locked = true, always = true), rule("sms", from = "16:00", to = "17:00", always = true), rule("game", "game", limit = 1)),
        tasks = listOf(task("pending")), limit = 10)
    check("always-on app works while the phone is locked", d("phone", ctx(ka, lockAll = true)) == OK)
    check("always-on app works even if it is also locked by the rule", d("phone", ctx(ka)) == OK)
    check("always-on app works outside its time window", d("sms", ctx(ka, minute = 3 * 60)) == OK)
    check("always-on app works with pending tasks and an exhausted limit", d("sms", ctx(ka, usage = mapOf("game" to 60L * 60), minute = 10 * 60)) == OK)
    check("always-on app time is not counted toward the limit", Policy.remainingMinutes(ka, ctx(ka, usage = mapOf("phone" to 50L * 60))) == 10)
    check("parent-only settings stay blocked even if an app is always-on", d("com.android.settings", ctx(ka, lockAll = true)) == PARENT_ONLY)
    check("other apps are still locked while the phone is locked", d("game", ctx(ka, lockAll = true)) == LOCKED_ALL)
    println("\nPOLICY: ALL $n CHECKS PASSED")
}
