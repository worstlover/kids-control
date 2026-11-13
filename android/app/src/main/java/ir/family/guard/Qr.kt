package ir.family.guard

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
import com.google.zxing.LuminanceSource
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader

/** Reads a QR code from raw pixels (ARGB ints). No Android classes, so it can be tested on a plain JVM. */
object Qr {
    private val hints = mapOf<DecodeHintType, Any>(
        DecodeHintType.TRY_HARDER to true,
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    )

    /** Tries the photo as is, then smaller versions (a QR inside a big photo is easier to find when shrunk). Returns the text or null. */
    fun decode(pixels: IntArray, w: Int, h: Int): String? {
        var cur = pixels; var cw = w; var ch = h
        repeat(5) {
            decodeOnce(cur, cw, ch)?.let { return it }
            if (cw < 240 || ch < 240) return null
            val nw = cw / 2; val nh = ch / 2
            val next = IntArray(nw * nh)
            for (y in 0 until nh) for (x in 0 until nw) {      // 2x2 average: keeps thin QR modules from vanishing
                var r = 0; var g = 0; var b = 0
                for (dy in 0..1) for (dx in 0..1) { val p = cur[(y * 2 + dy) * cw + x * 2 + dx]; r += (p shr 16) and 255; g += (p shr 8) and 255; b += p and 255 }
                next[y * nw + x] = (255 shl 24) or ((r / 4) shl 16) or ((g / 4) shl 8) or (b / 4)
            }
            cur = next; cw = nw; ch = nh
        }
        return null
    }

    private fun decodeOnce(px: IntArray, w: Int, h: Int): String? {
        val src = RGBLuminanceSource(w, h, px)
        for (b in listOf<(com.google.zxing.LuminanceSource) -> com.google.zxing.Binarizer>({ HybridBinarizer(it) }, { GlobalHistogramBinarizer(it) })) {
            try { return QRCodeReader().decode(BinaryBitmap(b(src)), hints).text } catch (e: NotFoundException) {} catch (e: Exception) {}
            try { return QRCodeReader().decode(BinaryBitmap(b(src.invert())), hints).text } catch (e: Exception) {}
        }
        return null
    }

    /**
     * One live camera frame (the Y = brightness plane of YUV, one byte per pixel, rows packed). Tries the middle of
     * the frame first (where the kid holds the QR, and faster), then the whole frame. Fast enough for several frames a second.
     */
    fun decodeY(y: ByteArray, w: Int, h: Int): String? {
        val side = (minOf(w, h) * 0.8).toInt()
        val regions = listOf(intArrayOf((w - side) / 2, (h - side) / 2, side, side), intArrayOf(0, 0, w, h))
        for (r in regions) {
            val src = try { PlanarYUVLuminanceSource(y, w, h, r[0], r[1], r[2], r[3], false) } catch (e: Exception) { continue }
            tryReaders(src)?.let { return it }
        }
        return null
    }

    private fun tryReaders(src: LuminanceSource): String? {
        for (b in listOf<(LuminanceSource) -> com.google.zxing.Binarizer>({ HybridBinarizer(it) }, { GlobalHistogramBinarizer(it) })) {
            try { return QRCodeReader().decode(BinaryBitmap(b(src)), hints).text } catch (e: Exception) {}
        }
        try { return QRCodeReader().decode(BinaryBitmap(HybridBinarizer(src.invert())), hints).text } catch (e: Exception) {}
        return null
    }
}

/** A video, audio or embedded player found on a lesson page. */
data class MediaItem(val type: String, val url: String, val title: String = "")

/**
 * Finds playable media on a page, on the phone (same rules as the server's extractMedia), so the kid can still
 * watch the lesson's video when the server or the AI cannot help.
 */
object LessonMedia {
    private val VID = Regex("""\.(mp4|webm|m4v|mov|m3u8)(\?|#|$)""", RegexOption.IGNORE_CASE)
    private val AUD = Regex("""\.(mp3|m4a|aac|ogg|oga|wav)(\?|#|$)""", RegexOption.IGNORE_CASE)

    fun fromResponse(finalUrl: String, contentType: String, html: String): List<MediaItem> = when {
        contentType.startsWith("video/") -> listOf(MediaItem("video", finalUrl))
        contentType.startsWith("audio/") -> listOf(MediaItem("audio", finalUrl))
        else -> extract(html, finalUrl)
    }

    fun extract(html: String, baseUrl: String, max: Int = 8): List<MediaItem> {
        val out = ArrayList<MediaItem>(); val seen = HashSet<String>()
        fun add(raw: String?, type: String, title: String = "") {
            if (raw.isNullOrBlank() || out.size >= max) return
            val abs = try { java.net.URI(baseUrl).resolve(raw.trim().replace("&amp;", "&").replace(" ", "%20")).toString() } catch (e: Exception) { return }
            if (!abs.startsWith("http://") && !abs.startsWith("https://")) return
            if (seen.add(abs)) out.add(MediaItem(type, abs, title.take(80)))
        }
        val opt = setOf(RegexOption.IGNORE_CASE)
        Regex("""<video\b[^>]*\bsrc=["']([^"']+)["']""", opt).findAll(html).forEach { add(it.groupValues[1], "video") }
        Regex("""<audio\b[^>]*\bsrc=["']([^"']+)["']""", opt).findAll(html).forEach { add(it.groupValues[1], "audio") }
        Regex("""<source\b[^>]*\bsrc=["']([^"']+)["'][^>]*>""", opt).findAll(html).forEach {
            val u = it.groupValues[1]; add(u, if (AUD.containsMatchIn(u) || Regex("""type=["']audio""", opt).containsMatchIn(it.value)) "audio" else "video")
        }
        Regex("""<meta[^>]+property=["']og:(video|audio)(?::url|:secure_url)?["'][^>]*content=["']([^"']+)["']""", opt).findAll(html)
            .forEach { add(it.groupValues[2], if (it.groupValues[1].lowercase() == "audio") "audio" else "video") }
        Regex("""<a\b[^>]*\bhref=["']([^"']+)["'][^>]*>([\s\S]*?)</a>""", opt).findAll(html).forEach {
            val u = it.groupValues[1]; val t = it.groupValues[2].replace(Regex("<[^>]+>"), " ").replace(Regex("\\s+"), " ").trim()
            if (VID.containsMatchIn(u)) add(u, "video", t) else if (AUD.containsMatchIn(u)) add(u, "audio", t)
        }
        Regex("""<iframe\b[^>]*\bsrc=["']([^"']+)["']""", opt).findAll(html).forEach {
            if (Regex("""aparat\.com|youtube\.com/embed|youtube-nocookie\.com""", opt).containsMatchIn(it.groupValues[1])) add(it.groupValues[1], "page")
        }
        return out
    }
}
