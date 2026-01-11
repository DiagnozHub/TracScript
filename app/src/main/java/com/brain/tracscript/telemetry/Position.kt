package com.brain.tracscript.telemetry

import android.location.Location
import java.util.Date

data class Position(
    val id: Long = 0,
    val deviceId: String,
    val time: Date,
    val latitude: Double = 0.0,
    val longitude: Double = 0.0,
    val altitude: Double = 0.0,
    val speed: Double = 0.0,    // в узлах, как у тебя
    val course: Double = 0.0,
    val accuracy: Double = 0.0,
    val battery: Double = 0.0,
    val charging: Boolean = false,
    val batteryTempC: Double? = null,
    val mock: Boolean = false,
    val sats: Int = 0
) {

    constructor(deviceId: String, location: Location, battery: BatteryStatus) : this(
        deviceId = deviceId,
        time = Date(location.time.correctRollover()),
        latitude = location.latitude,
        longitude = location.longitude,
        altitude = location.altitude,
        speed = location.speed * 3.6, // скорость в км/ч
        course = location.bearing.toDouble(),
        accuracy = location.accuracy.toDouble(),
        /*
        accuracy = if (location.provider != null && location.provider != LocationManager.GPS_PROVIDER) {
            location.accuracy.toDouble()
        } else {
            0.0
        },*/
        battery = battery.level,
        charging = battery.charging,
        batteryTempC = battery.temperatureC,
        sats = location.extras?.getInt("satellites") ?: 0,   // ← ключ, который мы положили в AndroidPositionProvider,
        mock = false
        /*
        mock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            location.isMock
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2) {
            @Suppress("DEPRECATION")
            location.isFromMockProvider
        } else {
            false
        },
         */
    )
}

private const val rolloverDate = 1554508800000L // April 6, 2019
private const val rolloverOffset = 619315200000L // 1024 weeks

private fun Long.correctRollover(): Long {
    return if (this < rolloverDate) this + rolloverOffset else this
}

enum class ParamType(val code: Int) { INT(1), FLOAT(2), STRING(3), BOOL(4) }

data class PositionParam(
    val positionId: Long = 0L,
    val name: String,
    val type: ParamType,
    val value: String
)
