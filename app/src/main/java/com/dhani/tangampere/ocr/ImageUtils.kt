package com.dhani.tangampere.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.File
import java.io.FileOutputStream

object ImageUtils {

    /** Memuat gambar dari Uri, mengecilkannya ke [maxSide] piksel dan memperbaiki rotasi EXIF. */
    fun loadBitmap(context: Context, uri: Uri, maxSide: Int = 1600): Bitmap {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        require(bounds.outWidth > 0 && bounds.outHeight > 0) { "Gambar tidak dapat dibaca" }

        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        var bmp = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("Gambar tidak dapat dibaca")

        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        if (scale < 1f) {
            bmp = Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        }

        val rotation = resolver.openInputStream(uri)?.use {
            when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                else -> 0f
            }
        } ?: 0f
        return rotate(bmp, rotation)
    }

    fun rotate(src: Bitmap, degrees: Float): Bitmap {
        if (degrees % 360f == 0f) return src
        val m = Matrix().apply { postRotate(degrees) }
        return Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
    }

    /**
     * Grayscale + kontras tinggi agar segmen LCD lebih tegas.
     * [invert] berguna untuk layar dengan lampu latar (angka terang di latar gelap).
     */
    fun enhance(src: Bitmap, invert: Boolean): Bitmap {
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val contrast = 1.8f
        val translate = (-0.5f * contrast + 0.5f) * 255f
        val gray = ColorMatrix().apply { setSaturation(0f) }
        val cm = ColorMatrix(
            floatArrayOf(
                contrast, 0f, 0f, 0f, translate,
                0f, contrast, 0f, 0f, translate,
                0f, 0f, contrast, 0f, translate,
                0f, 0f, 0f, 1f, 0f,
            )
        )
        gray.postConcat(cm)
        if (invert) {
            gray.postConcat(
                ColorMatrix(
                    floatArrayOf(
                        -1f, 0f, 0f, 0f, 255f,
                        0f, -1f, 0f, 0f, 255f,
                        0f, 0f, -1f, 0f, 255f,
                        0f, 0f, 0f, 1f, 0f,
                    )
                )
            )
        }
        val paint = Paint().apply { colorFilter = ColorMatrixColorFilter(gray) }
        Canvas(out).drawBitmap(src, 0f, 0f, paint)
        return out
    }

    /** Potong bagian tengah gambar (layar biasanya di tengah bidikan). */
    fun centerCrop(src: Bitmap, fraction: Float): Bitmap {
        val w = (src.width * fraction).toInt().coerceAtLeast(1)
        val h = (src.height * fraction).toInt().coerceAtLeast(1)
        return Bitmap.createBitmap(src, (src.width - w) / 2, (src.height - h) / 2, w, h)
    }

    fun saveJpeg(bmp: Bitmap, file: File, maxSide: Int = 1280, quality: Int = 85) {
        file.parentFile?.mkdirs()
        val scale = maxSide.toFloat() / maxOf(bmp.width, bmp.height)
        val toSave = if (scale < 1f) {
            Bitmap.createScaledBitmap(bmp, (bmp.width * scale).toInt(), (bmp.height * scale).toInt(), true)
        } else bmp
        FileOutputStream(file).use { toSave.compress(Bitmap.CompressFormat.JPEG, quality, it) }
    }

    fun loadThumbnail(path: String, maxSide: Int = 256): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0) return null
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxSide) sample *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })
    }
}
