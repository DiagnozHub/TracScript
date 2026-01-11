package com.brain.tracscript.telemetry

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.pow
import kotlin.math.sqrt

enum class MotionState {
    UNKNOWN,
    STATIONARY,
    MOVING
}

/**
 * MotionDetector НЕ решает "едем/стоим" как истину.
 * Он выдает "след движения": когда было движение и насколько оно "свежее".
 *
 * Фильтр (GpsStreamFilter) сам принимает решение, используя GPS+след акселя.
 */
class MotionDetector(
    context: Context,
    private val windowSize: Int = 40,

    // Порог по RMS(EMA) для "события движения"
    @Volatile private var motionThreshold: Float = 0.80f,

    // EMA сглаживание RMS
    private val emaAlpha: Float = 0.20f,

    // Затухание "следа движения": half-life, мс
    private val traceHalfLifeMs: Float = 25000f, // 20 секунд

    // Для UI/состояния (не критично, но оставим)
    private val enterConfirmMs: Long = 2500L,
    private val exitConfirmMs: Long = 4500L,

    // если нет LINEAR_ACCELERATION — вычитаем гравитацию LPF
    private val gravityAlpha: Float = 0.90f
) : SensorEventListener {

    private val sensorManager =
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private val linearSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_LINEAR_ACCELERATION)
    private val accelSensor: Sensor? =
        sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private val useLinearSensor = (linearSensor != null)

    private data class Vec3(val x: Float, val y: Float, val z: Float)
    private val window = ArrayDeque<Vec3>(windowSize)

    private var gx = 0f; private var gy = 0f; private var gz = 0f

    @Volatile private var lastRms: Float = 0f
    @Volatile private var lastRmsEma: Float = 0f

    // ======= ВАЖНОЕ: след движения =======
    @Volatile private var lastMotionRt: Long = 0L
    @Volatile private var motionConfidence: Float = 0f

    fun getLastRms(): Float = lastRms
    fun getLastRmsEma(): Float = lastRmsEma

    fun setMotionThreshold(value: Float) {
        motionThreshold = value
    }

    fun getMotionThreshold(): Float = motionThreshold

    /** Когда последний раз было зафиксировано движение по акселю */
    fun getLastMotionRt(): Long = lastMotionRt

    /** 0..1, затухающий след движения (чем свежее — тем ближе к 1) */
    fun getMotionConfidence(): Float = motionConfidence

    // ======= Состояние для UI (в фильтре не используем как истину) =======
    @Volatile private var currentState: MotionState = MotionState.UNKNOWN
    private var candidateState: MotionState = MotionState.UNKNOWN
    private var candidateSinceRt: Long = 0L
    fun getState(): MotionState = currentState

    private val listeners = CopyOnWriteArrayList<(MotionState) -> Unit>()
    private val startRefCount = AtomicInteger(0)

    fun addListener(listener: (MotionState) -> Unit) {
        listeners.add(listener)
        listener(currentState)
    }

    fun removeListener(listener: (MotionState) -> Unit) {
        listeners.remove(listener)
    }

    private fun emitState(state: MotionState) {
        for (l in listeners) l(state)
    }

    fun start() {
        val prev = startRefCount.getAndIncrement()
        if (prev > 0) return

        val s = if (useLinearSensor) linearSensor else accelSensor
        s?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        if (s == null) {
            currentState = MotionState.UNKNOWN
            emitState(currentState)
        }
    }

    fun stop() {
        val left = startRefCount.decrementAndGet()
        if (left > 0) return
        if (left < 0) {
            startRefCount.set(0)
            return
        }
        sensorManager.unregisterListener(this)
        window.clear()
    }

    override fun onSensorChanged(event: SensorEvent) {
        val now = SystemClock.elapsedRealtime()

        val vec = when (event.sensor.type) {
            Sensor.TYPE_LINEAR_ACCELERATION -> {
                Vec3(event.values[0], event.values[1], event.values[2])
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = event.values[0]; val ay = event.values[1]; val az = event.values[2]
                gx = gravityAlpha * gx + (1f - gravityAlpha) * ax
                gy = gravityAlpha * gy + (1f - gravityAlpha) * ay
                gz = gravityAlpha * gz + (1f - gravityAlpha) * az
                Vec3(ax - gx, ay - gy, az - gz)
            }
            else -> return
        }

        window.addLast(vec)
        if (window.size > windowSize) window.removeFirst()

        val rms = calcRmsDemeaned(window)
        lastRms = rms
        lastRmsEma = if (lastRmsEma == 0f) rms else (emaAlpha * rms + (1f - emaAlpha) * lastRmsEma)
        val used = lastRmsEma

        // ======= След движения =======
        if (used >= motionThreshold) {
            lastMotionRt = now
        }
        motionConfidence = calcTraceConfidence(now)

        // ======= UI state-machine (опционально) =======
        val newCandidate = if (used >= motionThreshold) MotionState.MOVING else MotionState.STATIONARY

        if (currentState == MotionState.UNKNOWN && newCandidate == MotionState.STATIONARY) {
            currentState = MotionState.STATIONARY
            candidateState = MotionState.STATIONARY
            candidateSinceRt = now
            emitState(currentState)
            return
        }

        if (newCandidate != candidateState) {
            candidateState = newCandidate
            candidateSinceRt = now
            return
        }

        val needMs =
            if (candidateState == MotionState.MOVING) enterConfirmMs
            else exitConfirmMs

        if (candidateState != currentState && (now - candidateSinceRt >= needMs)) {
            currentState = candidateState
            emitState(currentState)
        }
    }

    private fun calcTraceConfidence(now: Long): Float {
        val lm = lastMotionRt
        if (lm == 0L) return 0f
        val dt = (now - lm).coerceAtLeast(0L).toFloat()
        val x = dt / traceHalfLifeMs
        // 0.5^(dt/halfLife)
        return (0.5f).pow(x)
    }

    private fun calcRmsDemeaned(values: ArrayDeque<Vec3>): Float {
        if (values.isEmpty()) return 0f

        var mx = 0f; var my = 0f; var mz = 0f
        for (v in values) { mx += v.x; my += v.y; mz += v.z }
        val n = values.size.toFloat()
        mx /= n; my /= n; mz /= n

        var sumSq = 0f
        for (v in values) {
            val dx = v.x - mx
            val dy = v.y - my
            val dz = v.z - mz
            sumSq += dx * dx + dy * dy + dz * dz
        }
        return sqrt(sumSq / values.size)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    data class MotionSnapshot(
        val rms: Float,
        val rmsEma: Float,
        val threshold: Float,
        val confidence: Float,
        val lastMotionSecondsAgo: Float?, // null если движения еще не было
        val state: MotionState
    )

    fun getSnapshot(nowRt: Long = SystemClock.elapsedRealtime()): MotionSnapshot {
        val lm = lastMotionRt
        val secAgo = if (lm == 0L) null else ((nowRt - lm).coerceAtLeast(0L) / 1000f)

        return MotionSnapshot(
            rms = lastRms,
            rmsEma = lastRmsEma,
            threshold = motionThreshold,
            confidence = motionConfidence,
            lastMotionSecondsAgo = secAgo,
            state = currentState
        )
    }
}
