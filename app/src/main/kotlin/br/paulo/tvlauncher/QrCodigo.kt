package br.paulo.tvlauncher

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import java.io.ByteArrayOutputStream

/** QR code de um texto curto (URL), em preto no branco com borda: o celular lê da TV a 3 m. Biblioteca: zxing core. */
object QrCodigo {
    fun bitmap(texto: String, lado: Int): Bitmap {
        val m = QRCodeWriter().encode(texto, BarcodeFormat.QR_CODE, lado, lado,
            mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
        val px = IntArray(m.width * m.height)
        for (y in 0 until m.height) for (x in 0 until m.width) px[y * m.width + x] = if (m[x, y]) Color.BLACK else Color.WHITE
        return Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.RGB_565)
    }

    /** Para o painel web (imagem PNG). */
    fun png(texto: String, lado: Int = 360): ByteArray =
        ByteArrayOutputStream().also { bitmap(texto, lado).compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
}
