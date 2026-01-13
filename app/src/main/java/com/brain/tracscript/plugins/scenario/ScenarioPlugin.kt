package com.brain.tracscript.plugins.scenario

import android.content.Context
import com.brain.tracscript.core.Plugin
import com.brain.tracscript.core.PluginContext

object ScenarioPrefs {
    const val PREFS = "plugin_scenario"

    const val KEY_ENABLED = "enabled"

    const val KEY_PERIODIC_ENABLED = "periodic_enabled"
    const val KEY_PERIODIC_INTERVAL_SEC = "periodic_interval_sec"
    const val DEFAULT_PERIODIC_INTERVAL_SEC = 600

    fun prefs(appCtx: Context) =
        appCtx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isEnabled(appCtx: Context): Boolean =
        prefs(appCtx).getBoolean(KEY_ENABLED, false)

    fun setEnabled(appCtx: Context, enabled: Boolean) {
        prefs(appCtx).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isPeriodicEnabled(appCtx: Context): Boolean =
        prefs(appCtx).getBoolean(KEY_PERIODIC_ENABLED, false)

    fun getPeriodicIntervalSec(appCtx: Context): Int =
        prefs(appCtx).getInt(KEY_PERIODIC_INTERVAL_SEC, DEFAULT_PERIODIC_INTERVAL_SEC)

    fun setPeriodicEnabled(appCtx: Context, enabled: Boolean) {
        prefs(appCtx).edit().putBoolean(KEY_PERIODIC_ENABLED, enabled).apply()
    }

    fun setPeriodicIntervalSec(appCtx: Context, sec: Int) {
        prefs(appCtx).edit().putInt(KEY_PERIODIC_INTERVAL_SEC, sec).apply()
    }
}

class ScenarioPlugin (private val appCtx: Context) : Plugin {
    override val id: String = "scenario"
    override val displayName: String = "Сценарии"

    private var ctx: PluginContext? = null

    override fun onAttach(context: PluginContext) {
        ctx = context
        context.log(id, "ScenarioPlugin attached, enabled=${isEnabled()}")
        // НИЧЕГО не запускаем. AccessibilityService живёт сам по себе.
    }

    override fun onDetach() {
        ctx?.log(id, "ScenarioPlugin detached")
        ctx = null
    }

    override fun isEnabled(): Boolean {
        //val c = ctx?.appContext ?: return false
        //return isScenarioPluginEnabled(c)

        //return isScenarioPluginEnabled(appCtx)
        return  ScenarioPrefs.isEnabled(appCtx)
    }
}
