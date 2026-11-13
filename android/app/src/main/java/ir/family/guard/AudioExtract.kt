package ir.family.guard

import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

/** ADTS framing for raw AAC: makes a plain .aac stream any player/AI can read. Pure, tested on a plain JVM. */
object Adts {
    private val RATES = intArrayOf(96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000, 7350)

    data class Cfg(val profile: Int, val rateIndex: Int, val channels: Int)

    /** From the track's AudioSpecificConfig (csd-0) if there is one, else from its sample rate / channel count. */
    fun cfg(csd: ByteArray?, sampleRate: Int, channels: Int): Cfg {
        if (csd != null && csd.size >= 2) {
            val b0 = csd[0].toInt() and 255; val b1 = csd[1].toInt() and 255
            val aot = b0 shr 3
            val idx = ((b0 and 7) shl 1) or (b1 shr 7)
            val ch = (b1 shr 3) and 15
            // HE-AAC (5, 29) keeps its core LC stream in ADTS; decoders find the extra part themselves
            if (idx < 13) return Cfg(if (aot in 1..4) aot else 2, idx, if (ch in 1..7) ch else channels.coerceIn(1, 7))
        }
        val idx = RATES.indexOf(sampleRate).let { if (it >= 0) it else 4 }
        return Cfg(2, idx, channels.coerceIn(1, 7))
    }

    /** 7-byte header (no CRC) for one frame whose payload is [payload] bytes. */
    fun header(c: Cfg, payload: Int): ByteArray {
        val len = payload + 7
        return byteArrayOf(
            0xFF.toByte(), 0xF1.toByte(),
            (((c.profile - 1) shl 6) or (c.rateIndex shl 2) or (c.channels shr 2)).toByte(),
            (((c.channels and 3) shl 6) or (len shr 11)).toByte(),
            ((len shr 3) and 0xFF).toByte(),
            (((len and 7) shl 5) or 0x1F).toByte(),
            0xFC.toByte(),
        )
    }
}

/**
 * Takes only the sound out of a lesson video (no re-encoding, so it is fast and light on the phone):
 * AAC sound becomes a .aac stream, MP3 sound stays MP3. Reads straight from the link.
 */
object AudioExtract {
    class Out(val bytes: ByteArray, val mime: String, val seconds: Int, val partial: Boolean)

    /** null if the link has no readable sound track (or it is in a format we cannot pass on). */
    fun fromUrl(url: String, maxBytes: Int, maxSeconds: Int, progress: (doneSec: Int, totalSec: Int) -> Unit): Out? {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(url, mapOf("User-Agent" to "Mozilla/5.0 (Linux; Android) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"))
            var track = -1; var fmt: MediaFormat? = null
            for (i in 0 until ex.trackCount) {
                val f = ex.getTrackFormat(i)
                if ((f.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; fmt = f; break }
            }
            if (track < 0 || fmt == null) return null
            val mime = fmt.getString(MediaFormat.KEY_MIME) ?: ""
            val aac = mime == "audio/mp4a-latm"
            if (!aac && mime != "audio/mpeg") return null
            ex.selectTrack(track)
            val totalSec = if (fmt.containsKey(MediaFormat.KEY_DURATION)) (fmt.getLong(MediaFormat.KEY_DURATION) / 1_000_000L).toInt() else -1
            val cfg = if (aac) {
                val csd = fmt.getByteBuffer("csd-0")?.let { b -> val d = b.duplicate(); d.rewind(); ByteArray(d.remaining()).also { d.get(it) } }
                Adts.cfg(csd, if (fmt.containsKey(MediaFormat.KEY_SAMPLE_RATE)) fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE) else 44100,
                    if (fmt.containsKey(MediaFormat.KEY_CHANNEL_COUNT)) fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT) else 2)
            } else null
            val out = ByteArrayOutputStream()
            val buf = ByteBuffer.allocate(512 * 1024)
            var partial = false; var lastSec = -1; var sec = 0
            while (true) {
                buf.clear()
                val n = ex.readSampleData(buf, 0)
                if (n < 0) break
                val t = ex.sampleTime
                sec = (t / 1_000_000L).toInt()
                if (sec >= maxSeconds || out.size() + n + 7 > maxBytes) { partial = true; break }
                val frame = ByteArray(n); buf.position(0); buf.limit(n); buf.get(frame)
                if (cfg != null) out.write(Adts.header(cfg, n))
                out.write(frame)
                if (sec != lastSec && sec % 5 == 0) { lastSec = sec; progress(sec, totalSec) }
                ex.advance()
            }
            if (out.size() < 2000) return null
            return Out(out.toByteArray(), if (aac) "audio/aac" else "audio/mpeg", sec, partial)
        } catch (e: Exception) {
            return null
        } finally {
            try { ex.release() } catch (e: Exception) {}
        }
    }
}
