package com.brain.tracscript.telemetry

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager

object BatteryUtils {
    fun read(context: Context): BatteryStatus {
        val batteryIntent =
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        if (batteryIntent != null) {
            val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, 0)
            val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 1)
            val status = batteryIntent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)

            // EXTRA_TEMPERATURE: десятые градуса Цельсия, -1 если недоступно
            val tempTenths = batteryIntent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1)
            val tempC = if (tempTenths >= 0) tempTenths / 10.0 else null

            return BatteryStatus(
                level = level * 100.0 / scale,
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING
                        || status == BatteryManager.BATTERY_STATUS_FULL,
                temperatureC = tempC
            )
        }
        return BatteryStatus()
    }
}
