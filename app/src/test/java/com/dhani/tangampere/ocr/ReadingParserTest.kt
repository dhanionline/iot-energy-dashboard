package com.dhani.tangampere.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReadingParserTest {

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
    fun parse_prefersLargestAmpReading() {
        val tokens = listOf(
            OcrToken("UNI-T", 20f, "UNI-T"),
            OcrToken("UT204", 18f, "UT204"),
            OcrToken("AUTO", 25f, "AUTO HOLD"),
            OcrToken("187.3", 120f, "187.3 A"),
            OcrToken("A", 40f, "187.3 A"),
            OcrToken("600V", 14f, "CAT III 600V"),
            OcrToken("400", 22f, "400"),
        )
        val result = ReadingParser.parse(tokens)
        assertEquals("187.3", result.first().display)
        assertEquals("A", result.first().unit)
        assertTrue(result.none { it.display == "204" })
    }

    @Test
    fun parse_penalisesVoltageSpecs() {
        val tokens = listOf(
            OcrToken("1000V", 60f, "CAT III 1000V"),
            OcrToken("45.2", 50f, "45.2"),
        )
        assertEquals("45.2", ReadingParser.parse(tokens).first().display)
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
