package com.brain.tracscript.core

import android.app.Application

class TracScriptApp : Application() {
    lateinit var pluginRuntime: PluginRuntime
        private set

    override fun onCreate() {
        super.onCreate()
        pluginRuntime = PluginRuntime(applicationContext)
        pluginRuntime.attachAllOnce()
    }
}
