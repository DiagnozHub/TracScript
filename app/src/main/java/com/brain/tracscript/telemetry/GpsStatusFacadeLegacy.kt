package com.brain.tracscript.telemetry

import android.location.GpsSatellite
import android.location.GpsStatus
import android.location.LocationManager
import android.util.Log

@Suppress("DEPRECATION")
internal class GpsStatusFacadeLegacy(
    private val lm: LocationManager,
    private val onUpdate: (usedInFix: Int, total: Int) -> Unit
) : GnssFacade {

    private val listener = GpsStatus.Listener {
        try {
            val st = lm.getGpsStatus(null) ?: return@Listener
            var total = 0
            var used = 0
            val it: Iterable<GpsSatellite> = st.satellites as Iterable<GpsSatellite>
            for (s in it) {
                total++
                if (s.usedInFix()) used++
            }
            onUpdate(used, total)
        } catch (e: SecurityException) {
            // нет разрешения - молча
        } catch (e: Exception) {
            Log.w("GpsStatusFacadeLegacy", "GpsStatus read failed", e)
        }
    }

    override fun register() {
        try {
            lm.addGpsStatusListener(listener)
        } catch (_: SecurityException) {
        } catch (e: Exception) {
            Log.w("GpsStatusFacadeLegacy", "addGpsStatusListener failed", e)
        }
    }

    override fun unregister() {
        try {
            lm.removeGpsStatusListener(listener)
        } catch (_: Exception) {
        }
    }
}