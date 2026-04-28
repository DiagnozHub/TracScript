package com.brain.tracscript.plugins.gps

import android.content.Context
import com.brain.tracscript.plugins.gps.osmand.OsmAndProtocolSender
import com.brain.tracscript.plugins.gps.wialon.WialonProtocolSender
import com.brain.tracscript.telemetry.TelemetryRepository

enum class GpsProtocolType {
    WIALON,
    OSMAND
}

object GpsProtocolFactory {

    fun create(
        appContext: Context,
        cfg: GpsConfig,
        repo: TelemetryRepository
    ): GpsProtocolSender =
        when (cfg.protocol) {
            GpsProtocolType.WIALON ->
                WialonProtocolSender(appContext, cfg, repo)

            GpsProtocolType.OSMAND ->
                OsmAndProtocolSender(cfg, repo)
        }
}
