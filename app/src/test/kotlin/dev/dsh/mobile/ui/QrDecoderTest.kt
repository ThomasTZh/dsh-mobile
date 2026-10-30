package dev.dsh.mobile.ui

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import dev.dsh.mobile.ui.scanner.QrDecoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QrDecoderTest {
    @Test
    fun decodesAGeneratedQrCode() {
        val payload = "http://192.168.1.15:3081"
        val matrix: BitMatrix = QRCodeWriter().encode(payload, BarcodeFormat.QR_CODE, 240, 240, mapOf(EncodeHintType.MARGIN to 1))
        val width = matrix.width
        val height = matrix.height
        val pixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                pixels[y * width + x] = if (matrix.get(x, y)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        assertEquals(payload, QrDecoder.decodeRgb(width, height, pixels))
    }

    @Test
    fun returnsNullForNoise() {
        val pixels = IntArray(64 * 64) { 0xFFFFFFFF.toInt() }
        assertNull(QrDecoder.decodeRgb(64, 64, pixels))
        assertNull(QrDecoder.decodeRgb(0, 0, IntArray(0)))
    }
}
