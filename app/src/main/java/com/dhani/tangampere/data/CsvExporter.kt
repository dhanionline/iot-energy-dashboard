package com.dhani.tangampere.data

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CsvExporter {

    private fun esc(s: String): String =
        if (s.any { it == ',' || it == '"' || it == '\n' || it == ';' }) "\"" + s.replace("\"", "\"\"") + "\"" else s

    fun buildCsv(items: List<Measurement>): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val sb = StringBuilder("waktu,gardu,jurusan,fasa,nilai,satuan,catatan,teks_ocr\n")
        for (m in items.sortedBy { it.timestamp }) {
            sb.append(fmt.format(Date(m.timestamp))).append(',')
                .append(esc(m.gardu)).append(',')
                .append(esc(m.jurusan)).append(',')
                .append(m.phase).append(',')
                .append(m.value).append(',')
                .append(m.unit).append(',')
                .append(esc(m.note)).append(',')
                .append(esc(m.rawText.replace('\n', ' '))).append('\n')
        }
        return sb.toString()
    }

    /** Menulis CSV ke cache lalu membuka dialog bagikan (WhatsApp, Drive, email, dsb.). */
    fun share(context: Context, items: List<Measurement>) {
        val dir = File(context.cacheDir, "export").apply { mkdirs() }
        val stamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        val file = File(dir, "pengukuran_gardu_$stamp.csv")
        file.writeText(buildCsv(items))
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/csv"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Data pengukuran gardu")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(send, "Bagikan CSV").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}
