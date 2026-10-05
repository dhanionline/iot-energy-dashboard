package com.dhani.tangampere.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingParserTest {

    /** Token dengan kotak di (x, y) setinggi h; lebar kira-kira 0.6h per karakter. */
    private fun tok(text: String, x: Float, y: Float, h: Float, line: String = text, conf: Float? = null) =
        OcrToken(text, BBox(x, y, x + 0.6f * h * text.length, y + h), line, conf)

    private val photo = BBox(0f, 0f, 1000f, 1400f)

    @Test
    fun normalize_plainNumbers() {
        assertEquals("125.4", ReadingParser.normalize("125.4")?.display)
        assertEquals("0.5", ReadingParser.normalize(".5")?.display)
        assertEquals("12.5", ReadingParser.normalize("012.5")?.display)
        assertEquals("0", ReadingParser.normalize("000")?.display)
        assertEquals("12.5", ReadingParser.normalize("12,5")?.display)
    }

    @Test
    fun normalize_fixesSevenSegmentConfusions() {
        assertEquals("10.5", ReadingParser.normalize("1O.5")?.display)
        assertEquals("155", ReadingParser.normalize("1S5")?.display)
        assertEquals("281", ReadingParser.normalize("2B1")?.display)
        assertEquals("71.2", ReadingParser.normalize("7l.2")?.display)
    }

    @Test
    fun normalize_extractsUnits() {
        assertEquals("A", ReadingParser.normalize("98.6A")?.unit)
        assertEquals("V", ReadingParser.normalize("230V")?.unit)
        assertEquals("mA", ReadingParser.normalize("45mA")?.unit)
        assertEquals(98.6, ReadingParser.normalize("98.6A")!!.value, 1e-9)
    }

    @Test
    fun normalize_rejectsLabelsAndModelNumbers() {
        assertNull(ReadingParser.normalize("HOLD"))
        assertNull(ReadingParser.normalize("S"))
        assertNull(ReadingParser.normalize("OL"))
        assertNull(ReadingParser.normalize("UT204"))
        assertNull(ReadingParser.normalize("DT266"))
        assertNull(ReadingParser.normalize("SO1"))
        assertNull(ReadingParser.normalize("1234567"))
    }

    @Test
    fun parse_typicalClampMeterPhoto() {
        val tokens = listOf(
            tok("UNI-T", 380f, 150f, 50f),
            tok("UT204", 420f, 230f, 40f),
            tok("AUTO", 300f, 520f, 30f, "AUTO HOLD"),
            tok("HOLD", 450f, 520f, 30f, "AUTO HOLD"),
            tok("187.3", 320f, 580f, 120f, "187.3 A"),
            tok("A", 700f, 640f, 50f, "187.3 A"),
            tok("400A", 200f, 900f, 35f, "400A"),
            tok("40A", 650f, 900f, 35f, "40A"),
            tok("600V", 300f, 1250f, 25f, "CAT III 600V"),
        )
        val result = ReadingParser.parse(tokens, ParseContext(photo))
        assertEquals("187.3", result.first().display)
        assertEquals("A", result.first().unit)
        assertTrue(result.none { it.display == "204" })
        assertTrue(ReadingParser.isConfident(result))
    }

    @Test
    fun parse_bigBrandModelNumberLosesToLcdReading() {
        // Nomor model tercetak besar ("376") di atas, angka LCD tanpa titik desimal di tengah.
        val tokens = listOf(
            tok("FLUKE", 250f, 100f, 90f, "FLUKE 376"),
            tok("376", 600f, 100f, 90f, "FLUKE 376"),
            tok("52", 400f, 650f, 95f, "52"),
            tok("A", 520f, 690f, 40f, "A"),
        )
        val result = ReadingParser.parse(tokens, ParseContext(photo))
        assertEquals("52", result.first().display)
    }

    @Test
    fun parse_dialRangeLabelIsNotTheReading() {
        // Label saklar "400A" sedikit lebih besar dari digit LCD yang kecil.
        val tokens = listOf(
            tok("400A", 450f, 700f, 70f, "400A"),
            tok("23.6", 400f, 450f, 65f, "23.6"),
        )
        assertEquals("23.6", ReadingParser.parse(tokens, ParseContext(photo)).first().display)
    }

    @Test
    fun parse_penalisesVoltageSpecs() {
        val tokens = listOf(
            tok("1000V", 300f, 1200f, 60f, "CAT III 1000V"),
            tok("45.2", 400f, 600f, 50f, "45.2"),
        )
        assertEquals("45.2", ReadingParser.parse(tokens, ParseContext(photo)).first().display)
    }

    @Test
    fun parse_unitFromNeighbourToken() {
        val tokens = listOf(
            tok("88.4", 300f, 600f, 100f, "88.4"),
            tok("V", 560f, 620f, 60f, "V"),
            tok("12.1", 300f, 800f, 95f, "12.1"),
            tok("A", 560f, 820f, 60f, "A"),
        )
        val result = ReadingParser.parse(tokens, ParseContext(photo))
        assertEquals("12.1", result.first().display)
        assertEquals("V", result.first { it.display == "88.4" }.unit)
    }

    @Test
    fun parse_referenceFromOtherPhasesBreaksTies() {
        // Dua angka sama besar & sama posisi: yang sebanding dengan fasa R/S yang menang.
        val tokens = listOf(
            tok("125", 400f, 650f, 100f, "125"),
            tok("12.5", 400f, 650f, 100f, "12.5"),
        )
        val noRef = ReadingParser.parse(tokens, ParseContext(photo))
        assertEquals("12.5", noRef.first().display) // tanpa referensi, desimal sedikit unggul
        val withRef = ReadingParser.parse(tokens, ParseContext(photo, reference = listOf(118.0, 131.0)))
        assertEquals("125", withRef.first().display)
    }

    @Test
    fun isConfident_falseWhenCandidatesClose() {
        val close = listOf(ReadingCandidate("10", 10.0, "A", 0.8), ReadingCandidate("70", 70.0, "A", 0.75))
        assertFalse(ReadingParser.isConfident(close))
    }

    @Test
    fun merge_rewardsAgreementAcrossVariants() {
        val a = listOf(ReadingCandidate("88.1", 88.1, "A", 0.70), ReadingCandidate("38.1", 38.1, "A", 0.72))
        val b = listOf(ReadingCandidate("88.1", 88.1, "A", 0.69))
        val c = listOf(ReadingCandidate("88.1", 88.1, "A", 0.65))
        val merged = ReadingParser.merge(listOf(a, b, c))
        assertEquals("88.1", merged.first().display)
        assertEquals(3, merged.first().votes)
    }
}
