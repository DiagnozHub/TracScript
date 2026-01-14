package com.brain.tracscript

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.brain.tracscript.core.PluginSettingsDefinition
import com.brain.tracscript.core.PluginSettingsRegistry
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.brain.tracscript.ui.theme.TracScriptTheme
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            TracScriptTheme {

                val version = packageManager
                    .getPackageInfo(packageName, 0)
                    .versionName ?: "?"

                MainScreen(
                    appVersion = version,
                    onOpenSettings = {
                        startActivity(
                            Intent(this, SettingsActivity::class.java)
                        )
                    },
                    onSendTestCommand = {},
                    onOpenScriptEditor = {}
                )
            }
        }
    }
}

private fun prefsNameFor(pluginId: String): String = "plugin_$pluginId"

// Список GUI плагинов, закреплённых для отображения в главном окне.
// Обновляется сразу при переключении "pin_to_main" в любом плагине.
@Composable
private fun rememberPinnedPluginDefs(): List<PluginSettingsDefinition> {
    val context = LocalContext.current
    val defs = remember { PluginSettingsRegistry.getPluginSettings() }

    var tick by remember { mutableIntStateOf(0) }

    DisposableEffect(Unit) {
        val listeners =
            mutableListOf<Pair<SharedPreferences, SharedPreferences.OnSharedPreferenceChangeListener>>()

        defs.forEach { def ->
            val prefs =
                context.getSharedPreferences(prefsNameFor(def.id), Context.MODE_PRIVATE)

            val l = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == "pin_to_main") tick++
            }

            prefs.registerOnSharedPreferenceChangeListener(l)
            listeners += prefs to l
        }

        onDispose {
            listeners.forEach { (p, l) ->
                p.unregisterOnSharedPreferenceChangeListener(l)
            }
        }
    }

    // Читаем tick, чтобы Compose пересчитывал pinnedDefs
    val forceRecompose = tick

    return defs.filter { def ->
        val prefs =
            context.getSharedPreferences(prefsNameFor(def.id), Context.MODE_PRIVATE)
        prefs.getBoolean("pin_to_main", false)
    }
}

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    appVersion: String,
    onOpenSettings: () -> Unit,
    onSendTestCommand: () -> Unit,
    onOpenScriptEditor: () -> Unit
) {
    val pinnedDefs = rememberPinnedPluginDefs()

    val lifecycleOwner = LocalLifecycleOwner.current
    var resumeTick by remember { mutableIntStateOf(0) }

    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeTick++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp)
    ) {

        // ─────────────────────────────
        // Шапка
        // ─────────────────────────────
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "TracScript",
                    style = MaterialTheme.typography.headlineMedium.copy(
                        fontWeight = FontWeight.SemiBold
                    )
                )

                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = "ver. $appVersion",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = stringResource(R.string.accessibility_description),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.75f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ─────────────────────────────
        // Центральная зона (скролл, занимает всё доступное место)
        // ─────────────────────────────
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {

            // Если ничего не закреплено — показываем твою кнопку запуска сценария (fallback)
            if (pinnedDefs.isEmpty()) {
                // ─────────────────────────────
                // Центральная зона
                // ─────────────────────────────
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {

                    if (pinnedDefs.isEmpty()) {

                        Box(
                            modifier = Modifier
                                .fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp)
                                    .widthIn(max = 520.dp),
                                shape = MaterialTheme.shapes.extraLarge,
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                                ),
                                elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(20.dp),
                                    verticalArrangement = Arrangement.spacedBy(14.dp)
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.Top
                                    ) {
                                        /*
                                        Surface(
                                            shape = MaterialTheme.shapes.large,
                                            color = MaterialTheme.colorScheme.primaryContainer
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Edit,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                                modifier = Modifier.padding(10.dp)
                                            )
                                        }

                                         */

                                        Spacer(Modifier.width(12.dp))

                                        Column {
                                            Text(
                                                text = stringResource(R.string.customize_dashboards_view),
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )

                                            Spacer(Modifier.height(6.dp))

                                            Text(
                                                text = stringResource(R.string.pin_dashboards_hint),
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface.copy(
                                                    alpha = 0.75f
                                                )
                                            )
                                        }
                                    }

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.Center
                                    ) {
                                        val context = LocalContext.current
                                        Button(
                                            onClick = {
                                                context.startActivity(
                                                    Intent(
                                                        context,
                                                        PluginSettingsActivity::class.java
                                                    )
                                                )
                                            }
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.Settings,
                                                contentDescription = null,
                                                modifier = Modifier.padding(end = 8.dp)
                                            )
                                            Text(stringResource(R.string.configure))
                                        }
                                    }
                                }
                            }
                        }

                    } else {

                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            pinnedDefs.forEach { def ->
                                Card(modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(
                                            text = def.displayName(),
                                            style = MaterialTheme.typography.titleMedium,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                        Spacer(Modifier.height(8.dp))
                                        def.Content()
                                    }
                                }
                            }
                        }
                    }
                }

            } else {
                pinnedDefs.forEach { def ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(12.dp)) {
                            Text(
                                text = def.displayName(),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(Modifier.height(8.dp))

                            // ВСТРАИВАЕМ GUI ПЛАГИНА (вся его логика тоже отработает)
                            key(def.id, resumeTick)
                            {
                                def.Content()
                            }
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ─────────────────────────────
        // Низ (фикс снизу)
        // ─────────────────────────────
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
            ) {
                Icon(
                    imageVector = Icons.Filled.Settings,
                    contentDescription = stringResource(R.string.general_settings),
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(stringResource(R.string.general_settings))
            }
        }
    }
}
