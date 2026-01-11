package com.brain.tracscript.telemetry

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object RawNmeaBus {
    private val _lastHealth = MutableStateFlow<GnssHealth?>(null)
    val lastHealth: StateFlow<GnssHealth?> = _lastHealth

    private val _lastSentence = MutableStateFlow<String?>(null)
    val lastSentence: StateFlow<String?> = _lastSentence

    private val _lastError = MutableStateFlow<String?>(null)
    val lastError: StateFlow<String?> = _lastError

    fun publish(health: GnssHealth, sentence: String?) {
        _lastHealth.value = health
        if (!sentence.isNullOrBlank()) _lastSentence.value = sentence
        _lastError.value = null
    }

    fun publishError(err: Throwable) {
        _lastError.value = err.message ?: err.toString()
    }

    fun clearError() {
        _lastError.value = null
    }
}
