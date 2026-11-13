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

/**
 * Reads the product on the page the kid is looking at: name, price from the page's structured data (schema.org Product or
 * og price tags) and the visible text. No '$' in it: it is a Kotlin raw string.
 */
private const val READ_PAGE_JS = """(function(){try{
var r={title:'',price:null,cur:'',img:'',text:''};
var ld=document.querySelectorAll('script[type="application/ld+json"]');
for(var i=0;i<ld.length;i++){try{var j=JSON.parse(ld[i].textContent);var arr=Array.isArray(j)?j:(j['@graph']||[j]);
for(var k=0;k<arr.length;k++){var o=arr[k];if(!o)continue;var t=o['@type'];var isP=(t==='Product')||(Array.isArray(t)&&t.indexOf('Product')>=0);if(!isP)continue;
r.title=r.title||o.name||'';var of=o.offers;if(Array.isArray(of))of=of[0];
if(of){var p=of.price||of.lowPrice;if(p){r.price=Number(String(p).replace(/[^0-9.]/g,''));r.cur=String(of.priceCurrency||'').toUpperCase();}}
var im=o.image;if(Array.isArray(im))im=im[0];if(im&&typeof im==='object')im=im.url;if(typeof im==='string')r.img=im;}}catch(e){}}
var meta=function(n){var m=document.querySelector('meta[property="'+n+'"]')||document.querySelector('meta[name="'+n+'"]');return m?(m.getAttribute('content')||''):''};
r.title=r.title||meta('og:title')||document.title||'';r.img=r.img||meta('og:image');
var pm=meta('product:price:amount');if(!r.price&&pm){r.price=Number(String(pm).replace(/[^0-9.]/g,''));r.cur=String(meta('product:price:currency')).toUpperCase();}
r.text=(document.body?document.body.innerText:'').replace(/\s+/g,' ').slice(0,5000);
return JSON.stringify(r);}catch(e){return '{}';}})()"""

/** Price in toman from what the page says, or null when the currency is unknown (then the server reads the page text). */
private fun pagePriceToman(price: Double, cur: String): Long? = when {
    price <= 0 -> null
    cur == "IRR" -> (price / 10).toLong()
    cur == "IRT" || cur == "TOMAN" || cur == "TMN" -> price.toLong()
    else -> null
}

/** Browse the allowed shops (full screen, so the page scrolls), or type a name; then ask for the price and add it to the wish list. */
fun MainActivity.shopScreen(kid: Kid) {
    box.removeAllViews()
    add(ui.pill("← آرزوها", Pal.MUTED) { wishView = null; render() })
    add(ui.text("🛍 بگرد و آرزو پیدا کن", 22f, Pal.INK, true))
    add(ui.text("اسم چیزی که می‌خوای رو بنویس، یا فروشگاه رو باز کن، صفحه‌ی چیزی که دوست داری رو بیار و «قیمت همین صفحه» رو بزن.", 14f, Pal.MUTED))

    val out = ui.card()
    add(ui.pill("🛒 باز کردن فروشگاه", Pal.PINK) { openShop(kid, out) })
    val search = ui.card()
    val q = field("مثلاً: دوچرخه، هدفون بی‌سیم")
    search.addView(q)
    search.addView(ui.pill("🔎 اسمش رو بگرد", Pal.BLUE) {
        val s = q.text.toString().trim()
        if (s.isNotEmpty()) lookupAndShow(kid, JSONObject().put("query", s), s, out)
    })
    add(search)
    add(out)
}

/** The shop in a full-screen window: a web page inside the app's scrolling screen cannot scroll, so it gets the whole screen. */
private fun MainActivity.openShop(kid: Kid, out: LinearLayout) {
    val dlg = android.app.Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen)
    val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(android.graphics.Color.WHITE); layoutDirection = View.LAYOUT_DIRECTION_RTL }
    val bar = ui.row().apply { setPadding(ui.dp(8), ui.dp(8), ui.dp(8), ui.dp(8)) }
    // Keep one browser for the whole session so the kid's place in the shop is not lost.
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
    bar.addView(ui.pill("✕ بستن", Pal.MUTED) { dlg.dismiss() }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = ui.dp(6) } })
    bar.addView(ui.pill("→ قبلی", Pal.BLUE) { if (web.canGoBack()) web.goBack() }.apply { layoutParams = LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = ui.dp(6) } })
    root.addView(bar, LinearLayout.LayoutParams(-1, -2))
    root.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
    val take = ui.pill("📌 قیمت همین صفحه رو بگیر", Pal.ORANGE) {
        val u = web.url ?: ""
        if (!hostOk(u)) return@pill toast("اول یه صفحه‌ی محصول توی فروشگاه باز کن")
        web.evaluateJavascript(READ_PAGE_JS) { raw ->
            val page = try { JSONObject(org.json.JSONArray("[$raw]").getString(0)) } catch (e: Exception) { JSONObject() }
            val req = JSONObject().put("url", u)
                .put("page_title", page.optString("title"))
                .put("page_image", page.optString("img"))
                .put("page_text", page.optString("text"))
            pagePriceToman(page.optDouble("price", 0.0), page.optString("cur"))?.let { req.put("page_price_toman", it) }
            dlg.dismiss()
            lookupAndShow(kid, req, page.optString("title").takeIf { it.isNotBlank() }, out)
        }
    }
    root.addView(take, LinearLayout.LayoutParams(-1, -2).apply { setMargins(ui.dp(10), ui.dp(6), ui.dp(10), ui.dp(10)) })
    dlg.setContentView(root)
    dlg.setOnDismissListener { (web.parent as? android.view.ViewGroup)?.removeView(web) }
    dlg.show()
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
            when (code) { 409 -> "آرزوهات پر شده؛ اول یکی رو حذف کن"; 429 -> "امروز زیاد قیمت گرفتی؛ فردا دوباره 🌙"; else -> "نتونستم پیدا کنم. اسمش رو دقیق‌تر بنویس یا یه صفحه‌ی دیگه باز کن 🔄" },
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
