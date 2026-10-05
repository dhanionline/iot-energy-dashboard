package com.dhani.tangampere

import android.app.Application
import android.graphics.Bitmap
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.dhani.tangampere.data.Measurement
import com.dhani.tangampere.data.MeasurementDb
import com.dhani.tangampere.data.Phases
import com.dhani.tangampere.ocr.ImageUtils
import com.dhani.tangampere.ocr.MeterReader
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
        it.copy(valueText = c.display, unit = if (c.unit in UNITS) c.unit else it.unit)
    }

    fun onImage(uri: Uri) {
        _state.update { it.copy(busy = true, candidates = emptyList(), message = null) }
        viewModelScope.launch {
            try {
                val bmp = withContext(Dispatchers.IO) { ImageUtils.loadBitmap(getApplication(), uri) }
                _state.update { it.copy(bitmap = bmp) }
                val result = withContext(Dispatchers.Default) { reader.read(bmp) }
                val best = result.candidates.firstOrNull()
                _state.update {
                    it.copy(
                        busy = false,
                        candidates = result.candidates,
                        rawText = result.rawText,
                        valueText = best?.display ?: "",
                        unit = best?.unit?.takeIf { u -> u in UNITS } ?: it.unit,
                        message = if (best == null) "Angka tidak terbaca. Foto ulang lebih dekat/tegak lurus, atau isi manual." else null,
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(busy = false, message = "Gagal memproses foto: ${e.message}") }
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
