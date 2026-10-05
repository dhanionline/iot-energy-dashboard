package com.dhani.tangampere.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhani.tangampere.MainViewModel
import com.dhani.tangampere.data.CsvExporter
import com.dhani.tangampere.data.GroupSummary
import com.dhani.tangampere.data.LoadAnalysis
import com.dhani.tangampere.data.Measurement
import com.dhani.tangampere.data.Phases
import com.dhani.tangampere.ocr.ImageUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val idLocale = Locale("id", "ID")
private fun fmtTime(ts: Long) = SimpleDateFormat("dd MMM yyyy HH:mm", idLocale).format(Date(ts))
private fun fmtNum(v: Double) = String.format(idLocale, "%.1f", v)

@Composable
fun HistoryScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val all by vm.history.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var toDelete by remember { mutableStateOf<Measurement?>(null) }

    val filtered = remember(all, query) {
        if (query.isBlank()) all else all.filter { it.gardu.contains(query.trim(), ignoreCase = true) }
    }
    val summaries = remember(filtered) { LoadAnalysis.summarize(filtered) }

    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Riwayat Pengukuran",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = { CsvExporter.share(context, filtered) }, enabled = filtered.isNotEmpty()) {
                    Icon(Icons.Filled.Share, contentDescription = "Ekspor CSV")
                }
            }
        }
        item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                placeholder = { Text("Cari gardu") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (filtered.isEmpty()) {
            item {
                Text(
                    "Belum ada data. Ambil foto tang ampere di tab Ukur.",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 24.dp),
                )
            }
        }

        if (summaries.isNotEmpty()) {
            item { Text("Ringkasan beban terakhir", style = MaterialTheme.typography.titleMedium) }
            items(summaries, key = { "s_" + it.gardu.uppercase() + it.jurusan }) { SummaryCard(it) }
            item { Text("Semua pengukuran", style = MaterialTheme.typography.titleMedium) }
        }

        items(filtered, key = { it.id }) { m ->
            MeasurementRow(m, onDelete = { toDelete = m })
        }
    }

    toDelete?.let { m ->
        AlertDialog(
            onDismissRequest = { toDelete = null },
            title = { Text("Hapus pengukuran?") },
            text = { Text("${m.gardu} ${m.jurusan} fasa ${m.phase} = ${fmtNum(m.value)} ${m.unit}") },
            confirmButton = {
                TextButton(onClick = { vm.delete(m); toDelete = null }) { Text("Hapus") }
            },
            dismissButton = { TextButton(onClick = { toDelete = null }) { Text("Batal") } },
        )
    }
}

@Composable
private fun SummaryCard(s: GroupSummary) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${s.gardu} • ${s.jurusan}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Text(fmtTime(s.lastUpdated), style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Phases.ALL.forEach { p ->
                    val m = s.latest[p]
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(p, style = MaterialTheme.typography.labelMedium)
                        Text(
                            m?.let { fmtNum(it.value) } ?: "–",
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        )
                        Text(m?.unit ?: "", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            val imb = s.imbalancePercent
            if (imb != null) {
                val status = LoadAnalysis.status(imb)
                val color = when (status) {
                    "Seimbang" -> Color(0xFF2E7D32)
                    "Waspada" -> Color(0xFFF9A825)
                    else -> Color(0xFFC62828)
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(5.dp))
                            .background(color)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "Ketidakseimbangan ${fmtNum(imb)}% ($status) • ±${fmtNum(s.totalKva ?: 0.0)} kVA",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            } else {
                Text(
                    "Lengkapi fasa R, S, T untuk menghitung ketidakseimbangan beban.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeasurementRow(m: Measurement, onDelete: () -> Unit) {
    val thumb by produceState<Bitmap?>(null, m.photoPath) {
        value = m.photoPath?.let { path -> withContext(Dispatchers.IO) { ImageUtils.loadThumbnail(path) } }
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                thumb?.let {
                    Image(it.asImageBitmap(), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } ?: Text(m.phase, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("${m.gardu} • ${m.jurusan} • Fasa ${m.phase}", style = MaterialTheme.typography.titleSmall)
                Text(
                    "${fmtNum(m.value)} ${m.unit}",
                    style = MaterialTheme.typography.titleLarge,
                    fontFamily = FontFamily.Monospace,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    fmtTime(m.timestamp) + if (m.note.isNotBlank()) " • ${m.note}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "Hapus") }
        }
    }
}
