package com.brain.tracscript.telemetry

data class BatteryStatus(
    val level: Double = 0.0,
    val charging: Boolean = false,
    val temperatureC: Double? = null //(null если не удалось прочитать)
)