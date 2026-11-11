import ir.family.guard.*

// Parses a REAL device-sync response (captured from the e2e run) with a real org.json implementation.
fun main(args: Array<String>) {
    val st = StateParser.parse(java.io.File(args[0]).readText()) ?: error("parseState returned null")
    var n = 0
    fun check(name: String, c: Boolean) { if (!c) { println("FAIL: $name"); kotlin.system.exitProcess(1) }; println("  ok ${++n}. $name") }
    check("day + rate", st.day == "2026-11-07" && st.tomanPerPoint == 1000.0)
    check("two kids", st.kids.size == 2)
    val ali = st.kids.first { it.name == "علی" }
    check("kid basics (null pin, limit, balance)", ali.pinHash == null && ali.dailyLimitMin == 90 && ali.balance > 0)
    check("tasks parsed with statuses", ali.tasks.size == 4 && ali.tasks.any { it.status == "approved" && it.aiScore == 18.0 && it.points == 10 } && ali.tasks.any { it.status == "submitted" && it.aiScore == null && it.aiFeedback == null })
    check("rules parsed (null time window, limit)", ali.rules.any { it.pkg == "com.game" && it.dailyLimitMin == 30 && it.allowedFrom == null && !it.locked })
    check("wishlist parsed", ali.wishlist.any { it.title.isNotEmpty() && it.pointsLeft != null })
    check("dictations parsed", ali.dictations.isNotEmpty() && ali.dictations.any { it.status == "ready" && it.phraseCount >= 1 })
    check("dictation done today carries score", ali.dictations.any { it.status == "done" && it.aiScore == 17.0 && it.pointsAwarded == 9 })
    check("graded dictation without AI score has nulls", ali.dictations.any { it.status == "graded" && it.aiScore == null && it.aiFeedback == null })
    check("week chart data parsed", st.weekDays.size == 7 && st.weekDays.last() == "2026-11-07" && st.kids.all { it.weekPoints.size == 7 })
    check("a kid has 7 points on day 5, another none", st.kids.any { it.weekPoints[4] == 7 } && st.kids.any { k -> k.weekPoints.all { it == 0 } })
    check("screen minutes today parsed", st.kids.any { it.screenMinToday == 45 } && st.kids.any { it.screenMinToday == 0 })
    check("garbage -> null", StateParser.parse("{not json") == null && StateParser.parse("{}") == null)
    println("\nSTATE PARSE: ALL $n CHECKS PASSED")
}
