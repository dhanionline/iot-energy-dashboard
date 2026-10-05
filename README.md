# Tang Ampere AI — Pembaca Hasil Ukur Gardu (Offline)

Aplikasi Android untuk membaca angka pada layar **tang ampere (clamp meter)** dari foto,
menggunakan **AI yang berjalan sepenuhnya di perangkat (offline)**, lalu menyimpannya sebagai
data pengukuran beban gardu per jurusan dan per fasa (R, S, T, N).

## Fitur

- 📷 **Ambil foto** dari kamera atau pilih dari galeri.
- 🤖 **AI OCR offline** — model ML Kit Text Recognition dibundel di dalam APK. Tidak perlu internet,
  bahkan aplikasi ini tidak meminta izin `INTERNET`.
- 🔢 **Khusus layar LCD seven-segment** — foto diproses dalam 4 varian (asli, kontras tinggi,
  invers untuk layar ber-backlight, potong tengah). Karakter yang sering salah baca
  (`O→0`, `S→5`, `B→8`, `l→1`, dll.) dikoreksi otomatis; teks spesifikasi seperti
  `CAT III 600V` dan nomor model (`UT204`) diabaikan. Angka terbesar di layar yang
  berlabel `A` diberi skor tertinggi.
- 🧠 **Pemilihan angka cerdas** — tiap angka diberi skor berdasarkan: ukuran (digit LCD paling besar),
  satuan `A` di sebelahnya, posisi dekat tengah foto, berdiri sendiri di barisnya, bukan label skala
  saklar (200/400/600…) atau spesifikasi, nilai wajar untuk arus gardu, dan **sebanding dengan fasa lain**
  di gardu & jurusan yang sama (24 jam terakhir). Alasan pemilihan ditampilkan di layar.
- 👆 **Ketuk angka langsung di foto** — semua angka yang dikenali diberi kotak; ketuk kotak yang benar.
- ✂️ **Tandai layar** — tarik kotak mengelilingi layar LCD, AI membaca ulang hanya area itu
  (diperbesar hingga 4×), sehingga tulisan di badan alat tidak ikut terbaca.
- ⚠️ **Peringatan ragu-ragu** — jika dua kandidat skornya berdekatan, aplikasi meminta Anda memeriksa.
- 🗂️ **Simpan per gardu → jurusan (Induk, A–D) → fasa (R/S/T/N)**, lengkap dengan foto bukti.
  Setelah simpan, fasa otomatis maju (R → S → T → N) agar pengukuran di lapangan cepat.
- 📊 **Analisis beban**: persentase ketidakseimbangan beban
  `((|R−rata| + |S−rata| + |T−rata|) / (3 × rata)) × 100%` dengan status
  Seimbang (≤10%) / Waspada (≤20%) / Tidak seimbang, serta estimasi kVA (220 V fasa-netral).
- 📤 **Ekspor CSV** lewat menu bagikan (WhatsApp, Drive, email, dll.).

## Cara mendapatkan APK

Setiap push ke repo ini otomatis di-build oleh GitHub Actions:

1. Buka halaman **Releases** → **Tang Ampere AI (build terbaru)**.
2. Unduh **TangAmpereAI.apk** langsung dari HP, lalu buka file tersebut untuk menginstal
   (izinkan "Instal dari sumber tidak dikenal").

Atau build sendiri dengan Android Studio (Koala atau lebih baru) / command line:

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Minimal Android 8.0 (API 26).

## Tips foto agar akurat

- Foto **tegak lurus** ke layar, isi sebagian besar bingkai dengan layar tang ampere.
- Hindari pantulan cahaya/silau pada kaca LCD; gunakan tombol **HOLD** pada tang ampere.
- Jika titik desimal tidak terbaca (umum pada LCD), pilih kandidat lain atau koreksi manual.

## Struktur kode

| File | Fungsi |
| --- | --- |
| `ocr/MeterReader.kt` | Menjalankan AI OCR (ML Kit, offline) pada beberapa varian gambar |
| `ocr/ReadingParser.kt` | Koreksi karakter seven-segment, penyaringan & skor kandidat angka |
| `ocr/ImageUtils.kt` | Muat foto + rotasi EXIF, peningkatan kontras, invers, crop |
| `data/MeasurementDb.kt` | Database SQLite lokal |
| `data/LoadAnalysis.kt` | Ketidakseimbangan beban & ringkasan per gardu |
| `data/CsvExporter.kt` | Ekspor & bagikan CSV |
| `ui/MeasureScreen.kt`, `ui/HistoryScreen.kt` | Antarmuka Jetpack Compose |

Unit test untuk parser dan analisis beban: `./gradlew testDebugUnitTest`.
