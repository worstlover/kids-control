package ir.family.guard

import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import org.json.JSONObject
import java.net.URI
import kotlin.concurrent.thread
import kotlin.math.ceil

/** Shops a kid may browse inside the app. Any other site is blocked (subdomains of these are allowed). */
val SHOP_HOSTS = listOf("digikala.com", "torob.com", "technolife.com", "ketabsara.com")
private const val SHOP_HOME = "https://www.digikala.com/"

private fun hostOk(url: String): Boolean {
    val h = try {
        URI(url).host?.lowercase()
    } catch (e: Exception) {
        null
    } ?: return false

    return SHOP_HOSTS.any { h == it || h.endsWith(".$it") }
}

/** The kid's wishes: progress, what is already affordable, and how many points are still missing. */
fun MainActivity.wishlistSection(st: State, kid: Kid) {
    add(ui.title("🎁 آرزوهای من"))
    add(ui.pill("🛍 بگرد و آرزوی جدید پیدا کن", Pal.PINK) { wishView = { shopScreen(kid) }; render() })
    if (kid.wishlist.isEmpty()) {
        add(ui.card().apply { addView(ui.text("هنوز آرزویی نداری. برو بگرد و چیزی که دوست داری رو پیدا کن 🛍", 15f, Pal.MUTED)) })
        return
    }
    // affordable first, then the ones closest to being bought, unknown prices last
    kid.wishlist.sortedWith(compareBy<WishItem>({ it.pointsLeft == null }, { it.pointsLeft ?: 0L }))
        .forEach { w -> add(wishCard(w, kid, st)) }
}

private fun MainActivity.wishCard(w: WishItem, kid: Kid, st: State): View {
    val card = ui.card()
    card.addView(ui.text(w.title, 18f, Pal.INK, true))
    val price = w.priceToman
    if (price == null || price <= 0) {
        card.addView(ui.text("💬 قیمتش هنوز مشخص نیست. از «بگرد» دوباره قیمت بگیر.", 14f, Pal.MUTED))
    } else {
        val left = w.pointsLeft ?: 0L
        if (left <= 0L) card.addView(ui.text("✅ با امتیازهات می‌تونی بخریش! 🎉", 16f, Pal.GREEN, true))
        else card.addView(ui.text("🪙 ${fa(left)} امتیاز دیگه لازم داری (قیمت: ${fa(price)} تومان)", 15f, Pal.PURPLE, true))
        val saved = maxOf(0L, kid.balance) * st.tomanPerPoint
        val pct = (saved / price).coerceIn(0.0, 1.0)
        card.addView(BarView(this).apply {
            fraction = pct.toFloat(); barColor = Pal.PINK; barColor2 = Pal.ORANGE
            layoutParams = LinearLayout.LayoutParams(-1, ui.dp(16)).apply { topMargin = ui.dp(8) }
        })
        card.addView(ui.text("${fa(String.format("%.0f", pct * 100))}٪ جمع شده · هر ${fa(st.tomanPerPoint.toLong())} تومان = یک امتیاز", 13f, Pal.MUTED))
    }
    card.addView(ui.pill("🗑 حذف از آرزوها", Pal.MUTED) { removeWish(w, kid) })
    return card
}

private fun MainActivity.removeWish(w: WishItem, kid: Kid) {
    thread {
        val (code, _) = try {
            Sync.wish(prefs, JSONObject().put("action", "remove").put("kid_id", kid.id).put("wish_id", w.id))
        } catch (e: Exception) { Pair(-1, null) }
        runOnUiThread {
            toast(if (code == 200) "از آرزوها حذف شد" else "حذف نشد، دوباره امتحان کن")
            refresh()
        }
    }
}

/** Browse the allowed shops, or type a name; then ask for the price and add it to the wish list. */
fun MainActivity.shopScreen(kid: Kid) {
    box.removeAllViews()
    add(ui.pill("← آرزوها", Pal.MUTED) { wishView = null; render() })
    add(ui.text("🛍 بگرد و آرزو پیدا کن", 22f, Pal.INK, true))
    add(ui.text("اسم چیزی که می‌خوای رو بنویس، یا توی فروشگاه بگرد و وقتی چیزی پسندیدی، دکمه‌ی «قیمتش رو بگیر» رو بزن.", 14f, Pal.MUTED))

    val search = ui.card()
    val q = field("مثلاً: دوچرخه، هدفون بی‌سیم")
    search.addView(q)
    val out = ui.card()
    search.addView(ui.pill("🔎 اسمش رو بگرد", Pal.BLUE) {
        val s = q.text.toString().trim()
        if (s.isNotEmpty()) lookupAndShow(kid, JSONObject().put("query", s), s, out)
    })
    add(search)

    // Keep one browser for the whole session: render() runs on every refresh, and rebuilding the page each time reloads it forever.
    val web = (shopWeb ?: WebView(this).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true      // shop sites like Digikala render blank without it
        webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, req: WebResourceRequest?): Boolean {
                val u = req?.url?.toString() ?: return true
                if (hostOk(u)) return false
                toast("این سایت برای آرزوها مجاز نیست")
                return true
            }
        }
        loadUrl(SHOP_HOME)
    }.also { shopWeb = it })
    (web.parent as? android.view.ViewGroup)?.removeView(web)
    web.layoutParams = LinearLayout.LayoutParams(-1, ui.dp(380)).apply { topMargin = ui.dp(10) }
    add(web)
    add(ui.pill("📌 این صفحه رو بفرست و قیمتش رو بگیر", Pal.ORANGE) {
        val u = web.url ?: ""
        if (hostOk(u)) lookupAndShow(kid, JSONObject().put("url", u), null, out)
        else toast("اول یه صفحه‌ی محصول توی فروشگاه باز کن")
    })
    add(out)
}

private fun MainActivity.lookupAndShow(kid: Kid, req: JSONObject, fallbackTitle: String?, out: LinearLayout) {
    out.removeAllViews()
    out.addView(ui.text("⏳ دارم قیمت رو پیدا می‌کنم…", 15f))
    req.put("action", "lookup").put("kid_id", kid.id)
    thread {
        val (code, r) = try { Sync.wish(prefs, req) } catch (e: Exception) { Pair(-1, null) }
        runOnUiThread { showLookup(kid, code, r, out, fallbackTitle) }
    }
}

private fun MainActivity.showLookup(kid: Kid, code: Int, r: JSONObject?, out: LinearLayout, fallbackTitle: String?) {
    out.removeAllViews()
    if (code != 200 || r == null) {
        out.addView(ui.text(
            if (code == 409) "آرزوهات پر شده؛ اول یکی رو حذف کن" else "نتونستم پیدا کنم. اسمش رو دقیق‌تر بنویس یا یه صفحه‌ی دیگه باز کن 🔄",
            15f, Pal.RED))
        return
    }
    val title = r.optString("title").takeIf { it.isNotBlank() && it != "null" } ?: fallbackTitle ?: "یک آرزو"
    val price = if (r.isNull("price_toman")) null else r.optLong("price_toman")
    out.addView(ui.text("🎯 $title", 18f, Pal.INK, true))
    if (price == null || price <= 0) {
        out.addView(ui.text("قیمتش رو پیدا نکردم. باز هم می‌تونی به آرزوهات اضافه‌اش کنی.", 14f, Pal.MUTED))
    } else {
        val rate = Sync.loadState(prefs)?.tomanPerPoint ?: 1000.0
        val need = ceil(price / rate).toLong()
        out.addView(ui.text("💰 قیمت: ${fa(price)} تومان", 16f, Pal.PURPLE, true))
        out.addView(ui.text("🪙 ${fa(need)} امتیاز لازم داره", 15f, Pal.INK))
        val left = need - maxOf(0L, kid.balance)
        out.addView(ui.text(
            if (left <= 0) "✅ با امتیازهات الان می‌تونی بخریش!"
            else "با ${fa(kid.balance)} امتیازی که داری، ${fa(left)} امتیاز دیگه لازم داری",
            15f, if (left <= 0) Pal.GREEN else Pal.ORANGE, true))
    }
    out.addView(ui.pill("➕ به آرزوهام اضافه کن", Pal.GREEN) { addWish(kid, title, r, price) })
}

private fun MainActivity.addWish(kid: Kid, title: String, r: JSONObject, price: Long?) {
    val body = JSONObject().put("action", "add").put("kid_id", kid.id).put("title", title)
        .put("source_url", r.optString("source_url").takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
        .put("image_url", r.optString("image_url").takeIf { it.isNotBlank() && it != "null" } ?: JSONObject.NULL)
        .put("price_toman", price ?: JSONObject.NULL)
    thread {
        val (code, _) = try { Sync.wish(prefs, body) } catch (e: Exception) { Pair(-1, null) }
        runOnUiThread {
            if (code == 200) {
                toast("به آرزوهات اضافه شد ✓")
                wishView = null; kidTab = "points"; render(); refresh()
            } else toast(if (code == 409) "آرزوهات پر شده؛ اول یکی رو حذف کن" else "ذخیره نشد، دوباره امتحان کن")
        }
    }
}
