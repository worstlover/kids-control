package ir.family.guard

import android.content.Context
import org.json.JSONObject
import java.security.MessageDigest

fun sha256(s: String): String =
    MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("guard", Context.MODE_PRIVATE)

    var token: String
        get() = sp.getString("token", "") ?: ""
        set(v) { sp.edit().putString("token", v).apply() }
    var sbUrl: String
        get() = sp.getString("sbUrl", null) ?: BuildConfig.SB_URL
        set(v) { sp.edit().putString("sbUrl", v.trim().trimEnd('/')).apply() }
    var sbKey: String
        get() = sp.getString("sbKey", null) ?: BuildConfig.SB_KEY
        set(v) { sp.edit().putString("sbKey", v.trim()).apply() }
    var parentPinHash: String
        get() = sp.getString("parentPin", "") ?: ""
        set(v) { sp.edit().putString("parentPin", v).apply() }
    var activeKid: String?
        get() = sp.getString("activeKid", null)
        set(v) { sp.edit().putString("activeKid", v).apply() }
    var lockAll: Boolean
        get() = sp.getBoolean("lockAll", false)
        set(v) { sp.edit().putBoolean("lockAll", v).apply() }
    /** Wall-clock millis until which the parent has unlocked Settings etc. */
    var parentUntil: Long
        get() = sp.getLong("parentUntil", 0)
        set(v) { sp.edit().putLong("parentUntil", v).apply() }
    /** Short window (camera for homework photo) in which unknown apps are allowed. */
    var graceUntil: Long
        get() = sp.getLong("graceUntil", 0)
        set(v) { sp.edit().putLong("graceUntil", v).apply() }
    var stateJson: String
        get() = sp.getString("state", "") ?: ""
        set(v) { sp.edit().putString("state", v).apply() }
    var appsSentAt: Long
        get() = sp.getLong("appsSentAt", 0)
        set(v) { sp.edit().putLong("appsSentAt", v).apply() }

    val parentUnlocked get() = System.currentTimeMillis() < parentUntil
    val inGrace get() = System.currentTimeMillis() < graceUntil
    val configured get() = token.isNotEmpty() && sbUrl.isNotEmpty()

    // ---- usage: {kidId: {pkg: seconds}} per day ----
    @Synchronized fun usage(day: String): JSONObject = try { JSONObject(sp.getString("usage:$day", "{}")) } catch (e: Exception) { JSONObject() }

    @Synchronized fun addUsage(day: String, kid: String, pkg: String, sec: Long) {
        val u = usage(day)
        val k = u.optJSONObject(kid) ?: JSONObject().also { u.put(kid, it) }
        k.put(pkg, k.optLong(pkg, 0) + sec)
        val ed = sp.edit().putString("usage:$day", u.toString())
        sp.all.keys.filter { it.startsWith("usage:") && it != "usage:$day" }.sorted().dropLast(2).forEach { ed.remove(it) } // keep 3 days
        ed.apply()
    }

    @Synchronized fun mergeServerUsage(day: String, server: JSONObject?) {
        if (server == null) return
        val u = usage(day)
        for (kid in server.keys()) {
            val sk = server.getJSONObject(kid)
            val lk = u.optJSONObject(kid) ?: JSONObject().also { u.put(kid, it) }
            for (pkg in sk.keys()) lk.put(pkg, maxOf(lk.optLong(pkg, 0), sk.getLong(pkg) * 60))
        }
        sp.edit().putString("usage:$day", u.toString()).apply()
    }

    fun usageFor(day: String, kid: String): Map<String, Long> {
        val k = usage(day).optJSONObject(kid) ?: return emptyMap()
        return k.keys().asSequence().associateWith { k.getLong(it) }
    }

    // minutes the parent added today
    @Synchronized fun extraMin(day: String): Int = sp.getInt("extra:$day", 0)
    @Synchronized fun addExtra(day: String, min: Int) { sp.edit().putInt("extra:$day", extraMin(day) + min).apply() }
}
