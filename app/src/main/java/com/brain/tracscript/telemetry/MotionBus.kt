package com.brain.tracscript.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object MotionBus {
    private val _state = MutableStateFlow(MotionState.UNKNOWN)
    val state: StateFlow<MotionState> = _state

    private val _debug = MutableStateFlow<MotionDebug?>(null)
    val debug: StateFlow<MotionDebug?> = _debug

    fun publishState(st: MotionState) { _state.value = st }
    fun publishDebug(d: MotionDebug) { _debug.value = d }
}

data class MotionDebug(
    val confidence: Float,
    val secondsSinceMotion: Float?,
    val rmsEma: Float
)