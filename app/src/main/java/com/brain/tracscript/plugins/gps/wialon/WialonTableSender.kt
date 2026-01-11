package com.brain.tracscript.plugins.gps.wialon

import android.util.Log
import com.brain.tracscript.plugins.gps.RussianTransliterator
import com.brain.tracscript.plugins.gps.TableJsonExtractor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object WialonTableSender {

    private const val TAG = "TracScript_WialonTable"

    suspend fun sendTableJson(
        json: String,
        client: WialonIpsClient,
        imei: String,
        password: String,
        nav: NavData,
        extras: DExtras,
        paramsExtra: List<IpsParam> = emptyList()
    ) = withContext(Dispatchers.IO) {

        val rowsTexts = TableJsonExtractor.extractTextArrays(json)
        if (rowsTexts.isEmpty()) {
            Log.w(TAG, "В таблице нет строк для отправки в Wialon")
            return@withContext
        }

        Log.d(TAG, "Обрабатываю ${rowsTexts.size} строк из JSON")

        var lastSystemFromType1: String? = null   // запоминаем system из последнего блока типа 1

        rowsTexts.forEachIndexed { index, texts ->
            val res = buildParamsForRow(texts, lastSystemFromType1)
            val params = res.params
            lastSystemFromType1 = res.lastSystemFromType1

            if (params.isEmpty()) {
                Log.d(TAG, "row#$index: условий нет, строка пропущена. texts=$texts")
                return@forEachIndexed
            }

            Log.d(
                TAG,
                "row#$index: отправляю ${params.size} параметров: " +
                        params.joinToString { "${it.name}=${it.value}" }
            )

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

    // Результат обработки одной строки
    private data class RowBuildResult(
        val params: List<IpsParam>,
        val lastSystemFromType1: String? // новое значение "system" из блока типа 1 (или старое, если не менялось)
    )

    /**
     * texts — список text из узла texts.
     *
     * Тип 1:
     *  - РОВНО 3 элемента
     *  - второй элемент — число
     *    → system, err_cnt
     *
     * Тип 2:
     *  - РОВНО 2 элемента
     *    → err, active
     *    + ЕСЛИ до этого был тип 1 → ещё параметр system с тем же значением, что в последнем типе 1.
     */
    private fun buildParamsForRow(
        texts: List<String>,
        lastSystemFromType1: String?
    ): RowBuildResult {

        val result = mutableListOf<IpsParam>()
        var newLastSystem = lastSystemFromType1

        when {
            // Тип 1: ровно 3 элемента, второй — число
            texts.size == 3 && isDigits(texts[1]) -> {
                val systemRaw = texts[0]
                val errCntRaw = texts[1]

                val systemLatin = RussianTransliterator.toLatin(systemRaw)

                // type = 1
                result += IpsParam(
                    name = "type",
                    type = 1,
                    value = "1"
                )

                result += IpsParam(
                    name = "system",
                    type = 3,
                    value = systemLatin
                )

                result += IpsParam(
                    name = "err_cnt",
                    type = 1,
                    value = errCntRaw
                )

                newLastSystem = systemLatin
            }

            // Тип 2: ровно 2 элемента
            texts.size == 2 -> {
                val errRaw = texts[0]
                val statusRaw = texts[1]

                val errLatin = RussianTransliterator.toLatin(errRaw)
                val activeVal = if (statusRaw.equals("Active", ignoreCase = true)) "1" else "0"

                // type = 2
                result += IpsParam(
                    name = "type",
                    type = 1,
                    value = "2"
                )

                result += IpsParam(
                    name = "err",
                    type = 3,
                    value = errLatin
                )

                result += IpsParam(
                    name = "active",
                    type = 1,
                    value = activeVal
                )

                if (lastSystemFromType1 != null) {
                    result += IpsParam(
                        name = "system",
                        type = 3,
                        value = lastSystemFromType1
                    )
                }
            }

            // Остальные варианты игнорируем
        }

        return RowBuildResult(
            params = result,
            lastSystemFromType1 = newLastSystem
        )
    }


    private fun isDigits(s: String): Boolean =
        s.isNotEmpty() && s.all { it.isDigit() }
}
