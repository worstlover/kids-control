package ir.family.guard

import org.json.JSONArray
import org.json.JSONObject

// No Android classes here, so it can be tested on a plain JVM against real server responses.

class State(val day: String, val tomanPerPoint: Double, val kids: List<Kid>)

private fun JSONObject.str(k: String): String? = if (isNull(k)) null else getString(k)
private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else getInt(k)
private fun JSONObject.longOrNull(k: String): Long? = if (isNull(k)) null else getLong(k)
private fun JSONObject.dblOrNull(k: String): Double? = if (isNull(k)) null else getDouble(k)
private fun <T> JSONArray?.mapObjs(f: (JSONObject) -> T): List<T> =
    if (this == null) emptyList() else (0 until length()).map { f(getJSONObject(it)) }

object StateParser {
    fun parse(json: String): State? = try {
        val o = JSONObject(json)
        State(o.getString("day"), o.optDouble("toman_per_point", 1000.0), o.getJSONArray("kids").mapObjs { k ->
            Kid(
                k.getString("id"), k.getString("name"), k.str("pin_hash"), k.optInt("daily_limit_min", 120), k.optLong("balance", 0),
                k.optJSONArray("tasks").mapObjs { t ->
                    TaskItem(t.getString("log_id"), t.getString("title"), t.optInt("points", 0), t.optBoolean("needs_photo", false),
                        t.getString("status"), t.dblOrNull("ai_score"), t.str("ai_feedback"))
                },
                k.optJSONArray("wishlist").mapObjs { w -> WishItem(w.getString("title"), w.longOrNull("price_toman"), w.longOrNull("points_needed"), w.longOrNull("points_left")) },
                k.optJSONArray("rules").mapObjs { r ->
                    Rule(r.getString("package_name"), r.optBoolean("locked", false), r.optString("category", "other"), r.intOrNull("daily_limit_min"),
                        r.str("allowed_from"), r.str("allowed_to"), r.optBoolean("lock_until_tasks_done", false))
                },
                k.optJSONArray("dictations").mapObjs { d ->
                    DictItem(d.getString("id"), d.getString("title"), d.getString("status"), d.optInt("points", 0), d.optInt("phrase_count", 0),
                        d.dblOrNull("ai_score"), d.str("ai_feedback"), d.intOrNull("points_awarded"))
                },
            )
        })
    } catch (e: Exception) { null }

}
