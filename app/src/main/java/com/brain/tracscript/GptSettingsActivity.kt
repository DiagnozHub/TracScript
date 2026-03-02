package com.brain.tracscript

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.brain.tracscript.ui.theme.TracScriptTheme

class GptSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TracScriptTheme {
                Scaffold { padding ->
                    GptSettingsScreen(
                        modifier = Modifier.padding(padding),
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun GptSettingsScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val ctx = LocalContext.current
    val scroll = rememberScrollState()

    var enabled by remember { mutableStateOf(GptSettingsStorage.isEnabled(ctx)) }
    var apiUrl by remember { mutableStateOf(GptSettingsStorage.apiUrl(ctx)) }
    var apiToken by remember { mutableStateOf(GptSettingsStorage.apiToken(ctx)) }
    var model by remember { mutableStateOf(GptSettingsStorage.model(ctx)) }

    var proxyEnabled by remember { mutableStateOf(GptSettingsStorage.proxyEnabled(ctx)) }
    var proxyType by remember { mutableStateOf(GptSettingsStorage.proxyType(ctx)) }
    var proxyHost by remember { mutableStateOf(GptSettingsStorage.proxyHost(ctx)) }
    var proxyPortText by remember {
        mutableStateOf(GptSettingsStorage.proxyPort(ctx).takeIf { it > 0 }?.toString() ?: "")
    }
    var proxyUser by remember { mutableStateOf(GptSettingsStorage.proxyUser(ctx)) }
    var proxyPass by remember { mutableStateOf(GptSettingsStorage.proxyPass(ctx)) }

    fun saveAll() {
        GptSettingsStorage.setEnabled(ctx, enabled)
        GptSettingsStorage.setApiUrl(ctx, apiUrl)
        GptSettingsStorage.setApiToken(ctx, apiToken)
        GptSettingsStorage.setModel(ctx, model.ifBlank { "gpt-5.1" })

        GptSettingsStorage.setProxyEnabled(ctx, proxyEnabled)
        GptSettingsStorage.setProxyType(ctx, proxyType)
        GptSettingsStorage.setProxyHost(ctx, proxyHost)
        GptSettingsStorage.setProxyPort(ctx, proxyPortText.toIntOrNull() ?: 0)
        GptSettingsStorage.setProxyUser(ctx, proxyUser)
        GptSettingsStorage.setProxyPass(ctx, proxyPass)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(scroll),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {

        Text("GPT настройки", style = MaterialTheme.typography.headlineSmall)

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Включить GPT", modifier = Modifier.weight(1f))
                    Switch(
                        checked = enabled,
                        onCheckedChange = {
                            enabled = it
                            saveAll()
                        }
                    )
                }

                OutlinedTextField(
                    value = apiUrl,
                    onValueChange = { apiUrl = it; saveAll() },
                    label = { Text("GPT API URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it; saveAll() },
                    label = { Text("GPT API token") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = model,
                    onValueChange = { model = it; saveAll() },
                    label = { Text("Model") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Прокси", modifier = Modifier.weight(1f))
                    Switch(
                        checked = proxyEnabled,
                        onCheckedChange = { proxyEnabled = it; saveAll() }
                    )
                }

                OutlinedTextField(
                    value = proxyType,
                    onValueChange = { proxyType = it; saveAll() },
                    label = { Text("Proxy type (HTTP/SOCKS)") },
                    singleLine = true,
                    enabled = proxyEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = proxyHost,
                    onValueChange = { proxyHost = it; saveAll() },
                    label = { Text("Proxy host") },
                    singleLine = true,
                    enabled = proxyEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = proxyPortText,
                    onValueChange = {
                        proxyPortText = it.filter { ch -> ch.isDigit() }
                        saveAll()
                    },
                    label = { Text("Proxy port") },
                    singleLine = true,
                    enabled = proxyEnabled,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = proxyUser,
                    onValueChange = { proxyUser = it; saveAll() },
                    label = { Text("Proxy login (optional)") },
                    singleLine = true,
                    enabled = proxyEnabled,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = proxyPass,
                    onValueChange = { proxyPass = it; saveAll() },
                    label = { Text("Proxy password (optional)") },
                    singleLine = true,
                    enabled = proxyEnabled,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = { saveAll(); onClose() },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Закрыть")
        }
    }
}
