package com.dhani.tangampere.ocr

import java.util.Locale
import kotlin.math.max

/** Satu potongan teks hasil OCR beserta tinggi kotak (piksel) dan teks baris asalnya. */
data class OcrToken(
    val text: String,
    val height: Float,
    val lineText: String = text,
)

/** Kandidat angka yang mungkin merupakan hasil pengukuran pada layar tang ampere. */
data class ReadingCandidate(
    val display: String,
    val value: Double,
    val unit: String,
    val score: Double,
    val votes: Int = 1,
)

internal data class NormalizedNumber(val display: String, val value: Double, val unit: String)

/**
 * Mengubah teks OCR mentah dari layar LCD (seven-segment) menjadi kandidat angka.
 *
 * Layar seven-segment sering terbaca keliru oleh OCR (mis. "O" untuk 0, "S" untuk 5),
 * sehingga parser ini memperbaiki karakter yang mirip, membuang teks spesifikasi
 * (CAT III 600V, nomor model) dan memberi skor tertinggi pada angka yang paling besar
 * di layar, karena angka pembacaan utama selalu ditampilkan paling besar.
 */
object ReadingParser {

    private val confusions = mapOf(
        'O' to '0', 'o' to '0', 'D' to '0', 'Q' to '0',
        'I' to '1', 'l' to '1', 'i' to '1', '|' to '1', '!' to '1',
        'Z' to '2', 'z' to '2',
        'S' to '5', 's' to '5',
        'G' to '6', 'b' to '6',
        'T' to '7',
        'B' to '8',
        'g' to '9', 'q' to '9',
        ',' to '.',
    )

    private val unitRegex = Regex("^(.*?)(kA|mA|A~|AC|A|kV|mV|V~|V|Hz|Ω)\\.?$", RegexOption.IGNORE_CASE)
    private val ampLineRegex = Regex("(^|[^A-Za-z])(A|AC|ACA|A~|AMP|AMPS)([^A-Za-z]|$)", RegexOption.IGNORE_CASE)
    private val voltLineRegex = Regex("(^|[^A-Za-z])(V|VAC|V~)([^A-Za-z]|$)", RegexOption.IGNORE_CASE)
    private val specRegex = Regex("CAT|MAX|IEC|EN61010|\\d{3,4}\\s?V", RegexOption.IGNORE_CASE)

    internal fun normalize(raw: String): NormalizedNumber? {
        var text = raw.replace(" ", "").trim('-', '_', '~', ':', '\'', '"', '`', '*')
        if (text.isEmpty()) return null

        var unit = ""
        unitRegex.matchEntire(text)?.let { m ->
            val body = m.groupValues[1]
            if (body.any { it.isDigit() }) {
                text = body
                unit = canonicalUnit(m.groupValues[2])
            }
        }

        // Harus ada minimal satu digit asli; kalau tidak, ini hanya label (mis. "HOLD", "S").
        val realDigits = text.count { it.isDigit() }
        if (realDigits == 0) return null
        // Nomor model seperti "UT204" atau "DT266": diawali dua huruf.
        if (text.length >= 2 && text[0].isLetter() && text[1].isLetter()) return null

        val sb = StringBuilder()
        var mapped = 0
        for (c in text) {
            when {
                c.isDigit() || c == '.' -> sb.append(c)
                confusions.containsKey(c) -> {
                    sb.append(confusions.getValue(c)); mapped++
                }
                else -> return null // huruf asing di tengah angka -> bukan pembacaan
            }
        }
        if (mapped > realDigits) return null

        var digits = sb.toString().trimEnd('.')
        if (digits.isEmpty()) return null
        // Jika titik desimal lebih dari satu, pertahankan yang pertama.
        val firstDot = digits.indexOf('.')
        if (firstDot >= 0) {
            digits = digits.substring(0, firstDot + 1) + digits.substring(firstDot + 1).replace(".", "")
        }
        // Hilangkan nol di depan yang tidak perlu ("012.5" -> "12.5"), tapi pertahankan "0.5".
        digits = digits.trimStart('0').let { if (it.isEmpty() || it.startsWith(".")) "0$it" else it }

        if (digits.replace(".", "").length > 6) return null
        val value = digits.toDoubleOrNull() ?: return null
        return NormalizedNumber(digits, value, unit)
    }

    private fun canonicalUnit(u: String): String = when (u.lowercase(Locale.ROOT)) {
        "ka" -> "kA"
        "ma" -> "mA"
        "a", "a~", "ac" -> "A"
        "kv" -> "kV"
        "mv" -> "mV"
        "v", "v~" -> "V"
        "hz" -> "Hz"
        else -> u
    }

    /** Menghasilkan kandidat dari satu kali proses OCR, diurutkan dari skor tertinggi. */
    fun parse(tokens: List<OcrToken>): List<ReadingCandidate> {
        val numeric = tokens.mapNotNull { t -> normalize(t.text)?.let { t to it } }
        if (numeric.isEmpty()) return emptyList()
        val maxH = max(1f, numeric.maxOf { it.first.height })

        val best = LinkedHashMap<String, ReadingCandidate>()
        for ((token, num) in numeric) {
            var unit = num.unit
            if (unit.isEmpty()) {
                val rest = token.lineText.replace(token.text, " ")
                unit = when {
                    ampLineRegex.containsMatchIn(rest) -> "A"
                    voltLineRegex.containsMatchIn(rest) -> "V"
                    else -> ""
                }
            }

            var score = 0.6 * (token.height / maxH)
            when (unit) {
                "A", "kA", "mA" -> score += 0.25
                "" -> score += 0.05
                else -> score -= 0.4 // V, Hz, Ω: bukan arus
            }
            if (num.display.contains('.')) score += 0.08
            if (specRegex.containsMatchIn(token.lineText)) score -= 0.35
            if (num.display.replace(".", "").length > 5) score -= 0.2

            val cand = ReadingCandidate(num.display, num.value, unit.ifEmpty { "A" }, score)
            val prev = best[cand.display]
            if (prev == null || prev.score < cand.score) best[cand.display] = cand
        }
        return best.values.sortedByDescending { it.score }
    }

    /**
     * Menggabungkan kandidat dari beberapa varian gambar (asli, kontras, invers, potong tengah).
     * Angka yang konsisten terbaca di beberapa varian mendapat bonus.
     */
    fun merge(passes: List<List<ReadingCandidate>>): List<ReadingCandidate> {
        val grouped = passes.flatten().groupBy { it.display }
        return grouped.map { (_, list) ->
            val top = list.maxBy { it.score }
            top.copy(score = top.score + 0.08 * (list.size - 1), votes = list.size)
        }.sortedByDescending { it.score }
    }
}
