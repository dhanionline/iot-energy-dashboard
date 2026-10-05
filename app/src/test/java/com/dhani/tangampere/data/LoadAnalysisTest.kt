package com.dhani.tangampere.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadAnalysisTest {

    private fun m(gardu: String, jurusan: String, phase: String, value: Double, ts: Long) =
        Measurement(0, gardu, jurusan, phase, value, "A", "", null, ts, "")

    @Test
    fun imbalance_balancedLoadIsZero() {
        assertEquals(0.0, LoadAnalysis.imbalancePercent(100.0, 100.0, 100.0)!!, 1e-9)
    }

    @Test
    fun imbalance_matchesFormula() {
        // rata = 100; deviasi = 20 + 0 + 20 = 40; 40 / 300 * 100 = 13.33 %
        assertEquals(13.333, LoadAnalysis.imbalancePercent(120.0, 100.0, 80.0)!!, 1e-3)
        assertEquals("Waspada", LoadAnalysis.status(13.333))
    }

    @Test
    fun imbalance_noLoadIsNull() {
        assertNull(LoadAnalysis.imbalancePercent(0.0, 0.0, 0.0))
    }

    @Test
    fun summarize_usesLatestPerPhase() {
        val data = listOf(
            m("GT-01", "Induk", "R", 50.0, 1),
            m("GT-01", "Induk", "R", 120.0, 5),
            m("gt-01 ", "Induk", "S", 100.0, 6),
            m("GT-01", "Induk", "T", 80.0, 7),
            m("GT-02", "A", "R", 10.0, 2),
        )
        val summary = LoadAnalysis.summarize(data)
        assertEquals(2, summary.size)
        val gt1 = summary.first()
        assertEquals(120.0, gt1.latest.getValue("R").value, 1e-9)
        assertEquals(13.333, gt1.imbalancePercent!!, 1e-3)
        assertEquals(66.0, gt1.totalKva!!, 1e-9)
        assertNull(summary[1].imbalancePercent)
    }
}
