package com.brain.tracscript.plugins.gps

import android.content.Context
import com.brain.tracscript.core.DataBusEvent
import com.brain.tracscript.core.Plugin
import com.brain.tracscript.core.PluginContext
import com.brain.tracscript.core.Subscription
import com.brain.tracscript.plugins.gps.GpsPluginSettingsDefinition as GPSWialonSettings
import com.brain.tracscript.telemetry.TelemetryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

// Читаем конфиг плагина из SharedPreferences
fun loadGpsConfig(appContext: Context): GpsConfig {
    val prefs = appContext.getSharedPreferences(GPSWialonSettings.PREFS, Context.MODE_PRIVATE)

    val enabled = prefs.getBoolean(GPSWialonSettings.KEY_ENABLED, false)
    val host = prefs.getString(GPSWialonSettings.KEY_HOST, "") ?: ""
    val port = prefs.getInt(GPSWialonSettings.KEY_PORT, 20332)
    val imei = prefs.getString(GPSWialonSettings.KEY_IMEI, "") ?: ""
    val password = prefs.getString(GPSWialonSettings.KEY_PASSWORD, "NA") ?: "NA"

    val gpsIntervalSec = prefs.getInt(GPSWialonSettings.KEY_GPS_INTERVAL_SEC, GPSWialonSettings.DEFAULT_GPS_INTERVAL_SEC)
    val gpsMinDistanceM = prefs.getFloat(GPSWialonSettings.KEY_GPS_MIN_DISTANCE_M, GPSWialonSettings.DEFAULT_GPS_MIN_DISTANCE_M)
    val gpsMinAngleDeg = prefs.getFloat(GPSWialonSettings.KEY_GPS_MIN_ANGLE_DEG, GPSWialonSettings.DEFAULT_GPS_MIN_ANGLE_DEG)

    val protocol = GpsProtocolType.valueOf(
        prefs.getString(GPSWialonSettings.KEY_PROTOCOL, GPSWialonSettings.DEFAULT_PROTOCOL.name) ?: GPSWialonSettings.DEFAULT_PROTOCOL.name
    )


    return GpsConfig(
        enabled = enabled,
        host = host,
        port = port,
        imei = imei,
        password = password,
        gpsIntervalSec = gpsIntervalSec,
        gpsMinDistanceM = gpsMinDistanceM,
        gpsMinAngleDeg = gpsMinAngleDeg,
        protocol = protocol
    )
}

/**
 * Блокирующая ошибка отправки: нельзя продолжать отправку следующих сообщений,
 * пока эта ошибка не исчезнет (gate).
 *
 * Пример: Wialon login failed.
 */
class GpsSendBlockedException(
    message: String,
    cause: Throwable? = null
) : RuntimeException(message, cause)

class GpsPlugin (private val appCtx: Context) : Plugin {

    override val id: String = "gps_wialon"
    override val displayName: String = "GPS plugin"

    private var context: PluginContext? = null
    private var busSub: Subscription? = null
    private var telemetryRepo: TelemetryRepository? = null

    private val pluginJob = SupervisorJob()
    private val pluginScope = CoroutineScope(pluginJob + Dispatchers.IO)

    override fun isEnabled(): Boolean {
        return loadGpsConfig(appCtx).enabled
    }

    override fun onAttach(context: PluginContext) {

        this.context = context
        this.telemetryRepo = TelemetryRepository(context.appContext)

        context.log(
            id,
            "GpsWialonPlugin attach, enabled=${isEnabled()}"
        )

        // Всегда подписываемся на шину, но внутри handleEvent
        // смотрим, включён ли плагин.
        busSub = context.dataBus.subscribe { event ->
            handleEvent(event)
        }

        applyEnabledFromPrefs(context)
    }

    private fun applyEnabledFromPrefs(context: PluginContext) {
        val prefs = context.appContext.getSharedPreferences( GPSWialonSettings.PREFS, Context.MODE_PRIVATE)
        val enabled = prefs.getBoolean(GPSWialonSettings.KEY_ENABLED, false)

        context.log(id, "applyEnabledFromPrefs: enabled=$enabled")

        if (enabled) {
            GpsService.start(context.appContext)
        } else {
            GpsService.stop(context.appContext)
        }
    }

    override fun onDetach() {
        busSub?.unsubscribe()
        busSub = null

        telemetryRepo = null
        pluginJob.cancel()

        context?.let {
            it.log(id, "GpsWialonPlugin detached — останавливаю GpsService")
            GpsService.stop(it.appContext)
        }

        context = null
    }

    /**
     * Ловим события ядра (таблицы для Wialon) и складываем в БД.
     * Если плагин выключен — просто игнорируем.
     */
    private fun handleEvent(event: DataBusEvent) {

        when (event.type) {

            GPSWialonSettings.PLUGIN_ENABLED_CHANGED_EVENT -> {
                val pid = event.payload[GPSWialonSettings.PLUGIN_ID] as? String ?: return
                if (pid != id) return

                val enabled = event.payload[GPSWialonSettings.KEY_ENABLED] as? Boolean ?: return

                context?.log(id, "plugin_enabled_changed = $enabled")

                if (enabled) {
                    GpsService.start(context!!.appContext)
                } else {
                    GpsService.stop(context!!.appContext)
                }
            }

            "wialon_table_json" -> {
                val ctx = context ?: return
                val repo = telemetryRepo ?: return

                if (!isEnabled()) {
                    ctx.log(id, "gps_wialon выключен — событие wialon_table_json игнорируем")
                    return
                }

                val json = event.payload["json"] as? String ?: return
                val fileName = event.payload["fileName"] as? String

                ctx.log(
                    id,
                    "Получено событие ядра для Wialon: ${fileName ?: "<?>"} (len=${json.length}) ts=${event.timestamp}"
                )

                pluginScope.launch {
                    repo.saveCoreEvent(
                        eventTimeMillis = event.timestamp,
                        payloadJson = json,
                        source = fileName
                    )
                }
            }
        }
    }
}
