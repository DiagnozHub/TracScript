@file:Suppress("DEPRECATION", "StaticFieldLeak")

package com.brain.tracscript.telemetry

import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.Context
import android.database.SQLException
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.os.AsyncTask
import java.sql.Date

data class CoreEventRecord(
    val id: Long = 0,
    val eventTimeMillis: Long,   // время события из ядра (UTC или локальное — главное, в ms)
    val payloadJson: String,     // сырое json от ядра
    val source: String?,         // откуда пришло (опционально: имя сценария/плагина)
    val sent: Boolean            // отправлено ли в Wialon
)

class TelemetryDbHelper(context: Context) :
    SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {

    interface DatabaseHandler<T> {
        fun onComplete(success: Boolean, result: T)
    }

    private abstract class DatabaseAsyncTask<T>(val handler: DatabaseHandler<T?>) :
        AsyncTask<Unit, Unit, T?>() {

        private var error: RuntimeException? = null

        override fun doInBackground(vararg params: Unit): T? {
            return try {
                executeMethod()
            } catch (error: RuntimeException) {
                this.error = error
                null
            }
        }

        protected abstract fun executeMethod(): T

        override fun onPostExecute(result: T?) {
            handler.onComplete(error == null, result)
        }
    }

    private val db: SQLiteDatabase = writableDatabase

    // --------------------------
    //   СХЕМА
    // --------------------------
    override fun onCreate(db: SQLiteDatabase) {
        // Таблица GPS-точек
        db.execSQL(
            "CREATE TABLE position (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "deviceId TEXT," +
                    "time INTEGER," +
                    "latitude REAL," +
                    "longitude REAL," +
                    "altitude REAL," +
                    "speed REAL," +
                    "course REAL," +
                    "accuracy REAL," +
                    "battery REAL," +
                    "charging INTEGER," +
                    "batteryTempC REAL," +
                    "sent INTEGER NOT NULL DEFAULT 0," +
                    "sats INTEGER NOT NULL DEFAULT 0," +
                    "createdAt INTEGER NOT NULL," +
                    "mock INTEGER)"
        )

        // Таблица событий из ядра
        db.execSQL(
            "CREATE TABLE core_event (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "eventTime INTEGER," +
            "payload TEXT," +
                    "source TEXT," +
                    "sent INTEGER DEFAULT 0)"
        )

        db.execSQL(
            "CREATE TABLE position_param (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "position_id INTEGER NOT NULL," +
                    "name TEXT NOT NULL," +
                    "type INTEGER NOT NULL," +
                    "value TEXT NOT NULL," +
                    "FOREIGN KEY(position_id) REFERENCES position(id) ON DELETE CASCADE" +
                    ")"
        )

        db.execSQL("CREATE INDEX idx_position_param_pos ON position_param(position_id)")
        db.execSQL("CREATE INDEX idx_position_param_name ON position_param(name)")

    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Пока без миграций, просто дропаем (ты всё равно на стадии разработки)
        db.execSQL("DROP TABLE IF EXISTS position;")
        db.execSQL("DROP TABLE IF EXISTS core_event;")
        db.execSQL("DROP TABLE IF EXISTS position_param;")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        db.execSQL("DROP TABLE IF EXISTS position;")
        db.execSQL("DROP TABLE IF EXISTS core_event;")
        db.execSQL("DROP TABLE IF EXISTS position_param;")
        onCreate(db)
    }

    // -------------------------------------------------
    //  БЛОК GPS: вставка / чтение / удаление
    // -------------------------------------------------

    fun insertPosition(position: Position) : Long {
        val values = ContentValues()
        values.put("deviceId", position.deviceId)
        values.put("time", position.time.time)
        values.put("latitude", position.latitude)
        values.put("longitude", position.longitude)
        values.put("altitude", position.altitude)
        values.put("speed", position.speed)
        values.put("course", position.course)
        values.put("accuracy", position.accuracy)
        values.put("battery", position.battery)
        values.put("charging", if (position.charging) 1 else 0)
        values.put("batteryTempC", position.batteryTempC)
        values.put("mock", if (position.mock) 1 else 0)
        values.put("sent", 0) // ещё не отправлено
        values.put("sats", position.sats)
        values.put("createdAt", System.currentTimeMillis())
        return db.insertOrThrow("position", null, values)
    }

    fun insertPositionParams(positionId: Long, params: List<PositionParam>) {
        if (params.isEmpty()) return

        val stmt = db.compileStatement(
            "INSERT INTO position_param(position_id, name, type, value) VALUES (?, ?, ?, ?)"
        )

        db.beginTransaction()
        try {
            for (p in params) {
                stmt.clearBindings()
                stmt.bindLong(1, positionId)
                stmt.bindString(2, p.name)
                stmt.bindLong(3, p.type.code.toLong())
                stmt.bindString(4, p.value)
                stmt.executeInsert()
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }


    fun markPositionSent(id: Long) {
        val values = ContentValues().apply {
            put("sent", 1)
        }
        writableDatabase.update(
            "position",
            values,
            "id = ?",
            arrayOf(id.toString())
        )
    }

    @SuppressLint("Range")
    fun selectLastInsertTime(): Long? {
        db.rawQuery(
            "SELECT createdAt FROM position ORDER BY createdAt DESC LIMIT 1",
            null
        ).use { cursor ->
            return if (cursor.moveToFirst()) {
                cursor.getLong(cursor.getColumnIndex("createdAt"))
            } else {
                null
            }
        }
    }



    @SuppressLint("Range")
    fun selectOldestPosition(): Position? {
        db.rawQuery("SELECT * FROM position WHERE sent = 0 ORDER BY id LIMIT 1", null).use { cursor ->
            if (cursor.count > 0) {
                cursor.moveToFirst()
                return Position(
                    id = cursor.getLong(cursor.getColumnIndex("id")),
                    deviceId = cursor.getString(cursor.getColumnIndex("deviceId")),
                    time = Date(cursor.getLong(cursor.getColumnIndex("time"))),
                    latitude = cursor.getDouble(cursor.getColumnIndex("latitude")),
                    longitude = cursor.getDouble(cursor.getColumnIndex("longitude")),
                    altitude = cursor.getDouble(cursor.getColumnIndex("altitude")),
                    speed = cursor.getDouble(cursor.getColumnIndex("speed")),
                    course = cursor.getDouble(cursor.getColumnIndex("course")),
                    accuracy = cursor.getDouble(cursor.getColumnIndex("accuracy")),
                    battery = cursor.getDouble(cursor.getColumnIndex("battery")),
                    charging = cursor.getInt(cursor.getColumnIndex("charging")) > 0,
                    batteryTempC = if (cursor.isNull(cursor.getColumnIndex("batteryTempC"))) null
                                   else cursor.getDouble(cursor.getColumnIndex("batteryTempC")),
                    mock = cursor.getInt(cursor.getColumnIndex("mock")) > 0,
                    sats = cursor.getInt(cursor.getColumnIndex("sats"))
                )
            }
        }
        return null
    }

    /*
    fun deletePosition(id: Long) {
        if (db.delete("position", "id = ?", arrayOf(id.toString())) != 1) {
            throw SQLException()
        }
    }
    */

    @SuppressLint("Range")
    fun selectPositionParams(positionId: Long): List<PositionParam> {
        val out = mutableListOf<PositionParam>()

        db.rawQuery(
            "SELECT name, type, value FROM position_param WHERE position_id = ? ORDER BY id",
            arrayOf(positionId.toString())
        ).use { c ->
            val nameIdx = c.getColumnIndex("name")
            val typeIdx = c.getColumnIndex("type")
            val valueIdx = c.getColumnIndex("value")

            while (c.moveToNext()) {
                val name = c.getString(nameIdx)
                val typeCode = c.getInt(typeIdx)
                val value = c.getString(valueIdx)

                val type = when (typeCode) {
                    ParamType.INT.code -> ParamType.INT
                    ParamType.FLOAT.code -> ParamType.FLOAT
                    ParamType.STRING.code -> ParamType.STRING
                    ParamType.BOOL.code -> ParamType.BOOL
                    else -> ParamType.STRING // fallback, чтоб не падать
                }

                out += PositionParam(
                    positionId = positionId,
                    name = name,
                    type = type,
                    value = value
                )
            }
        }

        return out
    }


    fun cleanupOldGpsKeepLastN(n: Int = 5) {
        writableDatabase.execSQL(
            """
        DELETE FROM position
        WHERE sent = 1
          AND id NOT IN (
              SELECT id FROM position
              WHERE sent = 1
              ORDER BY time DESC
              LIMIT $n
          )
        """
        )
    }

    fun cleanupOldCoreEventsKeepLastN(n: Int = 5) {
        writableDatabase.execSQL(
            """
        DELETE FROM core_event
        WHERE sent = 1
          AND id NOT IN (
              SELECT id FROM core_event
              WHERE sent = 1
              ORDER BY eventTime DESC
              LIMIT $n
          )
        """
        )
    }


    /**
     * Находит GPS-точку, чьё время минимально отличается от [targetTimeMillis].
     * Если разница > maxDeltaMillis — вернём null.
     */
    @SuppressLint("Range")
    fun selectClosestPosition(targetTimeMillis: Long, maxDeltaMillis: Long): Position? {
        db.rawQuery(
            """
            SELECT * FROM position
            ORDER BY ABS(time - ?) 
            LIMIT 1
            """.trimIndent(),
            arrayOf(targetTimeMillis.toString())
        ).use { cursor ->
            if (cursor.count > 0) {
                cursor.moveToFirst()
                val time = cursor.getLong(cursor.getColumnIndex("time"))
                val delta = kotlin.math.abs(time - targetTimeMillis)
                if (delta > maxDeltaMillis) {
                    return null
                }

                return Position(
                    id = cursor.getLong(cursor.getColumnIndex("id")),
                    deviceId = cursor.getString(cursor.getColumnIndex("deviceId")),
                    time = Date(time),
                    latitude = cursor.getDouble(cursor.getColumnIndex("latitude")),
                    longitude = cursor.getDouble(cursor.getColumnIndex("longitude")),
                    altitude = cursor.getDouble(cursor.getColumnIndex("altitude")),
                    speed = cursor.getDouble(cursor.getColumnIndex("speed")),
                    course = cursor.getDouble(cursor.getColumnIndex("course")),
                    accuracy = cursor.getDouble(cursor.getColumnIndex("accuracy")),
                    battery = cursor.getDouble(cursor.getColumnIndex("battery")),
                    charging = cursor.getInt(cursor.getColumnIndex("charging")) > 0,
                    batteryTempC =  if (cursor.isNull(cursor.getColumnIndex("batteryTempC"))) null
                                    else cursor.getDouble(cursor.getColumnIndex("batteryTempC")),
                    mock = cursor.getInt(cursor.getColumnIndex("mock")) > 0,
                    sats = cursor.getInt(cursor.getColumnIndex("sats")),
                )
            }
        }
        return null
    }

    // -------------------------------------------------
    //  БЛОК СОБЫТИЙ ИЗ ЯДРА: вставка / чтение / пометка
    // -------------------------------------------------

    fun insertCoreEvent(
        eventTimeMillis: Long,
        payloadJson: String,
        source: String?
    ) {
        val values = ContentValues()
        values.put("eventTime", eventTimeMillis)
        values.put("payload", payloadJson)
        values.put("source", source)
        values.put("sent", 0)
        db.insertOrThrow("core_event", null, values)
    }

    /**
     * Берём самое старое НЕотправленное событие из ядра.
     */
    @SuppressLint("Range")
    fun selectOldestPendingCoreEvent(): CoreEventRecord? {
        db.rawQuery(
            "SELECT * FROM core_event WHERE sent = 0 ORDER BY id LIMIT 1",
            null
        ).use { cursor ->
            if (cursor.count > 0) {
                cursor.moveToFirst()
                return CoreEventRecord(
                    id = cursor.getLong(cursor.getColumnIndex("id")),
                    eventTimeMillis = cursor.getLong(cursor.getColumnIndex("eventTime")),
                    payloadJson = cursor.getString(cursor.getColumnIndex("payload")),
                    source = cursor.getString(cursor.getColumnIndex("source")),
                    sent = cursor.getInt(cursor.getColumnIndex("sent")) != 0
                )
            }
        }
        return null
    }

    fun markCoreEventSent(id: Long) {
        val values = ContentValues()
        values.put("sent", 1)
        if (db.update("core_event", values, "id = ?", arrayOf(id.toString())) != 1) {
            throw SQLException()
        }
    }

    companion object {
        const val DATABASE_VERSION = 7
        const val DATABASE_NAME = "telemetry.db"
    }
}
