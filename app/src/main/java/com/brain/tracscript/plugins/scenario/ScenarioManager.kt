package com.brain.tracscript.plugins.scenario

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log

class ScenarioManager(
    private val logTag: String,
    private val debug: (String) -> Unit,
    private val startScenarioCallback: (runId: Int) -> Unit,
    private val appContext: Context
) {

    private val handler = Handler(Looper.getMainLooper())

    // Текущий runId активного сценария
    private var currentRunId: Int = 0

    // Периодический запуск
    private var periodicEnabled = false
    private var periodicIntervalMs: Long = 10 * 60_000L // по умолчанию 10 минут

    // --- удерживаем CPU, пока включена периодика ---
    private var cpuWakeLock: PowerManager.WakeLock? = null

    private fun acquireCpuWakeLock() {
        if (cpuWakeLock?.isHeld == true) return

        val pm = appContext.getSystemService(PowerManager::class.java) ?: return

        cpuWakeLock = pm.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "TracScript:ScenarioCpu"
        ).apply {
            setReferenceCounted(false)
            try {
                Log.d(logTag, "ScenarioManager: acquireCpuWakeLock()")
                acquire()
            } catch (e: Exception) {
                Log.e(logTag, "ScenarioManager: ошибка acquireCpuWakeLock", e)
            }
        }
    }

    private fun releaseCpuWakeLock() {
        cpuWakeLock?.let {
            if (it.isHeld) {
                try {
                    Log.d(logTag, "ScenarioManager: releaseCpuWakeLock()")
                    it.release()
                } catch (_: Exception) {
                }
            }
        }
        cpuWakeLock = null
    }
    // --- конец блока CPU wake lock ---

    private val periodicRunnable = object : Runnable {
        override fun run() {
            if (!periodicEnabled) return

            val runId = nextRunId()
            Log.d(logTag, "ScenarioManager: periodic tick, runId=$runId")
            debug("Плановый перезапуск сценария")

            startScenarioCallback(runId)

            handler.postDelayed(this, periodicIntervalMs)
        }
    }

    private fun nextRunId(): Int {
        currentRunId++
        return currentRunId
    }

    /** Разовый запуск сценария (например, по команде open_app или по кнопке в UI) */
    fun startSingleRun() {
        val runId = nextRunId()
        Log.d(logTag, "ScenarioManager: single run, runId=$runId")
        startScenarioCallback(runId)
    }

    /** Включить периодический запуск сценария каждые intervalMs */
    fun startPeriodic(intervalMs: Long) {
        periodicIntervalMs = intervalMs
        periodicEnabled = true

        handler.removeCallbacks(periodicRunnable)
        handler.post(periodicRunnable)

        // держим CPU живым, пока периодика включена
        acquireCpuWakeLock()

        debug("Периодический запуск каждые ${intervalMs / 1000} сек включен")
        Log.d(logTag, "ScenarioManager: startPeriodic intervalMs=$intervalMs")
    }

    /** Выключить периодический запуск */
    fun stopPeriodic() {
        periodicEnabled = false
        handler.removeCallbacks(periodicRunnable)

        // отпускаем CPU
        releaseCpuWakeLock()

        debug("Периодический запуск выключен")
        Log.d(logTag, "ScenarioManager: stopPeriodic")
    }

    /** Проверка, актуален ли ещё этот runId */
    fun isRunActive(runId: Int): Boolean = runId == currentRunId

    /** Жёстко "обрывает" текущий сценарий (просто сдвигаем runId) */
    fun cancelCurrentScenario() {
        currentRunId++
        Log.d(logTag, "ScenarioManager: cancelCurrentScenario, newRunId=$currentRunId")
    }

    fun onDestroy() {
        periodicEnabled = false
        handler.removeCallbacksAndMessages(null)
        releaseCpuWakeLock()
    }
}
