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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.Box
import dev.umi.amperomini.protocol.EffectCatalog
import dev.umi.amperomini.protocol.ParameterScale
import androidx.compose.material3.Slider
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import kotlin.math.roundToInt
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.platform.LocalView
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.umi.amperomini.protocol.ParameterAddress
import dev.umi.amperomini.protocol.PresetId
import dev.umi.amperomini.protocol.PresetInfo

@Composable
fun PresetScreen(vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var live by rememberSaveable { mutableStateOf(false) }
    var violet by rememberSaveable { mutableStateOf(false) }
    val view = LocalView.current
    DisposableEffect(view, live) {
        val previous = view.keepScreenOn
        if (live) view.keepScreenOn = true
        onDispose { if (live) view.keepScreenOn = previous }
    }
    Scaffold { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (live) "Сцена" else "Пресеты", style = MaterialTheme.typography.titleLarge)
                OutlinedButton(onClick = { live = !live }) {
                    Text(if (live) "Список пресетов" else "На сцену")
                }
            }
            HorizontalDivider()
            if (live) {
                val active = state.presets.indexOfFirst { it.id == state.current }
                val current = state.livePreset ?: state.presets.getOrNull(active)
                val colors = MaterialTheme.colorScheme
                Column(Modifier.fillMaxSize()
                    .background(if (violet) colors.tertiaryContainer else colors.surface)
                    .verticalScroll(rememberScrollState()).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (state.status != Status.Ready) Header(state, vm::connect)
                    if (state.connected) {
                        Text(state.current?.label ?: "Выбери пресет", style = MaterialTheme.typography.titleLarge)
                        current?.let { Text(it.name.ifBlank { "Без имени" }, style = MaterialTheme.typography.displayMedium) }
                        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(enabled = !state.sending && active > 0, onClick = { state.presets.getOrNull(active - 1)?.let { vm.select(it.id) } }) { Text("Назад") }
                            Button(enabled = !state.sending && active >= 0 && active < state.presets.lastIndex,
                                onClick = { state.presets.getOrNull(active + 1)?.let { vm.select(it.id) } }) { Text("Вперёд") }
                        }
                    }
                    if (state.connected && current != null) key(current.id, state.selectionEpoch) {
                        state.modelNotice?.let { Text(it) }
                        EffectList(current, state.liveValues, state.status == Status.Ready && !state.sending,
                            onModel = { module, code -> vm.changeModel(current.id, state.selectionEpoch, module, code) }) { address, value ->
                            vm.setParameter(current.id, state.selectionEpoch, address, value)
                        }
                    }
                    OutlinedButton(onClick = { violet = !violet }) { Text("Сменить фон") }
                }
            } else {
                Header(state, onConnect = vm::connect)
                HorizontalDivider()
                PresetList(state.presets, state.current, state.connected && !state.sending, vm::select)
            }
        }
    }
}

@Composable
private fun EffectList(preset: PresetInfo, liveValues: Map<ParameterAddress, Int>, editable: Boolean, onModel: (String, Int) -> Unit, onChange: (ParameterAddress, Int) -> Unit) {
    var expanded by rememberSaveable(preset.id.bank, preset.id.slot) { mutableStateOf<String?>(null) }
    Text("Блоки пресета", style = MaterialTheme.typography.titleMedium)
    preset.effects.forEach { effect ->
        HorizontalDivider()
        Row(
            Modifier.fillMaxWidth().clickable {
                expanded = if (expanded == effect.module) null else effect.module
            }.padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(effect.module, modifier = Modifier.width(52.dp), fontFamily = FontFamily.Monospace)
            Text(effect.model, modifier = Modifier.weight(1f))
            Text(if (effect.enabled) "Вкл" else "Выкл")
        }
        if (expanded == effect.module) {
            var menu by remember { mutableStateOf(false) }
            val choices = remember(effect.module) { EffectCatalog.choices(effect.module) }
            Box {
                OutlinedButton(enabled = editable, onClick = { menu = true }) { Text("Список эффектов") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    choices.forEach { (code, title) ->
                        DropdownMenuItem(text = { Text(title) }, enabled = editable && code != effect.modelCode,
                            onClick = { menu = false; onModel(effect.module, code) })
                    }
                }
            }
            
            effect.parameters.forEach { (name, value, address, scale) ->
                if (address != null) {
                    key(address) {
                        val sync = effect.parameters.firstOrNull { it.name == scale.syncControl }
                        if (sync != null && (liveValues[sync.address] ?: sync.value) != 0) {
                            Text("$name · синхронизация")
                        } else {
                            ParameterSlider(name, liveValues[address] ?: value, scale, editable) { onChange(address, it) }
                        }
                    }
                } else {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(name)
                        Text(value.toString(), fontFamily = FontFamily.Monospace)
                    }
                }
            }
        }
    }
}

@Composable
private fun ParameterSlider(name: String, value: Int, scale: ParameterScale, enabled: Boolean, onCommit: (Int) -> Unit) {
    if (scale.options.isNotEmpty()) {
        var open by remember { mutableStateOf(false) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(name)
            Box {
                OutlinedButton(onClick = { open = true }, enabled = enabled) { Text(scale.label(value)) }
                DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                    scale.options.forEachIndexed { index, label ->
                        DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onCommit(index) })
                    }
                }
            }
        }
        return
    }
    var draft by remember(value) { mutableStateOf<Int?>(null) }
    val displayed = draft ?: value
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(name)
        Text(scale.label(displayed), fontFamily = FontFamily.Monospace)
    }
    Slider(
        value = displayed.toFloat(),
        onValueChange = { draft = it.roundToInt() },
        onValueChangeFinished = {
            draft?.let(onCommit)
            draft = null
        },
        valueRange = 0f..scale.maximum.toFloat(),
        steps = if (scale.maximum <= 100) scale.maximum - 1 else 0,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().semantics { contentDescription = name },
    )
    if (scale.maximum > 100) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(enabled = enabled && value > 0, onClick = { onCommit(value - 1) }) { Text("−1") }
            TextButton(enabled = enabled && value < scale.maximum, onClick = { onCommit(value + 1) }) { Text("+1") }
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
