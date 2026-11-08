package ir.family.parent

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/** Iran time for every "day" the parent sees (same rule as the kids' app and the server). */
val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")

class ApiException(val code: Int, body: String) : Exception("HTTP $code: ${body.take(160)}")

/** Talks to Supabase directly: sign-in with email/password, then PostgREST with the parent's session. */
class Api(ctx: Context) {
    private val sp = ctx.getSharedPreferences("parent", Context.MODE_PRIVATE)

    val url: String get() = (sp.getString("url", null) ?: BuildConfig.SB_URL).trimEnd('/')
    private val key: String get() = sp.getString("key", null) ?: BuildConfig.SB_KEY
    private var access: String
        get() = sp.getString("access", "") ?: ""
        set(v) { sp.edit().putString("access", v).apply() }
    private var refresh: String
        get() = sp.getString("refresh", "") ?: ""
        set(v) { sp.edit().putString("refresh", v).apply() }

    val loggedIn: Boolean get() = access.isNotEmpty()

    fun logout() { sp.edit().remove("access").remove("refresh").apply() }

    private fun call(method: String, full: String, body: String?, withSession: Boolean, prefer: String?): Pair<Int, String> {
        val c = URL(full).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000
        c.readTimeout = 30_000
        c.setRequestProperty("apikey", key)
        c.setRequestProperty("Authorization", "Bearer " + if (withSession) access else key)
        if (body != null) {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        if (prefer != null) c.setRequestProperty("Prefer", prefer)
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        return code to text
    }

    /** Email + password sign-in. Returns false if the credentials are wrong. */
    fun login(email: String, password: String): Boolean {
        val (code, text) = call("POST", "$url/auth/v1/token?grant_type=password",
            JSONObject().put("email", email).put("password", password).toString(), false, null)
        if (code !in 200..299) return false
        val j = JSONObject(text)
        access = j.getString("access_token")
        refresh = j.optString("refresh_token", "")
        return true
    }

    private fun refreshSession(): Boolean {
        if (refresh.isEmpty()) return false
        val (code, text) = call("POST", "$url/auth/v1/token?grant_type=refresh_token",
            JSONObject().put("refresh_token", refresh).toString(), false, null)
        if (code !in 200..299) return false
        val j = JSONObject(text)
        access = j.getString("access_token")
        refresh = j.optString("refresh_token", refresh)
        return true
    }

    /** Any PostgREST call. Refreshes the session once if it expired. Throws ApiException on failure. */
    fun send(method: String, path: String, body: Any? = null, prefer: String? = "return=minimal"): String {
        val payload = body?.toString()
        var (code, text) = call(method, "$url/rest/v1/$path", payload, true, prefer)
        if (code == 401 && refreshSession()) {
            val r = call(method, "$url/rest/v1/$path", payload, true, prefer)
            code = r.first; text = r.second
        }
        if (code !in 200..299) throw ApiException(code, text)
        return text
    }

    fun get(path: String): JSONArray {
        val t = send("GET", path, null, null)
        return if (t.isBlank()) JSONArray() else JSONArray(t)
    }

    /** Insert/update that returns the saved row. */
    fun write(method: String, path: String, body: JSONObject): JSONObject? {
        val t = send(method, path, body, "return=representation")
        return if (t.isBlank()) null else JSONArray(t).optJSONObject(0)
    }

    companion object {
        fun today(): String = LocalDate.now(TEHRAN).toString()
    }
}
