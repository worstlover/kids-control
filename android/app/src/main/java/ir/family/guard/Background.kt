package ir.family.guard

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File

/** Decode a picture scaled so its longest side is about maxSide px (keeps memory low). */
fun decodeScaled(path: String, maxSide: Int = 1080): Bitmap? {
    val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(path, o)
    if (o.outWidth <= 0) return null
    var s = 1
    while (maxOf(o.outWidth, o.outHeight) / s > maxSide) s *= 2
    return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
}

/** Copy a picked gallery image into the app's own storage. Returns the new file path, or null. */
fun saveBackground(ctx: Context, uri: Uri): String? {
    try {
        val tmp = File(ctx.cacheDir, "bg_pick.jpg")
        val input = ctx.contentResolver.openInputStream(uri) ?: return null
        input.use { i -> tmp.outputStream().use { o -> i.copyTo(o) } }
        val bmp = decodeScaled(tmp.path) ?: return null
        val out = File(ctx.filesDir, "bg.jpg")
        out.outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        tmp.delete()
        return out.path
    } catch (e: Exception) {
        return null
    }
}
