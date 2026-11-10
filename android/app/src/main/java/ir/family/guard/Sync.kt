package ir.family.guard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.ZoneId

/** The kid's day, windows and labels follow Iran time, whatever the phone's own zone is. */
val TEHRAN: ZoneId = ZoneId.of("Asia/Tehran")

object Sync {
    fun today(): String = LocalDate.now(TEHRAN).toString()

    fun loadState(p: Prefs): State? = if (p.stateJson.isEmpty()) null else StateParser.parse(p.stateJson)

    /** POST JSON to a Supabase Edge Function. Returns (httpCode, body). Code -1 = network error. */
    fun post(p: Prefs, fn: String, body: JSONObject, timeoutMs: Int = 30_000, onProgress: ((Int) -> Unit)? = null): Pair<Int, JSONObject?> = try {
        val c = URL("${p.sbUrl}/functions/v1/$fn").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("apikey", p.sbKey); c.setRequestProperty("Authorization", "Bearer ${p.sbKey}")
        val bytes = body.toString().toByteArray()
        if (onProgress == null) c.outputStream.use { it.write(bytes) }
        else {
            c.setFixedLengthStreamingMode(bytes.size)            // real upload progress for photos
            c.outputStream.use { out ->
                var sent = 0
                while (sent < bytes.size) { val n = minOf(16_384, bytes.size - sent); out.write(bytes, sent, n); sent += n; onProgress(sent * 100 / bytes.size) }
            }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        Pair(code, try { JSONObject(text) } catch (e: Exception) { null })
    } catch (e: Exception) { Pair(-1, null) }

    /**
     * Every app the kid could open: anything with a launch intent (including games that only show up
     * in Samsung Game Launcher / Game Booster) plus every user-installed app. Launcher-only query
     * misses some of those, so we read the full package list instead.
     */
    private fun installedApps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        val arr = JSONArray()
        val seen = HashSet<String>()
        val all = try { pm.getInstalledApplications(0) } catch (e: Exception) { emptyList() }
        for (ai in all) {
            val pkg = ai.packageName
            if (pkg == ctx.packageName || !seen.add(pkg)) continue
            val userApp = (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) == 0 ||
                (ai.flags and android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
            val launchable = try { pm.getLaunchIntentForPackage(pkg) != null } catch (e: Exception) { false }
            if (!userApp && !launchable) continue
            val label = try { pm.getApplicationLabel(ai).toString() } catch (e: Exception) { pkg }
            arr.put(JSONObject().put("package_name", pkg).put("label", label))
        }
        return arr
    }

    /** Send today's usage, receive rules/tasks/points. Returns the HTTP code (200 = ok, 401 = bad device code, -1 = offline). */
    @Synchronized fun syncNow(ctx: Context, forceApps: Boolean = false): Int {
        val p = Prefs(ctx)
        if (!p.configured) return 0
        val day = today()
        val usage = JSONObject()
        val u = p.usage(day)
        for (kid in u.keys()) {
            val m = JSONObject(); val k = u.getJSONObject(kid)
            for (pkg in k.keys()) m.put(pkg, (k.getLong(pkg) + 30) / 60)
            usage.put(kid, m)
        }
        val body = JSONObject().put("token", p.token).put("day", day).put("usage", usage)
        if (forceApps || System.currentTimeMillis() - p.appsSentAt > 10 * 60_000L) body.put("apps", installedApps(ctx))
        val (code, res) = post(p, "device-sync", body)
        if (code != 200 || res == null) return code
        if (body.has("apps")) p.appsSentAt = System.currentTimeMillis()
        p.stateJson = res.toString()
        p.mergeServerUsage(day, res.optJSONObject("usage_today"))
        val cmds = res.optJSONArray("commands")
        for (i in 0 until (cmds?.length() ?: 0)) {
            val c = cmds!!.getJSONObject(i)
            when (c.optString("command")) {
                "lock_all" -> p.lockAll = true
                "unlock" -> p.lockAll = false
                "extend" -> p.addExtra(day, c.optJSONObject("payload")?.optInt("minutes", 15) ?: 15)
            }
        }
        return 200
    }

    /** Photo of a book page -> new dictation (may wait for parent review). */
    fun dictCreate(p: Prefs, kidId: String, imageB64: String, onProgress: ((Int) -> Unit)? = null) = post(p, "dictation-create",
        JSONObject().put("token", p.token).put("kid_id", kidId).put("day", today()).put("image_b64", imageB64).put("media_type", "image/jpeg"), 90_000, onProgress)

    /** One spoken phrase: raw 16-bit mono PCM (base64) + sample rate. */
    fun dictAudio(p: Prefs, dictId: String, index: Int) = post(p, "dictation-audio",
        JSONObject().put("token", p.token).put("dictation_id", dictId).put("index", index).put("day", today()), 60_000)

    /** Kid sets (no oldPin) or changes (oldPin) their own PIN. Returns 200, 403 (wrong old PIN), 400, or network -1. */
    fun kidPin(p: Prefs, kidId: String, oldPin: String?, newPin: String): Pair<Int, JSONObject?> {
        val b = JSONObject().put("token", p.token).put("kid_id", kidId).put("new_pin", newPin)
        if (oldPin != null) b.put("old_pin", oldPin)
        return post(p, "kid-pin", b)
    }

    /** Tutor: level test, plan, practice, check, explain. The server answers in a JSON object. */
    fun tutor(p: Prefs, body: JSONObject): Pair<Int, JSONObject?> = post(p, "tutor", body.put("token", p.token), 90_000)

    /** Lessons from the textbook: create, list, get, progress, attach_qr, audio_dictation. */
    fun tutorLesson(p: Prefs, body: JSONObject, timeoutMs: Int = 90_000, onProgress: ((Int) -> Unit)? = null): Pair<Int, JSONObject?> = post(p, "tutor-lesson", body.put("token", p.token), timeoutMs, onProgress)

    fun submitDictation(p: Prefs, dictId: String, imageB64: String, onProgress: ((Int) -> Unit)? = null) = post(p, "device-submit",
        JSONObject().put("token", p.token).put("dictation_id", dictId).put("day", today()).put("image_b64", imageB64).put("media_type", "image/jpeg"), 90_000, onProgress)

    fun submit(p: Prefs, logId: String, imageB64: String?, onProgress: ((Int) -> Unit)? = null): Pair<Int, JSONObject?> {
        val b = JSONObject().put("token", p.token).put("log_id", logId)
        if (imageB64 != null) b.put("image_b64", imageB64).put("media_type", "image/jpeg")
        return post(p, "device-submit", b, 90_000, onProgress)
    }
}
