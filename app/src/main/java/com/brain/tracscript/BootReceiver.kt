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

        //val autoStart = SettingsStorage.isAutoStartEnabled(context)
        //if (!autoStart) return

        BootInitService.start(context)

        // если надо - оставляем твою periodic-команду
        //if (SettingsStorage.isPeriodicEnabled(context)) {
        //    val sec = SettingsStorage.getPeriodicIntervalSec(context).coerceAtLeast(1)
        //    CommandStorage.saveRaw(context, "start_periodic:${sec * 1000L}")
        //}
    }
}
