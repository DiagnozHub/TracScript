package com.brain.tracscript

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brain.tracscript.ui.theme.TracScriptTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.isActive

class DebugLogActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        // FileHelper без debugCallback — overlay тут не нужен
        val fileHelper = FileHelper(this, null)

        setContent {
            TracScriptTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    DebugLogScreen(
                        modifier = Modifier.padding(padding),
                        fileHelper = fileHelper,
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DebugLogScreen(
    modifier: Modifier = Modifier,
    fileHelper: FileHelper,
    onClose: () -> Unit
) {
    var logText by remember { mutableStateOf("") }
    var logFiles by remember { mutableStateOf<List<String>>(emptyList()) }
    var selectedFile by remember { mutableStateOf<String?>(null) }
    var expanded by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    // "Залипание" к низу: true пока пользователь сам не уедет вверх
    var stickToBottomWanted by remember { mutableStateOf(true) }
    var pendingScrollToBottom by remember { mutableStateOf(false) }

    // 1) первичная загрузка списка файлов
    LaunchedEffect(Unit) {
        logFiles = fileHelper.listLogFiles()
        selectedFile = logFiles.firstOrNull()
    }

    // 2) отслеживаем, находится ли пользователь внизу.
    // Если пользователь уехал вверх — отключаем stickToBottomWanted.
    LaunchedEffect(scrollState) {
        snapshotFlow {
            val thresholdPx = 24
            scrollState.value >= (scrollState.maxValue - thresholdPx).coerceAtLeast(0)
        }
            .distinctUntilChanged()
            .collect { atBottom ->
                stickToBottomWanted = atBottom
            }
    }

    // 3) online-обновление выбранного файла
    LaunchedEffect(selectedFile) {
        val file = selectedFile ?: run {
            logText = ""
            return@LaunchedEffect
        }

        // при открытии файла всегда "залипаем" к низу
        stickToBottomWanted = true

        // первичная загрузка
        logText = fileHelper.readTextFromDocuments(file) ?: ""
        pendingScrollToBottom = true

        while (isActive) {
            delay(500L)

            val cur = fileHelper.readTextFromDocuments(file) ?: ""
            if (cur != logText) {
                val shouldScroll = stickToBottomWanted
                logText = cur
                if (shouldScroll) {
                    pendingScrollToBottom = true
                }
            }
        }
    }

    // 4) Реальный скролл вниз после перерасчёта layout (maxValue станет актуальным)
    LaunchedEffect(pendingScrollToBottom, logText) {
        if (!pendingScrollToBottom) return@LaunchedEffect

        // дождаться применения нового текста и пересчёта maxValue
        withFrameNanos { }
        scrollState.scrollTo(scrollState.maxValue)

        pendingScrollToBottom = false
    }

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {

        // ---- ВЫБОР ФАЙЛА ----
        ExposedDropdownMenuBox(
            expanded = expanded,
            onExpandedChange = { expanded = !expanded }
        ) {
            OutlinedTextField(
                modifier = Modifier
                    .menuAnchor()
                    .fillMaxWidth(),
                readOnly = true,
                value = selectedFile ?: "",
                onValueChange = {},
                label = { Text(stringResource(R.string.log_file)) },
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }
            )

            ExposedDropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false }
            ) {
                logFiles.forEach { file ->
                    DropdownMenuItem(
                        text = { Text(file) },
                        onClick = {
                            selectedFile = file
                            expanded = false
                        }
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        // ---- КНОПКИ ----
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {

            Button(
                enabled = selectedFile != null,
                onClick = {
                    selectedFile?.let { f ->
                        logText = fileHelper.readTextFromDocuments(f) ?: ""
                        if (stickToBottomWanted) pendingScrollToBottom = true
                    }
                }
            ) { Text(stringResource(R.string.update)) }

            Button(
                enabled = selectedFile != null,
                onClick = {
                    selectedFile?.let { f ->
                        fileHelper.deleteFileFromDocuments(f)
                        logFiles = fileHelper.listLogFiles()
                        selectedFile = logFiles.firstOrNull()
                        // logText обновится через LaunchedEffect(selectedFile)
                    }
                }
            ) { Text(stringResource(R.string.clear)) }

            Button(onClick = onClose) {
                Text(stringResource(R.string.close))
            }
        }

        // ---- ТЕКСТ ЛОГА ----
        val displayText =
            if (logText.isNotEmpty()) logText else stringResource(R.string.debug_log_empty)

        val dateColor = MaterialTheme.colorScheme.tertiary
        val colored = remember(displayText, dateColor) {
            buildColoredLog(displayText, dateColor)
        }

        // Копирование текста
        SelectionContainer(
            modifier = Modifier
                .padding(top = 12.dp)
                .weight(1f)
                .fillMaxWidth()
        ) {
            Text(
                text = colored,
                fontSize = 12.sp,
                lineHeight = 14.sp,
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            )
        }
    }
}

/**
 * Подсвечивает ведущую дату вида: [yyyy-MM-dd HH:mm:ss.SSS]
 * Подсветка применяется только если строка начинается с '[' и имеет закрывающую ']'.
 */
private fun buildColoredLog(text: String, dateColor: Color): AnnotatedString {
    val dateStyle = SpanStyle(color = dateColor)
    val errorStyle = SpanStyle(color = Color(0xFFD32F2F)) // красный
    val warnStyle = SpanStyle(color = Color(0xFFFBC02D))  // жёлтый (читаемый на тёмном/светлом)

    fun isNewLogEntry(line: String): Boolean {
        // типичная строка лога: [2026-01-05 12:34:56.789] [I] ...
        return line.startsWith("[") && line.indexOf("] [") > 0
    }

    fun isErrorStart(line: String): Boolean {
        return "[E]" in line ||
                line.startsWith("Caused by") ||
                "Exception" in line ||
                "Error" in line
    }

    fun isWarnStart(line: String): Boolean {
        // у тебя может быть [W] или [w] — учитываем оба
        return "[W]" in line || "[w]" in line
    }

    return buildAnnotatedString {
        var inErrorBlock = false
        var inWarnBlock = false

        val lines = text.split('\n')
        for (line in lines) {

            // Новый лог-энтри: сбрасываем/устанавливаем блоки по уровню
            if (isNewLogEntry(line)) {
                inErrorBlock = isErrorStart(line)
                inWarnBlock = !inErrorBlock && isWarnStart(line) // error важнее warning
            } else {
                // Внутри "не-энтри" строк:
                // если внезапно встретили маркер ошибки/варнинга — включаем соответствующий блок
                if (!inErrorBlock && isErrorStart(line)) {
                    inErrorBlock = true
                    inWarnBlock = false
                } else if (!inErrorBlock && !inWarnBlock && isWarnStart(line)) {
                    inWarnBlock = true
                }
            }

            val styleToApply: SpanStyle? = when {
                inErrorBlock -> errorStyle
                inWarnBlock -> warnStyle
                else -> null
            }

            if (line.startsWith("[")) {
                val end = line.indexOf(']')
                if (end > 0) {
                    // Дата всегда отдельным цветом
                    withStyle(dateStyle) { append(line.substring(0, end + 1)) }

                    val rest = line.substring(end + 1)
                    if (styleToApply != null) {
                        withStyle(styleToApply) { append(rest) }
                    } else {
                        append(rest)
                    }
                } else {
                    // странная строка, красим целиком если нужно
                    if (styleToApply != null) withStyle(styleToApply) { append(line) } else append(line)
                }
            } else {
                if (styleToApply != null) withStyle(styleToApply) { append(line) } else append(line)
            }

            append('\n')
        }
    }
}





