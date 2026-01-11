package com.brain.tracscript.plugins.scenario

import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.brain.tracscript.R
import com.brain.tracscript.SettingsStorage
import com.brain.tracscript.core.PluginSettingsDefinition
import com.brain.tracscript.util.isMyAccessibilityServiceEnabled

object ScenarioPluginSettingsDefinition : PluginSettingsDefinition {

    override val id: String = "scenario"
    //override val displayName: String = "Сценарии"

    @Composable
    override fun displayName(): String =
        stringResource(R.string.scenarios)

    private const val KEY_PIN_TO_MAIN = "pin_to_main"
    private const val KEY_ENABLED = "enabled"
    private const val PREFS = "plugin_scenario"

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = remember { context.getSharedPreferences(PREFS, Context.MODE_PRIVATE) }

        var periodicEnabled by remember {
            mutableStateOf(SettingsStorage.isPeriodicEnabled(context))
        }

        var periodicSecondsText by remember {
            mutableStateOf(
                SettingsStorage.getPeriodicIntervalSec(context).toString()
            )
        }


        var pinToMain by remember { mutableStateOf(prefs.getBoolean(KEY_PIN_TO_MAIN, false)) }


        fun savePinToMain(v: Boolean) {
            prefs.edit().putBoolean(KEY_PIN_TO_MAIN, v).apply()
        }

        var enabled by remember { mutableStateOf(prefs.getBoolean(KEY_ENABLED, false)) }
        fun saveEnabled(v: Boolean) {
            prefs.edit().putBoolean(KEY_ENABLED, v).apply()
        }

        var a11yOn by remember { mutableStateOf(isMyAccessibilityServiceEnabled(context)) }

        // Лаунчер: открыли системные настройки, вернулись -> проверили a11y, выставили enabled корректно
        val a11ySettingsLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {
            a11yOn = isMyAccessibilityServiceEnabled(context)

            // если пользователь реально включил службу — включаем плагин, иначе откатываем
            if (a11yOn) {
                enabled = true
                saveEnabled(true)
            } else {
                enabled = false
                saveEnabled(false)
            }
        }

        LaunchedEffect(Unit) {
            a11yOn = isMyAccessibilityServiceEnabled(context)

            // если служба выключена — плагин принудительно выключаем (чтобы не было "включен" при OFF a11y)
            if (!a11yOn && enabled) {
                enabled = false
                saveEnabled(false)
            }
        }

        Column(Modifier.padding(16.dp)) {

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.enabled), modifier = Modifier.weight(1f))
                Switch(
                    checked = enabled,
                    onCheckedChange = { v ->
                        if (!v) {
                            // выключаем всегда сразу
                            enabled = false
                            saveEnabled(false)
                            return@Switch
                        }

                        // включение: если служба уже включена — включаем плагин сразу
                        a11yOn = isMyAccessibilityServiceEnabled(context)
                        if (a11yOn) {
                            enabled = true
                            saveEnabled(true)
                        } else {
                            // служба выключена — НЕ включаем плагин, отправляем пользователя в настройки
                            enabled = false
                            saveEnabled(false)

                            a11ySettingsLauncher.launch(
                                Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                            )
                        }
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.show_on_main_screen), modifier = Modifier.weight(1f))
                Switch(
                    checked = pinToMain,
                    onCheckedChange = { v ->
                        pinToMain = v
                        savePinToMain(v)
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Checkbox(
                    checked = periodicEnabled,
                    onCheckedChange = { checked ->
                        periodicEnabled = checked
                        SettingsStorage.setPeriodicEnabled(context, checked)

                        val sec = periodicSecondsText.toLongOrNull() ?: 0L
                        if (checked && sec > 0L) {
                            SettingsStorage.setPeriodicIntervalSec(context, sec.toInt())
                            CommandSender.send(
                                context,
                                ControlCommand.StartPeriodic(sec * 1000L)
                            )
                        } else {
                            CommandSender.send(
                                context,
                                ControlCommand.StopPeriodic
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = stringResource(R.string.repeat_scenario_every),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium
                )

                OutlinedTextField(
                    value = periodicSecondsText,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter { it.isDigit() }
                        periodicSecondsText = filtered

                        val sec = filtered.toLongOrNull()
                        if (sec != null && sec > 0L) {
                            SettingsStorage.setPeriodicIntervalSec(context, sec.toInt())

                            if (periodicEnabled) {
                                CommandSender.send(
                                    context,
                                    ControlCommand.StartPeriodic(sec * 1000L)
                                )
                            }
                        }
                    },
                    modifier = Modifier.width(72.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number
                    ),
                    label = { Text(stringResource(R.string.seconds)) }
                )
            }


            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = {
                    context.startActivity(
                        Intent(context, ScriptEditorActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Edit,
                    contentDescription = stringResource(R.string.scenario_editor),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.open_scenario_editor))
            }

            Spacer(Modifier.height(16.dp))

            ElevatedButton(
                onClick = {
                    // запуск возможен только если служба включена
                    if (!isMyAccessibilityServiceEnabled(context)) {
                        a11ySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        val bus =
                            (context.applicationContext as com.brain.tracscript.core.TracScriptApp)
                                .pluginRuntime.dataBus

                        bus.post(
                            com.brain.tracscript.core.DataBusEvent(
                                type = "scenario_cmd",
                                payload = mapOf("cmd" to "open_app")
                            )
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp),
                colors = ButtonDefaults.elevatedButtonColors(
                    containerColor = androidx.compose.ui.graphics.Color(0xFF4CAF50),
                    contentColor = androidx.compose.ui.graphics.Color.White
                )
            ) {
                Icon(
                    imageVector = Icons.Filled.PlayArrow,
                    contentDescription = stringResource(R.string.run_scenario),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.run_scenario))
            }
        }
    }
}