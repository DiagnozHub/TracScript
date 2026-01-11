package com.brain.tracscript.plugins.gps.wialon

import com.brain.tracscript.plugins.gps.GpsConfig
import com.brain.tracscript.plugins.gps.GpsProtocolSender
import com.brain.tracscript.telemetry.CoreEventRecord
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.PositionParam
import com.brain.tracscript.telemetry.TelemetryRepository

class WialonProtocolSender(
    private val cfg: GpsConfig,
    private val repo: TelemetryRepository
) : GpsProtocolSender {

    override suspend fun sendGps(pos: Position, params: List<PositionParam>) {
        val nav = WialonMapping.navFromPosition(pos)
        val extras = WialonMapping.defaultExtras()

        val client = WialonIpsClient(cfg.host, cfg.port)

        client.sendParams(
            imei = cfg.imei,
            password = cfg.password,
            params = WialonMapping.mergeParams(WialonMapping.buildBatteryParams(pos), params),
            nav = nav,
            extras = extras
        )

        repo.deleteGpsPosition(pos.id)
    }

    override suspend fun sendCoreEvent(
        core: CoreEventRecord,
        bestGps: Position?,
        params: List<PositionParam>
    ) {
        WialonTableSender.sendTableJson(
            json = core.payloadJson,
            client = WialonIpsClient(cfg.host, cfg.port),
            imei = cfg.imei,
            password = cfg.password,
            nav = bestGps?.let { WialonMapping.navFromPosition(it) } ?: WialonMapping.navWithoutGps(),
            extras = WialonMapping.defaultExtras(),
            paramsExtra = params.map { IpsParam(it.name, it.type.code, it.value) }
        )
        repo.markCoreEventSent(core.id)
    }
}
