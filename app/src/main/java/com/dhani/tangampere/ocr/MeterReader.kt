package com.dhani.tangampere.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await
import kotlin.math.max
import kotlin.math.roundToInt

data class ReadResult(
    val candidates: List<ReadingCandidate>,
    val rawText: String,
    val confident: Boolean,
)

/**
 * Mesin AI pembaca layar tang ampere.
 *
 * Menggunakan model ML Kit Text Recognition yang dibundel di APK (berjalan 100% offline).
 * Gambar diproses dalam beberapa varian lalu hasilnya digabung oleh [ReadingParser].
 * Semua kotak hasil dipetakan kembali ke koordinat foto asli agar bisa ditampilkan & diketuk.
 */
class MeterReader : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    /** Pemetaan koordinat varian -> foto asli: x_asli = x / scale + offsetX. */
    private data class Variant(val bitmap: Bitmap, val offsetX: Float = 0f, val offsetY: Float = 0f, val scale: Float = 1f)

    /** Membaca seluruh foto. */
    suspend fun read(bitmap: Bitmap, reference: List<Double>): ReadResult {
        val cropW = (bitmap.width * 0.6f).roundToInt()
        val cropH = (bitmap.height * 0.6f).roundToInt()
        val variants = listOf(
            Variant(bitmap),
            Variant(ImageUtils.enhance(bitmap, invert = false)),
            Variant(ImageUtils.enhance(bitmap, invert = true)),
            Variant(
                ImageUtils.centerCrop(bitmap, 0.6f),
                offsetX = ((bitmap.width - cropW) / 2).toFloat(),
                offsetY = ((bitmap.height - cropH) / 2).toFloat(),
            ),
        )
        val frame = BBox(0f, 0f, bitmap.width.toFloat(), bitmap.height.toFloat())
        return run(variants, ParseContext(frame, reference))
    }

    /**
     * Membaca hanya area layar yang ditandai pengguna. Area diperbesar agar digit LCD
     * yang kecil tetap terbaca jelas oleh model.
     */
    suspend fun readRegion(bitmap: Bitmap, region: BBox, reference: List<Double>): ReadResult {
        val left = region.left.roundToInt().coerceIn(0, bitmap.width - 1)
        val top = region.top.roundToInt().coerceIn(0, bitmap.height - 1)
        val w = region.width.roundToInt().coerceIn(1, bitmap.width - left)
        val h = region.height.roundToInt().coerceIn(1, bitmap.height - top)
        val crop = Bitmap.createBitmap(bitmap, left, top, w, h)
        val scale = (1000f / max(w, h)).coerceIn(1f, 4f)
        val big = if (scale > 1f) {
            Bitmap.createScaledBitmap(crop, (w * scale).roundToInt(), (h * scale).roundToInt(), true)
        } else crop
        val variants = listOf(big, ImageUtils.enhance(big, false), ImageUtils.enhance(big, true))
            .map { Variant(it, left.toFloat(), top.toFloat(), scale) }
        val frame = BBox(left.toFloat(), top.toFloat(), (left + w).toFloat(), (top + h).toFloat())
        return run(variants, ParseContext(frame, reference))
    }

    private suspend fun run(variants: List<Variant>, ctx: ParseContext): ReadResult {
        val passes = mutableListOf<List<ReadingCandidate>>()
        var rawText = ""
        for (v in variants) {
            val text = recognizer.process(InputImage.fromBitmap(v.bitmap, 0)).await()
            if (rawText.isBlank()) rawText = text.text
            passes += ReadingParser.parse(tokensOf(text, v), ctx)
        }
        val merged = ReadingParser.merge(passes).take(8)
        return ReadResult(merged, rawText.trim(), ReadingParser.isConfident(merged))
    }

    private fun mapBox(r: android.graphics.Rect?, v: Variant): BBox? = r?.let {
        BBox(
            it.left / v.scale + v.offsetX,
            it.top / v.scale + v.offsetY,
            it.right / v.scale + v.offsetX,
            it.bottom / v.scale + v.offsetY,
        )
    }

    private fun tokensOf(text: Text, v: Variant): List<OcrToken> {
        val tokens = mutableListOf<OcrToken>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineBox = mapBox(line.boundingBox, v) ?: continue
                for (element in line.elements) {
                    val box = mapBox(element.boundingBox, v) ?: lineBox
                    tokens += OcrToken(element.text, box, line.text, element.confidence)
                }
                // Angka LCD kadang terpecah ("12" ".5"), jadi baris utuh juga dijadikan kandidat.
                if (line.elements.size > 1) {
                    tokens += OcrToken(line.text.replace(" ", ""), lineBox, line.text, line.confidence)
                }
            }
        }
        return tokens
    }

    override fun close() = recognizer.close()
}
