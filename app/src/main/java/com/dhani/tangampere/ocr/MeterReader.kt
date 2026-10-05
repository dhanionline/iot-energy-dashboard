package com.dhani.tangampere.ocr

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.tasks.await

data class ReadResult(
    val candidates: List<ReadingCandidate>,
    val rawText: String,
)

/**
 * Mesin AI pembaca layar tang ampere.
 *
 * Menggunakan model ML Kit Text Recognition yang dibundel di APK (berjalan 100% offline).
 * Gambar diproses dalam beberapa varian (asli, kontras tinggi, invers, potong tengah)
 * lalu hasilnya digabung oleh [ReadingParser] untuk memilih angka pembacaan yang paling mungkin.
 */
class MeterReader : AutoCloseable {

    private val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)

    suspend fun read(bitmap: Bitmap): ReadResult {
        val variants = listOf(
            bitmap,
            ImageUtils.enhance(bitmap, invert = false),
            ImageUtils.enhance(bitmap, invert = true),
            ImageUtils.centerCrop(bitmap, 0.6f),
        )
        val passes = mutableListOf<List<ReadingCandidate>>()
        var rawText = ""
        for (variant in variants) {
            val text = recognizer.process(InputImage.fromBitmap(variant, 0)).await()
            if (rawText.isBlank()) rawText = text.text
            passes += ReadingParser.parse(tokensOf(text))
        }
        return ReadResult(ReadingParser.merge(passes).take(6), rawText.trim())
    }

    private fun tokensOf(text: Text): List<OcrToken> {
        val tokens = mutableListOf<OcrToken>()
        for (block in text.textBlocks) {
            for (line in block.lines) {
                val lineHeight = line.boundingBox?.height()?.toFloat() ?: 0f
                for (element in line.elements) {
                    val h = element.boundingBox?.height()?.toFloat() ?: lineHeight
                    tokens += OcrToken(element.text, h, line.text)
                }
                // Angka LCD kadang terpecah ("12" ".5"), jadi baris utuh juga dijadikan kandidat.
                if (line.elements.size > 1) {
                    tokens += OcrToken(line.text.replace(" ", ""), lineHeight, line.text)
                }
            }
        }
        return tokens
    }

    override fun close() = recognizer.close()
}
