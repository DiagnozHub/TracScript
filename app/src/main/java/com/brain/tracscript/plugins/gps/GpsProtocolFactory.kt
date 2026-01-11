package com.brain.tracscript.plugins.gps

import com.brain.tracscript.plugins.gps.osmand.OsmAndProtocolSender
import com.brain.tracscript.plugins.gps.wialon.WialonProtocolSender
import com.brain.tracscript.telemetry.TelemetryRepository

enum class GpsProtocolType {
    WIALON,
    OSMAND
}

object GpsProtocolFactory {

    fun create(
        cfg: GpsConfig,
        repo: TelemetryRepository
    ): GpsProtocolSender =
        when (cfg.protocol) {
            GpsProtocolType.WIALON ->
                WialonProtocolSender(cfg, repo)

            GpsProtocolType.OSMAND ->
                OsmAndProtocolSender(cfg, repo)
        }
}
