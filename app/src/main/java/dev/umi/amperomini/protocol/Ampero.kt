package dev.umi.amperomini.protocol

import java.io.ByteArrayOutputStream

/** Preset slot: bank 0..1, slot 0..98. */
data class PresetId(val bank: Int, val slot: Int) {
    /** How the preset is numbered in the app; not yet checked against the device screen. */
    val label: String get() = "${bank + 1}-${(slot + 1).toString().padStart(2, '0')}"

    companion object {
        val all: List<PresetId> =
            (0 until Ampero.BANKS).flatMap { bank -> (0 until Ampero.PRESETS_PER_BANK).map { PresetId(bank, it) } }
    }
}

data class PresetInfo(val id: PresetId, val name: String, val effects: List<PresetEffect> = emptyList())

/**
 * A SysEx message from the device with the common header stripped:
 * `F0 21 25 7F 'M' 'P' '-' x <cmd> <body...> F7`.
 */
class AmperoMessage(val cmd: Int, val body: ByteArray) {
    operator fun get(index: Int): Int = body[index].toInt() and 0xFF

    fun startsWith(vararg prefix: Int): Boolean =
        prefix.size <= body.size && prefix.indices.all { get(it) == prefix[it] }

    val isNotification: Boolean get() = cmd == Ampero.CMD_DATA && startsWith(0x00, 0x02, 0x06)

    /** Set when the preset was changed on the device itself. */
    val presetChanged: PresetId?
        get() = if (cmd == Ampero.CMD_DATA && startsWith(0x00, 0x02, 0x06, 0x04) && body.size >= 6) {
            PresetId(get(4), get(5))
        } else {
            null
        }

    val isIdentity: Boolean get() = cmd == Ampero.CMD_DATA && startsWith(0x00, 0x00, 0x05, 0x00)

    fun isPresetHeader(id: PresetId) = isPresetReply(0x0C, id)
    fun isPresetChunk(id: PresetId) = isPresetReply(0x0E, id)
    fun isPresetEnd(id: PresetId) = isPresetReply(0x10, id)

    private fun isPresetReply(sub: Int, id: PresetId) =
        cmd == Ampero.CMD_DATA && startsWith(0x00, 0x02, sub, 0x00, id.bank, id.slot)
}

/** Message builders and parsers for the Ampero Mini editor protocol (see PROTOCOL.md). */
object Ampero {
    const val BANKS = 2
    const val PRESETS_PER_BANK = 99

    const val CMD_READ = 0x11
    const val CMD_DATA = 0x12

    private val MANUFACTURER = intArrayOf(0x21, 0x25, 0x7F)
    private const val MODEL = "MP-2"

    /** The editor's first request uses this model id, as if searching for any device. */
    private const val MODEL_ANY = "MP-d"

    /** Preset chunk body: 00 02 0E 00 <bank> <slot> 00 <chunk> <nibbles...> */
    private const val CHUNK_INDEX = 7
    private const val CHUNK_DATA = 8

    private const val NAME_OFFSET = 4
    private const val NAME_LENGTH = 16

    fun selectModel(block: Int, code: Int): ByteArray {
        require(block in 1..9 && code in 0..0x3fff)
        return sysex(MODEL, CMD_DATA, 0x10, block, 0, 1, code shr 7, code and 127)
    }

    fun currentReadRequest() = sysex(MODEL, CMD_READ, 0, 2, 0x0a, 0, 0, 0)
    fun currentStart(id: PresetId) = sysex(MODEL, CMD_DATA, 0, 2, 0x13, 0, id.bank, id.slot, 1)
    fun currentAck(id: PresetId, chunk: Int) =
        sysex(MODEL, CMD_DATA, 0, 2, 0x15, 0, id.bank, id.slot, 0, chunk, 1)

    fun identityRequest() = sysex(MODEL_ANY, CMD_READ, 0x00, 0x00, 0x05, 0x00)

    fun presetReadRequest(id: PresetId) = sysex(MODEL, CMD_READ, 0x00, 0x02, 0x08, 0x00, id.bank, id.slot)

    fun presetReadStart(id: PresetId) = sysex(MODEL, CMD_DATA, 0x00, 0x02, 0x0D, 0x00, id.bank, id.slot, 0x01)

    fun presetChunkAck(id: PresetId, chunk: Int) =
        sysex(MODEL, CMD_DATA, 0x00, 0x02, 0x0F, 0x00, id.bank, id.slot, 0x00, chunk, 0x01)

    /** Bank select (CC 0) followed by Program Change, MIDI channel 1. */
    fun selectPreset(id: PresetId) = bytes(0xB0, 0x00, id.bank, 0xC0, id.slot)

    /** Live knob write, verified against captures 04_param and 08_params. */
    fun setParameter(address: ParameterAddress, value: Int): ByteArray {
        require(value in 0..0x3fff)
        require(address.block in 1..9)
        require(address.model in 0..0x3fff && address.index in 0..14)
        return sysex(MODEL, CMD_DATA, 0x10, address.block, 0x00, 0x02,
            address.model shr 7, address.model and 127, address.index, value shr 7, value and 127)
    }

    fun parse(message: ByteArray): AmperoMessage? {
        if (message.size < 10) return null
        if (message.first() != 0xF0.toByte() || message.last() != 0xF7.toByte()) return null
        if (MANUFACTURER.indices.any { (message[1 + it].toInt() and 0xFF) != MANUFACTURER[it] }) return null
        if (String(message, 4, 3, Charsets.US_ASCII) != "MP-") return null
        return AmperoMessage(message[8].toInt() and 0xFF, message.copyOfRange(9, message.size - 1))
    }

    /** Version reported in the identity reply, e.g. "1.5". The meaning is still a guess. */
    fun identityVersion(reply: AmperoMessage): String =
        if (reply.body.size >= 6) "${reply[4]}.${reply[5]}" else "?"

    /** Long data is sent as 4-bit halves: each byte becomes two MIDI bytes, high half first. */
    fun decodeNibbles(nibbles: ByteArray): ByteArray =
        ByteArray(nibbles.size / 2) { ((nibbles[2 * it].toInt() and 0x0F) shl 4 or (nibbles[2 * it + 1].toInt() and 0x0F)).toByte() }

    /** Preset blob: <bank> <slot> 00 <?> <16-byte ASCII name> <parameters...> */
    fun parsePreset(id: PresetId, blob: ByteArray): PresetInfo {
        val end = minOf(blob.size, NAME_OFFSET + NAME_LENGTH)
        val name = if (blob.size > NAME_OFFSET) {
            blob.copyOfRange(NAME_OFFSET, end).takeWhile { it != 0.toByte() }.toByteArray()
                .toString(Charsets.US_ASCII).trim()
        } else {
            ""
        }
        return PresetInfo(id, name, EffectCatalog.decode(blob))
    }

    private fun sysex(model: String, cmd: Int, vararg body: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(0xF0)
        MANUFACTURER.forEach(out::write)
        out.write(model.toByteArray(Charsets.US_ASCII))
        out.write(cmd)
        body.forEach(out::write)
        out.write(0xF7)
        return out.toByteArray()
    }

    private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

    internal fun chunkIndex(chunk: AmperoMessage) = chunk[CHUNK_INDEX]

    internal fun chunkData(chunk: AmperoMessage): ByteArray = chunk.body.copyOfRange(CHUNK_DATA, chunk.body.size)
}

/** Collects the chunked reply that follows [Ampero.presetReadStart] for one preset. */
class PresetDownload(private val id: PresetId, private val live: Boolean = false) {
    private val nibbles = ByteArrayOutputStream()

    private var nextChunk = 0
    var finished = false
        private set

    /** Takes a reply from the device; returns the chunk number to acknowledge, or null. */
    fun accept(message: AmperoMessage): Int? {
        if (message.cmd != Ampero.CMD_DATA || finished) return null
        if (message.startsWith(0, 2, if (live) 0x14 else 0x0e, 0, id.bank, id.slot)) {
            require(message.body.size > 8 && Ampero.chunkIndex(message) == nextChunk++)
            require(nibbles.size() + message.body.size - 8 <= 16384)
            nibbles.write(Ampero.chunkData(message))
            return Ampero.chunkIndex(message)
        }
        if (message.startsWith(0, 2, if (live) 0x16 else 0x10, 0, id.bank, id.slot)) {
            require(message.body.size >= 8 && message[7] == nextChunk && nextChunk > 0)
            finished = true
        }
        return null
    }

    fun result(): PresetInfo = Ampero.parsePreset(id, Ampero.decodeNibbles(nibbles.toByteArray()))
}
