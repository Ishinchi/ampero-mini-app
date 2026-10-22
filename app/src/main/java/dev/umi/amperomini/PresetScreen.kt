package dev.umi.amperomini

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.umi.amperomini.protocol.PresetId
import dev.umi.amperomini.protocol.PresetInfo

@Composable
fun PresetScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Header(state, onConnect = vm::connect)
            HorizontalDivider()
            PresetList(
                presets = state.presets,
                current = state.current,
                enabled = state.connected,
                onSelect = vm::select,
            )
        }
    }
}

@Composable
private fun Header(state: UiState, onConnect: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Ampero Mini", style = MaterialTheme.typography.headlineSmall)
        val status = state.status
        Text(
            text = when (status) {
                Status.Disconnected -> "Не подключён"
                Status.Connecting -> "Подключение…"
                is Status.Loading -> "Читаю пресеты: ${status.done} из ${status.total}"
                Status.Ready -> "Подключён" + (state.version?.let { " · версия $it" } ?: "")
                is Status.Error -> "Ошибка: ${status.message}"
            },
            color = if (status is Status.Error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status is Status.Loading) {
            LinearProgressIndicator(
                progress = { status.done.toFloat() / status.total },
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (status is Status.Disconnected || status is Status.Error) {
            Button(onClick = onConnect) { Text("Подключить") }
        }
    }
}

@Composable
private fun PresetList(
    presets: List<PresetInfo>,
    current: PresetId?,
    enabled: Boolean,
    onSelect: (PresetId) -> Unit,
) {
    LazyColumn(Modifier.fillMaxSize()) {
        presets.groupBy { it.id.bank }.forEach { (bank, inBank) ->
            item(key = "bank-$bank") {
                Text(
                    text = "Банк ${bank + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
                )
            }
            items(inBank, key = { "${it.id.bank}-${it.id.slot}" }) { preset ->
                PresetRow(preset, selected = preset.id == current, enabled = enabled, onClick = { onSelect(preset.id) })
            }
        }
        item { Spacer(Modifier.height(16.dp)) }
    }
}

@Composable
private fun PresetRow(preset: PresetInfo, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (selected) colors.primaryContainer else colors.surface)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = preset.id.label,
            fontFamily = FontFamily.Monospace,
            color = if (selected) colors.onPrimaryContainer else colors.onSurfaceVariant,
        )
        Spacer(Modifier.width(16.dp))
        Text(
            text = preset.name.ifEmpty { "—" },
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (selected) colors.onPrimaryContainer else colors.onSurface,
        )
    }
}
