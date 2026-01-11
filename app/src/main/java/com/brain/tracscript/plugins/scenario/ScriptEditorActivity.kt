package com.brain.tracscript.plugins.scenario

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.brain.tracscript.ui.theme.TracScriptTheme

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import com.brain.tracscript.R


class ScriptEditorActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TracScriptTheme {
                ScriptEditorScreen(
                    modifier = Modifier.fillMaxSize(),
                    onClose = { finish() }
                )
            }
        }
    }
}

private val EditorDarkColors = darkColorScheme(
    primary = Color(0xFF4FC1FF),
    onPrimary = Color.Black,

    background = Color(0xFF1E1E1E),
    onBackground = Color(0xFFD0D0D0),

    surface = Color(0xFF252526),
    onSurface = Color(0xFFD0D0D0),

    surfaceVariant = Color(0xFF3A3D41),
    onSurfaceVariant = Color(0xFFBEBEBE),

    outline = Color(0xFF4E4E4E)
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptEditorScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current


    var scenarios by remember {
        mutableStateOf(ScenarioStorage.loadAll(context))
    }

    var selectedId by remember {
        mutableStateOf(scenarios.firstOrNull()?.id)
    }

    // показывать/прятать панель со списком сценариев
    var isListVisible by remember { mutableStateOf(true) }

    // по умолчанию — СВЕТЛАЯ тема редактора
    var isDarkEditorTheme by remember { mutableStateOf(false) }

    val selectedScenario = scenarios.firstOrNull { it.id == selectedId }

    var scriptText by remember(selectedId) {
        mutableStateOf(selectedScenario?.text ?: "")
    }
    var scenarioName by remember(selectedId) {
        mutableStateOf(selectedScenario?.name ?: "")
    }

    fun persistCurrentScenarioState() {
        val id = selectedId ?: return
        scenarios = scenarios.map {
            if (it.id == id) it.copy(
                text = scriptText,
                name = scenarioName.ifBlank { it.name }
            ) else it
        }
    }

    val baseColors = MaterialTheme.colorScheme

    // Локальная тема для окна редактора
    MaterialTheme(
        colorScheme = if (isDarkEditorTheme) EditorDarkColors else baseColors
    ) {
        val commandColor = MaterialTheme.colorScheme.primary
        val commentColor = MaterialTheme.colorScheme.tertiary

        val syntaxTransformation = remember(commandColor, commentColor, isDarkEditorTheme) {
            ScriptSyntaxHighlightTransformation(
                commandColor = commandColor,
                commentColor = commentColor
            )
        }

        Scaffold(
            modifier = modifier,
            topBar = {
                TopAppBar(
                    title = {}, // без текстового заголовка
                    navigationIcon = {
                        // Меню и переключатель темы стоят рядом слева
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { isListVisible = !isListVisible }) {
                                Icon(
                                    imageVector = Icons.Filled.Menu,
                                    contentDescription = stringResource(R.string.scenario_list)
                                )
                            }
                            IconButton(onClick = { isDarkEditorTheme = !isDarkEditorTheme }) {
                                Icon(
                                    imageVector = Icons.Filled.Face,
                                    contentDescription = stringResource(R.string.toggle_editor_theme)
                                )
                            }
                        }
                    },
                    actions = {
                        TextButton(
                            onClick = {
                                persistCurrentScenarioState()
                                ScenarioStorage.saveAll(context, scenarios)
                                Toast.makeText(
                                    context,
                                    context.getString(R.string.scenarios_saved),
                                    Toast.LENGTH_SHORT
                                ).show()
                                onClose()
                            }
                        ) {
                            Text(stringResource(R.string.save))
                        }
                    }
                )
            }
        ) { paddingValues ->
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // ПАНЕЛЬ СО СЦЕНАРИЯМИ — плавно выезжает слева и уезжает влево
                AnimatedVisibility(
                    visible = isListVisible,
                    enter = slideInHorizontally(initialOffsetX = { -it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { -it }) + fadeOut()
                ) {
                    // БОКОВАЯ ПАНЕЛЬ – НА ВЕСЬ ЭКРАН
                    Surface(
                        tonalElevation = 1.dp,
                        shadowElevation = 4.dp,
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
                        modifier = Modifier
                            .fillMaxSize()
                            .pointerInput(isListVisible) {
                                // отслеживаем горизонтальный свайп по панели
                                var dragAmount = 0f
                                val threshold = with(density) { 80.dp.toPx() } // порог ~80dp

                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { _, delta ->
                                        // delta < 0 — тянем влево, > 0 — вправо
                                        dragAmount += delta
                                    },
                                    onDragEnd = {
                                        // если достаточно сильно смахнули влево — прячем панель
                                        if (dragAmount < -threshold) {
                                            isListVisible = false
                                        }
                                        dragAmount = 0f
                                    },
                                    onDragCancel = {
                                        dragAmount = 0f
                                    }
                                )
                            }
                    )  {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.scenarios),
                                style = MaterialTheme.typography.titleMedium,
                                modifier = Modifier.padding(bottom = 8.dp)
                            )

                            Divider()

                            Spacer(modifier = Modifier.height(8.dp))

                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth()
                            ) {
                                items(
                                    items = scenarios,
                                    key = { it.id }
                                ) { s ->
                                    val isSelected = s.id == selectedId
                                    ScenarioListItem(
                                        scenario = s,
                                        selected = isSelected,
                                        onClick = {
                                            persistCurrentScenarioState()
                                            selectedId = s.id
                                            scriptText = s.text
                                            scenarioName = s.name

                                            // при выборе сценария сразу скрываем панель
                                            isListVisible = false
                                        },
                                        onMakeMain = {
                                            scenarios = scenarios.map {
                                                it.copy(isMain = it.id == s.id)
                                            }
                                        }
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // Кнопки "Новый" и "Удалить" под списком
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Новый
                                OutlinedButton(
                                    onClick = {
                                        persistCurrentScenarioState()

                                        val index = scenarios.size + 1
                                        val newId = System.currentTimeMillis().toString()

                                        val newScenario = ScenarioStorage.Scenario(
                                            id = newId,
                                            name = context.getString(
                                                R.string.scenario_default_name,
                                                index
                                            ),
                                            text = "",
                                            isMain = scenarios.isEmpty()
                                        )


                                        /*
                                        val newScenario = ScenarioStorage.Scenario(
                                            id = newId,
                                            name = "Сценарий $index",
                                            text = "",
                                            isMain = scenarios.isEmpty()
                                        )
                                        */

                                        scenarios = scenarios + newScenario
                                        selectedId = newId
                                        scriptText = ""
                                        scenarioName = newScenario.name
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Add,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }

                                // Удалить выбранный
                                OutlinedButton(
                                    onClick = {
                                        if (scenarios.size <= 1) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.cannot_delete_only_scenario),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@OutlinedButton
                                        }

                                        val idToDelete = selectedId ?: scenarios.first().id
                                        val newList = scenarios.filterNot { it.id == idToDelete }
                                        if (newList.isEmpty()) {
                                            Toast.makeText(
                                                context,
                                                context.getString(R.string.nothing_to_delete),
                                                Toast.LENGTH_SHORT
                                            ).show()
                                            return@OutlinedButton
                                        }

                                        val fixedList =
                                            if (newList.none { it.isMain }) {
                                                newList.mapIndexed { index, s ->
                                                    if (index == 0) s.copy(isMain = true)
                                                    else s.copy(isMain = false)
                                                }
                                            } else {
                                                newList
                                            }

                                        scenarios = fixedList

                                        val newSelected = fixedList.first()
                                        selectedId = newSelected.id
                                        scriptText = newSelected.text
                                        scenarioName = newSelected.name

                                        Toast.makeText(
                                            context,
                                            context.getString(R.string.scenario_deleted_notice),
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.outlinedButtonColors(
                                        contentColor = MaterialTheme.colorScheme.error
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Filled.Delete,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 4.dp)
                                    )
                                }
                            }
                        }
                    }
                }

                // РЕДАКТОР — плавно выезжает справа и уезжает вправо
                AnimatedVisibility(
                    visible = !isListVisible,
                    enter = slideInHorizontally(initialOffsetX = { it }) + fadeIn(),
                    exit = slideOutHorizontally(targetOffsetX = { it }) + fadeOut()
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp)
                            .pointerInput(isListVisible) {
                                var dragAmount = 0f
                                val threshold = with(density) { 80.dp.toPx() } // тот же порог ~80dp

                                detectHorizontalDragGestures(
                                    onHorizontalDrag = { _, delta ->
                                        // delta > 0 — тянем вправо
                                        dragAmount += delta
                                    },
                                    onDragEnd = {
                                        if (dragAmount > threshold) {
                                            isListVisible = true
                                        }
                                        dragAmount = 0f
                                    },
                                    onDragCancel = {
                                        dragAmount = 0f
                                    }
                                )
                            }
                    ) {
                        if (selectedScenario == null) {
                            Surface(
                                tonalElevation = 2.dp,
                                shape = MaterialTheme.shapes.large,
                                modifier = Modifier.fillMaxSize()
                            ) {
                                Box(
                                    modifier = Modifier.fillMaxSize(),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        context.getString(R.string.no_scenarios_to_edit),
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier.fillMaxSize(),
                            ) {
                                // Карточка заголовка (имя сценария)
                                Surface(
                                    tonalElevation = 2.dp,
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            OutlinedTextField(
                                                value = scenarioName,
                                                onValueChange = { scenarioName = it },
                                                modifier = Modifier.fillMaxWidth(),
                                                textStyle = MaterialTheme.typography.titleMedium,
                                                singleLine = true,
                                                label = { Text(stringResource(R.string.scenario_name)) }
                                            )
                                        }
                                    }
                                }

                                // Основной редактор — максимально растянут по ширине и высоте
                                Surface(
                                    tonalElevation = 1.dp,
                                    shape = MaterialTheme.shapes.large,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .weight(1f)
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .padding(8.dp)
                                    ) {
                                        OutlinedTextField(
                                            value = scriptText,
                                            onValueChange = { scriptText = it },
                                            modifier = Modifier.fillMaxSize(),
                                            minLines = 12,
                                            maxLines = Int.MAX_VALUE,
                                            textStyle = TextStyle(
                                                fontFamily = FontFamily.Monospace,
                                                fontSize = MaterialTheme.typography.bodyMedium.fontSize
                                            ),
                                            visualTransformation = syntaxTransformation
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ScenarioListItem(
    scenario: ScenarioStorage.Scenario,
    selected: Boolean,
    onClick: () -> Unit,
    onMakeMain: () -> Unit
) {
    val bgColor =
        if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
        else Color.Transparent

    Surface(
        tonalElevation = if (selected) 3.dp else 1.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .clickable { onClick() }
    ) {
        Row(
            modifier = Modifier
                .background(bgColor)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = scenario.name,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1
                )
                if (selected) {
                    Text(
                        text = stringResource(R.string.selected_for_editing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (scenario.isMain) {
                AssistChip(
                    onClick = { /* уже главный */ },
                    label = { Text(stringResource(R.string.main)) },
                    enabled = false
                )
            } else {
                TextButton(onClick = onMakeMain) {
                    Text(
                        text = stringResource(R.string.make_main_multiline),
                        style = MaterialTheme.typography.labelSmall
                    )

                    /*
                    Text(
                        text = "Сделать\nглавным",
                        style = MaterialTheme.typography.labelSmall
                    )
                    */
                }
            }
        }
    }
}
