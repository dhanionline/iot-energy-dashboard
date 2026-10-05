package com.dhani.tangampere.data

data class Measurement(
    val id: Long = 0,
    val gardu: String,
    val jurusan: String,
    val phase: String,
    val value: Double,
    val unit: String,
    val rawText: String,
    val photoPath: String?,
    val timestamp: Long,
    val note: String,
)

object Phases {
    val ALL = listOf("R", "S", "T", "N")
}

object Jurusan {
    val ALL = listOf("Induk", "A", "B", "C", "D")
}
