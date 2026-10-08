package com.ayuvo.health.partner

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.common.BitMatrix
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

/** Renders the pairing QR text (docs/partner-sync.md §4) for the Show code screen. Generated on device by zxing. */
object PartnerQrBitmap {
    /** The module matrix (JVM-testable). */
    fun matrix(text: String, sizePx: Int): BitMatrix = QRCodeWriter().encode(
        text, BarcodeFormat.QR_CODE, sizePx, sizePx,
        mapOf(EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M, EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8")
    )

    fun bitmap(text: String, sizePx: Int = 720, dark: Int = Color.BLACK, light: Int = Color.WHITE): Bitmap {
        val m = matrix(text, sizePx)
        val w = m.width
        val h = m.height
        val pixels = IntArray(w * h)
        for (y in 0 until h) {
            val row = y * w
            for (x in 0 until w) pixels[row + x] = if (m[x, y]) dark else light
        }
        return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
    }
}
