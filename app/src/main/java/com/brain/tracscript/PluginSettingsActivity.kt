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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brain.tracscript.core.PluginSettingsRegistry
import com.brain.tracscript.ui.theme.TracScriptTheme

class PluginSettingsActivity : ComponentActivity() {

    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TracScriptTheme {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    topBar = {
                        SmallTopAppBar(
                            title = {
                                Text(
                                    text = stringResource(R.string.plugin_settings),
                                    style = MaterialTheme.typography.titleMedium.copy(
                                        fontWeight = FontWeight.SemiBold
                                    )
                                )
                            }
                        )
                    }
                ) { padding ->
                    PluginSettingsScreen(
                        modifier = Modifier.padding(padding),
                        onClose = { finish() }
                    )
                }
            }
        }
    }
}

@Composable
private fun PluginSettingsScreen(
    modifier: Modifier = Modifier,
    onClose: () -> Unit
) {
    val plugins = remember {
        PluginSettingsRegistry.getPluginSettings()
    }

    if (plugins.isEmpty()) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = stringResource(R.string.no_plugins_available),
                style = MaterialTheme.typography.bodyLarge
            )

            Button(
                onClick = onClose,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Text(stringResource(R.string.close))
            }
        }
        return
    }

    var selectedTabIndex by remember { mutableStateOf(0) }
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // Вкладки (табы)
        ScrollableTabRow(
            selectedTabIndex = selectedTabIndex
        ) {
            plugins.forEachIndexed { index, def ->
                Tab(
                    selected = index == selectedTabIndex,
                    onClick = { selectedTabIndex = index },
                    text = { Text(def.displayName()) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Контент активного плагина с прокруткой
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(scrollState)
            ) {
                plugins[selectedTabIndex].Content()
                Spacer(Modifier.height(8.dp)) // небольшой отступ снизу, чтобы не прилипало
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onClose,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text(stringResource(R.string.close))
        }
    }
}

