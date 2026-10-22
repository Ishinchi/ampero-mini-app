package dev.umi.amperomini.protocol

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Checks the protocol against the exchange recorded from Ampero Editor (preset 1:12 "FUZZZZZZ"). */
class AmperoProtocolTest {
    private val fuzz = PresetId(bank = 1, slot = 12)

    private val recorded: List<Pair<String, ByteArray>> =
        javaClass.getResource("/preset_1_12.txt")!!.readText().lines()
            .filter { it.isNotBlank() }
            .map { line -> line.substring(0, 3).trim() to hex(line.substring(4)) }
    private val sent = recorded.filter { it.first == "OUT" }.map { it.second }
    private val received = recorded.filter { it.first == "IN" }.map { it.second }

    @Test
    fun requestsMatchTheEditor() {
        assertArrayEquals(sent[0], Ampero.identityRequest())
        assertArrayEquals(sent[1], Ampero.presetReadRequest(fuzz))
        assertArrayEquals(sent[2], Ampero.presetReadStart(fuzz))
        for (chunk in 0..4) assertArrayEquals(sent[3 + chunk], Ampero.presetChunkAck(fuzz, chunk))
    }

    @Test
    fun downloadsPresetFromSplitStream() {
        val messages = mutableListOf<ByteArray>()
        val framer = MidiFramer { messages += it }
        val stream = received.reduce { a, b -> a + b }
        // Android may cut SysEx anywhere; feed uneven pieces.
        var pos = 0
        while (pos < stream.size) {
            val n = minOf(7, stream.size - pos)
            framer.feed(stream, pos, n)
            pos += n
        }
        assertEquals(received.size, messages.size)

        val download = PresetDownload(fuzz)
        val acks = messages.mapNotNull(Ampero::parse).mapNotNull(download::accept)
        assertEquals(listOf(0, 1, 2, 3, 4), acks)
        assertTrue(download.finished)
        assertEquals(PresetInfo(fuzz, "FUZZZZZZ"), download.result())
    }

    @Test
    fun identityReply() {
        val reply = Ampero.parse(received[0])!!
        assertTrue(reply.isIdentity)
        assertEquals("1.5", Ampero.identityVersion(reply))
    }

    @Test
    fun presetChangedOnDevice() {
        // Recorded in 06_device_side: switched to 1:14 with the footswitch.
        val message = Ampero.parse(hex("f0 21 25 7f 4d 50 2d 32 12 00 02 06 04 01 0e f7"))!!
        assertEquals(PresetId(1, 14), message.presetChanged)
        assertTrue(message.isNotification)
    }

    @Test
    fun selectPresetSendsBankAndProgram() {
        // Editor sent B0 00 01 + C0 0D when picking 1:13.
        assertArrayEquals(hex("b0 00 01 c0 0d"), Ampero.selectPreset(PresetId(1, 13)))
    }

    @Test
    fun framerHandlesShortMessagesAndRunningStatus() {
        val messages = mutableListOf<String>()
        val framer = MidiFramer { messages += it.joinToString(" ") { b -> "%02x".format(b) } }
        framer.feed(hex("b0 30 7f 30 00 f8 c0 0d"))
        assertEquals(listOf("b0 30 7f", "b0 30 00", "c0 0d"), messages)
    }

    @Test
    fun foreignSysexIsIgnored() {
        assertNull(Ampero.parse(hex("f0 7e 7f 06 01 f7")))
    }

    private fun hex(s: String): ByteArray =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
