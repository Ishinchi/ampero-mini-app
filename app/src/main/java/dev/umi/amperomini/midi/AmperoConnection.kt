package dev.umi.amperomini.midi

import android.content.Context
import android.media.midi.MidiDevice
import android.media.midi.MidiDeviceInfo
import android.media.midi.MidiInputPort
import android.media.midi.MidiManager
import android.media.midi.MidiOutputPort
import android.media.midi.MidiReceiver
import android.os.Build
import android.os.Handler
import android.os.Looper
import dev.umi.amperomini.protocol.Ampero
import dev.umi.amperomini.protocol.AmperoMessage
import dev.umi.amperomini.protocol.MidiFramer
import dev.umi.amperomini.protocol.PresetDownload
import dev.umi.amperomini.protocol.PresetId
import dev.umi.amperomini.protocol.PresetInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.Closeable
import java.io.IOException
import kotlin.coroutines.resume

/** Something the device reported on its own, without a request. */
sealed interface AmperoEvent {
    data class PresetChanged(val id: PresetId) : AmperoEvent
}

/** An open MIDI link to the Ampero Mini. Requests run one at a time. */
class AmperoConnection private constructor(
    private val device: MidiDevice,
    private val input: MidiInputPort, // app -> device
    private val output: MidiOutputPort, // device -> app
) : Closeable {

    private val replies = Channel<AmperoMessage>(Channel.UNLIMITED)
    private val lock = Mutex()

    private val _events = MutableSharedFlow<AmperoEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<AmperoEvent> = _events

    private val framer = MidiFramer(::dispatch)
    private val receiver = object : MidiReceiver() {
        override fun onSend(msg: ByteArray, offset: Int, count: Int, timestamp: Long) {
            framer.feed(msg, offset, count)
        }
    }

    init {
        output.connect(receiver)
    }

    private fun dispatch(message: ByteArray) {
        val parsed = Ampero.parse(message) ?: return // plain CC/PC from the device are not used yet
        val changed = parsed.presetChanged
        when {
            changed != null -> _events.tryEmit(AmperoEvent.PresetChanged(changed))
            parsed.isNotification -> Unit
            else -> replies.trySend(parsed)
        }
    }

    /** Asks the device who it is; returns the reported version. */
    suspend fun identify(): String = transaction {
        send(Ampero.identityRequest())
        Ampero.identityVersion(awaitReply { it.isIdentity })
    }

    suspend fun readPreset(id: PresetId): PresetInfo = transaction {
        send(Ampero.presetReadRequest(id))
        awaitReply { it.isPresetHeader(id) }
        send(Ampero.presetReadStart(id))
        val download = PresetDownload(id)
        while (!download.finished) {
            val chunk = download.accept(awaitReply { it.isPresetChunk(id) || it.isPresetEnd(id) })
            if (chunk != null) send(Ampero.presetChunkAck(id, chunk))
        }
        download.result()
    }

    suspend fun selectPreset(id: PresetId) = transaction {
        send(Ampero.selectPreset(id))
    }

    private suspend fun <T> transaction(block: suspend () -> T): T = lock.withLock {
        withContext(Dispatchers.IO) {
            while (replies.tryReceive().isSuccess) Unit // drop stale replies
            block()
        }
    }

    private fun send(bytes: ByteArray) = input.send(bytes, 0, bytes.size)

    private suspend fun awaitReply(match: (AmperoMessage) -> Boolean): AmperoMessage =
        withTimeoutOrNull(REPLY_TIMEOUT_MS) {
            var reply = replies.receive()
            while (!match(reply)) reply = replies.receive()
            reply
        } ?: throw IOException("Процессор не ответил")

    override fun close() {
        runCatching { output.disconnect(receiver) }
        runCatching { output.close() }
        runCatching { input.close() }
        runCatching { device.close() }
        replies.close()
    }

    companion object {
        private const val REPLY_TIMEOUT_MS = 2000L

        fun isAmpero(info: MidiDeviceInfo): Boolean {
            val names = listOf(MidiDeviceInfo.PROPERTY_PRODUCT, MidiDeviceInfo.PROPERTY_NAME)
                .mapNotNull { info.properties.getString(it) }
            return info.type == MidiDeviceInfo.TYPE_USB &&
                info.inputPortCount > 0 && info.outputPortCount > 0 &&
                names.any { it.contains("Ampero", ignoreCase = true) }
        }

        fun devices(midi: MidiManager): List<MidiDeviceInfo> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                midi.getDevicesForTransport(MidiManager.TRANSPORT_MIDI_BYTE_STREAM).toList()
            } else {
                @Suppress("DEPRECATION")
                midi.devices.toList()
            }

        suspend fun open(context: Context): AmperoConnection {
            val midi = context.getSystemService(MidiManager::class.java)
                ?: throw IOException("Телефон не поддерживает MIDI")
            val info = devices(midi).firstOrNull(::isAmpero)
                ?: throw IOException("Ampero Mini не найден. Подключи процессор по USB.")
            val device = suspendCancellableCoroutine<MidiDevice?> { cont ->
                midi.openDevice(info, { cont.resume(it) }, Handler(Looper.getMainLooper()))
            } ?: throw IOException("Не удалось открыть Ampero Mini")
            // Port 0 is the one the desktop editor uses (USB-MIDI cable 0).
            val input = device.openInputPort(0)
            val output = device.openOutputPort(0)
            if (input == null || output == null) {
                input?.close()
                output?.close()
                device.close()
                throw IOException("MIDI-порт Ampero Mini занят другим приложением")
            }
            return AmperoConnection(device, input, output)
        }
    }
}
