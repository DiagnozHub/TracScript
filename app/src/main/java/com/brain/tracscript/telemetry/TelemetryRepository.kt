package com.brain.tracscript.telemetry

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Date

/**
 * Обёртка над TelemetryDbHelper с нормальными suspend-методами.
 * Никакой логики matching здесь нет — только CRUD.
 */
class TelemetryRepository(context: Context) {

    private val db = TelemetryDbHelper(context.applicationContext)
    private val appContext = context.applicationContext

    // ---------------- GPS ----------------

    suspend fun saveGpsPosition(position: Position) = withContext(Dispatchers.IO) {
        db.insertPosition(position)
    }

    suspend fun saveGpsPosition(position: Position, params: List<PositionParam>) = withContext(Dispatchers.IO) {
        val id = db.insertPosition(position)
        db.insertPositionParams(id, params)
    }

    suspend fun getOldestGpsPosition(): Position? = withContext(Dispatchers.IO) {
        db.selectOldestPosition()
    }

    suspend fun markGpsPositionSent(id: Long) = withContext(Dispatchers.IO) {
        db.markPositionSent(id)
    }

    suspend fun cleanupGpsKeepLastN(n: Int = 5) = withContext(Dispatchers.IO) {
        db.cleanupOldGpsKeepLastN(n)
    }

    suspend fun cleanupOldCoreEventsKeepLastN(n: Int = 5) = withContext(Dispatchers.IO) {
        db.cleanupOldCoreEventsKeepLastN(n)
    }

    suspend fun getGpsPositionParams(positionId: Long): List<PositionParam> =
        withContext(Dispatchers.IO) { db.selectPositionParams(positionId) }


    // старый метод можно оставить для совместимости, но внутри просто вызывать mark:
    @Deprecated("Используй markGpsPositionSent")
    suspend fun deleteGpsPosition(id: Long) = markGpsPositionSent(id)

    /**
     * Найти GPS-точку, ближайшую по времени к targetTimeMillis.
     * Если дельта > maxDeltaMillis — вернёт null.
     */
    suspend fun getClosestGpsPosition(
        targetTimeMillis: Long,
        maxDeltaMillis: Long
    ): Position? = withContext(Dispatchers.IO) {
        db.selectClosestPosition(targetTimeMillis, maxDeltaMillis)
    }

    // ---------------- CORE EVENTS ----------------

    suspend fun saveCoreEvent(
        eventTimeMillis: Long,
        payloadJson: String,
        source: String?
    ) = withContext(Dispatchers.IO) {
        db.insertCoreEvent(eventTimeMillis, payloadJson, source)
    }

    suspend fun getOldestPendingCoreEvent(): CoreEventRecord? = withContext(Dispatchers.IO) {
        db.selectOldestPendingCoreEvent()
    }

    suspend fun markCoreEventSent(id: Long) = withContext(Dispatchers.IO) {
        db.markCoreEventSent(id)
    }

    /**
     * alias, чтобы код, который ждёт findBestGpsForTime, тоже работал
     */
    suspend fun findBestGpsForTime(
        eventTimeMillis: Long,
        maxDeltaMs: Long
    ): Position? = getClosestGpsPosition(eventTimeMillis, maxDeltaMs)

    suspend fun insertHeartbeatIfNeeded(
        deviceId: String,
        intervalMs: Long,
        paramsExtra: List<PositionParam> = emptyList()
    ) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val lastInserted = db.selectLastInsertTime()   // ВАЖНО: НЕ time, а createdAt

        val bat = BatteryUtils.read(appContext)

        if (lastInserted == null || now - lastInserted >= intervalMs) {
            val pos = Position(
                deviceId = deviceId,
                time = Date(now),        // в Wialon пойдёт нормальное "сейчас"
                latitude = 0.0,
                longitude = 0.0,
                altitude = 0.0,
                speed = 0.0,
                course = 0.0,
                accuracy = 0.0,
                battery = bat.level,
                charging = bat.charging,
                batteryTempC = bat.temperatureC,
                mock = true,
                sats = 0
            )
            val id = db.insertPosition(pos)

            db.insertPositionParams(id, paramsExtra)      // прицепили аксель
        }
    }


}
