package com.brain.tracscript.plugins.scenario

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
import com.brain.tracscript.core.DataBusEvent
import com.brain.tracscript.core.PluginSettingsDefinition
import com.brain.tracscript.core.TracScriptApp
import com.brain.tracscript.util.isMyAccessibilityServiceEnabled

object ScenarioPluginSettingsDefinition : PluginSettingsDefinition {

    override val id: String = ScenarioPluginKey.ID

    @Composable
    override fun displayName(): String = stringResource(R.string.scenarios)

    private const val KEY_PIN_TO_MAIN = "pin_to_main"

    @Composable
    override fun Content() {
        val context = LocalContext.current
        val prefs = remember { ScenarioPrefs.prefs(context) }

        fun postScenarioCmd(cmd: String, extras: Map<String, Any> = emptyMap()) {
            val bus =
                (context.applicationContext as TracScriptApp)
                    .pluginRuntime.dataBus

            bus.post(
                DataBusEvent(
                    type = ScenarioCommands.EVENT,
                    payload = mapOf(ScenarioCommands.CMD to cmd) + extras
                )
            )
        }

        var pinToMain by remember { mutableStateOf(prefs.getBoolean(KEY_PIN_TO_MAIN, false)) }
        fun savePinToMain(v: Boolean) {
            prefs.edit().putBoolean(KEY_PIN_TO_MAIN, v).apply()
        }

        var enabled by remember { mutableStateOf(ScenarioPrefs.isEnabled(context)) }
        fun saveEnabled(v: Boolean) = ScenarioPrefs.setEnabled(context, v)

        var periodicEnabled by remember { mutableStateOf(ScenarioPrefs.isPeriodicEnabled(context)) }
        var periodicSecondsText by remember {
            mutableStateOf(ScenarioPrefs.getPeriodicIntervalSec(context).toString())
        }

        var a11yOn by remember { mutableStateOf(isMyAccessibilityServiceEnabled(context)) }

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

            // если служба выключена — плагин принудительно выключаем
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
                            enabled = false
                            saveEnabled(false)
                            return@Switch
                        }

                        a11yOn = isMyAccessibilityServiceEnabled(context)
                        if (a11yOn) {
                            enabled = true
                            saveEnabled(true)
                        } else {
                            enabled = false
                            saveEnabled(false)
                            a11ySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
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
                        ScenarioPrefs.setPeriodicEnabled(context, checked)

                        val sec = periodicSecondsText.toLongOrNull() ?: 0L

                        if (checked && sec > 0L) {
                            ScenarioPrefs.setPeriodicIntervalSec(context, sec.toInt())
                            postScenarioCmd(
                                ScenarioCommands.START_PERIODIC,
                                mapOf(ScenarioCommands.INTERVAL_MS to (sec * 1000L))
                            )
                        } else {
                            postScenarioCmd(ScenarioCommands.STOP_PERIODIC)
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
                            ScenarioPrefs.setPeriodicIntervalSec(context, sec.toInt())
                            if (periodicEnabled) {
                                postScenarioCmd(
                                    "start_periodic",
                                    mapOf("intervalMs" to (sec * 1000L))
                                )
                            }
                        }
                    },
                    modifier = Modifier.width(72.dp),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
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
                    if (!isMyAccessibilityServiceEnabled(context)) {
                        a11ySettingsLauncher.launch(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                    } else {
                        postScenarioCmd(ScenarioCommands.OPEN_APP)
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
