package ir.family.guard

// Pure Kotlin (no Android imports) so the rules can be unit-checked on a plain JVM.

data class Rule(
    val pkg: String, val locked: Boolean, val category: String, val dailyLimitMin: Int?,
    val allowedFrom: String?, val allowedTo: String?, val lockUntilTasksDone: Boolean,
    val alwaysOn: Boolean = false,   // parent's "always available": ignores locks, hours, limits, tasks; not counted
)
data class TaskItem(
    val logId: String, val title: String, val points: Int, val needsPhoto: Boolean,
    val status: String, val aiScore: Double?, val aiFeedback: String?,
)
data class WishItem(val title: String, val priceToman: Long?, val pointsNeeded: Long?, val pointsLeft: Long?)
data class PenaltyItem(val title: String, val points: Int)
data class DictItem(
    val id: String, val title: String, val status: String, val points: Int, val phraseCount: Int,
    val aiScore: Double?, val aiFeedback: String?, val pointsAwarded: Int?,
)
data class Kid(
    val id: String, val name: String, val pinHash: String?, val dailyLimitMin: Int, val balance: Long,
    val tasks: List<TaskItem>, val wishlist: List<WishItem>, val rules: List<Rule>,
    val dictations: List<DictItem> = emptyList(),
    val weekPoints: List<Int> = emptyList(),   // points earned per day, last 7 days (oldest first)
    val screenMinToday: Int = 0,
    val penalties: List<PenaltyItem> = emptyList(),
)

object Policy {
    enum class Reason(val message: String) {
        OK(""),
        NO_KID("اول وارد شو و اسمت را انتخاب کن"),
        LOCKED_ALL("گوشی الان قفل است"),
        NOT_ALLOWED("این برنامه برای تو مجاز نیست"),
        APP_LOCKED("این برنامه قفل است"),
        OUTSIDE_HOURS("الان وقت این برنامه نیست"),
        APP_LIMIT("وقت این برنامه تمام شد"),
        DAILY_LIMIT("وقت گوشی امروز تمام شد"),
        TASKS_FIRST("اول تکالیف امروزت را انجام بده"),
        PARENT_ONLY("فقط پدر و مادر اجازه دارند"),
    }

    data class Decision(val allowed: Boolean, val reason: Reason)

    class Ctx(
        val ownPkg: String,
        val essential: Set<String>,        // launcher, dialer, keyboard, system UI ...
        val protectedPkgs: (String) -> Boolean, // Settings / installers
        val kid: Kid?,
        val lockAll: Boolean,
        val parentUnlocked: Boolean,
        val grace: Boolean,                // e.g. while the camera is open for a homework photo
        val minuteOfDay: Int,
        val usageSec: Map<String, Long>,   // package -> seconds today, for this kid
        val extraMin: Int,                 // minutes the parent added today
    )

    /** Tasks the kid still has to do today (submitted / graded / approved ones are fine). */
    fun pendingTasks(kid: Kid): Int = kid.tasks.count { it.status == "pending" || it.status == "rejected" }

    fun toMinutes(hhmm: String?): Int? {
        if (hhmm == null) return null
        val p = hhmm.split(":")
        val h = p.getOrNull(0)?.toIntOrNull() ?: return null
        val m = p.getOrNull(1)?.toIntOrNull() ?: 0
        return h * 60 + m
    }

    fun inWindow(from: String?, to: String?, now: Int): Boolean {
        val f = toMinutes(from) ?: return true
        val t = toMinutes(to) ?: return true
        return if (f <= t) now in f until t else (now >= f || now < t)
    }

    fun usedMinutes(sec: Long) = (sec / 60).toInt()

    fun remainingMinutes(kid: Kid, c: Ctx): Int {
        val counted = kid.rules.filter { it.category != "edu" && !it.alwaysOn }.sumOf { c.usageSec[it.pkg] ?: 0L }
        return maxOf(0, kid.dailyLimitMin + c.extraMin - usedMinutes(counted))
    }

    fun decide(pkg: String, c: Ctx): Decision {
        if (pkg == c.ownPkg || pkg in c.essential) return Decision(true, Reason.OK)
        if (c.parentUnlocked) return Decision(true, Reason.OK)
        if (c.protectedPkgs(pkg)) return Decision(false, Reason.PARENT_ONLY)
        val kid = c.kid
        val always = kid?.rules?.firstOrNull { it.pkg == pkg }?.alwaysOn == true
        if (always) return Decision(true, Reason.OK)          // calls, SMS, ... always available
        if (c.grace) return Decision(true, Reason.OK)
        if (c.lockAll) return Decision(false, Reason.LOCKED_ALL)
        if (kid == null) return Decision(false, Reason.NO_KID)
        val r = kid.rules.firstOrNull { it.pkg == pkg } ?: return Decision(false, Reason.NOT_ALLOWED)
        if (r.locked) return Decision(false, Reason.APP_LOCKED)
        if (!inWindow(r.allowedFrom, r.allowedTo, c.minuteOfDay)) return Decision(false, Reason.OUTSIDE_HOURS)
        if (r.lockUntilTasksDone && pendingTasks(kid) > 0) return Decision(false, Reason.TASKS_FIRST)
        val limit = r.dailyLimitMin
        if (limit != null && usedMinutes(c.usageSec[pkg] ?: 0L) >= limit + c.extraMin) return Decision(false, Reason.APP_LIMIT)
        if (r.category != "edu" && remainingMinutes(kid, c) <= 0) return Decision(false, Reason.DAILY_LIMIT)
        return Decision(true, Reason.OK)
    }
}
