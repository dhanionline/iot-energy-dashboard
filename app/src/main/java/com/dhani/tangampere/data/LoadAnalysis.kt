package com.dhani.tangampere.data

import kotlin.math.abs

/** Ringkasan beban terakhir untuk satu gardu + jurusan. */
data class GroupSummary(
    val gardu: String,
    val jurusan: String,
    val latest: Map<String, Measurement>,
    val imbalancePercent: Double?,
    val totalKva: Double?,
    val lastUpdated: Long,
)

object LoadAnalysis {

    /** Tegangan fasa-netral standar jaringan tegangan rendah (V). */
    const val PHASE_VOLTAGE = 220.0

    /**
     * Ketidakseimbangan beban (%) dengan metode rata-rata deviasi:
     * ((|R-rata| + |S-rata| + |T-rata|) / (3 x rata)) x 100.
     */
    fun imbalancePercent(r: Double, s: Double, t: Double): Double? {
        val avg = (r + s + t) / 3.0
        if (avg <= 0.0) return null
        return (abs(r - avg) + abs(s - avg) + abs(t - avg)) / (3.0 * avg) * 100.0
    }

    fun status(percent: Double): String = when {
        percent <= 10.0 -> "Seimbang"
        percent <= 20.0 -> "Waspada"
        else -> "Tidak seimbang"
    }

    /** Estimasi daya semu total (kVA) dari arus R+S+T pada tegangan fasa 220 V. */
    fun totalKva(r: Double, s: Double, t: Double): Double = (r + s + t) * PHASE_VOLTAGE / 1000.0

    private fun amps(m: Measurement): Double? = when (m.unit) {
        "A" -> m.value
        "kA" -> m.value * 1000.0
        "mA" -> m.value / 1000.0
        else -> null
    }

    /** Mengambil pengukuran terbaru tiap fasa untuk setiap gardu + jurusan. */
    fun summarize(measurements: List<Measurement>): List<GroupSummary> =
        measurements
            .groupBy { it.gardu.trim().uppercase() to it.jurusan }
            .map { (_, list) ->
                val latest = list.groupBy { it.phase }
                    .mapValues { (_, l) -> l.maxBy { it.timestamp } }
                val r = latest["R"]?.let(::amps)
                val s = latest["S"]?.let(::amps)
                val t = latest["T"]?.let(::amps)
                val complete = r != null && s != null && t != null
                GroupSummary(
                    gardu = list.maxBy { it.timestamp }.gardu.trim(),
                    jurusan = list.first().jurusan,
                    latest = latest,
                    imbalancePercent = if (complete) imbalancePercent(r!!, s!!, t!!) else null,
                    totalKva = if (complete) totalKva(r!!, s!!, t!!) else null,
                    lastUpdated = list.maxOf { it.timestamp },
                )
            }
            .sortedByDescending { it.lastUpdated }
}
