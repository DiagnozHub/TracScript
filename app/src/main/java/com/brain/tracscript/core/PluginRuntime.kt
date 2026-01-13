package com.brain.tracscript.core

import android.content.Context
import android.util.Log
import com.brain.tracscript.plugins.gps.GpsPlugin
import com.brain.tracscript.plugins.scenario.ScenarioPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class PluginRuntime(appContext: Context) {

    private val appCtx = appContext.applicationContext

    private val job = SupervisorJob()
    private val scope = CoroutineScope(job + Dispatchers.Default)

    private val _dataBus: DataBus = SimpleDataBus()
    val dataBus: DataBus get() = _dataBus

    private val pluginContext: PluginContext by lazy {
        object : PluginContext {
            override val appContext: Context = appCtx
            override val dataBus: DataBus = this@PluginRuntime.dataBus
            override val coroutineScope: CoroutineScope = scope

            override fun log(tag: String, message: String) {
                Log.d(tag, message)
            }
        }
    }

    private val plugins: List<Plugin> = listOf(
        GpsPlugin(appCtx),
        ScenarioPlugin(appCtx)
        // позже добавим новый плагин сценариев
    )

    private val attached = HashSet<String>()

    fun attachEnabled() {
        for (p in plugins) {
            if (!p.isEnabled()) continue
            if (attached.add(p.id)) {
                p.onAttach(pluginContext)
            }
        }
    }

    /*
    fun detachAll() {
        // detach в обратном порядке — меньше сюрпризов
        for (p in plugins.asReversed()) {
            if (attached.remove(p.id)) {
                p.onDetach()
            }
        }
        job.cancel()
    }

    fun attachAllOnce() {
        for (p in plugins) {
            if (attached.add(p.id)) {
                p.onAttach(pluginContext)
            }
        }
    }
     */

}
