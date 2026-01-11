package com.brain.tracscript.plugins.gps.wialon

import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.PositionParam
import java.util.Locale
import kotlin.math.absoluteValue

object WialonMapping {

    fun navWithoutGps(): NavData {
        val now = System.currentTimeMillis()
        return NavData(
            timeMillis = now,
            lat1 = "0000.0000",
            lat2 = "N",
            lon1 = "00000.0000",
            lon2 = "E",
            speed = "0",
            course = "0",
            alt = "0",
            sats = "0"
        )
    }

    fun navFromPosition(pos: Position): NavData {
        val (lat1, latDir) = toWialonLat(pos.latitude)
        val (lon1, lonDir) = toWialonLon(pos.longitude)

        val speedKmh = (pos.speed).toInt().coerceAtLeast(0)
        val courseInt = pos.course.toInt().coerceIn(0, 359)
        val altInt = pos.altitude.toInt()

        return NavData(
            timeMillis = pos.time.time,
            lat1 = lat1,
            lat2 = latDir,
            lon1 = lon1,
            lon2 = lonDir,
            speed = speedKmh.toString(),
            course = courseInt.toString(),
            alt = altInt.toString(),
            sats = pos.sats.toString()
        )
    }

    fun defaultExtras(): DExtras = DExtras(
        hdop = "NA",
        inputs = "NA",
        outputs = "NA",
        adc = "",
        ibutton = "NA"
    )

    fun buildBatteryParams(pos: Position): List<IpsParam> {
        val params = mutableListOf<IpsParam>()

        val level = pos.battery.coerceIn(0.0, 100.0)
        params += IpsParam(
            name = "bat_lvl",
            type = 2,
            value = String.format(Locale.US, "%.1f", level)
        )

        params += IpsParam(
            name = "bat_chg",
            type = 1,
            value = if (pos.charging) "1" else "0"
        )

        val t = pos.batteryTempC
        if (t != null) {
            params += IpsParam(
                name = "bat_tmp",
                type = 2,
                value = String.format(Locale.US, "%.1f", t)
            )
        }

        params += IpsParam(
            name = "mock",
            type = 1,
            value = if (pos.mock) "1" else "0"
        )

        return params
    }

    fun mergeParams(
        base: List<IpsParam>,
        extra: List<PositionParam>
    ): List<IpsParam> {
        if (extra.isEmpty()) return base
        val out = base.toMutableList()
        for (p in extra) {
            out += IpsParam(
                name = p.name,
                type = p.type.code,
                value = p.value
            )
        }
        return out
    }

    private fun toWialonLat(latDeg: Double): Pair<String, String> {
        val dir = if (latDeg >= 0) "N" else "S"
        val absDeg = latDeg.absoluteValue
        val d = absDeg.toInt()
        val minutes = (absDeg - d) * 60.0
        val degStr = d.toString().padStart(2, '0')
        val minStr = String.format(Locale.US, "%06.4f", minutes)
        return (degStr + minStr) to dir
    }

    private fun toWialonLon(lonDeg: Double): Pair<String, String> {
        val dir = if (lonDeg >= 0) "E" else "W"
        val absDeg = lonDeg.absoluteValue
        val d = absDeg.toInt()
        val minutes = (absDeg - d) * 60.0
        val degStr = d.toString().padStart(3, '0')
        val minStr = String.format(Locale.US, "%06.4f", minutes)
        return (degStr + minStr) to dir
    }
}
