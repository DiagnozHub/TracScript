package com.brain.tracscript.plugins.scenario

import android.util.Log

enum class ConditionCommand {
    IS_SCREEN_ON
    // сюда потом добавишь IS_APP_FOREGROUND, IS_BLUETOOTH_ON и т.п.
}

sealed class ScenarioStep {
    data class WaitText(val text: String) : ScenarioStep()
    data class ClickText(val text: String) : ScenarioStep()
    data class Delay(val ms: Long) : ScenarioStep()

    /**
     * Тап по относительным координатам (0.0 .. 1.0)
     * xRatio, yRatio — то, что показывает overlay в режиме исследования (rx, ry).
     */
    data class Tap(val xRatio: Float, val yRatio: Float, val durationMs: Long = 100L) : ScenarioStep()

    /** Запуск приложения по packageName через TargetAppController */
    data class LaunchApp(val packageName: String) : ScenarioStep()

    /** Закрытие приложения по packageName */
    data class CloseApp(val packageName: String) : ScenarioStep()

    data class KillAppRoot(val packageName: String) : ScenarioStep()

    data class ExtractTableById(
        val viewId: String,
        val fileName: String
    ) : ScenarioStep()

    data class SendWialonTable(
        val fileName: String
    ) : ScenarioStep()

    data class DeleteFile(val fileName: String) : ScenarioStep()

    data class ExtractExpandableList(val resId: String, val file: String) : ScenarioStep()

    data object Back : ScenarioStep()

    /**
     * Универсальный IF:
     * IF <condition> THEN <innerStep>
     * Пример: IF IS_SCREEN_ON THEN CLICK_TEXT Продолжить
     */
    data class If(
        val condition: ConditionCommand,
        val thenStep: ScenarioStep,
        val elseStep: ScenarioStep? = null    // можно без else
    ) : ScenarioStep()

    data object StopScenario : ScenarioStep()

    data object ScreenOn : ScenarioStep()
    data object ScreenOff : ScenarioStep()

    data class Light(val value: Int) : ScenarioStep()

    data class SendTableToBus(
        val fileName: String
    ) : ScenarioStep()
}

object ScenarioParser {

    fun parse(script: String): List<ScenarioStep> {
        val steps = mutableListOf<ScenarioStep>()

        script.lines().forEachIndexed { idx, rawLine ->
            val line = rawLine.trim()
            if (line.isEmpty()) return@forEachIndexed
            if (line.startsWith("#")) return@forEachIndexed

            val cmd = line.substringBefore(' ').uppercase()
            val arg = line.substringAfter(' ', missingDelimiterValue = "").trim()

            when (cmd) {
                "WAIT_TEXT" -> {
                    if (arg.isNotEmpty()) {
                        steps += ScenarioStep.WaitText(arg)
                    } else {
                        Log.w("TracScript", "Пустой аргумент в WAIT_TEXT на строке $idx")
                    }
                }

                "CLICK_TEXT" -> {
                    if (arg.isNotEmpty()) {
                        steps += ScenarioStep.ClickText(arg)
                    } else {
                        Log.w("TracScript", "Пустой аргумент в CLICK_TEXT на строке $idx")
                    }
                }

                "DELAY" -> {
                    val ms = arg.toLongOrNull()
                    if (ms != null) {
                        steps += ScenarioStep.Delay(ms)
                    } else {
                        Log.w("TracScript", "Некорректный DELAY '$arg' на строке $idx")
                    }
                }

                // TAP x y [durationMs]
                "TAP" -> {
                    val parts = arg.split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }

                    if (parts.size < 2) {
                        Log.w("TracScript", "Слишком мало аргументов в TAP на строке $idx: '$arg'")
                    } else {
                        val x = parts[0].toFloatOrNull()
                        val y = parts[1].toFloatOrNull()
                        val dur = if (parts.size >= 3) parts[2].toLongOrNull() else null

                        if (x == null || y == null) {
                            Log.w("TracScript", "Некорректные координаты в TAP на строке $idx: '$arg'")
                        } else {
                            steps += ScenarioStep.Tap(
                                xRatio = x,
                                yRatio = y,
                                durationMs = dur ?: 100L
                            )
                        }
                    }
                }

                // НОВОЕ: LAUNCH_APP pkg=com.diagzone.pro.v2
                // или: LAUNCH_APP com.diagzone.pro.v2
                "LAUNCH_APP" -> {
                    val pkg = arg.trim()
                    if (pkg.isNotEmpty()) {
                        steps += ScenarioStep.LaunchApp(pkg)
                    } else {
                        Log.w("TracScript", "Пустой аргумент в LAUNCH_APP на строке $idx")
                    }
                }

                "CLOSE_APP" -> {
                    val pkg = arg.trim()
                    if (pkg.isNotEmpty()) {
                        steps += ScenarioStep.CloseApp(pkg)
                    } else {
                        Log.w("TracScript", "Пустой аргумент в CLOSE_APP на строке $idx")
                    }
                }

                "KILL_APP_ROOT" -> {
                    val pkg = arg.trim()
                    if (pkg.isNotEmpty()) {
                        steps += ScenarioStep.KillAppRoot(pkg)
                    } else {
                        Log.w("TracScript", "Пустой аргумент в KILL_APP_ROOT на строке $idx")
                    }
                }


                "EXTRACT_TABLE_BY_ID" -> {
                    // Формат: EXTRACT_TABLE_BY_ID id=... file=...
                    val parts = arg.split(" ")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }

                    var id: String? = null
                    var file: String? = null

                    for (p in parts) {
                        when {
                            p.startsWith("id=") -> id = p.removePrefix("id=")
                            p.startsWith("file=") -> file = p.removePrefix("file=")
                        }
                    }

                    if (!id.isNullOrEmpty() && !file.isNullOrEmpty()) {
                        steps += ScenarioStep.ExtractTableById(id!!, file!!)
                    } else {
                        Log.w("TracScript", "Некорректный EXTRACT_TABLE_BY_ID '$arg' на строке $idx")
                    }
                }

                "EXTRACT_EXPANDABLE_LIST" -> {
                    val parts = arg.split(" ")
                    var id: String? = null
                    var file: String? = null

                    parts.forEach {
                        when {
                            it.startsWith("id=") -> id = it.removePrefix("id=")
                            it.startsWith("file=") -> file = it.removePrefix("file=")
                        }
                    }

                    if (id != null && file != null) {
                        steps += ScenarioStep.ExtractExpandableList(id!!, file!!)
                    } else {
                        Log.w("TracScript", "Некорректная команда EXTRACT_EXPANDABLE_LIST на строке $idx")
                    }
                }

                "SEND_WIALON_TABLE" -> {
                    val parts = arg.split(' ')
                        .filter { it.isNotBlank() }

                    val argsMap = mutableMapOf<String, String>()
                    for (p in parts) {
                        val k = p.substringBefore('=', "")
                        val v = p.substringAfter('=', "")
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            argsMap[k.lowercase()] = v
                        }
                    }

                    val fileName = argsMap["file"]
                    if (fileName.isNullOrEmpty()) {
                        Log.w("TracScript", "SEND_WIALON_TABLE без file= на строке $idx")
                    } else {
                        steps += ScenarioStep.SendWialonTable(fileName)
                    }
                }

                "DELETE_FILE" -> {
                    val fileName = when {
                        arg.startsWith("file=", ignoreCase = true) ->
                            arg.substringAfter("=").trim()
                        else -> arg
                    }

                    if (fileName.isNotBlank()) {
                        steps += ScenarioStep.DeleteFile(fileName)
                    } else {
                        Log.w("TracScript", "Пустой аргумент в DELETE_FILE на строке $idx")
                    }
                }

                "BACK" -> {
                    if (arg.isNotEmpty()) {
                        Log.w("TracScript", "Команда BACK не принимает аргументы, строка $idx: '$arg'")
                    }
                    steps += ScenarioStep.Back
                }

                "IF" -> {
                    if (arg.isEmpty()) {
                        Log.w("TracScript", "IF без аргументов на строке $idx")
                        return@forEachIndexed
                    }

                    val upper = arg.uppercase()

                    val thenPos = upper.indexOf(" THEN ")
                    if (thenPos < 0) {
                        Log.w("TracScript", "IF: нет THEN в '$arg' строка $idx")
                        return@forEachIndexed
                    }

                    val elsePos = upper.indexOf(" ELSE ")

                    val conditionPart = arg.substring(0, thenPos).trim()
                    val thenPart = if (elsePos >= 0)
                        arg.substring(thenPos + 6, elsePos).trim()
                    else
                        arg.substring(thenPos + 6).trim()

                    val elsePart = if (elsePos >= 0)
                        arg.substring(elsePos + 6).trim()
                    else null

                    // 1. Разбираем условие
                    val cond = when (conditionPart.uppercase()) {
                        "IS_SCREEN_ON" -> ConditionCommand.IS_SCREEN_ON
                        else -> {
                            Log.w("TracScript", "IF: неизвестное условие '$conditionPart' строка $idx")
                            return@forEachIndexed
                        }
                    }

                    // 2. THEN — парсим как отдельную команду
                    val thenSteps = parse(thenPart)
                    if (thenSteps.size != 1) {
                        Log.w("TracScript", "IF THEN: должна быть одна команда, строка $idx")
                        return@forEachIndexed
                    }

                    // 3. ELSE (если есть)
                    val elseStep = elsePart?.let {
                        val elseSteps = parse(it)
                        if (elseSteps.size != 1) {
                            Log.w("TracScript", "IF ELSE: должна быть одна команда, строка $idx")
                            null
                        } else elseSteps.first()
                    }

                    steps += ScenarioStep.If(
                        condition = cond,
                        thenStep = thenSteps.first(),
                        elseStep = elseStep
                    )
                }

                "ELSE" -> {
                    val elseArg = arg
                    if (elseArg.isBlank()) {
                        Log.w("TracScript", "ELSE без команды на строке $idx")
                        return@forEachIndexed
                    }

                    if (steps.isEmpty()) {
                        Log.w("TracScript", "ELSE без предшествующего IF на строке $idx")
                        return@forEachIndexed
                    }

                    val last = steps.last()
                    if (last !is ScenarioStep.If) {
                        Log.w("TracScript", "ELSE, но предыдущий шаг не IF, строка $idx")
                        return@forEachIndexed
                    }
                    if (last.elseStep != null) {
                        Log.w("TracScript", "Повторный ELSE для одного IF, строка $idx")
                        return@forEachIndexed
                    }

                    // Парсим то, что после ELSE, как ОДНУ команду
                    val elseSteps = parse(elseArg)
                    if (elseSteps.size != 1) {
                        Log.w(
                            "TracScript",
                            "ELSE: после ELSE должна быть одна команда, строка $idx, получили ${elseSteps.size}"
                        )
                        return@forEachIndexed
                    }

                    val newIf = last.copy(elseStep = elseSteps.first())
                    steps[steps.lastIndex] = newIf
                }


                "STOP_SCENARIO" -> {
                    if (arg.isNotEmpty()) {
                        Log.w("TracScript", "Команда STOP_SCENARIO не принимает аргументы, строка $idx: '$arg'")
                    }
                    steps += ScenarioStep.StopScenario
                }

                "SCREEN_ON" -> {
                    if (arg.isNotEmpty()) {
                        Log.w("TracScript", "SCREEN_ON не принимает аргументы, строка $idx: '$arg'")
                    }
                    steps += ScenarioStep.ScreenOn
                }

                // НОВОЕ: SCREEN_OFF
                "SCREEN_OFF" -> {
                    if (arg.isNotEmpty()) {
                        Log.w("TracScript", "SCREEN_OFF не принимает аргументы, строка $idx: '$arg'")
                    }
                    steps += ScenarioStep.ScreenOff
                }

                "LIGHT" -> {
                    val v = arg.toIntOrNull()
                    if (v == null || (v != 0 && v != 1)) {
                        Log.w("TracScript", "LIGHT: требуется 0 или 1, строка $idx, arg='$arg'")
                    } else {
                        steps += ScenarioStep.Light(v)
                    }
                }

                "SEND_TABLE_TO_BUS" -> {
                    val parts = arg.split(' ')
                        .filter { it.isNotBlank() }

                    val argsMap = mutableMapOf<String, String>()
                    for (p in parts) {
                        val k = p.substringBefore('=', "")
                        val v = p.substringAfter('=', "")
                        if (k.isNotEmpty() && v.isNotEmpty()) {
                            argsMap[k.lowercase()] = v
                        }
                    }

                    val fileName = argsMap["file"]
                    if (fileName.isNullOrEmpty()) {
                        Log.w("TracScript", "SEND_TABLE_TO_BUS без file= на строке $idx")
                    } else {
                        steps += ScenarioStep.SendTableToBus(fileName)
                    }
                }

                else -> {
                    Log.w("TracScript", "Неизвестная команда '$cmd' на строке $idx")
                }
            }
        }

        return steps
    }
}

