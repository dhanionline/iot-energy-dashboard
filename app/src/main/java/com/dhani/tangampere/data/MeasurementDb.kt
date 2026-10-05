package com.dhani.tangampere.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/** Penyimpanan lokal (SQLite) untuk hasil pengukuran. Tidak membutuhkan internet. */
class MeasurementDb(context: Context) : SQLiteOpenHelper(context, "pengukuran.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE measurement (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                gardu TEXT NOT NULL,
                jurusan TEXT NOT NULL,
                phase TEXT NOT NULL,
                value REAL NOT NULL,
                unit TEXT NOT NULL,
                raw_text TEXT NOT NULL,
                photo_path TEXT,
                timestamp INTEGER NOT NULL,
                note TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_measurement_ts ON measurement(timestamp)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    fun insert(m: Measurement): Long = writableDatabase.insert("measurement", null, ContentValues().apply {
        put("gardu", m.gardu)
        put("jurusan", m.jurusan)
        put("phase", m.phase)
        put("value", m.value)
        put("unit", m.unit)
        put("raw_text", m.rawText)
        put("photo_path", m.photoPath)
        put("timestamp", m.timestamp)
        put("note", m.note)
    })

    fun delete(id: Long) {
        writableDatabase.delete("measurement", "id = ?", arrayOf(id.toString()))
    }

    fun all(): List<Measurement> {
        val result = mutableListOf<Measurement>()
        readableDatabase.rawQuery(
            "SELECT id, gardu, jurusan, phase, value, unit, raw_text, photo_path, timestamp, note " +
                "FROM measurement ORDER BY timestamp DESC",
            null,
        ).use { c ->
            while (c.moveToNext()) {
                result += Measurement(
                    id = c.getLong(0),
                    gardu = c.getString(1),
                    jurusan = c.getString(2),
                    phase = c.getString(3),
                    value = c.getDouble(4),
                    unit = c.getString(5),
                    rawText = c.getString(6),
                    photoPath = if (c.isNull(7)) null else c.getString(7),
                    timestamp = c.getLong(8),
                    note = c.getString(9),
                )
            }
        }
        return result
    }
}
