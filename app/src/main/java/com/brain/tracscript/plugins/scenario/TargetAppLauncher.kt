package com.brain.tracscript.plugins.scenario

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log

object TargetAppLauncher {

    private const val TAG = "TracScript.Launcher"

    fun launch(service: AccessibilityService, packageName: String) {
        try {
            val pm: PackageManager = service.packageManager
            val launchIntent: Intent? = pm.getLaunchIntentForPackage(packageName)

            if (launchIntent == null) {
                Log.w(TAG, "Не найден launch intent для пакета $packageName")
                return
            }

            launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            service.startActivity(launchIntent)
            Log.d(TAG, "Запустил пакет $packageName")

        } catch (e: Exception) {
            Log.e(TAG, "Ошибка при запуске пакета $packageName", e)
        }
    }
}
