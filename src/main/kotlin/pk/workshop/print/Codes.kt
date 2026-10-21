package pk.workshop.print

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import java.awt.image.BufferedImage

/** Barcode and QR images (ZXing). */
object Codes {
    private fun toImage(m: BitMatrix): BufferedImage {
        val img = BufferedImage(m.width, m.height, BufferedImage.TYPE_BYTE_BINARY)
        for (x in 0 until m.width) for (y in 0 until m.height) img.setRGB(x, y, if (m[x, y]) 0x000000 else 0xFFFFFF)
        return img
    }

    /** EAN-13 when the code is 13 digits with a valid check digit, otherwise Code 128. */
    fun barcode(code: String, width: Int = 600, height: Int = 160): BufferedImage {
        val fmt = if (code.length == 13 && code.all { it.isDigit() } && ean13Valid(code)) BarcodeFormat.EAN_13 else BarcodeFormat.CODE_128
        val m = MultiFormatWriter().encode(code, fmt, width, height, mapOf(EncodeHintType.MARGIN to 4))
        return toImage(m)
    }

    fun qr(text: String, size: Int = 300): BufferedImage {
        val m = MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 1, EncodeHintType.CHARACTER_SET to "UTF-8"))
        return toImage(m)
    }

    fun ean13Valid(code: String): Boolean {
        val d = code.map { it - '0' }
        val sum = (0 until 12).sumOf { if (it % 2 == 0) d[it] else d[it] * 3 }
        return (10 - sum % 10) % 10 == d[12]
    }
}
