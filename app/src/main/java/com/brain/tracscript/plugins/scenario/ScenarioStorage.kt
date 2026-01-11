package com.brain.tracscript.plugins.scenario

import android.content.Context

object ScenarioStorage {

    private const val PREFS = "TracScript_script"

    // новый формат
    private const val KEY_IDS = "scenario_ids"

    // легаси-ключ (один сценарий)
    private const val KEY_SCENARIO_LEGACY = "scenario_text"

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

        // Миграция: если старый формат, поднимаем его в один сценарий
        if (rawIds == null || rawIds.isEmpty()) {
            val legacyText = prefs.getString(KEY_SCENARIO_LEGACY, null)
            val text = legacyText ?: ScenarioDefaults.DEFAULT_SCENARIO

            val scenario = Scenario(
                id = "main",
                name = "Сценарий 1",
                text = text,
                isMain = true
            )

            saveAllInternal(prefs, listOf(scenario))
            return listOf(scenario)
        }

        val ids = rawIds.toList()
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
                name = "Сценарий 1",
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

    fun setMainScenario(context: Context, id: String) {
        val list = loadAll(context)
        val updated = list.map {
            it.copy(isMain = it.id == id)
        }
        saveAll(context, updated)
    }

    fun getMainScenario(context: Context): Scenario {
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

    /** Старый метод: сохранит текст в текущий главный сценарий */
    fun save(context: Context, text: String) {
        val list = loadAll(context)
        if (list.isEmpty()) {
            val s = Scenario(
                id = "main",
                name = "Сценарий 1",
                text = text,
                isMain = true
            )
            saveAll(context, listOf(s))
            return
        }

        val main = list.find { it.isMain } ?: list.first()
        val updated = list.map {
            if (it.id == main.id) it.copy(text = text) else it
        }
        saveAll(context, updated)
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

        // старый одинарный сценарий больше не используем
        editor.remove(KEY_SCENARIO_LEGACY)

        editor.apply()
    }
}
