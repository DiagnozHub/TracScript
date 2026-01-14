package com.brain.tracscript.plugins.gps.osmand

import android.net.Uri
import android.util.Log
import com.brain.tracscript.plugins.gps.GpsConfig
import com.brain.tracscript.plugins.gps.GpsProtocolSender
import com.brain.tracscript.telemetry.CoreEventRecord
import com.brain.tracscript.telemetry.ParamType
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.PositionParam
import com.brain.tracscript.telemetry.TelemetryRepository
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.math.abs

class OsmAndProtocolSender(
    private val cfg: GpsConfig,
    private val repo: TelemetryRepository
) : GpsProtocolSender {

    companion object {
        private const val TAG = "OsmAndSender"
        private const val TIMEOUT_MS = 15_000 // как в старом приложении

        // Масштаб для мелких float, чтобы Traccar UI не округлял до 0
        private const val ACC_SCALE = 10000.0
    }

    override suspend fun sendGps(pos: Position, params: List<PositionParam>) {
        val baseUrl = buildBaseUrl(cfg.host, cfg.port)
        val requestUrl = buildRequestUrl(baseUrl, pos, params)

        httpPostNoBody(requestUrl)

        // удаляем ТОЛЬКО если отправка не упала исключением
        //repo.deleteGpsPosition(pos.id)
    }

    override suspend fun sendCoreEvent(
        core: CoreEventRecord,
        bestGps: Position?,
        params: List<PositionParam>
    ) {
        // Заглушка: core-events через OsmAnd пока не поддерживаем
        Log.d(TAG, "sendCoreEvent ignored for OsmAnd (coreId=${core.id})")
    }

    private fun buildBaseUrl(host: String, port: Int): String {
        val h = host.trim().trimEnd('/')
        // Протокол OsmAnd в старом приложении работает через обычный http://
        return "http://$h:$port/"
    }

    private fun buildRequestUrl(
        baseUrl: String,
        position: Position,
        extraParams: List<PositionParam>
    ): String {
        val builder = Uri.parse(baseUrl).buildUpon()
            .appendQueryParameter("id", position.deviceId)
            .appendQueryParameter("timestamp", (position.time.time / 1000).toString())
            .appendQueryParameter("lat", position.latitude.toString())
            .appendQueryParameter("lon", position.longitude.toString())
            .appendQueryParameter("speed", position.speed.toString())
            .appendQueryParameter("bearing", position.course.toString())
            .appendQueryParameter("altitude", position.altitude.toString())
            .appendQueryParameter("accuracy", position.accuracy.toString())
            .appendQueryParameter("batt", position.battery.toString())

        builder.appendQueryParameter(
            "bat_chg",
            if (position.charging) "1" else "0"
        )

        position.batteryTempC?.let { temp ->
            builder.appendQueryParameter(
                "bat_tmp",
                String.format(Locale.US, "%.1f", temp)
            )
        }

        builder.appendQueryParameter(
            "mock",
            if (position.mock) "1" else "0"
        )

        // доп. параметры из БД (+ аксель масштабируем, но имя оставляем тем же)
        for (p in extraParams) {
            builder.appendQueryParameter(p.name, normalizeAccelValueIfNeeded(p))
        }

        return builder.build().toString()
    }

    /**
     * Если параметр акселя мелкий float (< 1.0), то Traccar UI часто показывает 0.
     * Поэтому отправляем только масштабированное значение (int x10000),
     *
     * Пример:
     *  acc_conf = "0.0123" -> "123"
     *  acc_rms_ema = "0.0100" -> "100"
     */
    private fun normalizeAccelValueIfNeeded(p: PositionParam): String {
        // только acc_*
        if (!p.name.startsWith("acc_")) return p.value

        // state не трогаем
        if (p.name == "acc_state") return p.value

        // только float
        if (p.type != ParamType.FLOAT) return p.value

        val v = p.value.toDoubleOrNull() ?: return p.value

        // масштабируем только мелкие
        //if (abs(v) >= 1.0) return p.value

        val scaled = (v * ACC_SCALE).toInt()
        return scaled.toString()
    }

    /**
     * Как в старом RequestManager:
     * - method POST
     * - без body
     * - connect + drain inputStream
     * - успех = не поймали IOException
     */
    private fun httpPostNoBody(requestUrl: String) {
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(requestUrl).openConnection() as HttpURLConnection)
            conn.readTimeout = TIMEOUT_MS
            conn.connectTimeout = TIMEOUT_MS
            conn.requestMethod = "POST"

            conn.connect()

            conn.inputStream.use { input ->
                while (input.read() != -1) {
                    // drain
                }
            }
        } catch (e: IOException) {
            throw IllegalStateException("OsmAnd POST failed: ${e.message}", e)
        } finally {
            try { conn?.disconnect() } catch (_: Exception) {}
        }
    }
}
