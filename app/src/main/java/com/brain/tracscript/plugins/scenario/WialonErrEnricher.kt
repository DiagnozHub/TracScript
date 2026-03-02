package com.brain.tracscript.plugins.scenario

import android.util.Base64
import com.brain.tracscript.GptClient
import org.json.JSONArray
import org.json.JSONObject

object WialonErrEnricher {

    /**
     * Если GPT недоступен/не настроен/ошибка — вернет originalJson БЕЗ изменений.
     * Если получилось — добавит в каждую строку с "err" новый параметр:
     * { "name": "err_b64", "type": 3, "value": "<base64 русской расшифровки>" }
     */
    fun enrichErrToBase64IfPossible(
        originalJson: String,
        gpt: GptClient
    ): String {
        if (!gpt.isConfigured()) return originalJson

        val root = try { JSONObject(originalJson) } catch (_: Exception) { return originalJson }
        val rows = root.optJSONArray("rows") ?: return originalJson

        var changed = false

        // простой in-memory кеш на время обработки одного JSON
        val cache = HashMap<String, String?>()

        for (i in 0 until rows.length()) {
            val row = rows.optJSONArray(i) ?: continue

            // если уже есть err_b64 — не трогаем
            if (rowHasName(row, "err_b64")) continue

            val errText = findCellValueByName(row, "err") ?: continue

            val ru = cache.getOrPut(errText) { gpt.explainErrRuShort(errText) } ?: continue
            val b64 = Base64.encodeToString(ru.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)

            row.put(JSONObject().apply {
                put("name", "err_b64")
                put("type", 3)
                put("value", b64)
            })

            changed = true
        }

        return if (changed) root.toString() else originalJson
    }

    private fun rowHasName(row: JSONArray, name: String): Boolean {
        for (j in 0 until row.length()) {
            val cell = row.optJSONObject(j) ?: continue
            if (cell.optString("name") == name) return true
        }
        return false
    }

    private fun findCellValueByName(row: JSONArray, name: String): String? {
        for (j in 0 until row.length()) {
            val cell = row.optJSONObject(j) ?: continue
            if (cell.optString("name") == name) {
                val v = cell.optString("value", "")
                return v.takeIf { it.isNotBlank() }
            }
        }
        return null
    }
}
