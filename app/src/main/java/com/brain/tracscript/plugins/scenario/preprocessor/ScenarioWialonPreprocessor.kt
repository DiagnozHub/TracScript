package com.brain.tracscript.plugins.scenario.preprocessor

import org.json.JSONArray
import org.json.JSONObject

data class PreParam(val name: String, val type: Int, val value: String)
data class PreRow(val params: List<PreParam>)

object ScenarioWialonPreprocessor {

    fun buildPreparedRowsJson(rawJson: String): String {
        val rows = buildRows(rawJson)

        val rowsArr = JSONArray()
        for (r in rows) {
            val rowArr = JSONArray()
            for (p in r.params) {
                rowArr.put(
                    JSONObject()
                        .put("name", p.name)
                        .put("type", p.type)
                        .put("value", p.value)
                )
            }
            rowsArr.put(rowArr)
        }

        return JSONObject()
            .put("rows", rowsArr)
            .toString()
    }

    fun buildRows(json: String): List<PreRow> {
        val rowsTexts = TableJsonExtractor.extractTextArrays(json)
        if (rowsTexts.isEmpty()) return emptyList()

        var lastSystem: String? = null
        val out = mutableListOf<PreRow>()

        rowsTexts.forEach { texts ->
            val res = buildParamsForRow(texts, lastSystem)
            lastSystem = res.lastSystemFromType1
            if (res.params.isNotEmpty()) out += PreRow(res.params)
        }

        return out
    }

    private data class RowBuildResult(
        val params: List<PreParam>,
        val lastSystemFromType1: String?
    )

    private fun buildParamsForRow(texts: List<String>, lastSystemFromType1: String?): RowBuildResult {
        val result = mutableListOf<PreParam>()
        var newLastSystem = lastSystemFromType1

        when {
            texts.size == 3 && texts[1].all { it.isDigit() } -> {
                val systemLatin = RussianTransliterator.toLatin(texts[0])
                result += PreParam("type", 1, "1")
                result += PreParam("system", 3, systemLatin)
                result += PreParam("err_cnt", 1, texts[1])
                newLastSystem = systemLatin
            }

            texts.size == 2 -> {
                val errLatin = RussianTransliterator.toLatin(texts[0])
                val activeVal = if (texts[1].equals("Active", true)) "1" else "0"
                result += PreParam("type", 1, "2")
                result += PreParam("err", 3, errLatin)
                result += PreParam("active", 1, activeVal)
                if (lastSystemFromType1 != null) {
                    result += PreParam("system", 3, lastSystemFromType1)
                }
            }
        }

        return RowBuildResult(result, newLastSystem)
    }
}
