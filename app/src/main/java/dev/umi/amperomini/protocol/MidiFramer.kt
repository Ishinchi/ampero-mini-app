package dev.umi.amperomini.protocol

import java.io.ByteArrayOutputStream

/**
 * Splits a raw MIDI byte stream into complete messages.
 * Android may deliver one SysEx in several pieces, or several messages in one piece.
 */
class MidiFramer(private val onMessage: (ByteArray) -> Unit) {
    private val sysex = ByteArrayOutputStream()
    private var inSysex = false

    private var status = 0
    private val data = ByteArray(2)
    private var dataCount = 0

    fun feed(bytes: ByteArray, offset: Int = 0, count: Int = bytes.size) {
        for (i in offset until offset + count) {
            val b = bytes[i].toInt() and 0xFF
            when {
                b >= 0xF8 -> Unit // real-time messages (clock etc.) are not used
                b == 0xF0 -> {
                    inSysex = true
                    sysex.reset()
                    sysex.write(b)
                }
                b == 0xF7 -> if (inSysex) {
                    sysex.write(b)
                    inSysex = false
                    onMessage(sysex.toByteArray())
                }
                b >= 0x80 -> {
                    inSysex = false // a status byte inside SysEx means it was cut off
                    status = b
                    dataCount = 0
                    if (dataLength(b) == 0) {
                        onMessage(byteArrayOf(b.toByte()))
                        status = 0
                    }
                }
                inSysex -> sysex.write(b)
                status != 0 -> {
                    data[dataCount++] = b.toByte()
                    if (dataCount == dataLength(status)) {
                        onMessage(byteArrayOf(status.toByte()) + data.copyOf(dataCount))
                        dataCount = 0 // keep status: running status
                        if (status >= 0xF0) status = 0
                    }
                }
            }
        }
    }

    private fun dataLength(status: Int): Int = when (status and 0xF0) {
        0xC0, 0xD0 -> 1
        0xF0 -> when (status) {
            0xF1, 0xF3 -> 1
            0xF2 -> 2
            else -> 0
        }
        else -> 2
    }
}
