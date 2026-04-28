package com.brain.tracscript.plugins.gps.wialon

import android.content.Context
import com.brain.tracscript.plugins.gps.GpsConfig
import com.brain.tracscript.plugins.gps.GpsProtocolSender
import com.brain.tracscript.plugins.gps.RemoteConfigService
import com.brain.tracscript.telemetry.CoreEventRecord
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.PositionParam
import com.brain.tracscript.telemetry.TelemetryRepository
import kotlinx.coroutines.flow.SharedFlow

class WialonProtocolSender(
    private val appContext: Context,
    private val cfg: GpsConfig,
    private val repo: TelemetryRepository
) : GpsProtocolSender {

    private val client = WialonIpsClient(cfg.host, cfg.port)

    val events: SharedFlow<WialonInbound> get() = client.events

    /**
     * Открыть сокет, если он ещё не открыт. Вызывается отдельной корутиной в GpsService,
     * чтобы reader работал и принимал команды от Wialon даже когда нечего отправлять.
     */
    suspend fun ensureConnected() {
        client.connect(cfg.imei, cfg.password)
    }

    override suspend fun sendGps(pos: Position, params: List<PositionParam>) {
        val nav = WialonMapping.navFromPosition(pos)
        val extras = WialonMapping.defaultExtras()

        val pendingText = RemoteConfigService.peekPendingText(appContext)
        val baseParams = WialonMapping.mergeParams(
            WialonMapping.buildBatteryParams(pos), params
        )
        val finalParams = if (pendingText != null) {
            // text:3 — стандартный параметр Wialon IPS для текстового сообщения.
            // Используется для одноразового подтверждения применённой одиночной команды.
            baseParams + IpsParam(name = "text", type = 3, value = pendingText)
        } else baseParams

        try {
            client.sendParams(
                imei = cfg.imei,
                password = cfg.password,
                params = finalParams,
                nav = nav,
                extras = extras
            )
            if (pendingText != null) RemoteConfigService.clearPendingText(appContext)
            repo.deleteGpsPosition(pos.id)
        } catch (e: Exception) {
            // Сетевая/IO ошибка — закрываем сокет, на следующей отправке reconnect.
            // pending_text не трогаем — попробуем снова.
            try { client.disconnect() } catch (_: Throwable) {}
            throw e
        }
    }

    override suspend fun sendCoreEvent(
        core: CoreEventRecord,
        bestGps: Position?,
        params: List<PositionParam>
    ) {
        try {
            WialonTableSender.sendTableJson(
                json = core.payloadJson,
                client = client,
                imei = cfg.imei,
                password = cfg.password,
                nav = bestGps?.let { WialonMapping.navFromPosition(it) } ?: WialonMapping.navWithoutGps(),
                extras = WialonMapping.defaultExtras(),
                paramsExtra = params.map { IpsParam(it.name, it.type.code, it.value) }
            )
            repo.markCoreEventSent(core.id)
        } catch (e: Exception) {
            try { client.disconnect() } catch (_: Throwable) {}
            throw e
        }
    }
}
