package dev.dsh.mobile.ui.scanner

import android.graphics.Bitmap
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.common.HybridBinarizer

/** QR decoding, kept independent of the camera so it can be unit tested. */
object QrDecoder {
    private val hints: Map<DecodeHintType, Any> = mapOf(
        DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
        DecodeHintType.TRY_HARDER to true,
    )

    fun decodeYuvImage(image: ImageProxy): String? {
        val plane = image.planes.firstOrNull() ?: return null
        val width = image.width
        val height = image.height
        if (width <= 0 || height <= 0) return null
        val buffer = plane.buffer
        val rowStride = plane.rowStride
        val pixelStride = plane.pixelStride
        if (rowStride <= 0 || pixelStride <= 0) return null
        val data = ByteArray(width * height)
        val row = ByteArray(rowStride)
        var offset = 0
        for (y in 0 until height) {
            val position = y * rowStride
            if (position >= buffer.limit()) break
            buffer.position(position)
            val length = minOf(rowStride, buffer.remaining())
            buffer.get(row, 0, length)
            for (x in 0 until width) {
                val index = x * pixelStride
                if (index < length) data[offset + x] = row[index]
            }
            offset += width
        }
        val (rotated, rotatedWidth, rotatedHeight) = rotate(data, width, height, image.imageInfo.rotationDegrees)
        return decodeLuminance(rotated, rotatedWidth, rotatedHeight)
    }

    fun decodeBitmap(bitmap: Bitmap): String? {
        val max = 1600
        val scale = minOf(1f, max.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat())
        val target = if (scale < 1f) {
            Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt(), (bitmap.height * scale).toInt(), true)
        } else {
            bitmap
        }
        val targetWidth = target.width
        val targetHeight = target.height
        val pixels = IntArray(targetWidth * targetHeight)
        target.getPixels(pixels, 0, targetWidth, 0, 0, targetWidth, targetHeight)
        if (target !== bitmap) target.recycle()
        return decodeRgb(targetWidth, targetHeight, pixels)
    }

    fun decodeRgb(width: Int, height: Int, pixels: IntArray): String? {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return null
        return runCatching {
            val source = RGBLuminanceSource(width, height, pixels)
            decodeBinary(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()
    }

    private fun decodeLuminance(data: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0 || data.size < width * height) return null
        return runCatching {
            val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
            decodeBinary(BinaryBitmap(HybridBinarizer(source)))
        }.getOrNull()
    }

    private fun decodeBinary(bitmap: BinaryBitmap): String? {
        val reader = MultiFormatReader()
        return try {
            reader.setHints(hints)
            reader.decode(bitmap).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    private fun rotate(data: ByteArray, width: Int, height: Int, degrees: Int): Triple<ByteArray, Int, Int> {
        return when (degrees % 360) {
            90 -> {
                val out = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[x * height + (height - 1 - y)] = data[y * width + x]
                    }
                }
                Triple(out, height, width)
            }
            180 -> {
                val out = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(height - 1 - y) * width + (width - 1 - x)] = data[y * width + x]
                    }
                }
                Triple(out, width, height)
            }
            270 -> {
                val out = ByteArray(width * height)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        out[(width - 1 - x) * height + y] = data[y * width + x]
                    }
                }
                Triple(out, height, width)
            }
            else -> Triple(data, width, height)
        }
    }
}
