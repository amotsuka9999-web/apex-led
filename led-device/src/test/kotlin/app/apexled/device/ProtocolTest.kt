package app.apexled.device

import app.apexled.device.protocol.ProtocolRegistry
import app.apexled.device.protocol.Sp61xPwmProtocol
import app.apexled.device.protocol.Sp621eProtocol
import app.apexled.device.protocol.Sp63xProtocol
import app.apexled.device.sim.Sp61xPwmFirmware
import app.apexled.device.sim.Sp621eFirmware
import app.apexled.device.sim.Sp63xFirmware
import app.apexled.pattern.LedFrame
import app.apexled.pattern.Rgb
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private fun List<ByteArray>?.hex(): List<String> =
    this!!.map { payload -> payload.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } }

class Sp63xProtocolTest {

    private val protocol = Sp63xProtocol

    @Test
    fun `message layout is header opcode key 01 00 length data`() {
        assertContentEquals(bytes(0x53, 0x50, 0x00, 0x01, 0x00, 0x01, 0x01), Sp63xProtocol.message(0x50, 1))
        assertContentEquals(bytes(0x53, 0x02, 0x00, 0x01, 0x00, 0x00), Sp63xProtocol.message(0x02))
    }

    @Test
    fun `frame is a static colour with level`() {
        val frame = LedFrame(Rgb(0x11, 0x22, 0xFF), 1.0)
        assertEquals(listOf("53 52 00 01 00 04 11 22 FF FF"), protocol.frame(frame).hex())
        assertEquals(listOf("53 52 00 01 00 04 FF 00 00 80"), protocol.frame(LedFrame(Rgb.RED, 0.5)).hex())
        assertEquals(listOf("53 52 00 01 00 04 FF 00 00 00"), protocol.frame(LedFrame(Rgb.RED, 0.0)).hex())
    }

    @Test
    fun `entering direct colour powers on and selects the solid static effect`() {
        assertEquals(
            listOf("53 50 00 01 00 01 01", "53 53 00 01 00 02 01 01"),
            protocol.enterDirectColor().hex(),
        )
    }

    @Test
    fun `board commands encode to the documented opcodes`() {
        assertEquals(listOf("53 50 00 01 00 01 00"), protocol.encode(BoardCommand.Power(false)).hex())
        assertEquals(listOf("53 51 00 01 00 02 00 7F"), protocol.encode(BoardCommand.Brightness(127)).hex())
        assertEquals(
            listOf("53 53 00 01 00 02 03 2A"),
            protocol.encode(BoardCommand.SelectEffect(LightMode.DYNAMIC, 0x2A)).hex(),
        )
        assertEquals(
            listOf("53 53 00 01 00 02 05 03"),
            protocol.encode(BoardCommand.SelectEffect(LightMode.MUSIC, 3)).hex(),
        )
        assertEquals(listOf("53 54 00 01 00 01 0A"), protocol.encode(BoardCommand.EffectSpeed(10)).hex())
        assertEquals(listOf("53 55 00 01 00 01 96"), protocol.encode(BoardCommand.EffectLength(150)).hex())
        assertEquals(listOf("53 56 00 01 00 01 01"), protocol.encode(BoardCommand.EffectDirection(true)).hex())
        assertEquals(listOf("53 57 00 01 00 03 00 FF 00"), protocol.encode(BoardCommand.EffectColor(Rgb.GREEN)).hex())
        assertEquals(listOf("53 58 00 01 00 01 01"), protocol.encode(BoardCommand.EffectLoop(true)).hex())
        assertEquals(
            listOf("53 59 00 01 00 01 02"),
            protocol.encode(BoardCommand.SelectAudioInput(AudioInput.EXTERNAL_MIC)).hex(),
        )
        assertEquals(listOf("53 5A 00 01 00 01 10"), protocol.encode(BoardCommand.Sensitivity(16)).hex())
        assertEquals(listOf("53 5D 00 01 00 01 00"), protocol.encode(BoardCommand.EffectPlay(false)).hex())
        assertEquals(
            listOf("53 0B 00 01 00 01 02"),
            protocol.encode(BoardCommand.SetPowerOnState(PowerOnState.LAST_STATE)).hex(),
        )
        assertEquals(
            listOf("53 08 00 01 00 05 01 03 02 01 2C"),
            protocol.encode(BoardCommand.SetOnOffAnimation(OnOffAnimation.GRADIENT, OnOffSpeed.MEDIUM, 300)).hex(),
        )
        assertEquals(listOf("53 02 00 01 00 01 01"), protocol.encode(BoardCommand.QueryStatus).hex())
    }

    @Test
    fun `static colour command selects the mode before setting the colour`() {
        assertEquals(
            listOf("53 53 00 01 00 02 01 01", "53 52 00 01 00 04 00 00 FF 40"),
            protocol.encode(BoardCommand.StaticColor(Rgb.BLUE, 64)).hex(),
        )
    }

    @Test
    fun `status round trips through the simulated firmware`() {
        val firmware = Sp63xFirmware(firmwareVersion = "V2.3.07")
        protocol.enterDirectColor().forEach { firmware.receive(it) }
        protocol.frame(LedFrame(Rgb(10, 200, 30), 0.5)).forEach { firmware.receive(it) }
        protocol.encode(BoardCommand.EffectSpeed(7))!!.forEach { firmware.receive(it) }

        val reply = firmware.receive(protocol.encode(BoardCommand.QueryStatus)!!.single()).single()
        val status = assertNotNull(protocol.parseStatus(reply))

        assertTrue(status.power)
        assertEquals(LightMode.STATIC, status.mode)
        assertEquals(Sp63xProtocol.EFFECT_SOLID, status.effect)
        assertEquals(Rgb(10, 200, 30), status.color)
        assertEquals(128, status.level)
        assertEquals(7, status.effectSpeed)
        assertEquals("V2.3.07", status.firmware)
        assertEquals(PowerOnState.LAST_STATE, status.powerOnState)
        assertEquals(0, firmware.rejected)
    }

    @Test
    fun `malformed or foreign notifications are not a status`() {
        val firmware = Sp63xFirmware()
        val valid = firmware.receive(protocol.encode(BoardCommand.QueryStatus)!!.single()).single()

        assertNull(protocol.parseStatus(ByteArray(0)))
        assertNull(protocol.parseStatus(valid.copyOf(valid.size - 1)), "truncated")
        assertNull(protocol.parseStatus(valid.copyOf().also { it[0] = 0x54 }), "wrong header")
        assertNull(protocol.parseStatus(valid.copyOf().also { it[1] = 0x50 }), "not a status message")
        assertNull(protocol.parseStatus(valid.copyOf().also { it[5] = 0x10 }), "length mismatch")
        assertNull(protocol.parseStatus(valid.copyOf().also { it[2] = 0x5A }), "obfuscated payload")
    }

    @Test
    fun `command arguments are range checked`() {
        assertFailsWith<IllegalArgumentException> { BoardCommand.Brightness(256) }
        assertFailsWith<IllegalArgumentException> { BoardCommand.EffectSpeed(0) }
        assertFailsWith<IllegalArgumentException> { BoardCommand.EffectLength(151) }
        assertFailsWith<IllegalArgumentException> { BoardCommand.Sensitivity(17) }
        assertFailsWith<IllegalArgumentException> { BoardCommand.StaticColor(Rgb.RED, -1) }
    }
}

class Sp61xPwmProtocolTest {

    private val protocol = Sp61xPwmProtocol

    @Test
    fun `frame is opcode length colour level`() {
        assertEquals(listOf("13 04 11 22 FF FF"), protocol.frame(LedFrame(Rgb(0x11, 0x22, 0xFF), 1.0)).hex())
    }

    @Test
    fun `entering direct colour powers on and selects the solid effect`() {
        assertEquals(listOf("0F 01 01", "15 01 63"), protocol.enterDirectColor().hex())
    }

    @Test
    fun `basic commands`() {
        assertEquals(listOf("0F 01 00"), protocol.encode(BoardCommand.Power(false)).hex())
        assertEquals(listOf("12 01 80"), protocol.encode(BoardCommand.Brightness(128)).hex())
        assertEquals(listOf("14 01 05"), protocol.encode(BoardCommand.EffectSpeed(5)).hex())
        assertEquals(listOf("15 01 07"), protocol.encode(BoardCommand.SelectEffect(LightMode.DYNAMIC, 7)).hex())
        assertEquals(listOf("1D 00"), protocol.encode(BoardCommand.QueryStatus).hex())
    }

    @Test
    fun `functions this family lacks are reported as unsupported`() {
        assertNull(protocol.encode(BoardCommand.EffectLength(10)))
        assertNull(protocol.encode(BoardCommand.SetPowerOnState(PowerOnState.ON)))
        assertNull(protocol.encode(BoardCommand.SetOnOffAnimation(OnOffAnimation.STARS, OnOffSpeed.FAST, 60)))
    }

    @Test
    fun `simulated firmware follows frames`() {
        val firmware = Sp61xPwmFirmware()
        protocol.enterDirectColor().forEach { firmware.receive(it) }
        protocol.frame(LedFrame(Rgb.CYAN, 0.25)).forEach { firmware.receive(it) }
        assertTrue(firmware.light.showsSolidColor)
        assertEquals(Rgb.CYAN, firmware.light.color)
        assertEquals(64, firmware.light.level)
        assertEquals(0, firmware.rejected)
    }
}

class Sp621eProtocolTest {

    private val protocol = Sp621eProtocol

    @Test
    fun `messages follow A0 opcode length data`() {
        assertEquals(listOf("A0 62 01 01", "A0 63 01 BE"), protocol.enterDirectColor().hex())
        assertEquals(listOf("A0 69 04 FF 00 00 80"), protocol.frame(LedFrame(Rgb.RED, 0.5)).hex())
        assertEquals(listOf("A0 62 01 00"), protocol.encode(BoardCommand.Power(false)).hex())
        assertEquals(listOf("A0 66 01 FF"), protocol.encode(BoardCommand.Brightness(255)).hex())
        assertEquals(listOf("A0 67 01 0A"), protocol.encode(BoardCommand.EffectSpeed(10)).hex())
        assertEquals(listOf("A0 68 01 96"), protocol.encode(BoardCommand.EffectLength(150)).hex())
        assertEquals(listOf("A0 63 01 07"), protocol.encode(BoardCommand.SelectEffect(LightMode.DYNAMIC, 7)).hex())
        assertNull(protocol.encode(BoardCommand.SetPowerOnState(PowerOnState.ON)))
        assertNull(protocol.encode(BoardCommand.QueryStatus))
    }

    @Test
    fun `simulated firmware follows frames`() {
        val firmware = Sp621eFirmware()
        protocol.enterDirectColor().forEach { firmware.receive(it) }
        protocol.frame(LedFrame(Rgb.YELLOW, 1.0)).forEach { firmware.receive(it) }
        assertTrue(firmware.light.showsSolidColor)
        assertEquals(Rgb.YELLOW, firmware.light.color)
        assertEquals(0, firmware.rejected)
    }
}

class ProtocolRegistryTest {

    @Test
    fun `protocols are found by id`() {
        val registry = ProtocolRegistry()
        assertEquals(Sp621eProtocol, registry["sp621e"])
        assertEquals(Sp621eProtocol, registry.all.first(), "the specified board is the default choice")
        assertEquals(Sp63xProtocol, registry["sp63x"])
        assertEquals(Sp61xPwmProtocol, registry["sp61x-pwm"])
        assertNull(registry["nope"])
        assertFailsWith<IllegalArgumentException> { registry.require("nope") }
    }

    @Test
    fun `duplicate ids are rejected`() {
        assertFailsWith<IllegalArgumentException> { ProtocolRegistry(listOf(Sp63xProtocol, Sp63xProtocol)) }
    }
}
