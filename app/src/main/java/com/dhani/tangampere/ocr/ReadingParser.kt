package com.dhani.tangampere.ocr

import java.util.Locale
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Kotak pembatas dalam koordinat piksel foto asli. */
data class BBox(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
    val centerX get() = (left + right) / 2f
    val centerY get() = (top + bottom) / 2f

    fun contains(x: Float, y: Float, margin: Float = 0f) =
        x >= left - margin && x <= right + margin && y >= top - margin && y <= bottom + margin

    fun verticalOverlap(o: BBox): Float = max(0f, min(bottom, o.bottom) - max(top, o.top))
}

/** Satu potongan teks hasil OCR beserta posisinya dan teks baris asalnya. */
data class OcrToken(
    val text: String,
    val box: BBox,
    val lineText: String = text,
    val confidence: Float? = null,
) {
    val height get() = box.height
}

/** Kandidat angka yang mungkin merupakan hasil pengukuran pada layar tang ampere. */
data class ReadingCandidate(
    val display: String,
    val value: Double,
    val unit: String,
    val score: Double,
    val votes: Int = 1,
    val box: BBox? = null,
    val reasons: List<String> = emptyList(),
)

/**
 * Informasi tambahan untuk memilih angka yang benar.
 * @param frame area yang sedang dibaca (seluruh foto, atau area layar yang ditandai pengguna).
 * @param reference nilai arus fasa lain pada gardu & jurusan yang sama (A), untuk cek kewajaran.
 */
data class ParseContext(
    val frame: BBox? = null,
    val reference: List<Double> = emptyList(),
)

internal data class NormalizedNumber(val display: String, val value: Double, val unit: String)

/**
 * Mengubah teks OCR mentah dari layar LCD (seven-segment) menjadi kandidat angka berperingkat.
 *
 * Angka pembacaan pada tang ampere memiliki ciri khas yang dipakai sebagai bobot skor:
 *  - karakter paling besar di foto (digit LCD jauh lebih besar dari tulisan di badan alat),
 *  - berdiri sendiri pada barisnya, biasanya diikuti satuan "A",
 *  - berada dekat tengah bidikan,
 *  - bukan label skala saklar putar (2/20/200/400/600/1000) atau spesifikasi (CAT III 600V),
 *  - nilainya wajar untuk arus gardu dan sebanding dengan fasa lain di gardu yang sama.
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
    private val ampWordRegex = Regex("^(A|AC|ACA|A~|AMP|AMPS|AAC)$", RegexOption.IGNORE_CASE)
    private val voltWordRegex = Regex("^(V|VAC|V~|VDC|Hz|Ω|kΩ|MΩ)$", RegexOption.IGNORE_CASE)
    private val specRegex = Regex("CAT|IEC|EN ?61010|\\d{3,4}\\s?V\\b", RegexOption.IGNORE_CASE)

    /** Kata yang memang muncul di layar LCD di sekitar angka (bukan tulisan cetak di badan alat). */
    private val annunciators = setOf(
        "HOLD", "H", "AUTO", "AC", "DC", "A", "V", "MAX", "MIN", "REL", "NCV", "HZ", "~", "AC~", "ACA",
        "RANGE", "PEAK", "INRUSH", "TRMS", "MANU", "APO",
    )

    /** Angka label skala saklar putar yang sering tercetak di badan tang ampere. */
    private val rangeLabels = setOf(2.0, 4.0, 20.0, 40.0, 60.0, 200.0, 400.0, 600.0, 750.0, 1000.0, 2000.0)

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

    private fun isAmp(unit: String) = unit == "A" || unit == "kA" || unit == "mA"

    /** Cari satuan dari token di sebelah kanan angka (sejajar), atau dari teks barisnya. */
    private fun nearbyUnit(token: OcrToken, all: List<OcrToken>): String {
        val h = token.height
        for (o in all) {
            if (o === token) continue
            val word = o.text.trim()
            val isAmpWord = ampWordRegex.matches(word)
            val isVoltWord = voltWordRegex.matches(word)
            if (!isAmpWord && !isVoltWord) continue
            val gap = o.box.left - token.box.right
            val sameRow = token.box.verticalOverlap(o.box) > 0.3f * min(h, o.height)
            if (sameRow && gap > -0.2f * h && gap < 1.5f * h) return if (isAmpWord) "A" else "V"
        }
        val rest = token.lineText.split(Regex("\\s+")).filter { it.isNotBlank() && it != token.text }
        return when {
            rest.any { ampWordRegex.matches(it) } -> "A"
            rest.any { voltWordRegex.matches(it) } -> "V"
            else -> ""
        }
    }

    /** Menghasilkan kandidat dari satu kali proses OCR, diurutkan dari skor tertinggi. */
    fun parse(tokens: List<OcrToken>, ctx: ParseContext = ParseContext()): List<ReadingCandidate> {
        val numeric = tokens.mapNotNull { t -> normalize(t.text)?.let { t to it } }
        if (numeric.isEmpty()) return emptyList()
        val maxH = max(1f, numeric.maxOf { it.first.height })
        val frame = ctx.frame ?: BBox(
            tokens.minOf { it.box.left }, tokens.minOf { it.box.top },
            tokens.maxOf { it.box.right }, tokens.maxOf { it.box.bottom },
        )
        val refMedian = ctx.reference.filter { it > 0 }.sorted().let { if (it.isEmpty()) null else it[it.size / 2] }

        val best = LinkedHashMap<String, ReadingCandidate>()
        for ((token, num) in numeric) {
            val reasons = mutableListOf<String>()
            val unit = num.unit.ifEmpty { nearbyUnit(token, tokens) }

            // 1. Ukuran: digit LCD hampir selalu teks terbesar. Dikuadratkan agar selisih ukuran terasa.
            val hRel = token.height / maxH
            var score = 0.55 * hRel * hRel
            if (hRel > 0.9f) reasons += "angka terbesar"

            // 2. Satuan.
            when {
                isAmp(unit) -> { score += 0.2; reasons += "satuan A" }
                unit.isEmpty() -> score += 0.03
                else -> { score -= 0.45; reasons += "satuan $unit (bukan arus)" }
            }

            // 3. Posisi: layar biasanya di tengah bidikan.
            val dx = (token.box.centerX - frame.centerX) / max(1f, frame.width / 2f)
            val dy = (token.box.centerY - frame.centerY) / max(1f, frame.height / 2f)
            val dist = min(1.0, hypot(dx.toDouble(), dy.toDouble()) / 1.414)
            score += 0.1 * (1.0 - dist)

            // 4. Berdiri sendiri pada barisnya (tulisan cetak biasanya bercampur kata lain).
            val otherWords = token.lineText.split(Regex("\\s+"))
                .filter { it.isNotBlank() && it != token.text && normalize(it) == null }
                .filterNot { it.uppercase(Locale.ROOT) in annunciators }
            if (otherWords.isEmpty()) score += 0.06 else score -= 0.08

            if (num.display.contains('.')) score += 0.06
            if (specRegex.containsMatchIn(token.lineText)) { score -= 0.35; reasons += "teks spesifikasi" }
            if (token.confidence != null) score += 0.1 * (token.confidence - 0.5)

            // 5. Label skala saklar (200, 400, 600...) tanpa desimal.
            if (!num.display.contains('.') && num.value in rangeLabels) {
                score -= if (num.unit.isNotEmpty()) 0.3 else 0.1
                reasons += "mirip label skala"
            }

            // 6. Kewajaran nilai arus gardu tegangan rendah.
            val amps = when (unit) { "kA" -> num.value * 1000; "mA" -> num.value / 1000; else -> num.value }
            if (num.display.replace(".", "").length > 5) score -= 0.2
            if (isAmp(unit) || unit.isEmpty()) {
                if (amps > 3000) { score -= 0.2; reasons += "terlalu besar" }
                if (amps == 0.0) score -= 0.05
                if (refMedian != null && amps > 0) {
                    val ratio = amps / refMedian
                    if (ratio in 0.33..3.0) { score += 0.12; reasons += "sebanding fasa lain" }
                    else if (ratio > 10 || ratio < 0.1) score -= 0.1
                }
            }

            val cand = ReadingCandidate(num.display, num.value, unit.ifEmpty { "A" }, score, box = token.box, reasons = reasons)
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
            val votes = list.size
            top.copy(score = top.score + 0.06 * (min(votes, 4) - 1), votes = votes)
        }.sortedByDescending { it.score }
    }

    /** Selisih skor juara vs runner-up; kecil berarti AI ragu dan pengguna sebaiknya memeriksa. */
    fun isConfident(candidates: List<ReadingCandidate>): Boolean {
        if (candidates.isEmpty()) return false
        if (candidates.size == 1) return candidates[0].score > 0.5
        return candidates[0].score - candidates[1].score > 0.15 && candidates[0].score > 0.5
    }
}
