package com.brain.tracscript.telemetry

import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import android.os.Process


object RawGpsBus {
    private val _lastRawPosition = MutableStateFlow<Position?>(null)
    val lastRawPosition: StateFlow<Position?> = _lastRawPosition.asStateFlow()

    private val _lastRawError = MutableStateFlow<String?>(null)
    val lastRawError: StateFlow<String?> = _lastRawError.asStateFlow()

    fun publish(position: Position) {
        Log.d("RAWGPS", "publish pid=${Process.myPid()} bus=${System.identityHashCode(this)} lat=${position.latitude}")
        _lastRawError.value = null
        _lastRawPosition.value = position
    }

    fun publishError(error: Throwable) {
        _lastRawError.value = error.message ?: error.toString()
    }
}
