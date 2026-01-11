package com.brain.tracscript.plugins.gps

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject

object TableJsonExtractor {

    private const val TAG = "TracScript_Table"

    data class Row(
        val col1: String?,
        val col2: String?
    )

    /**
     * Парсим JSON вида:
     * {
     *   "viewId": "...",
     *   "rows": [
     *      {
     *        "texts": [
     *          { "id": "...", "text": "..." }, // col1
     *          { "id": "...", "text": "..." }, // col2
     *          ...
     *        ]
     *      },
     *      ...
     *   ]
     * }
     */
    fun extractTwoColumns(json: String): List<Row> {
        val result = mutableListOf<Row>()

        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
            return emptyList()
        }

        val rows = root.optJSONArray("rows") ?: return emptyList()

        for (i in 0 until rows.length()) {
            val rowObj = rows.optJSONObject(i) ?: continue
            val textsArr = rowObj.optJSONArray("texts") ?: JSONArray()

            val col1 = textsArr.optJSONObject(0)?.optString("text")?.takeIf { it.isNotBlank() }
            val col2 = textsArr.optJSONObject(1)?.optString("text")?.takeIf { it.isNotBlank() }

            // если обе колонки пустые — пропускаем
            if (col1 == null && col2 == null) continue

            result += Row(col1 = col1, col2 = col2)
        }

        Log.d(TAG, "Извлечено строк: ${result.size}")
        return result
    }

    /**
     * Новый метод: вытаскиваем ВСЕ text из узла texts как список строк.
     * Для каждой row → List<String> (по порядку в JSON).
     */
    fun extractTextArrays(json: String): List<List<String>> {
        val result = mutableListOf<List<String>>()

        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e(TAG, "JSON parse error", e)
            return emptyList()
        }

        val rows = root.optJSONArray("rows") ?: return emptyList()

        for (i in 0 until rows.length()) {
            val rowObj = rows.optJSONObject(i) ?: continue
            val textsArr = rowObj.optJSONArray("texts") ?: continue

            val texts = mutableListOf<String>()
            for (j in 0 until textsArr.length()) {
                val tObj = textsArr.optJSONObject(j) ?: continue
                val text = tObj.optString("text").trim()
                if (text.isNotEmpty()) {
                    texts += text
                }
            }

            if (texts.isNotEmpty()) {
                result += texts
            }
        }

        Log.d(TAG, "Извлечено строк (массивы texts): ${result.size}")
        return result
    }


    fun buildNoErrorsTableJson(
        viewId: String,
        reason: String
    ): String {

        fun esc(s: String) =
            s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")

        val v = esc(viewId)
        val r = esc(reason)

        return """
            {
              "viewId":"$v",
              "rows":[
                {
                  "index":0,
                  "top":0,
                  "bottom":0,
                  "mainText":"NO_ERRORS($r)",
                  "checkboxes":[],
                  "texts":[
                    { "id": null, "text": "DiagzoneErrors" },
                    { "id": null, "text": "0" },
                    { "id": null, "text": "$r" }
                  ]
                }
              ]
            }
        """.trimIndent()
    }

}
