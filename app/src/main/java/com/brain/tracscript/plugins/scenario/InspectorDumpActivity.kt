package com.brain.tracscript.plugins.scenario

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.brain.tracscript.R
import com.brain.tracscript.ui.theme.TracScriptTheme
import java.io.File

class InspectorDumpActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            TracScriptTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
                    InspectorDumpScreen(
                        modifier = Modifier.padding(padding),
                        loadDump = { loadDumpText() },
                        clearDump = { clearDumpFile() },
                        onClose = { finish() }
                    )
                }
            }
        }
    }

    private fun dumpFile(): File = File(filesDir, "inspector_dump.txt")

    private fun loadDumpText(): String {
        return try {
            val f = dumpFile()
            if (f.exists()) f.readText()
            else getString(R.string.dump_not_created)
        } catch (e: Exception) {
            getString(
                R.string.dump_read_error,
                e.message ?: getString(R.string.not_available)
            )
        }
    }

    private fun clearDumpFile() {
        try {
            val f = dumpFile()
            if (f.exists()) f.writeText("") else f.createNewFile()
        } catch (_: Exception) { }
    }
}

@Composable
private fun InspectorDumpScreen(
    modifier: Modifier = Modifier,
    loadDump: () -> String,
    clearDump: () -> Unit,
    onClose: () -> Unit
) {
    var dumpText by remember { mutableStateOf(loadDump()) }

    Column(
        modifier = modifier
            .padding(16.dp)
            .fillMaxSize()
    ) {
        // ==== кнопки сверху ====
        Row {
            Button(
                onClick = onClose,
                modifier = Modifier.padding(end = 8.dp)
            ) {
                Text(stringResource(R.string.close))
            }

            Button(
                onClick = {
                    clearDump()
                    dumpText = loadDump()
                }
            ) {
                Text(stringResource(R.string.clear))
            }
        }

        // ==== текст дампа ====
        val scroll = rememberScrollState()

        SelectionContainer {
            Text(
                text = dumpText,
                modifier = Modifier
                    .padding(top = 16.dp)
                    .verticalScroll(scroll)
                    .fillMaxWidth(),
                fontFamily = FontFamily.Monospace
            )
        }
    }
}