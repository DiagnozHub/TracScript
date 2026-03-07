package com.brain.tracscript.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.HandlerThread
import android.util.Log

class AndroidPositionProvider(
    context: Context,
    listener: PositionListener,
    deviceId: String,
    interval: Long,
    minDistanceM: Float,
    minAngleDeg: Double,
    accuracy: String
) : PositionProvider(
    context = context,
    listener = listener,
    deviceId = deviceId,
    interval = interval,
    distance = minDistanceM,
    angle = minAngleDeg
), LocationListener {

    private val locationManager =
        context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private val provider: String = getProvider(accuracy)

    // ---- НОВОЕ: храним количество спутников, участвующих в фиксе ----
    private var lastSatellitesUsedInFix: Int = 0

    private val thread = HandlerThread("gps-callback").apply { start() }
    private val looper = thread.looper

    private val nmeaMonitor = NmeaMonitor(
        context = context,
        lm = locationManager,
        debugParseAll = false,
        parseIntervalMs = 1000L
    ) { health, sentence ->
        // пушим в UI
        RawNmeaBus.publish(health, sentence)
    }


    /*
    private val gnssCallback = object : GnssStatus.Callback() {
        override fun onSatelliteStatusChanged(status: GnssStatus) {
            var used = 0
            val count = status.satelliteCount
            for (i in 0 until count) {
                if (status.usedInFix(i)) used++
            }
            lastSatellitesUsedInFix = used
            Log.d(
                "AndroidPositionProvider",
                "GNSS status: total=$count, usedInFix=$used"
            )
        }
    }
    */

    private var gnssFacade: GnssFacade? = null

    //fun getGnssHealth(): GnssHealth = nmeaMonitor.snapshot()

    // аккуратная установка satellites в extras Location
    private fun injectSatellites(location: Location) {
        val extras = location.extras ?: Bundle()
        extras.putInt("satellites", lastSatellitesUsedInFix)
        location.extras = extras
    }
    // -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    override fun startUpdates() {
        // 0) если перезапуск — прибери старые подписки, чтобы не плодить listeners
        try { gnssFacade?.unregister() } catch (_: Exception) {}
        gnssFacade = null

        try {
            // 1) NMEA (теперь у тебя в NmeaMonitor есть legacy-ветка, значит можно и на API 23)
            try {
                nmeaMonitor.start()
            } catch (se: SecurityException) {
                RawNmeaBus.publishError(se)
                Log.w("AndroidPositionProvider", "NMEA start blocked (no permission)", se)
            } catch (e: Exception) {
                RawNmeaBus.publishError(e)
                Log.w("AndroidPositionProvider", "NMEA start failed", e)
            }

            // 2) SATS: API 24+ -> GnssStatus, API 23 -> GpsStatus
            try {
                gnssFacade =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        GnssFacadeN(locationManager) { used, total ->
                            lastSatellitesUsedInFix = used
                            Log.d("AndroidPositionProvider", "GNSS status: total=$total, usedInFix=$used")
                        }
                    } else {
                        GpsStatusFacadeLegacy(locationManager) { used, total ->
                            lastSatellitesUsedInFix = used
                            Log.d("AndroidPositionProvider", "GPS status: total=$total, usedInFix=$used")
                        }
                    }

                gnssFacade?.register()
            } catch (se: SecurityException) {
                Log.w("AndroidPositionProvider", "Sat facade register blocked (no permission)", se)
            } catch (e: Exception) {
                Log.w("AndroidPositionProvider", "Sat facade register failed", e)
            }

            // 3) Location updates
            val minInterval = if (distance > 0 || angle > 0) MINIMUM_INTERVAL else interval
            Log.i(
                "AndroidPositionProvider",
                "startUpdates: provider=$provider interval=$minInterval dist=$distance angle=$angle"
            )

            try {
                locationManager.requestLocationUpdates(
                    provider,
                    minInterval,
                    0f,
                    this,
                    looper
                )
            } catch (se: SecurityException) {
                // разрешения нет/отозвали — сообщаем наверх
                listener.onPositionError(se)
            }

        } catch (re: RuntimeException) {
            listener.onPositionError(re)
        }
    }

    override fun stopUpdates() {
        // Останавливаем NMEA
        try {
            nmeaMonitor.stop()
        } catch (e: Exception) {
            Log.w("AndroidPositionProvider", "NMEA stop failed", e)
        }

        // Отписываемся от GNSS (только если был создан на API 24+)
        try {
            gnssFacade?.unregister()
        } catch (e: Exception) {
            Log.w("AndroidPositionProvider", "GNSS unregister failed", e)
        }
        gnssFacade = null

        // Отписываемся от Location updates
        try {
            locationManager.removeUpdates(this)
        } catch (e: Exception) {
            Log.w("AndroidPositionProvider", "removeUpdates failed", e)
        }
    }

    override fun onLocationChanged(location: Location) {

        if (location.provider != LocationManager.GPS_PROVIDER) return

        if (location.accuracy <= 0f || location.accuracy > 50f) return

        injectSatellites(location)
        processLocation(location)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}

    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    private fun getProvider(accuracy: String?): String {
        return when (accuracy) {
            "high" -> LocationManager.GPS_PROVIDER
            "low" -> LocationManager.PASSIVE_PROVIDER
            else -> LocationManager.NETWORK_PROVIDER
        }
    }
}
