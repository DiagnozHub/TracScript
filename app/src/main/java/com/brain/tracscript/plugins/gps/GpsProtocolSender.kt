package com.brain.tracscript.plugins.gps

import com.brain.tracscript.telemetry.CoreEventRecord
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.PositionParam

interface GpsProtocolSender {

    suspend fun sendGps(
        pos: Position,
        params: List<PositionParam>
    )

    suspend fun sendCoreEvent(
        core: CoreEventRecord,
        bestGps: Position?,
        params: List<PositionParam>
    )
}
