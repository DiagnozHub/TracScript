package com.brain.tracscript.telemetry

import android.content.Context
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

data class GnssHealth(
    // поток
    val lastNmeaTimeMs: Long = 0L,     // wall-clock

    // фикса/качество (из GGA/RMC)
    val lastFixTimeMs: Long = 0L,      // wall-clock
    val fixQuality: Int? = null,       // GGA field 6
    val rmcValid: Boolean? = null,     // RMC status A/V
    val hdop: Double? = null,          // GGA field 8
    val sats: Int? = null,             // GGA field 7

    // отладка: последняя строка (любая), чтобы UI менялся
    val lastSentence: String? = null
) {
    fun isNmeaAlive(nowMs: Long, ttlMs: Long = 3000L) = nowMs - lastNmeaTimeMs <= ttlMs
    fun isFixAlive(nowMs: Long, ttlMs: Long = 5000L) = nowMs - lastFixTimeMs <= ttlMs
}

class NmeaMonitor(
    private val context: Context,
    private val lm: LocationManager,
    /**
     * Если true — парсим каждое GGA/RMC без ограничения (тяжелее).
     * Если false — парсим GGA/RMC не чаще parseIntervalMs.
     */
    private val debugParseAll: Boolean = false,
    /**
     * Интервал парсинга GGA/RMC (по умолчанию 1 Гц).
     */
    private val parseIntervalMs: Long = 1000L,
    private val onUpdate: ((GnssHealth, String?) -> Unit)? = null
) {
    private val last = AtomicReference(GnssHealth())
    private val handler = Handler(Looper.getMainLooper())

    private var listener: Any? = null

    // throttling парсинга по монотонным часам
    private val lastParsedAtElapsed = AtomicLong(0L)

    // throttling UI/BUS: не чаще 1 раза/сек
    private val lastUiPushAtElapsed = AtomicLong(0L)
    private val UI_PUSH_INTERVAL_MS = 1000L

    fun start() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
            Log.w("NmeaMonitor", "NMEA requires API 24+")
            return
        }
        if (listener != null) return

        val l = android.location.OnNmeaMessageListener { message, _ ->
            try {
                onNmea(message)
            } catch (e: Exception) {
                Log.w("NmeaMonitor", "onNmea failed", e)
                runCatching { pushUiThrottled(snapshot().lastSentence) }
            }
        }
        listener = l

        try {
            lm.addNmeaListener(l, handler)
            Log.d("NmeaMonitor", "NMEA listener registered (debug=$debugParseAll, interval=${parseIntervalMs}ms)")
        } catch (se: SecurityException) {
            Log.w("NmeaMonitor", "No permission for NMEA", se)
            throw se
        } catch (e: Exception) {
            Log.w("NmeaMonitor", "addNmeaListener failed", e)
            throw e
        }
    }

    fun stop() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        val l = listener as? android.location.OnNmeaMessageListener ?: return
        try { lm.removeNmeaListener(l) } catch (_: Exception) {}
        listener = null
    }

    fun snapshot(): GnssHealth = last.get()

    private fun pushUiThrottled(sentence: String?) {
        val nowElapsed = SystemClock.elapsedRealtime()
        val lastPush = lastUiPushAtElapsed.get()
        if (nowElapsed - lastPush < UI_PUSH_INTERVAL_MS) return
        lastUiPushAtElapsed.set(nowElapsed)

        val h = last.get()
        onUpdate?.invoke(h, sentence ?: h.lastSentence)
    }

    private fun onNmea(msg: String) {
        val trimmed = msg.trim()
        if (!trimmed.startsWith("$")) return

        val nowWall = System.currentTimeMillis()

        // 1) На КАЖДОЙ строке: отмечаем, что поток жив + сохраняем последнюю строку
        val prev0 = last.get()
        last.set(
            prev0.copy(
                lastNmeaTimeMs = nowWall,
                lastSentence = trimmed.take(120)
            )
        )

        // 2) Парсим только то, что даёт состояние фикса/качества
        val isGga = trimmed.contains("GGA")
        val isRmc = trimmed.contains("RMC")
        if (!isGga && !isRmc) {
            // UI/BUS не чаще 1 Гц — но строка будет меняться, потому что lastSentence обновляем всегда
            pushUiThrottled(null)
            return
        }

        // 3) Throttle парсинга GGA/RMC (если debug выключен)
        if (!debugParseAll) {
            val nowElapsed = SystemClock.elapsedRealtime()
            val lastElapsed = lastParsedAtElapsed.get()
            if (nowElapsed - lastElapsed < parseIntervalMs) {
                // Даже если не парсим — UI можно обновить 1 Гц
                pushUiThrottled(null)
                return
            }
            lastParsedAtElapsed.set(nowElapsed)
        }

        // 4) Реальный парсинг GGA/RMC
        val prev = last.get()

        var fixQuality: Int? = prev.fixQuality
        var rmcValid: Boolean? = prev.rmcValid
        var hdop: Double? = prev.hdop
        var sats: Int? = prev.sats
        var lastFixTime = prev.lastFixTimeMs

        if (isGga) {
            // $GxGGA: quality(6), sats(7), hdop(8)
            val p = trimmed.split(',')
            if (p.size > 9) {
                fixQuality = p[6].toIntOrNull()
                sats = p[7].toIntOrNull()
                hdop = p[8].toDoubleOrNull()
                if ((fixQuality ?: 0) > 0) lastFixTime = max(lastFixTime, nowWall)
            }
        }

        if (isRmc) {
            // $GxRMC: status(2) A/V
            val p = trimmed.split(',')
            if (p.size > 3) {
                rmcValid = (p[2] == "A")
                if (rmcValid == true) lastFixTime = max(lastFixTime, nowWall)
            }
        }

        last.set(
            prev.copy(
                lastNmeaTimeMs = nowWall,
                lastFixTimeMs = lastFixTime,
                fixQuality = fixQuality,
                rmcValid = rmcValid,
                hdop = hdop,
                sats = sats,
                // lastSentence уже обновили в начале на любой строке, но оставим актуальную
                lastSentence = trimmed.take(120)
            )
        )

        // 5) UI/BUS — не чаще 1 Гц
        pushUiThrottled(null)
    }
}
