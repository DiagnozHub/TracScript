package com.brain.tracscript.plugins.gps

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.brain.tracscript.R
import com.brain.tracscript.FileHelper
import com.brain.tracscript.ScreenOnController
import com.brain.tracscript.telemetry.Position
import com.brain.tracscript.telemetry.PositionProvider
import com.brain.tracscript.telemetry.AndroidPositionProvider
import com.brain.tracscript.telemetry.AppLog
import com.brain.tracscript.telemetry.GpsStreamFilter
import com.brain.tracscript.telemetry.MotionBus
import com.brain.tracscript.telemetry.MotionDebug
import com.brain.tracscript.telemetry.MotionDetector
import com.brain.tracscript.telemetry.MotionState
import com.brain.tracscript.telemetry.NetworkState
import com.brain.tracscript.telemetry.ParamType
import com.brain.tracscript.telemetry.PositionParam
import com.brain.tracscript.telemetry.RawGpsBus
import com.brain.tracscript.telemetry.TelemetryRepository
import kotlinx.coroutines.*
import java.util.Locale
import kotlin.coroutines.CoroutineContext

class GpsService : Service(), CoroutineScope {

    companion object {
        private const val TAG = "GpsService"

        const val ACTION_START = "com.brain.tracscript.GPS_START"
        const val ACTION_STOP = "com.brain.tracscript.GPS_STOP"

        private const val NOTIF_CHANNEL_ID = "gps_channel6"
        private const val NOTIF_ID = 1006

        fun start(context: Context) {
            val intent = Intent(context, GpsService::class.java).apply {
                action = ACTION_START
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, GpsService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }

    private val job = SupervisorJob()
    override val coroutineContext: CoroutineContext = job + Dispatchers.IO

    private var telemetryRepo: TelemetryRepository? = null
    private var positionProvider: PositionProvider? = null
    private var workerJob: Job? = null

    private var lastCleanupTime = 0L

    private var gpsFilter: GpsStreamFilter? = null
    private var motionDetector: MotionDetector? = null

    private var motionCfgJob: Job? = null
    private var motionDebugJob: Job? = null

    private var fileHelper: FileHelper? = null
    private var logGps: AppLog? = null

    private var gpsWakeLock: PowerManager.WakeLock? = null
    private var lastWakeLockRenewElapsed = 0L
    private var protocolSender: GpsProtocolSender? = null

    private lateinit var screenOnController: ScreenOnController

    @Volatile
    private var lastGpsCbElapsed = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        gpsWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TracScript:GpsWialon"
        ).apply {
            setReferenceCounted(false)
        }

        // --- управление экраном ---
        screenOnController = ScreenOnController(this).also {
            it.register()
            it.initFromSettings()
        }

        logGps?.i(TAG, "GpsService: onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWork()
            ACTION_STOP -> stopSelf()
            else -> startWork() // на всякий случай
        }
        return START_STICKY
    }

    private fun ensureWakeLock() {
        val wl = gpsWakeLock ?: return
        try {
            wl.acquire(30 * 60 * 1000L)
            logGps?.i(TAG, "wakelock refreshed (30m)")
        } catch (t: Throwable) {
            logGps?.e(TAG, "wakelock acquire failed: ${t.message}", t)
        }
    }


    private fun startWork() {
        // Уже запущен
        if (workerJob != null && workerJob!!.isActive) {
            logGps?.i(TAG, "startWork: already started workerJob")
            return
        }


        val cfg = loadGpsConfig(applicationContext)
        logGps?.i(TAG, "startWork: cfg.enabled=${cfg.enabled}")

        if (!cfg.enabled) {
            logGps?.i(TAG, "startWork: GpsService is off — stopping the service")
            stopSelf()
            return
        }

        fileHelper = FileHelper(applicationContext)

        // отдельный лог под GPS/ресабскрайбы
        logGps = AppLog(fileHelper!!, this, fileName = "gps_service.log")
        logGps?.i(TAG, "service start")

        telemetryRepo = TelemetryRepository(applicationContext)

        protocolSender = GpsProtocolFactory.create(cfg, telemetryRepo!!)

        // Foreground-уведомление
        val notif = buildNotification(cfg)
        startForeground(NOTIF_ID, notif)

        logGps?.i(
            TAG,
            "foreground started host=${cfg.host} port=${cfg.port} intervalSec=${cfg.gpsIntervalSec}"
        )

        ensureWakeLock();

        val prefs =
            applicationContext.getSharedPreferences("plugin_gps_wialon", Context.MODE_PRIVATE)
        val thr = prefs.getFloat(
            GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD,
            GpsPluginSettingsDefinition.DEFAULT_MOTION_THRESHOLD
        )

        // Детектор движения
        motionDetector = MotionDetector(applicationContext).apply {
            setMotionThreshold(thr)
            addListener { st -> MotionBus.publishState(st) }
            start()
        }

        motionCfgJob?.cancel()
        motionCfgJob = launch {
            val prefs =
                applicationContext.getSharedPreferences("plugin_gps_wialon", Context.MODE_PRIVATE)

            var lastThr = Float.NaN
            var lastConf = Float.NaN

            while (isActive) {
                // 1) Порог сработки акселя
                val thr = prefs.getFloat(
                    GpsPluginSettingsDefinition.KEY_MOTION_THRESHOLD,
                    GpsPluginSettingsDefinition.DEFAULT_MOTION_THRESHOLD
                )
                if (thr != lastThr) {
                    motionDetector?.setMotionThreshold(thr)
                    lastThr = thr
                    logGps?.i(TAG, "motionThreshold updated: $thr")
                }

                // 2) Порог прохождения уверенности (accelConfidenceMoving)
                val conf = prefs.getFloat(
                    GpsPluginSettingsDefinition.KEY_ACCEL_CONFIDENCE_MOVING,
                    GpsPluginSettingsDefinition.DEFAULT_ACCEL_CONFIDENCE_MOVING
                ).coerceIn(0f, 1f)

                if (conf != lastConf) {
                    gpsFilter?.setAccelConfidenceMoving(conf) // важно: gpsFilter может быть null до старта GPS
                    lastConf = conf
                    logGps?.i(TAG, "accelConfidenceMoving updated: $conf")
                }

                delay(1500L)
            }
        }


        motionDebugJob?.cancel()
        motionDebugJob = launch {
            while (isActive) {
                val md = motionDetector ?: break
                val now = SystemClock.elapsedRealtime()
                val last = md.getLastMotionRt()
                val snap = md.getSnapshot(now)
                MotionBus.publishDebug(
                    MotionDebug(
                        confidence = md.getMotionConfidence(),
                        secondsSinceMotion = if (last == 0L) null else (now - last) / 1000f,
                        rmsEma = snap.rmsEma
                    )
                )
                delay(250L)
            }
        }

        val conf = prefs.getFloat(
            GpsPluginSettingsDefinition.KEY_ACCEL_CONFIDENCE_MOVING,
            GpsPluginSettingsDefinition.DEFAULT_ACCEL_CONFIDENCE_MOVING
        )

        // GPS-провайдер (если есть разрешение)
        if (hasLocationPermission()) {
            val gpsIntervalMs = cfg.gpsIntervalSec
                .coerceAtLeast(1)
                .toLong() * 1000L

            // Создаём фильтр GPS-потока
            gpsFilter = GpsStreamFilter(
                reportIntervalMs = gpsIntervalMs,
                distanceM = cfg.gpsMinDistanceM.toFloat(),
                angleDeg = cfg.gpsMinAngleDeg.toDouble(),
                motionDetector = motionDetector,
                stationaryHeartbeatMs = gpsIntervalMs,
                accelConfidenceMoving = conf
            )

            positionProvider = AndroidPositionProvider(
                context = applicationContext,
                listener = object : PositionProvider.PositionListener {
                    override fun onPositionUpdate(position: Position) {

                        lastGpsCbElapsed = SystemClock.elapsedRealtime()

                        // СЫРОЕ (до фильтра) — публикуем для UI/отладки
                        RawGpsBus.publish(position)

                        val filter = gpsFilter

                        // Если фильтра по какой-то причине нет — ведём себя как раньше
                        if (filter == null) {
                            launch {
                                try {
                                    telemetryRepo?.saveGpsPosition(position)
                                } catch (e: Exception) {
                                    logGps?.e(TAG, "Error saving GPS data to the database (without filter): ${e.message}")
                                }
                            }
                            return
                        }

                        val accepted = filter.filter(position)
                        if (accepted != null) {
                            launch {
                                try {
                                    //telemetryRepo?.saveGpsPosition(accepted)
                                    val snap = motionDetector?.getSnapshot()

                                    val extra = buildAccelParams(snap)

                                    telemetryRepo?.saveGpsPosition(accepted, extra)

                                } catch (e: Exception) {
                                    logGps?.e(TAG, "Error saving GPS to DB: ${e.message}")
                                }
                            }
                        } else {
                            logGps?.i(TAG, "GPS-point is bad: GpsStreamFilter")
                        }
                    }


                    override fun onPositionError(error: Throwable) {
                        RawGpsBus.publishError(error)
                        logGps?.e(TAG, "gps_error ${error.message}", error)
                    }
                },
                deviceId = cfg.imei,
                interval = gpsIntervalMs,
                minDistanceM = cfg.gpsMinDistanceM.toFloat(),
                minAngleDeg = cfg.gpsMinAngleDeg.toDouble(),
                accuracy = "high"
            ).also {
                it.startUpdates()
                lastGpsCbElapsed = SystemClock.elapsedRealtime()
                logGps?.i(
                    TAG,
                    "AndroidPositionProvider started minDist=${cfg.gpsMinDistanceM} angle=${cfg.gpsMinAngleDeg}"
                )
            }
        } else {
            logGps?.w(TAG, "ACCESS_FINE_LOCATION permission not granted — GPS will not start")
        }

        // Воркер Wialon
        workerJob = launch {
            runWorkerLoop()
        }
    }

    private fun buildNotification(cfg: GpsConfig): Notification {
        return NotificationCompat.Builder(this, NOTIF_CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(
                getString(
                    R.string.notif_nav_server,
                    cfg.host,
                    cfg.port
                )
            )
            .setSmallIcon(R.mipmap.ic_launcher)
            .setOngoing(true)
            .build()
    }


    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            val ch = NotificationChannel(
                NOTIF_CHANNEL_ID,
                "GPS",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "GPS"
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        logGps?.i(TAG, "GpsService: onDestroy")

        motionDebugJob?.cancel()
        motionDebugJob = null

        motionCfgJob?.cancel()
        motionCfgJob = null

        motionDetector?.stop()
        motionDetector = null
        gpsFilter = null

        workerJob?.cancel()
        workerJob = null

        positionProvider?.stopUpdates()
        positionProvider = null

        telemetryRepo = null

        gpsWakeLock?.let { if (it.isHeld) it.release() }
        gpsWakeLock = null

        logGps?.i(TAG, "service destroy")
        logGps?.close()
        logGps = null
        fileHelper = null

        job.cancel()
        super.onDestroy()
    }

    // -----------------------------------------
    //   WORKER LOOP: GPS + CORE_EVENTS -> Gps Server
    // -----------------------------------------

    private suspend fun runWorkerLoop() {
        val repo = telemetryRepo ?: return

        logGps?.i(TAG, "GPS worked started")

        while (isActive) {

            val now = System.currentTimeMillis()

            try {
                val cfg = loadGpsConfig(applicationContext)

                if (!cfg.enabled) {
                    logGps?.i(TAG, "GpsService is disabled in settings — stopping the service")
                    stopSelf()
                    return
                }

                val nowElapsed = SystemClock.elapsedRealtime()
                //val timeoutMs = cfg.gpsIntervalSec * 1000L * 5

                // продлеваем wakelock раз в 10 минут, если сервис жив
                if (nowElapsed - lastWakeLockRenewElapsed > 10 * 60 * 1000L) {
                    ensureWakeLock()
                    lastWakeLockRenewElapsed = nowElapsed
                }

                /*
                if (lastGpsCbElapsed != 0L && nowElapsed - lastGpsCbElapsed > timeoutMs) {

                    logGps?.w(
                        TAG,
                        "gps_stalled dtMs=${nowElapsed - lastGpsCbElapsed} timeoutMs=$timeoutMs intervalSec=${cfg.gpsIntervalSec}"
                    )

                    positionProvider?.stopUpdates()
                    delay(500)
                    positionProvider?.startUpdates()
                    lastGpsCbElapsed = nowElapsed

                    logGps?.i(TAG, "gps_resubscribe_done")
                }
                */

                // --- ПРОВЕРКА ДОСТУПНОСТИ ИНТЕРНЕТА ---
                if (!NetworkState.isInternetAvailable(applicationContext)) {
                    logGps?.w(TAG, "No internet connection — skipping sending, buffering location points")
                    delay(10_000L)
                    continue
                }
                // --- КОНЕЦ ПРОВЕРКИ ---


                // --- ОДНО МЕСТО ДЛЯ ЧИСТКИ ---
                if (now - lastCleanupTime > 10 * 60 * 1000) {
                    repo.cleanupGpsKeepLastN(5)
                    repo.cleanupOldCoreEventsKeepLastN(5)
                    lastCleanupTime = now
                    logGps?.i(TAG, "Cleanup of old GPS and core_event records completed")
                }
                // --- КОНЕЦ БЛОКА ЧИСТКИ ---

                // 1. core_event (таблицы)
                val core = repo.getOldestPendingCoreEvent()
                if (core != null) {

                    val bestGps = repo.findBestGpsForTime(core.eventTimeMillis, 120_000L)
                    val dyn = bestGps?.let { repo.getGpsPositionParams(it.id) } ?: emptyList()

                    try {
                        protocolSender!!.sendCoreEvent(
                            core = core,
                            bestGps = bestGps,
                            params = dyn
                        )

                        logGps?.i(TAG, "CORE sent OK id=${core.id} gpsId=${bestGps?.id}")

                        // успех -> помечаем core как отправленный
                        repo.markCoreEventSent(core.id)

                        // удаляем связанные GPS только после УСПЕХА
                        if (bestGps != null) {
                            repo.deleteGpsPosition(bestGps.id)
                        }
                    } catch (e: GpsSendBlockedException) {
                        // gate -> НЕ помечаем, иначе потеряешь событие; просто ждём
                        logGps?.e(TAG, "CORE blocked id=${core.id} err=${e.message}", e)
                        delay(5_000L)

                    } catch (e: Exception) {

                        if (isTransientNetworkError(e)) {
                            logGps?.e(TAG, "CORE send FAILED (network, keep queued) id=${core.id} err=${e.message}", e)
                            delay(10_000L)
                        } else {
                            logGps?.e(TAG, "CORE send FAILED (skip permanent) id=${core.id} err=${e.message}", e)
                            repo.markCoreEventSent(core.id)
                            delay(5_000L)
                        }
                    }

                    continue
                }

                // 2. position (чистый GPS)
                val gps = repo.getOldestGpsPosition()
                if (gps != null) {
                    val dyn = repo.getGpsPositionParams(gps.id)

                    try {
                        protocolSender!!.sendGps(
                            pos = gps,
                            params = dyn
                        )

                        logGps?.i(TAG, "GPS sent OK id=${gps.id} lat=${gps.latitude} lon=${gps.longitude}")

                        // успех -> помечаем
                        repo.markGpsPositionSent(gps.id)

                    } catch (e: GpsSendBlockedException) {
                        // gate -> НЕ помечаем
                        logGps?.e(TAG, "GPS blocked id=${gps.id} err=${e.message}", e)
                        delay(5_000L)

                    } catch (e: Exception) {

                        if (isTransientNetworkError(e)) {
                            // СЕТЬ/ТАЙМАУТ -> НЕ помечаем sent, иначе потеряешь точку
                            logGps?.e(TAG, "GPS send FAILED (network, keep queued) id=${gps.id} err=${e.message}", e)
                            delay(10_000L)
                        } else {
                            // НЕ сеть -> считаем перманентным, чтобы очередь не клинила
                            logGps?.e(TAG, "GPS send FAILED (skip permanent) id=${gps.id} err=${e.message}", e)
                            repo.markGpsPositionSent(gps.id)
                            delay(5_000L)
                        }
                    }

                    continue
                }



                // 3. В БД вообще нет точек — при необходимости добавим heartbeat в БД
                // Hertbeat отправляем, тоько если не было координат в интервале gpsIntervalSec*2
                val intervalMs = (cfg.gpsIntervalSec * 2)
                    .coerceAtLeast(1)
                    .toLong() * 1000L

                val hbExtra = buildAccelParams(motionDetector?.getSnapshot())

                repo.insertHeartbeatIfNeeded(
                    deviceId = cfg.imei,
                    intervalMs = intervalMs,
                    paramsExtra = hbExtra
                )

                // 4. Нечего отправлять — подождём
                delay(2_000L)

            } catch (e: Exception) {
                logGps?.e(TAG, "Error in GPS worker: ${e.message}")
                delay(5_000L)
            }
        }

        logGps?.i(TAG, "GPS worker stopped (isActive == false)")
    }

    // -----------------------------------------
    //   УТИЛИТЫ WIALON: NAV + EXTRAS
    // -----------------------------------------
    private fun buildAccelParams(
        snap: MotionDetector.MotionSnapshot?
    ): List<PositionParam> {
        if (snap == null) return emptyList()

        return buildList {
            /*
            add(
                PositionParam(
                    name = "acc_rms",
                    type = ParamType.FLOAT,
                    value = String.format(Locale.US, "%.4f", snap.rms)
                )
            )
            */

            add(
                PositionParam(
                    name = "acc_rms_ema",
                    type = ParamType.FLOAT,
                    value = String.format(Locale.US, "%.4f", snap.rmsEma)
                )
            )

            /*
            add(
                PositionParam(
                    name = "acc_thr",
                    type = ParamType.FLOAT,
                    value = String.format(Locale.US, "%.4f", snap.threshold)
                )
            )
            */

            add(
                PositionParam(
                    name = "acc_conf",
                    type = ParamType.FLOAT,
                    value = String.format(Locale.US, "%.4f", snap.confidence)
                )
            )

            /*
            snap.lastMotionSecondsAgo?.let {
                add(
                    PositionParam(
                        name = "acc_last_s",
                        type = ParamType.FLOAT,
                        value = String.format(Locale.US, "%.1f", it)
                    )
                )
            }
            */

            add(
                PositionParam(
                    name = "acc_state",
                    type = ParamType.INT,
                    value = when (snap.state) {
                        MotionState.UNKNOWN -> "0"
                        MotionState.STATIONARY -> "1"
                        MotionState.MOVING -> "2"
                    }
                )
            )
        }
    }

    private fun isTransientNetworkError(t: Throwable): Boolean {
        // разворачиваем цепочку причин до корня
        var e: Throwable? = t
        while (e?.cause != null && e.cause !== e) e = e.cause

        return when (e) {
            is java.net.ConnectException,
            is java.net.SocketTimeoutException,
            is java.net.UnknownHostException,
            is java.net.NoRouteToHostException,
            is java.io.InterruptedIOException -> true
            else -> {
                val msg = (t.message ?: "").lowercase()
                msg.contains("failed to connect") ||
                        msg.contains("timeout") ||
                        msg.contains("unable to resolve host") ||
                        msg.contains("no route to host")
            }
        }
    }

}
