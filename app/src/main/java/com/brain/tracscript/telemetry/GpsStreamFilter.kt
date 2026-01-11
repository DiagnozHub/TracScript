package com.brain.tracscript.telemetry

import android.location.Location
import android.os.SystemClock
import java.util.Date
import kotlin.math.abs
import kotlin.math.min

class GpsStreamFilter(
    private val reportIntervalMs: Long,
    private val distanceM: Float,
    private val angleDeg: Double,

    private val motionDetector: MotionDetector?, // nullable

    private val stationaryHeartbeatMs: Long,

    // аксель = факт движения
    private val accelRecentMs: Long = 3_000L,
    accelConfidenceMoving: Float = 0.10f,

    // GPS подтверждение движения
    private val gpsMovingSpeedKmh: Double = 5.0,

    // "хорошая точка"
    private val minSats: Int = 4,
    private val maxAcc: Double = 60.0,

    // ЯКОРЬ: нужно N подряд хороших точек
    private val anchorNeedGoodStreak: Int = 5,
    private val anchorWarmupTimeoutMs: Long = 30_000L,

    // НОВОЕ: геометрическая стабильность streak
    private val anchorStreakMaxDriftM: Float = 25f

) {

    @Volatile
    private var accelConfidenceMoving: Float = accelConfidenceMoving

    private var lastSent: Position? = null
    private var lastSentRt: Long = 0L

    // ЯКОРЬ: координаты не меняем после установки
    private var anchor: Position? = null

    // для монотонного времени
    private var lastGoodWallTimeMs: Long = 0L
    private var lastGoodRt: Long = 0L

    // warmup якоря (на стоянке)
    private var anchorWarmupStartRt: Long = 0L
    private var goodStreak: Int = 0
    private var bestCandidate: Position? = null // лучшая по sats/accuracy

    // НОВОЕ: первая точка streak (для проверки дрейфа)
    private var streakFirst: Position? = null

    // --- Anti-jump by speed ---
    //private val jumpMaxSpeedMps: Double = 45.0      // 162 km/h
    private val jumpMaxSpeedMps: Double = 80.0   // было 45.0

    private val jumpCheckMaxDtMs: Long = 30_000L    // проверяем только если dt <= 30 sec
    private val jumpHoldMs: Long = 20_000L          // держим якорь после телепорта

    private var jumpHoldUntilRt: Long = 0L
    private val JUMP_DIST_WHEN_NO_DT = 300.0

    private val NO_DT_MS = 5L

    private var startGoodStreak: Int = 0

    private var lastBattery: BatteryStatus? = null

    fun filter(pos: Position): Position? {
        val nowRt = SystemClock.elapsedRealtime()

        val sane = isSane(pos)
        val goodFix = sane && isGoodFix(pos)

        lastBattery = BatteryStatus(
            level = pos.battery,
            charging = pos.charging,
            temperatureC = pos.batteryTempC
        )


        // запоминаем последнее “хорошее” GPS-время, чтобы heartbeat имел растущее время
        if (goodFix) {
            lastGoodWallTimeMs = pos.time.time
            lastGoodRt = nowRt
        }

        if (!sane) {
            ensureAnchorFromLastSent()
            return emitAnchorIfDue(nowRt)
        }

        if (lastSent == null) {
            if (goodFix)
                startGoodStreak++
            else
                startGoodStreak = 0

            if (startGoodStreak < 30)
                return null
        }

        /*
        // Анти джамп фильтр
        // Пока не используем, нужно доводить до ума
        if (maybeEnterJumpHold(goodFix, pos, nowRt)) {
            return emitAnchorHeartbeatWhileHolding(nowRt)
        }
        if (isInJumpHold(nowRt)) {
            return emitAnchorHeartbeatWhileHolding(nowRt)
        }
        */

        val accelMotion = hasRecentMotion(nowRt)

        // если аксель есть — обязателен
        // если акселя нет — деградация на GPS
        val movingNow =
            if (motionDetector != null)
                accelMotion && goodFix //&& pos.speed >= gpsMovingSpeedKmh
            else
                goodFix && pos.speed >= gpsMovingSpeedKmh

        return if (movingNow) {
            anchor = null
            resetAnchorWarmup()
            emitMovingIfDue(pos, nowRt)
        } else {
            ensureAnchorStationary(pos, nowRt, goodFix)
            emitAnchorIfDue(nowRt)
        }
    }

    fun setAccelConfidenceMoving(v: Float) {
        accelConfidenceMoving = v.coerceIn(0.0f, 1.0f)
    }

    private fun maybeEnterJumpHold(goodFix: Boolean, pos: Position, nowRt: Long): Boolean {
        if (!goodFix) return false
        if (lastSentRt == 0L) return false

        // ✅ НОВОЕ: если мы стояли на якоре и началось движение — антиджамп не применяем
        // Иначе якорь-ошибка (50 км) навсегда заблокирует выход.
        if (anchor != null && hasRecentMotion(nowRt)) return false

        val ref = anchor ?: lastSent ?: return false

        var dtMs = nowRt - lastSentRt

        // ✅ fallback на время самой точки
        if (dtMs <= 0L) {
            val lastWall = lastSent?.time?.time ?: 0L
            val curWall = pos.time.time
            val wallDt = curWall - lastWall
            if (wallDt in 1..jumpCheckMaxDtMs) dtMs = wallDt
        }

        val d = distanceLatLon(ref.latitude, ref.longitude, pos.latitude, pos.longitude).toDouble()

        if (dtMs <= NO_DT_MS) {
            if (d > JUMP_DIST_WHEN_NO_DT) {
                jumpHoldUntilRt = nowRt + jumpHoldMs
                return true
            }
            return false
        }

        /*
        if (dtMs > jumpCheckMaxDtMs)
            return false
        */

        val v = d / (dtMs / 1000.0)

        if (v > jumpMaxSpeedMps) {
            jumpHoldUntilRt = nowRt + jumpHoldMs
            return true
        }
        return false
    }

    private fun isInJumpHold(nowRt: Long): Boolean {
        val until = jumpHoldUntilRt
        if (until == 0L) return false
        if (nowRt < until) return true
        jumpHoldUntilRt = 0L
        return false
    }

    private fun emitAnchorHeartbeatWhileHolding(nowRt: Long): Position? {
        // якорь должен быть, чтобы не было тишины
        ensureAnchorFromLastSent()
        return emitAnchorIfDue(nowRt)
    }


    // =====================================================
    // MOTION (АКСЕЛЬ КАК ФАКТ)
    // =====================================================

    private fun hasRecentMotion(nowRt: Long): Boolean {
        val md = motionDetector ?: return false
        val lm = md.getLastMotionRt()
        if (lm == 0L) return false
        if (nowRt - lm <= accelRecentMs) return true
        return md.getMotionConfidence() >= accelConfidenceMoving
    }

    // =====================================================
    // MOVING
    // =====================================================

    private fun emitMovingIfDue(pos: Position, nowRt: Long): Position? {
        val prev = lastSent
        if (prev == null) {
            return send(pos, nowRt)
        }

        val dtMs = nowRt - lastSentRt
        val byInterval = dtMs >= reportIntervalMs

        val distM = distance(prev, pos)
        val byDistance = distM >= distanceM

        val courseDiffDeg = bearingDiffDeg(prev.course, pos.course)
        //val byAngle = courseDiffDeg >= angleDeg

        val byAngle = distM >= 10.0 && courseDiffDeg >= angleDeg

        val shouldSend = byInterval || byDistance || byAngle
        if (!shouldSend) return null

        return send(pos, nowRt)
    }

    // =====================================================
    // STATIONARY / ANCHOR WARMUP
    // =====================================================

    private fun ensureAnchorStationary(pos: Position, nowRt: Long, goodFix: Boolean) {
        if (anchor != null) return

        if (anchorWarmupStartRt == 0L) anchorWarmupStartRt = nowRt

        // обновим лучшего кандидата (даже если не goodFix — пригодится на таймауте)
        bestCandidate = chooseBetter(bestCandidate, pos)

        if (goodFix) {
            // streak логика
            if (goodStreak == 0) {
                streakFirst = pos
            }
            goodStreak++

            // если набрали N — проверяем дрейф между первой и текущей
            if (goodStreak >= anchorNeedGoodStreak) {
                val first = streakFirst
                val driftOk = if (first == null) true
                else distanceLatLon(
                    first.latitude,
                    first.longitude,
                    pos.latitude,
                    pos.longitude
                ) <= anchorStreakMaxDriftM

                if (driftOk) {
                    // ставим якорь на лучшую из накопленных (обычно это одна из goodFix)
                    val chosen = bestCandidate ?: pos
                    anchor = chosen.copy(speed = 0.0)
                    resetAnchorWarmup()
                    return
                } else {
                    // дрейф слишком большой — считаем что streak “сорвался”
                    // начинаем заново с текущей точки как первой
                    goodStreak = 1
                    streakFirst = pos
                }
            }
        } else {
            // streak обнулили
            goodStreak = 0
            streakFirst = null
        }

        // таймаут 30 сек — ставим что есть (лучшее)
        val elapsed = nowRt - anchorWarmupStartRt
        if (elapsed >= anchorWarmupTimeoutMs) {
            val chosen = bestCandidate ?: pos
            anchor = chosen.copy(speed = 0.0)
            resetAnchorWarmup()
        }
    }

    private fun ensureAnchorFromLastSent() {
        if (anchor != null) return
        anchor = lastSent?.copy(speed = 0.0)
    }

    private fun resetAnchorWarmup() {
        anchorWarmupStartRt = 0L
        goodStreak = 0
        bestCandidate = null
        streakFirst = null

        //startGoodStreak = 0
    }

    private fun chooseBetter(a: Position?, b: Position): Position {
        if (a == null) return b
        // "лучше" = больше sats, при равенстве меньше accuracy
        return when {
            b.sats > a.sats -> b
            b.sats < a.sats -> a
            b.accuracy < a.accuracy -> b
            else -> a
        }
    }

    private fun emitAnchorIfDue(nowRt: Long): Position? {
        val a = anchor ?: return null
        if (lastSentRt != 0L && nowRt - lastSentRt < stationaryHeartbeatMs) return null

        val b = lastBattery
        val out = a.copy(
            speed = 0.0,
            battery = b?.level ?: a.battery,
            charging = b?.charging ?: a.charging,
            batteryTempC = b?.temperatureC ?: a.batteryTempC
        )

        return send(out, nowRt)
    }

    // =====================================================
    // SEND (ВАЖНО: ОБНОВЛЯЕМ TIME)
    // =====================================================

    private fun send(p: Position, nowRt: Long): Position {
        val out = p.copy(time = Date(calcWallTime(nowRt)))
        lastSent = out
        lastSentRt = nowRt

        //startGoodStreak = 0

        return out
    }

    private fun calcWallTime(nowRt: Long): Long {
        return if (lastGoodWallTimeMs != 0L) {
            lastGoodWallTimeMs + (nowRt - lastGoodRt)
        } else {
            System.currentTimeMillis()
        }
    }

    // =====================================================
    // QUALITY / SANITY
    // =====================================================

    private fun isGoodFix(p: Position): Boolean =
        p.sats >= minSats && p.accuracy in 1.0..maxAcc

    private fun isSane(p: Position): Boolean =
        p.latitude in -90.0..90.0 &&
                p.longitude in -180.0..180.0 &&
                p.accuracy in 1.0..500.0 &&
                p.speed >= 0.0

    // =====================================================
    // GEO
    // =====================================================

    private fun distance(a: Position, b: Position): Float {
        val r = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, r)
        return r[0]
    }

    private fun distanceLatLon(aLat: Double, aLon: Double, bLat: Double, bLon: Double): Float {
        val r = FloatArray(1)
        Location.distanceBetween(aLat, aLon, bLat, bLon, r)
        return r[0]
    }

    private fun bearingDiffDeg(a: Double, b: Double): Double {
        if (!a.isFinite() || !b.isFinite()) return 0.0

        val d = abs(a - b) % 360.0
        return min(d, 360.0 - d)
    }
}
