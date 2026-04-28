package com.brain.tracscript.core

import android.app.Application
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import com.brain.tracscript.security.AdminAuth

class TracScriptApp : Application() {
    lateinit var pluginRuntime: PluginRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        pluginRuntime = PluginRuntime(applicationContext)
        pluginRuntime.attachAllOnce()

        // Сбрасываем разблокировку каждый раз, когда приложение целиком уходит
        // в фон (последняя Activity покидает foreground). Переходы между
        // Activity внутри приложения (Main → Settings и т.п.) НЕ сбрасывают —
        // ProcessLifecycleOwner смотрит на процесс, а не на отдельные экраны.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_STOP) {
                    AdminAuth.lock()
                }
            }
        )
    }
}
