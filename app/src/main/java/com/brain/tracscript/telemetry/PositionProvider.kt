package com.brain.tracscript.telemetry

import android.content.Context
import android.location.Location
import android.util.Log

abstract class PositionProvider(
    protected val context: Context,
    protected val listener: PositionListener,
    protected val deviceId: String,
    /**
     * Интервал между точками, мс
     */
    protected val interval: Long,
    /**
     * Мин. дистанция, м
     */
    protected val distance: Float,
    /**
     * Мин. изменение курса, градусы
     */
    protected val angle: Double,
) {

    interface PositionListener {
        fun onPositionUpdate(position: Position)
        fun onPositionError(error: Throwable)
    }

    private var lastLocation: Location? = null

    abstract fun startUpdates()
    abstract fun stopUpdates()
    //abstract fun requestSingleLocation()

    protected fun processLocation(location: Location?) {
        if (location == null) {
            Log.i(TAG, "location nil")
            return
        }

        // Просто запоминаем последнюю "сырую" локацию,
        // но решение "писать/не писать" принимает GpsStreamFilter выше по стеку.
        this.lastLocation = location

        listener.onPositionUpdate(
            Position(
                deviceId = deviceId,
                location = location,
                battery =  BatteryUtils.read(context)
            )
        )
    }

    companion object {
        private val TAG = PositionProvider::class.java.simpleName
        const val MINIMUM_INTERVAL: Long = 1000
    }
}
