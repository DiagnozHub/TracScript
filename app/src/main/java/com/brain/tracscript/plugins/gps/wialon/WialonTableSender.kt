package com.brain.tracscript.plugins.gps.wialon

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

object WialonTableSender {

    private const val TAG = "TracScript_WialonTable"

    /**
     * ВНИМАНИЕ: теперь ожидается ТОЛЬКО НОВЫЙ формат (prepared rows):
     *
     * {
     *   "rows": [
     *     [ {"name":"type","type":1,"value":"1"}, {"name":"system","type":3,"value":"ABS"}, ... ],
     *     [ {"name":"type","type":1,"value":"2"}, {"name":"err","type":3,"value":"SPN 97 FMI 3"}, ... ]
     *   ]
     * }
     */
    suspend fun sendTableJson(
        json: String,
        client: WialonIpsClient,
        imei: String,
        password: String,
        nav: NavData,
        extras: DExtras,
        paramsExtra: List<IpsParam> = emptyList()
    ) = withContext(Dispatchers.IO) {

        val rowsParams = parsePreparedRows(json)
        if (rowsParams.isEmpty()) {
            Log.w(TAG, "Prepared rows пустые или не распарсились — нечего отправлять в Wialon")
            return@withContext
        }

        Log.d(TAG, "Обрабатываю ${rowsParams.size} prepared-строк для отправки в Wialon")

        rowsParams.forEachIndexed { index, params ->

            if (params.isEmpty()) {
                Log.d(TAG, "row#$index: params пустые, строка пропущена")
                return@forEachIndexed
            }

            val finalParams = if (paramsExtra.isEmpty()) params else (params + paramsExtra)

            Log.d(
                TAG,
                "row#$index: отправляю ${finalParams.size} параметров: " +
                        finalParams.joinToString { "${it.name}=${it.value}" }
            )

            val resp = client.sendParams(
                imei = imei,
                password = password,
                params = finalParams,
                nav = nav,
                extras = extras
            )

            Log.d(TAG, "row#$index: ответ Wialon на D-пакет: $resp")
        }
    }

    /**
     * Парсит НОВЫЙ формат:
     * {"rows":[ [ {"name":..,"type":..,"value":..}, ... ], ... ]}
     *
     * Возвращает список строк, где каждая строка — список IpsParam.
     */
    private fun parsePreparedRows(json: String): List<List<IpsParam>> {
        val root = try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.e(TAG, "parsePreparedRows: invalid JSON: ${e.message}", e)
            return emptyList()
        }

        val rowsArr = root.optJSONArray("rows")
        if (rowsArr == null || rowsArr.length() == 0) {
            Log.w(TAG, "parsePreparedRows: 'rows' отсутствует или пустой массив")
            return emptyList()
        }

        val out = ArrayList<List<IpsParam>>(rowsArr.length())

        for (i in 0 until rowsArr.length()) {
            val rowArr = rowsArr.optJSONArray(i) ?: continue

            val rowParams = ArrayList<IpsParam>(rowArr.length())

            for (j in 0 until rowArr.length()) {
                val pObj = rowArr.optJSONObject(j) ?: continue

                val name = pObj.optString("name", "").trim()
                val type = pObj.optInt("type", -1)
                val value = pObj.optString("value", "")

                if (name.isBlank() || type < 0) continue

                rowParams += IpsParam(
                    name = name,
                    type = type,
                    value = value
                )
            }

            if (rowParams.isNotEmpty()) {
                out += rowParams
            } else {
                Log.d(TAG, "parsePreparedRows: row#$i пустой после фильтрации параметров")
            }
        }

        return out
    }
}
