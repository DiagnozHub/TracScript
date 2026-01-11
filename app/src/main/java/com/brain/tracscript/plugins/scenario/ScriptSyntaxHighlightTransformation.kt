package com.brain.tracscript.plugins.scenario

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.withStyle

/**
 * Простая подсветка синтаксиса для сценариев:
 * - команды (WAIT_TEXT, CLICK_TEXT, DELAY, TAP, EXTRACT_..., SEND_WIALON_TABLE, DELETE_FILE) — цвет + bold
 * - строки, начинающиеся с # — как комментарии
 */
class ScriptSyntaxHighlightTransformation(
    private val commandColor: Color,
    private val commentColor: Color
) : VisualTransformation {

    private val commandKeywords = setOf(
        "WAIT_TEXT",
        "CLICK_TEXT",
        "DELAY",
        "TAP",
        "EXTRACT_TABLE_BY_ID",
        "EXTRACT_EXPANDABLE_LIST",
        "SEND_WIALON_TABLE",
        "DELETE_FILE",
        "LAUNCH_APP",
        "CLOSE_APP",
        "KILL_APP_ROOT",
        "BACK",
        "IF",
        "ELSE",
        "THEN",
        "STOP_SCENARIO",
        "SCREEN_ON",
        "SCREEN_OFF",
        "LIGHT",
        "SEND_TABLE_TO_BUS"
    )

    override fun filter(text: AnnotatedString): TransformedText {
        val highlighted = highlight(text.text)
        return TransformedText(
            text = highlighted,
            offsetMapping = OffsetMapping.Identity
        )
    }

    private fun highlight(source: String): AnnotatedString {
        return buildAnnotatedString {
            val lines = source.split('\n')
            lines.forEachIndexed { index, line ->
                val trimmed = line.trimStart()

                if (trimmed.startsWith("#")) {
                    // комментарий — весь ряд одним стилем
                    withStyle(SpanStyle(color = commentColor)) {
                        append(line)
                    }
                } else {
                    // проходим по всей строке, подсвечиваем КАЖДОЕ ключевое слово
                    var i = 0
                    while (i < line.length) {
                        val ch = line[i]

                        // "слово" = последовательность [A-Za-z0-9_]
                        if (ch.isLetterOrDigit() || ch == '_') {
                            val start = i
                            var j = i + 1
                            while (j < line.length) {
                                val cj = line[j]
                                if (cj.isLetterOrDigit() || cj == '_') {
                                    j++
                                } else break
                            }

                            val token = line.substring(start, j)
                            val upper = token.uppercase()

                            if (commandKeywords.contains(upper)) {
                                withStyle(
                                    SpanStyle(
                                        color = commandColor,
                                        fontWeight = FontWeight.Bold
                                    )
                                ) {
                                    append(token)
                                }
                            } else {
                                append(token)
                            }

                            i = j
                        } else {
                            // не-слово (пробел, знак, кириллица и т.п.) — просто добавляем
                            append(ch)
                            i++
                        }
                    }
                }

                if (index < lines.lastIndex) {
                    append('\n')
                }
            }
        }
    }

}
