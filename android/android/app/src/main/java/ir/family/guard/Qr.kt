package ir.family.guard

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.NotFoundException
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
}
