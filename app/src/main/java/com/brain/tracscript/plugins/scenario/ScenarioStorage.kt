package com.brain.tracscript.plugins.scenario

import android.content.Context
import com.brain.tracscript.R

object ScenarioStorage {

    private const val PREFS = "TracScript_script"

    // новый формат
    private const val KEY_IDS = "scenario_ids"

    data class Scenario(
        val id: String,
        val name: String,
        val text: String,
        val isMain: Boolean
    )

    // --- Публичные методы нового API ---

    fun loadAll(context: Context): List<Scenario> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

        val rawIds = prefs.getStringSet(KEY_IDS, null)
        val ids = rawIds?.toList().orEmpty()
        val result = mutableListOf<Scenario>()

        for (id in ids) {
            val name = prefs.getString("scenario_${id}_name", null)
            val text = prefs.getString("scenario_${id}_text", null)
            val isMain = prefs.getBoolean("scenario_${id}_is_main", false)

            if (name != null && text != null) {
                result += Scenario(
                    id = id,
                    name = name,
                    text = text,
                    isMain = isMain
                )
            }
        }

        // страхуемся: если вдруг всё умерло — создаём дефолт
        if (result.isEmpty()) {
            val scenario = Scenario(
                id = "main",
                name = context.getString(R.string.default_scenario_name),
                text = ScenarioDefaults.DEFAULT_SCENARIO,
                isMain = true
            )
            saveAllInternal(prefs, listOf(scenario))
            return listOf(scenario)
        }

        // гарантируем, что главный ровно один
        if (result.none { it.isMain }) {
            val fixed = result.toMutableList()
            fixed[0] = fixed[0].copy(isMain = true)
            saveAllInternal(prefs, fixed)
            return fixed
        }

        if (result.count { it.isMain } > 1) {
            val firstMainId = result.first { it.isMain }.id
            val fixed = result.map {
                it.copy(isMain = it.id == firstMainId)
            }
            saveAllInternal(prefs, fixed)
            return fixed
        }

        return result
    }

    fun saveAll(context: Context, scenarios: List<Scenario>) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        saveAllInternal(prefs, scenarios)
    }

    private fun getMainScenario(context: Context): Scenario {
        val list = loadAll(context)
        return list.find { it.isMain } ?: list.first()
    }

    // --- Легаси-API: чтобы не ломать старый код ---

    /** Старый метод: вернёт текст ГЛАВНОГО сценария */
    fun load(context: Context): String? {
        return try {
            getMainScenario(context).text
        } catch (_: Exception) {
            null
        }
    }

    // --- Внутреннее сохранение ---

    private fun saveAllInternal(
        prefs: android.content.SharedPreferences,
        scenarios: List<Scenario>
    ) {
        val editor = prefs.edit()

        // очистим старые сценарии
        val oldIds = prefs.getStringSet(KEY_IDS, emptySet()) ?: emptySet()
        for (id in oldIds) {
            editor.remove("scenario_${id}_name")
            editor.remove("scenario_${id}_text")
            editor.remove("scenario_${id}_is_main")
        }

        val ids = scenarios.map { it.id }.toSet()
        editor.putStringSet(KEY_IDS, ids)

        for (s in scenarios) {
            editor.putString("scenario_${s.id}_name", s.name)
            editor.putString("scenario_${s.id}_text", s.text)
            editor.putBoolean("scenario_${s.id}_is_main", s.isMain)
        }

        editor.apply()
    }
}
