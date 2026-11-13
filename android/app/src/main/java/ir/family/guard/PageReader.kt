package ir.family.guard

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONArray
import org.json.JSONObject
import org.json.JSONTokener
import java.util.Collections

/**
 * Opens a QR page in an invisible browser, like the phone's own browser would, and reads it after its scripts ran.
 * Many lesson sites (roshd.ir …) build the page and the video player with JavaScript, so the plain HTML has no
 * video in it. This catches the video/audio links the player asks for, plus the page's visible text.
 */
object PageReader {
    class Result(val finalUrl: String, val title: String, val html: String, val text: String, val media: List<MediaItem>)

    private val VID = Regex("""\.(mp4|webm|m4v|mov|m3u8)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val AUD = Regex("""\.(mp3|m4a|aac|ogg|oga|wav)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val SITES = listOf("sch.ir", "roshd.ir", "medu.ir", "aparat.com")

    private const val JS = """(function(){var m=[];function add(u,t,ti){if(u&&u.indexOf('blob:')!==0)m.push({url:u,type:t,title:ti||''});}
document.querySelectorAll('video,audio').forEach(function(e){var t=e.tagName.toLowerCase();add(e.currentSrc||e.src,t);e.querySelectorAll('source').forEach(function(s){add(s.src,t);});});
document.querySelectorAll('iframe').forEach(function(f){add(f.src,'page');});
document.querySelectorAll('a[href]').forEach(function(a){var h=a.href,ti=(a.innerText||'').trim().slice(0,80);
if(/\.(mp4|webm|m4v|mov|m3u8)(\?|#|$)/i.test(h))add(h,'video',ti);else if(/\.(mp3|m4a|aac|ogg|oga|wav)(\?|#|$)/i.test(h))add(h,'audio',ti);});
var b=document.body;return JSON.stringify({title:document.title||'',text:(b?b.innerText:'').slice(0,12000),html:document.documentElement.outerHTML.slice(0,1400000),media:m});})()"""

    fun siteOk(host: String?): Boolean = host != null && SITES.any { host == it || host.endsWith(".$it") }

    /** Main thread only. Calls [done] once (on the main thread), with null if nothing could be read. */
    @SuppressLint("SetJavaScriptEnabled")
    fun read(act: MainActivity, url: String, timeoutMs: Long = 20_000, done: (Result?) -> Unit) {
        val h = Handler(Looper.getMainLooper())
        val caught = Collections.synchronizedList(ArrayList<MediaItem>())
        var finished = false
        var lastUrl = url
        val web = try { WebView(act) } catch (e: Exception) { done(null); return }
        fun finish(r: Result?) {
            if (finished) return
            finished = true
            h.removeCallbacksAndMessages(null)
            try { web.stopLoading(); web.loadUrl("about:blank"); web.destroy() } catch (e: Exception) {}
            done(r)
        }
        fun collect() {
            if (finished) return
            web.evaluateJavascript(JS) { raw ->
                val r = try {
                    val o = JSONObject(JSONTokener(raw).nextValue() as String)
                    val media = ArrayList<MediaItem>()
                    val arr = o.optJSONArray("media") ?: JSONArray()
                    for (i in 0 until arr.length()) {
                        val m = arr.getJSONObject(i); val u = m.optString("url")
                        if (!u.startsWith("http")) continue
                        val type = m.optString("type")
                        if (type == "page" && !(siteOk(android.net.Uri.parse(u).host) || u.contains("aparat.com") || u.contains("youtube"))) continue
                        media.add(MediaItem(type, u, m.optString("title")))
                    }
                    synchronized(caught) { media.addAll(caught) }
                    Result(lastUrl, o.optString("title"), o.optString("html"), o.optString("text"), media.distinctBy { it.url }.take(8))
                } catch (e: Exception) { null }
                finish(r)
            }
        }
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.mediaPlaybackRequiresUserGesture = true      // read the page, don't play it
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                val host = req?.url?.host
                return !(host == android.net.Uri.parse(url).host || siteOk(host))
            }
            override fun onPageStarted(v: WebView?, u: String?, favicon: Bitmap?) { if (u != null && u.startsWith("http")) lastUrl = u }
            // the player's own requests for its video/audio files (also .m3u8 streams that never appear in the page)
            override fun shouldInterceptRequest(v: WebView?, req: WebResourceRequest?): WebResourceResponse? {
                val u = req?.url?.toString() ?: return null
                if (VID.containsMatchIn(u)) caught.add(MediaItem("video", u))
                else if (AUD.containsMatchIn(u)) caught.add(MediaItem("audio", u))
                return null
            }
            override fun onPageFinished(v: WebView?, u: String?) {
                if (u != null && u.startsWith("http")) lastUrl = u
                h.removeCallbacksAndMessages("collect")
                // give the page's scripts a moment to build the player
                h.postAtTime({ collect() }, "collect", android.os.SystemClock.uptimeMillis() + 3_000)
            }
        }
        h.postDelayed({ if (!finished) collect() }, timeoutMs)
        h.postDelayed({ finish(null) }, timeoutMs + 4_000)
        try { web.loadUrl(url) } catch (e: Exception) { finish(null) }
    }
}
