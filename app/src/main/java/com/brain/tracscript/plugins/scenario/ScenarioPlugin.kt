package com.brain.tracscript.plugins.scenario

import android.content.Context
import com.brain.tracscript.core.Plugin
import com.brain.tracscript.core.PluginContext

private const val PREFS = "plugin_scenario"
private const val KEY_ENABLED = "enabled"

fun isScenarioPluginEnabled(appContext: Context): Boolean {
    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    return prefs.getBoolean(KEY_ENABLED, false)
}

fun setScenarioPluginEnabled(appContext: Context, enabled: Boolean) {
    val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
}

class ScenarioPlugin : Plugin {
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
        val c = ctx?.appContext ?: return false
        return isScenarioPluginEnabled(c)
    }
}
