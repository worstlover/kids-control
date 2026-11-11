import ir.family.guard.Qr
import javax.imageio.ImageIO
import java.io.File

fun main() {
    var n = 0
    fun px(name: String): Triple<IntArray, Int, Int> {
        val img = ImageIO.read(File("/tmp/qrimg/$name.png")); val w = img.width; val h = img.height
        return Triple(img.getRGB(0, 0, w, h, null, 0, w), w, h)
    }
    for (name in listOf("plain", "small", "rotated", "big_photo")) {
        val (p, w, h) = px(name)
        val t = Qr.decode(p, w, h)
        if (t != "https://book.sch.ir/lesson3?x=1") { println("FAIL $name -> $t"); kotlin.system.exitProcess(1) }
        println("  ok ${++n}. QR decoded from '$name' photo")
    }
    val (p, w, h) = px("blank")
    if (Qr.decode(p, w, h) != null) { println("FAIL blank"); kotlin.system.exitProcess(1) }
    println("  ok ${++n}. blank picture -> null (no false QR)")
    println("\nQR: ALL $n CHECKS PASSED")
}
