package com.brain.tracscript.core
import androidx.compose.runtime.Composable
import com.brain.tracscript.plugins.gps.GpsPluginSettingsDefinition
import com.brain.tracscript.plugins.scenario.ScenarioPluginSettingsDefinition

// ------------------------
// Общие интерфейсы
// ------------------------

interface PluginSettingsDefinition {
    val id: String
    //val displayName: String

    @Composable
    fun displayName(): String

    @Composable
    fun Content()
}

object PluginSettingsRegistry {

    fun getPluginSettings(): List<PluginSettingsDefinition> {
        return listOf(
            GpsPluginSettingsDefinition,
            ScenarioPluginSettingsDefinition
            // сюда потом добавишь другие плагины
        )
    }
}