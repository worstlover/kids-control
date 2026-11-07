package ir.family.guard

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate

object Sync {
    fun today(): String = LocalDate.now().toString()

    fun loadState(p: Prefs): State? = if (p.stateJson.isEmpty()) null else StateParser.parse(p.stateJson)

    /** POST JSON to a Supabase Edge Function. Returns (httpCode, body). Code -1 = network error. */
    fun post(p: Prefs, fn: String, body: JSONObject, timeoutMs: Int = 30_000): Pair<Int, JSONObject?> = try {
        val c = URL("${p.sbUrl}/functions/v1/$fn").openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.doOutput = true; c.connectTimeout = timeoutMs; c.readTimeout = timeoutMs
        c.setRequestProperty("Content-Type", "application/json")
        c.setRequestProperty("apikey", p.sbKey); c.setRequestProperty("Authorization", "Bearer ${p.sbKey}")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        Pair(code, try { JSONObject(text) } catch (e: Exception) { null })
    } catch (e: Exception) { Pair(-1, null) }

    private fun installedApps(ctx: Context): JSONArray {
        val pm = ctx.packageManager
        @Suppress("DEPRECATION")
        val list = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val arr = JSONArray()
        list.map { it.activityInfo.packageName }.distinct().filter { it != ctx.packageName }.forEach { pkg ->
            val label = try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (e: PackageManager.NameNotFoundException) { pkg }
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
        if (forceApps || System.currentTimeMillis() - p.appsSentAt > 6 * 3600_000L) body.put("apps", installedApps(ctx))
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
    fun dictCreate(p: Prefs, kidId: String, imageB64: String) = post(p, "dictation-create",
        JSONObject().put("token", p.token).put("kid_id", kidId).put("day", today()).put("image_b64", imageB64).put("media_type", "image/jpeg"), 90_000)

    /** One spoken phrase: raw 16-bit mono PCM (base64) + sample rate. */
    fun dictAudio(p: Prefs, dictId: String, index: Int) = post(p, "dictation-audio",
        JSONObject().put("token", p.token).put("dictation_id", dictId).put("index", index).put("day", today()), 60_000)

    fun submitDictation(p: Prefs, dictId: String, imageB64: String) = post(p, "device-submit",
        JSONObject().put("token", p.token).put("dictation_id", dictId).put("day", today()).put("image_b64", imageB64).put("media_type", "image/jpeg"), 90_000)

    fun submit(p: Prefs, logId: String, imageB64: String?): Pair<Int, JSONObject?> {
        val b = JSONObject().put("token", p.token).put("log_id", logId)
        if (imageB64 != null) b.put("image_b64", imageB64).put("media_type", "image/jpeg")
        return post(p, "device-submit", b, 90_000)
    }
}
