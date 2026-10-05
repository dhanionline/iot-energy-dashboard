package com.dhani.tangampere.ui

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dhani.tangampere.MainViewModel
import com.dhani.tangampere.data.Jurusan
import com.dhani.tangampere.data.Phases
import java.io.File

private fun newCaptureUri(context: Context): Uri {
    val dir = File(context.cacheDir, "images").apply { mkdirs() }
    val file = File.createTempFile("foto_", ".jpg", dir)
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

@Composable
fun MeasureScreen(vm: MainViewModel, modifier: Modifier = Modifier) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var captureUri by rememberSaveable { mutableStateOf<Uri?>(null) }

    val takePicture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = captureUri
        if (ok && uri != null) vm.onImage(uri)
    }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) vm.onImage(uri)
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Tang Ampere AI", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Text(
            "Foto layar tang ampere, AI di perangkat akan membaca angkanya (tanpa internet).",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = state.gardu,
            onValueChange = vm::setGardu,
            label = { Text("Nama / kode gardu") },
            placeholder = { Text("mis. GT-DOBO-01") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        ChipRow("Jurusan", Jurusan.ALL, state.jurusan, vm::setJurusan)
        ChipRow("Fasa", Phases.ALL, state.phase, vm::setPhase)

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    val uri = newCaptureUri(context)
                    captureUri = uri
                    try {
                        takePicture.launch(uri)
                    } catch (e: ActivityNotFoundException) {
                        Toast.makeText(context, "Aplikasi kamera tidak ditemukan", Toast.LENGTH_SHORT).show()
                    }
                },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.CameraAlt, null)
                Spacer(Modifier.width(8.dp))
                Text("Kamera")
            }
            OutlinedButton(
                onClick = {
                    pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                },
                enabled = !state.busy,
                modifier = Modifier.weight(1f),
            ) {
                Icon(Icons.Filled.PhotoLibrary, null)
                Spacer(Modifier.width(8.dp))
                Text("Galeri")
            }
        }

        state.bitmap?.let { bmp ->
            Box(contentAlignment = Alignment.Center) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "Foto tang ampere",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 280.dp)
                        .clip(RoundedCornerShape(12.dp)),
                )
                if (state.busy) CircularProgressIndicator()
            }
        }
        if (state.busy && state.bitmap == null) {
            CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
        }

        if (state.candidates.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.width(6.dp))
                Text("Hasil baca AI (ketuk untuk memilih)", style = MaterialTheme.typography.titleSmall)
            }
            Row(
                Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                state.candidates.forEach { c ->
                    FilterChip(
                        selected = state.valueText == c.display,
                        onClick = { vm.pickCandidate(c) },
                        label = { Text("${c.display} ${c.unit}") },
                    )
                }
            }
        }

        Card(
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "Fasa ${state.phase} • ${state.jurusan}",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = state.valueText,
                        onValueChange = vm::setValue,
                        label = { Text("Nilai") },
                        singleLine = true,
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.Bold,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(state.unit, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                }
                ChipRow("Satuan", MainViewModel.UNITS, state.unit, vm::setUnit)
            }
        }

        OutlinedTextField(
            value = state.note,
            onValueChange = vm::setNote,
            label = { Text("Catatan (opsional)") },
            modifier = Modifier.fillMaxWidth(),
        )

        state.message?.let { msg ->
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        msg,
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    TextButton(onClick = vm::consumeMessage) { Text("OK") }
                }
            }
        }

        Button(
            onClick = vm::save,
            enabled = !state.busy,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
        ) {
            Icon(Icons.Filled.Save, null)
            Spacer(Modifier.width(8.dp))
            Text("Simpan Pengukuran")
        }

        if (state.rawText.isNotBlank()) {
            Text(
                "Teks terbaca: " + state.rawText.replace('\n', ' '),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.size(8.dp))
    }
}

@Composable
fun ChipRow(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium)
        Row(
            Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            options.forEach { opt ->
                FilterChip(selected = opt == selected, onClick = { onSelect(opt) }, label = { Text(opt) })
            }
        }
    }
}
