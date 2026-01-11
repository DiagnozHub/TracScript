package com.brain.tracscript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.brain.tracscript.plugins.scenario.CommandStorage

class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "TracScript"
    }

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        Log.d(TAG, "BootReceiver: ACTION_BOOT_COMPLETED получен")

        // Проверяем, разрешён ли автозапуск в настройках
        val autoStart = SettingsStorage.isAutoStartEnabled(context)
        val periodicEnabled = SettingsStorage.isPeriodicEnabled(context)
        Log.d(TAG, "BootReceiver: autoStart=$autoStart, periodicEnabled=$periodicEnabled")

        when {
            // 1) Если включён периодический запуск — он главный
            periodicEnabled -> {
                val sec = SettingsStorage
                    .getPeriodicIntervalSec(context)
                    .coerceAtLeast(1)
                val intervalMs = sec * 1000L
                val raw = "start_periodic:$intervalMs"

                CommandStorage.saveRaw(context, raw)
                Log.d(TAG, "BootReceiver: записал команду '$raw' в CommandStorage")
            }

            /*
            // 2) Если периодический выключен, но включён автостарт — один раз запустим сценарий
            autoStart -> {
                CommandStorage.saveRaw(context, "open_app")
                Log.d(TAG, "BootReceiver: записал команду 'open_app' в CommandStorage")
            }
            */

            // 3) Всё выключено — ничего не делаем
            else -> {
                Log.d(TAG, "BootReceiver: автозапуск и периодический запуск выключены, ничего не делаю")
            }
        }
    }
}
