package com.brain.tracscript

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brain.tracscript.ui.theme.TracScriptTheme

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import com.brain.tracscript.R
import com.brain.tracscript.plugins.scenario.InspectorDumpActivity


class SettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TracScriptTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    SettingsScreen(
                        modifier = Modifier.padding(padding),
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun openInspectorDump() {
        startActivity(Intent(this, InspectorDumpActivity::class.java))
    }

    private fun openAccessibilitySettings() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivity(intent)
    }
}

@Composable
private fun SettingsScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()

    var autoStartEnabled by remember {
        mutableStateOf(SettingsStorage.isAutoStartEnabled(context))
    }

    var periodicEnabled by remember {
        mutableStateOf(SettingsStorage.isPeriodicEnabled(context))
    }

    var periodicSecondsText by remember {
        mutableStateOf(
            SettingsStorage.getPeriodicIntervalSec(context).toString()
        )
    }

    var preventScreenOffEnabled by remember {
        mutableStateOf(SettingsStorage.isPreventScreenOffEnabled(context))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(scrollState),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // Верх: заголовок + описание
        Column(
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = stringResource(R.string.settings),
                style = MaterialTheme.typography.headlineSmall.copy(
                    fontWeight = FontWeight.SemiBold
                )
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = stringResource(R.string.tracscript_settings_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Блок «Общие»
            Text(
                text = stringResource(R.string.general),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(8.dp))

            /*
            RowWithCheckbox(
                checked = autoStartEnabled,
                onCheckedChange = { checked ->
                    autoStartEnabled = checked
                    SettingsStorage.setAutoStartEnabled(context, checked)
                },
                text = "Автоматически запускать сценарий при старте устройства"
            )
            */

            

            RowWithCheckbox(
                checked = preventScreenOffEnabled,
                onCheckedChange = { checked ->
                    preventScreenOffEnabled = checked
                    SettingsStorage.setPreventScreenOffEnabled(context, checked)

                    val intent = Intent(ScreenOnController.ACTION_UPDATE_SCREEN_OFF).apply {
                        putExtra(ScreenOnController.EXTRA_PREVENT_SCREEN_OFF, checked)
                    }

                    // лучше использовать applicationContext
                    context.applicationContext.sendBroadcast(intent)
                },
                text = stringResource(R.string.prevent_screen_off)
            )
        }

        // Открыть настройки плагинов
        OutlinedButton(
            onClick = {
                context.startActivity(
                    Intent(context, PluginSettingsActivity::class.java)
                )
            },
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.plugin_settings),
                modifier = Modifier.padding(end = 8.dp)
            )
            Text(stringResource(R.string.plugin_settings))
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Низ: служебные кнопки и «Закрыть»
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = stringResource(R.string.service_tools),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary
            )

            // Открыть настройки спец. возможностей
            OutlinedButton(
                onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    context.startActivity(intent)
                },
                modifier = Modifier
                    .fillMaxWidth()
            ) {
                androidx.compose.foundation.layout.Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Filled.Settings,
                        contentDescription = stringResource(R.string.open_accessibility_settings),
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text(
                        text = stringResource(R.string.open_accessibility_settings),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }
            }



            // Открыть дамп нод
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(context, InspectorDumpActivity::class.java)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Info,
                    contentDescription = stringResource(R.string.open_node_dump),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.open_node_dump))
            }

            // Открыть debug.log
            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(context, DebugLogActivity::class.java)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                androidx.compose.material3.Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = "Debug log",
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.open_log))
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Кнопка «Закрыть»
            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors()
            ) {
                Text(stringResource(R.string.close))
            }
        }
    }
}

@Composable
public fun RowWithCheckbox(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    text: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier
                .padding(start = 8.dp)
                .weight(1f)
        )
    }
}
