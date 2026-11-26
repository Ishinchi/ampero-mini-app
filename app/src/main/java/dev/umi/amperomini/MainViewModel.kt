package dev.umi.amperomini

import android.app.Application
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.umi.amperomini.midi.AmperoConnection
import dev.umi.amperomini.midi.AmperoEvent
import dev.umi.amperomini.protocol.EffectCatalog
import dev.umi.amperomini.protocol.ParameterAddress
import dev.umi.amperomini.protocol.PresetId
import dev.umi.amperomini.protocol.PresetInfo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface Status {
    data object Disconnected : Status
    data object Connecting : Status
    data class Loading(val done: Int, val total: Int) : Status
    data object Ready : Status
    data class Error(val message: String) : Status
}

data class UiState(
    val status: Status = Status.Disconnected,
    val version: String? = null,
    val presets: List<PresetInfo> = emptyList(),
    val current: PresetId? = null,
    val livePreset: PresetInfo? = null,
    val modelNotice: String? = null,
    val liveValues: Map<ParameterAddress, Int> = emptyMap(),
    val sending: Boolean = false,
    val selectionEpoch: Long = 0,

) {
    val connected: Boolean get() = status is Status.Loading || status == Status.Ready
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val midi: MidiManager? = app.getSystemService(MidiManager::class.java)

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    private var connection: AmperoConnection? = null
    private var session: Job? = null

    private val deviceCallback = object : MidiManager.DeviceCallback() {
        override fun onDeviceAdded(device: MidiDeviceInfo) {
            if (AmperoConnection.isAmpero(device)) connect()
        }

        override fun onDeviceRemoved(device: MidiDeviceInfo) {
            if (AmperoConnection.isAmpero(device)) disconnect(Status.Disconnected)
        }
    }

    init {
        midi?.let {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.registerDeviceCallback(MidiManager.TRANSPORT_MIDI_BYTE_STREAM, app.mainExecutor, deviceCallback)
            } else {
                @Suppress("DEPRECATION")
                it.registerDeviceCallback(deviceCallback, Handler(Looper.getMainLooper()))
            }
        }
        connect()
    }

    fun connect() {
        if (session?.isActive == true) return
        session = viewModelScope.launch {
            _state.value = UiState(status = Status.Connecting)
            try {
                val conn = AmperoConnection.open(getApplication<Application>())
                connection = conn
                launch {
                    conn.events.collect { event ->
                        when (event) {
                            is AmperoEvent.PresetChanged -> _state.update { it.copy(current = event.id, liveValues = emptyMap(), livePreset = null, modelNotice = null, selectionEpoch = it.selectionEpoch + 1) }
                        }
                    }
                }
                val version = conn.identify()
                val total = PresetId.all.size
                _state.update { it.copy(version = version, status = Status.Loading(0, total)) }
                val presets = ArrayList<PresetInfo>(total)
                for (id in PresetId.all) {
                    presets += conn.readPreset(id)
                    _state.update { it.copy(presets = presets.toList(), status = Status.Loading(presets.size, total)) }
                }
                _state.update { it.copy(status = Status.Ready) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                disconnect(Status.Error(e.message ?: e.toString()))
            }
        }
    }

    fun setParameter(id: PresetId, epoch: Long, address: ParameterAddress, value: Int) {
        val conn = connection ?: return
        val state = _state.value
        if (state.status != Status.Ready || state.sending || state.current != id ||
            state.selectionEpoch != epoch) return
        val preset = state.livePreset ?: state.presets.firstOrNull { it.id == id } ?: return
        val effect = preset.effects.firstOrNull { block -> block.parameters.any { it.address == address } } ?: return
        val parameter = effect.parameters.first { it.address == address }
        if (value !in 0..parameter.scale.maximum) return
        val sync = effect.parameters.firstOrNull { it.name == parameter.scale.syncControl }
        if (sync != null && (state.liveValues[sync.address] ?: sync.value) != 0) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                conn.setParameter(address, value)
                if (connection === conn) _state.update {
                    it.copy(sending = false, liveValues = if (it.selectionEpoch == epoch) it.liveValues + (address to value) else it.liveValues)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connection === conn) disconnect(Status.Error(e.message ?: e.toString()))
            }
        }
    }

    fun changeModel(id: PresetId, epoch: Long, module: String, code: Int) {
        val conn = connection ?: return
        val state = _state.value
        if (state.current != id || state.selectionEpoch != epoch || state.sending || state.status != Status.Ready) return
        if (code !in EffectCatalog.choices(module)) return
        _state.update { it.copy(sending = true, modelNotice = null) }
        viewModelScope.launch {
            try {
                val actual = conn.changeModel(EffectCatalog.blockId(module), code)
                if (connection === conn) _state.update {
                    if (it.selectionEpoch != epoch) it.copy(sending = false)
                    else {
                        val matches = actual.id == id && actual.effects.any { fx -> fx.module == module && fx.modelCode == code }
                        it.copy(current = actual.id, livePreset = actual, liveValues = emptyMap(), sending = false,
                            modelNotice = if (matches) null else "Процессор сообщил о другом выборе. Показано его текущее состояние.")
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connection === conn) disconnect(Status.Error(e.message ?: e.toString()))
            }
        }
    }

    fun select(id: PresetId) {
        val conn = connection ?: return
        if (_state.value.sending) return
        _state.update { it.copy(sending = true) }
        viewModelScope.launch {
            try {
                conn.selectPreset(id)
                if (connection === conn) _state.update { it.copy(current = id, liveValues = emptyMap(), livePreset = null, modelNotice = null, sending = false, selectionEpoch = it.selectionEpoch + 1) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (connection === conn) disconnect(Status.Error(e.message ?: e.toString()))
            }
        }
    }

    private fun disconnect(status: Status) {
        session?.cancel()
        session = null
        connection?.close()
        connection = null
        _state.update { it.copy(status = status, liveValues = emptyMap(), livePreset = null, modelNotice = null, sending = false, selectionEpoch = it.selectionEpoch + 1) }
    }

    override fun onCleared() {
        midi?.unregisterDeviceCallback(deviceCallback)
        disconnect(Status.Disconnected)
    }
}
