package com.brain.tracscript.telemetry

import android.location.GnssStatus
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.annotation.RequiresApi

@RequiresApi(Build.VERSION_CODES.N)
internal class GnssFacadeN(
    private val lm: LocationManager,
    private val onUpdate: (usedInFix: Int, total: Int) -> Unit
) : GnssFacade {

    private val cb = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val total = status.satelliteCount
            for (i in 0 until total) {
                if (status.usedInFix(i)) used++
            }
            onUpdate(used, total)
        }
    }

    override fun register() {
        try {
            lm.registerGnssStatusCallback(cb, Handler(Looper.getMainLooper()))
        } catch (e: SecurityException) {
            // разрешение на локацию не выдано/отозвано — GNSS просто не стартуем
        } catch (e: RuntimeException) {
            // на некоторых девайсах/прошивках могут быть странные падения — не валим приложение
        }
    }

    override fun unregister() {
        lm.unregisterGnssStatusCallback(cb)
    }
}