package com.brain.tracscript.telemetry

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.location.GnssStatus
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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

    fun getGnssHealth(): GnssHealth = nmeaMonitor.snapshot()

    // аккуратная установка satellites в extras Location
    private fun injectSatellites(location: Location) {
        val extras = location.extras ?: Bundle()
        extras.putInt("satellites", lastSatellitesUsedInFix)
        location.extras = extras
    }
    // -----------------------------------------------------------------

    @SuppressLint("MissingPermission")
    override fun startUpdates() {
        try {
            try {
                nmeaMonitor.start()
            } catch (e: Exception) {
                RawNmeaBus.publishError(e)
                Log.w("AndroidPositionProvider", "NMEA start failed", e)
            }

            // Пытаемся подписаться на GNSS-статус (для GPS-спутников)
            try {
                locationManager.registerGnssStatusCallback(
                    gnssCallback,
                    Handler(Looper.getMainLooper())
                )
            } catch (e: Exception) {
                Log.w("AndroidPositionProvider", "registerGnssStatusCallback failed", e)
            }

            val minInterval = if (distance > 0 || angle > 0) MINIMUM_INTERVAL else interval
            Log.i(
                "AndroidPositionProvider",
                "startUpdates: provider=$provider interval=$minInterval dist=$distance angle=$angle"
            )

            locationManager.requestLocationUpdates(
                provider,
                minInterval,
                0f,
                this,
                looper
            )
        } catch (e: RuntimeException) {
            listener.onPositionError(e)
        }
    }

    override fun stopUpdates() {
        // Сначала отписываемся от GNSS
        try {
            nmeaMonitor.stop()

            locationManager.unregisterGnssStatusCallback(gnssCallback)
        } catch (_: Exception) {
        }

        locationManager.removeUpdates(this)
    }

    /*
    @Suppress("MissingPermission")
    override fun requestSingleLocation() {
        try {
            val location = locationManager.getLastKnownLocation(LocationManager.PASSIVE_PROVIDER)
            if (location != null) {
                // подмешиваем количество спутников
                injectSatellites(location)

                // одиночный запрос — отдаём сразу, без фильтра по интервалу/дистанции/углу
                listener.onPositionUpdate(
                    Position(
                        deviceId = deviceId,
                        location = location,
                        battery =  BatteryUtils.read(context)
                    )
                )
            } else {
                locationManager.requestSingleUpdate(
                    provider,
                    object : LocationListener {
                        override fun onLocationChanged(location: Location) {
                            // тоже подмешиваем спутники
                            injectSatellites(location)

                            listener.onPositionUpdate(
                                Position(
                                    deviceId = deviceId,
                                    location = location,
                                    battery =  BatteryUtils.read(context)
                                )
                            )
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onStatusChanged(
                            provider: String,
                            status: Int,
                            extras: Bundle
                        ) {
                        }

                        override fun onProviderEnabled(provider: String) {}
                        override fun onProviderDisabled(provider: String) {}
                    },
                    Looper.getMainLooper()
                )
            }
        } catch (e: RuntimeException) {
            listener.onPositionError(e)
        }
    }
    */

    override fun onLocationChanged(location: Location) {
        // сюда приходят регулярные обновления — тоже подмешиваем satellites
        injectSatellites(location)

        // дальше всё как раньше: фильтрация по интервалу/дистанции/углу в базовом классе
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
