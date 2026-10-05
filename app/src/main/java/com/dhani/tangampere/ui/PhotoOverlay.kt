package com.dhani.tangampere.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.dhani.tangampere.ocr.BBox
import com.dhani.tangampere.ocr.ReadingCandidate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

private val Selected = Color(0xFFFFD54F)
private val Other = Color(0xFF4FC3F7)
private val RegionColor = Color(0xFF66BB6A)

/**
 * Menampilkan foto beserta kotak setiap angka yang dikenali AI.
 *  - Mode biasa: ketuk kotak untuk memilih angka itu sebagai hasil.
 *  - Mode tandai area: tarik kotak di sekitar layar LCD, lalu AI membaca ulang area itu saja.
 */
@Composable
fun PhotoOverlay(
    bitmap: Bitmap,
    candidates: List<ReadingCandidate>,
    selectedValue: String,
    region: BBox?,
    selectingRegion: Boolean,
    busy: Boolean,
    onPick: (ReadingCandidate) -> Unit,
    onRegion: (BBox) -> Unit,
    modifier: Modifier = Modifier,
) {
    val textMeasurer = rememberTextMeasurer()
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }
    val currentCandidates by rememberUpdatedState(candidates)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnRegion by rememberUpdatedState(onRegion)
    val bw = bitmap.width.toFloat()
    val bh = bitmap.height.toFloat()

    Box(
        modifier
            .fillMaxWidth()
            .aspectRatio(bw / bh)
            .clip(RoundedCornerShape(12.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Foto tang ampere",
            contentScale = ContentScale.FillBounds,
            modifier = Modifier.fillMaxSize(),
        )
        Canvas(
            Modifier
                .fillMaxSize()
                .pointerInput(selectingRegion, bitmap) {
                    val s = size.width / bw
                    if (selectingRegion) {
                        detectDragGestures(
                            onDragStart = { dragStart = it; dragEnd = it },
                            onDrag = { change, _ -> change.consume(); dragEnd = change.position },
                            onDragEnd = {
                                val a = dragStart
                                val b = dragEnd
                                if (a != null && b != null) {
                                    currentOnRegion(
                                        BBox(
                                            (min(a.x, b.x) / s).coerceIn(0f, bw),
                                            (min(a.y, b.y) / s).coerceIn(0f, bh),
                                            (max(a.x, b.x) / s).coerceIn(0f, bw),
                                            (max(a.y, b.y) / s).coerceIn(0f, bh),
                                        )
                                    )
                                }
                                dragStart = null; dragEnd = null
                            },
                            onDragCancel = { dragStart = null; dragEnd = null },
                        )
                    } else {
                        detectTapGestures { p ->
                            val margin = 16f / s
                            currentCandidates
                                .filter { it.box?.contains(p.x / s, p.y / s, margin) == true }
                                .minByOrNull { it.box!!.width * it.box!!.height }
                                ?.let { currentOnPick(it) }
                        }
                    }
                }
        ) {
            val s = size.width / bw
            if (selectingRegion) drawRect(Color.Black.copy(alpha = 0.25f))

            region?.let { r ->
                drawRect(
                    RegionColor,
                    topLeft = Offset(r.left * s, r.top * s),
                    size = Size(r.width * s, r.height * s),
                    style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(18f, 10f))),
                )
            }

            if (!selectingRegion) {
                // Gambar kandidat lain dulu, kandidat terpilih paling atas.
                val ordered = candidates.filter { it.box != null }.sortedBy { it.display == selectedValue }
                ordered.forEach { c ->
                    val b = c.box!!
                    val isSel = c.display == selectedValue
                    val color = if (isSel) Selected else Other
                    val pad = 6f
                    drawRect(
                        color,
                        topLeft = Offset(b.left * s - pad, b.top * s - pad),
                        size = Size(b.width * s + 2 * pad, b.height * s + 2 * pad),
                        style = Stroke(width = if (isSel) 7f else 3f),
                    )
                    val label = textMeasurer.measure(
                        "${c.display} ${c.unit}",
                        TextStyle(color = Color.Black, fontSize = 12.sp, fontWeight = FontWeight.Bold, background = color),
                    )
                    val ty = (b.top * s - pad - label.size.height).let { if (it < 0f) b.bottom * s + pad else it }
                    drawText(label, topLeft = Offset((b.left * s - pad).coerceAtLeast(0f), ty))
                }
            }

            val a = dragStart
            val e = dragEnd
            if (a != null && e != null) {
                drawRect(
                    RegionColor,
                    topLeft = Offset(min(a.x, e.x), min(a.y, e.y)),
                    size = Size(abs(e.x - a.x), abs(e.y - a.y)),
                    style = Stroke(width = 5f),
                )
            }
        }
        if (busy) CircularProgressIndicator()
    }
}
