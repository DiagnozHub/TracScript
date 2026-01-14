package com.brain.tracscript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {

    //companion object {
    //    private const val TAG = "TracScript"
    //}

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return

        //val autoStart = SettingsStorage.isAutoStartEnabled(context)
        //if (!autoStart) return

        BootInitService.start(context)
    }
}
