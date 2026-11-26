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
    fun gainWritesMatchTheThreeCapturedValues() {
        val prefix = "f0 21 25 7f 4d 50 2d 32 12 10 03 00 02 00 66 00 00"
        for ((value, payload) in listOf(0 to "00", 48 to "30", 100 to "64")) {
            assertArrayEquals(hex("$prefix $payload f7"), Ampero.setParameter(ParameterAddress(3, 102, 0), value))
        }
        for (outside in listOf(-1, 16384)) {
            assertTrue(runCatching { Ampero.setParameter(ParameterAddress(3, 102, 0), outside) }.exceptionOrNull() is IllegalArgumentException)
        }
    }

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
        val preset = download.result()
        assertEquals(fuzz, preset.id)
        assertEquals("FUZZZZZZ", preset.name)
        val fx = preset.effects.associateBy { it.module }
        assertEquals(9, fx.size)
        assertEquals(2, fx.getValue("FX2").parameters.first().address!!.block)
        assertEquals(7, fx.getValue("FX3").parameters.first().address!!.block)
        fx.forEach { (module, effect) ->
            assertEquals(effect.model, EffectCatalog.choices(module)[effect.modelCode])
        }
        assertArrayEquals(hex("f0 21 25 7f 4d 50 2d 32 12 10 01 00 01 00 46 f7"),
            Ampero.selectModel(1, 70))
        val bend = fx.getValue("FX1")
        assertTrue(bend.enabled)
        assertEquals("Bend Fuzz", bend.model)
        assertEquals(listOf("Fuzz" to 80, "Volume" to 40), bend.parameters.map { it.name to it.value })
        fun checkWrite(module: String, name: String, value: Int, expectedBody: String) {
            val address = fx.getValue(module).parameters.single { it.name == name }.address!!
            assertArrayEquals(hex("f0 21 25 7f 4d 50 2d 32 12 $expectedBody f7"),
                Ampero.setParameter(address, value))
        }
        checkWrite("AMP", "Presence", 45, "10 03 00 02 00 66 01 00 2d")
        checkWrite("FX1", "Fuzz", 70, "10 01 00 02 00 46 00 00 46")
        checkWrite("FX1", "Volume", 35, "10 01 00 02 00 46 01 00 23")
        assertEquals("Fryman HB+", fx["AMP"]!!.model)
        assertTrue(fx["AMP"]!!.enabled)
        assertEquals(33, fx["AMP"]!!.parameters.single { it.name == "Gain" }.value)
        assertEquals(false, fx["FX2"]!!.enabled)
        fun control(block: String, name: String) = fx.getValue(block).parameters.single { it.name == name }
        assertEquals(4, control("NR", "Threshold").address!!.block)
        assertEquals(listOf("I", "II"), control("NR", "Mode").scale.options)
        assertEquals("Dyn421", control("CAB", "Mic Type").scale.label(3))
        assertEquals("-9", control("EQ", "31Hz").scale.label(3))
        assertEquals("Ping Pong", fx.getValue("DLY").model)
        val time = control("DLY", "Time")
        assertEquals(480, time.value)
        assertEquals("500 мс", time.scale.label(480))
        assertEquals(3980, time.scale.maximum)
        assertEquals("Sync", time.scale.syncControl)
        checkWrite("DLY", "Time", 480, "10 08 00 02 00 07 02 03 60")
        assertEquals(9, control("RVB", "Mix").address!!.block)
        assertEquals("Hall", fx["RVB"]!!.model)
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

    @Test
    fun readsCurrentEditBufferUsingRecordedHandshake() {
        val lines = javaClass.getResource("/current_preset.txt")!!.readText().lines().filter { it.isNotBlank() }
        val outgoing = lines.filter { it.startsWith("OUT ") }.map { hex(it.substringAfter(' ')) }
        val incoming = lines.filter { it.startsWith("IN ") }.map { Ampero.parse(hex(it.substringAfter(' ')))!! }
        val header = incoming.first()
        val id = PresetId(header[4], header[5])
        val download = PresetDownload(id, live = true)
        val commands = listOf(Ampero.currentReadRequest(), Ampero.currentStart(id)) +
            incoming.drop(1).mapNotNull(download::accept).map { Ampero.currentAck(id, it) }
        assertEquals(outgoing.size, commands.size)
        outgoing.zip(commands).forEach { (expected, actual) -> assertArrayEquals(expected, actual) }
        assertTrue(download.finished)
        assertEquals(9, download.result().effects.size)
    }

    private fun hex(s: String): ByteArray =
        s.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()
}
