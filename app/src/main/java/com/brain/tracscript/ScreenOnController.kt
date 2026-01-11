package com.brain.tracscript

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraCharacteristics
import android.os.Build
import android.os.PowerManager
import android.util.Log

class ScreenOnController(
    context: Context
) {

    companion object {
        const val ACTION_UPDATE_SCREEN_OFF = "com.brain.tracscript.UPDATE_SCREEN_OFF"
        const val EXTRA_PREVENT_SCREEN_OFF = "prevent_screen_off"

        private const val TAG = "ScreenOnController"
    }

    private val appContext: Context = context.applicationContext

    private var wakeLock: PowerManager.WakeLock? = null

    private var cameraManager: CameraManager? = null
    private var torchCameraId: String? = null

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            if (intent.action != ACTION_UPDATE_SCREEN_OFF) return

            val enabled = intent.getBooleanExtra(EXTRA_PREVENT_SCREEN_OFF, false)
            Log.d(TAG, "onReceive: preventScreenOff=$enabled")

            if (enabled) {
                enableWakeLock()
            } else {
                disableWakeLock()
            }
        }
    }

    /** Вызвать при старте службы, до/после register() — не критично */
    fun initFromSettings() {
        val enabled = SettingsStorage.isPreventScreenOffEnabled(appContext)
        Log.d(TAG, "initFromSettings: preventScreenOff=$enabled")
        if (enabled) {
            enableWakeLock()
        } else {
            disableWakeLock()
        }
    }

    /** Зарегистрировать приёмник broadcast'а настройки */
    fun register() {
        val filter = IntentFilter(ACTION_UPDATE_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(
                receiver,
                filter,
                Context.RECEIVER_NOT_EXPORTED
            )
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            appContext.registerReceiver(receiver, filter)
        }
    }

    /** Снять wakelock и отписаться от broadcast'а */
    fun unregister() {
        disableWakeLock()
        try {
            appContext.unregisterReceiver(receiver)
        } catch (_: Exception) {
        }
    }

    // --- внутреннее управление wakelock ---

    private fun enableWakeLock() {
        val pm = appContext.getSystemService(PowerManager::class.java) ?: return

        if (wakeLock == null) {
            wakeLock = pm.newWakeLock(
                PowerManager.SCREEN_DIM_WAKE_LOCK or PowerManager.ON_AFTER_RELEASE,
                "TracScript:PreventScreenOff"
            )
        }

        if (wakeLock?.isHeld != true) {
            Log.d(TAG, "enableWakeLock: acquire()")
            wakeLock?.acquire()
        }
    }

    private fun disableWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                Log.d(TAG, "disableWakeLock: release()")
                it.release()
            }
        }
        wakeLock = null
    }

    /**
     * true — экран включён (устройство интерактивно), false — выключен.
     */
    fun isScreenOn(): Boolean {
        val pm = appContext.getSystemService(PowerManager::class.java) ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            pm.isInteractive
        } else {
            @Suppress("DEPRECATION")
            pm.isScreenOn
        }
    }

    /**
     * Выполнить root-команду (su -c ...)
     */
    private fun execRoot(command: String) {
        try {
            Log.d(TAG, "execRoot: $command")
            val proc = Runtime.getRuntime().exec(arrayOf("su", "-c", command))
            // можно не ждать, но пусть дочищает
            proc.waitFor()
        } catch (e: Exception) {
            Log.e(TAG, "execRoot failed: $command", e)
        }
    }

    /**
     * Включить экран (если он сейчас выключен).
     * Для простоты — имитация нажатия Power (keyevent 26).
     */
    fun turnScreenOn() {
        if (isScreenOn()) {
            Log.d(TAG, "turnScreenOn: экран уже включен")
            return
        }
        // power toggle
        execRoot("input keyevent 26")
    }

    /**
     * Выключить экран (если он сейчас включен).
     */
    fun turnScreenOff() {
        if (!isScreenOn()) {
            Log.d(TAG, "turnScreenOff: экран уже выключен")
            return
        }
        // power toggle
        execRoot("input keyevent 26")
    }

    private fun ensureTorchInitialized() {
        if (cameraManager != null && torchCameraId != null) return

        val cm = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
        if (cm == null) {
            Log.w(TAG, "ensureTorchInitialized: CameraManager = null")
            return
        }

        cameraManager = cm

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "ensureTorchInitialized: torch API недоступен (<23)")
            return
        }

        try {
            for (id in cm.cameraIdList) {
                val chars = cm.getCameraCharacteristics(id)
                val hasFlash = chars.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
                if (hasFlash) {
                    torchCameraId = id
                    Log.d(TAG, "ensureTorchInitialized: torchCameraId = $id")
                    break
                }
            }

            if (torchCameraId == null) {
                Log.w(TAG, "ensureTorchInitialized: не нашли камеру с фонариком")
            }
        } catch (e: Exception) {
            Log.e(TAG, "ensureTorchInitialized: ошибка инициализации фонарика", e)
        }
    }

    /**
     * Включить/выключить фонарик стандартным API.
     * Нужны разрешения CAMERA (+FLASHLIGHT на некоторых девайсах) и API >= 23.
     */
    fun setLight(enabled: Boolean) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            Log.w(TAG, "setLight: API < 23, фонарик не поддерживается стандартным API")
            return
        }

        ensureTorchInitialized()

        val cm = cameraManager
        val id = torchCameraId

        if (cm == null || id == null) {
            Log.w(TAG, "setLight: cameraManager/torchCameraId = null")
            return
        }

        try {
            Log.d(TAG, "setLight: setTorchMode(id=$id, enabled=$enabled)")
            cm.setTorchMode(id, enabled)
        } catch (e: SecurityException) {
            Log.e(TAG, "setLight: нет разрешений CAMERA/FLASHLIGHT", e)
        } catch (e: Exception) {
            Log.e(TAG, "setLight: ошибка setTorchMode", e)
        }
    }

}
