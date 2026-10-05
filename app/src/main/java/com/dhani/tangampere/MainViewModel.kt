package com.dhani.tangampere

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhani.tangampere.data.Measurement
import com.dhani.tangampere.data.MeasurementDb
import com.dhani.tangampere.data.Phases
import com.dhani.tangampere.ocr.BBox
import com.dhani.tangampere.ocr.ImageUtils
import com.dhani.tangampere.ocr.MeterReader
import com.dhani.tangampere.ocr.ReadResult
import com.dhani.tangampere.ocr.ReadingCandidate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale

data class MeasureUiState(
    val gardu: String = "",
    val jurusan: String = "Induk",
    val phase: String = "R",
    val bitmap: Bitmap? = null,
    val candidates: List<ReadingCandidate> = emptyList(),
    val valueText: String = "",
    val unit: String = "A",
    val rawText: String = "",
    val note: String = "",
    val busy: Boolean = false,
    val message: String? = null,
    /** true jika AI yakin; false berarti ada kandidat lain yang skornya dekat. */
    val confident: Boolean = true,
    /** Mode menandai area layar LCD pada foto. */
    val selectingRegion: Boolean = false,
    val region: BBox? = null,
)

class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val db = MeasurementDb(app)
    private val reader = MeterReader()

    private val _state = MutableStateFlow(MeasureUiState())
    val state: StateFlow<MeasureUiState> = _state.asStateFlow()

    private val _history = MutableStateFlow<List<Measurement>>(emptyList())
    val history: StateFlow<List<Measurement>> = _history.asStateFlow()

    init {
        refreshHistory()
    }

    fun setGardu(v: String) = _state.update { it.copy(gardu = v) }
    fun setJurusan(v: String) = _state.update { it.copy(jurusan = v) }
    fun setPhase(v: String) = _state.update { it.copy(phase = v) }
    fun setValue(v: String) = _state.update { it.copy(valueText = v) }
    fun setUnit(v: String) = _state.update { it.copy(unit = v) }
    fun setNote(v: String) = _state.update { it.copy(note = v) }
    fun consumeMessage() = _state.update { it.copy(message = null) }

    fun pickCandidate(c: ReadingCandidate) = _state.update {
        it.copy(valueText = c.display, unit = if (c.unit in UNITS) c.unit else it.unit, confident = true)
    }

    fun setSelectingRegion(on: Boolean) = _state.update { it.copy(selectingRegion = on) }

    /**
     * Arus fasa lain (R/S/T) pada gardu & jurusan yang sama dalam 24 jam terakhir.
     * Dipakai AI untuk menilai kewajaran angka (beban antar fasa biasanya sebanding).
     */
    private fun referenceFor(s: MeasureUiState): List<Double> {
        val since = System.currentTimeMillis() - 24 * 3600 * 1000L
        val key = s.gardu.trim()
        if (key.isEmpty()) return emptyList()
        return _history.value
            .filter {
                it.gardu.trim().equals(key, ignoreCase = true) && it.jurusan == s.jurusan &&
                    it.phase != s.phase && it.phase != "N" && it.unit == "A" && it.timestamp >= since
            }
            .groupBy { it.phase }
            .map { (_, l) -> l.maxBy { it.timestamp }.value }
    }

    private fun applyResult(result: ReadResult) {
        val best = result.candidates.firstOrNull()
        _state.update {
            it.copy(
                busy = false,
                candidates = result.candidates,
                rawText = result.rawText,
                valueText = best?.display ?: "",
                unit = best?.unit?.takeIf { u -> u in UNITS } ?: it.unit,
                confident = result.confident,
                message = when {
                    best == null -> "Angka tidak terbaca. Tandai area layar, foto ulang lebih dekat, atau isi manual."
                    !result.confident -> "AI ragu. Ketuk kotak angka yang benar pada foto, atau tandai area layar."
                    else -> null
                },
            )
        }
    }

    fun onImage(uri: Uri) {
        _state.update {
            it.copy(busy = true, candidates = emptyList(), message = null, region = null, selectingRegion = false)
        }
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(getApplication(), uri) }
                _state.update { it.copy(bitmap = bmp) }
                val ref = referenceFor(_state.value)
                applyResult(withContext(Dispatchers.Default) { reader.read(bmp, ref) })
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Gagal memproses foto: ${e.message}") }
            }
        }
    }

    /** Baca ulang hanya pada area layar yang ditandai pengguna (koordinat foto). */
    fun readRegion(region: BBox) {
        val bmp = _state.value.bitmap ?: return
        if (region.width < 20f || region.height < 20f) {
            _state.update { it.copy(message = "Area terlalu kecil, tarik kotak mengelilingi layar LCD.") }
            return
        }
        _state.update { it.copy(busy = true, selectingRegion = false, region = region, message = null) }
        viewModelScope.launch {
            try {
                val ref = referenceFor(_state.value)
                applyResult(withContext(Dispatchers.Default) { reader.readRegion(bmp, region, ref) })
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Gagal membaca area: ${e.message}") }
            }
        }
    }

    fun save() {
        val s = _state.value
        val value = s.valueText.replace(',', '.').toDoubleOrNull()
        when {
            s.gardu.isBlank() -> {
                _state.update { it.copy(message = "Isi nama/kode gardu terlebih dahulu") }
                return
            }
            value == null -> {
                _state.update { it.copy(message = "Nilai pengukuran belum valid") }
                return
            }
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val ts = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                val photoPath = s.bitmap?.let { bmp ->
                    val file = File(getApplication<Application>().filesDir, "photos/ukur_$ts.jpg")
                    ImageUtils.saveJpeg(bmp, file)
                    file.absolutePath
                }
                db.insert(
                    Measurement(
                        gardu = s.gardu.trim(),
                        jurusan = s.jurusan,
                        phase = s.phase,
                        value = value!!,
                        unit = s.unit,
                        rawText = s.rawText,
                        photoPath = photoPath,
                        timestamp = ts,
                        note = s.note.trim(),
                    )
                )
            }
            refreshHistory()
            val idx = Phases.ALL.indexOf(s.phase)
            val next = Phases.ALL[(idx + 1) % Phases.ALL.size]
            _state.update {
                it.copy(
                    busy = false,
                    phase = next,
                    bitmap = null,
                    candidates = emptyList(),
                    valueText = "",
                    rawText = "",
                    note = "",
                    region = null,
                    selectingRegion = false,
                    confident = true,
                    message = "Tersimpan: ${s.gardu.trim()} ${s.jurusan} fasa ${s.phase} = " +
                        String.format(Locale("id"), "%.1f", value) + " ${s.unit}. Lanjut fasa $next.",
                )
            }
        }
    }

    fun delete(m: Measurement) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                db.delete(m.id)
                m.photoPath?.let { File(it).delete() }
            }
            refreshHistory()
        }
    }

    private fun refreshHistory() {
        viewModelScope.launch {
            _history.value = withContext(Dispatchers.IO) { db.all() }
        }
    }

    override fun onCleared() {
        reader.close()
        db.close()
    }

    companion object {
        val UNITS = listOf("A", "mA", "kA", "V")
    }
}
