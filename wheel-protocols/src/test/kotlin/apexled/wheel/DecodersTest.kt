package apexled.wheel

import apexled.core.PwmCalibration
import java.util.zip.CRC32
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun String.hex(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/**
 * Vetores marcados como REAL vêm dos testes do WheelLog (app/src/test/.../VeteranAdapterTest.kt e
 * GotwayAdapterTest.kt, commit f44e39e): são frames capturados de rodas de verdade. Vetores SINTÉTICOS
 * são montados aqui, só para exercitar campos que os capturados não cobrem (velocidade e PWM > 0).
 */
class DecodersTest {

    // ---------------- Veteran / Nosfet ----------------

    private val aeroChunks = listOf(
        "dc5a5c533124000007f700000a5900000002102b",
        "04ad0000015e00c8a8f60780000200c580c80000",
        "8080808080800228808064808010661066106610",
        "6610661066106510661067106710671066106610",
        "661066ce555677",
    ).map { it.hex() }

    @Test fun veteranRealNosfetAeroFrameSplitInFiveBlePackets() {
        val d = VeteranDecoder()
        val out = aeroChunks.flatMapIndexed { i, c -> d.feed(c, 10L * i) }
        assertEquals(1, out.size)
        val t = out.single()
        assertEquals(125.80f, t.voltage, 0.001f)
        assertEquals(0f, t.speedKmh)
        assertEquals(41.39f, t.temperatureC!!, 0.01f)
        assertEquals(43, d.protocolVersion)
        assertEquals("Nosfet Aero", d.model)
    }

    @Test fun veteranRealNosfetAeonFrame() {
        val chunks = listOf(
            "dc5a5c5337f000002342000025150000fff80a66",
            "04ae0000025807d0acd907c8ffe8015080c80000",
            "808080808080068080808080800f870f870f800f",
            "940f920f930f940f930f910f930f940f950f950f",
            "960f91d94e297a",
        ).map { it.hex() }
        val d = VeteranDecoder()
        val out = chunks.flatMapIndexed { i, c -> d.feed(c, 10L * i) }
        assertEquals(1, out.size)
        assertEquals(143.20f, out.single().voltage, 0.001f)   // mesmo valor que o teste do WheelLog
        assertEquals(26.62f, out.single().temperatureC!!, 0.01f)
        assertEquals("Nosfet Aeon", d.model)
    }

    private fun apexFrame(
        voltage01V: Int = 14980,
        speed01Kmh: Int = 400,
        phase01A: Int = 250,
        temp001C: Int = 5500,
        hwPwm: Int = 7200,
        version: Int = 42254,
        corruptCrc: Boolean = false,
    ): ByteArray {
        val len = 83
        val f = ByteArray(len + 4)
        f[0] = 0xDC.toByte(); f[1] = 0x5A; f[2] = 0x5C; f[3] = len.toByte()
        fun put16(i: Int, v: Int) { f[i] = (v shr 8).toByte(); f[i + 1] = v.toByte() }
        put16(4, voltage01V); put16(6, speed01Kmh); put16(16, phase01A); put16(18, temp001C)
        put16(28, version); put16(34, hwPwm)
        val crc = CRC32().apply { update(f, 0, len) }.value
        f[len] = (crc shr 24).toByte(); f[len + 1] = (crc shr 16).toByte(); f[len + 2] = (crc shr 8).toByte()
        f[len + 3] = (crc xor if (corruptCrc) 1L else 0L).toByte()
        return f
    }

    @Test fun veteranSyntheticApexUsesHardwarePwm() {
        val d = VeteranDecoder()
        val t = d.feed(apexFrame(), 1000).single()
        assertEquals(149.80f, t.voltage, 0.001f)
        assertEquals(40.0f, t.speedKmh, 0.001f)
        assertEquals(0.72f, t.pwm!!, 0.0001f)
        assertEquals(false, t.pwmEstimated)
        assertEquals(55.0f, t.temperatureC!!, 0.001f)
        assertEquals(0.72f * 25.0f, t.currentA!!, 0.001f)
        assertEquals("Nosfet Apex", d.model)
        assertEquals(1000L, t.timestampMs)
    }

    @Test fun veteranEstimatesPwmWhenHardwarePwmDisabled() {
        val cal = PwmCalibration(100f, 150f, 1.0f)
        val d = VeteranDecoder(VeteranConfig(calibration = cal, preferHardwarePwm = false))
        val t = d.feed(apexFrame(voltage01V = 15000, speed01Kmh = 500), 0).single()
        assertEquals(0.5f, t.pwm!!, 0.001f)
        assertTrue(t.pwmEstimated)
    }

    @Test fun veteranRejectsBadCrcAndRecovers() {
        val d = VeteranDecoder()
        assertTrue(d.feed(apexFrame(corruptCrc = true), 0).isEmpty())
        assertEquals(1, d.feed(apexFrame(), 50).size)
    }

    @Test fun veteranResyncsAfterGarbageAndHandlesTwoFramesInOnePacket() {
        val d = VeteranDecoder()
        val noise = byteArrayOf(0x01, 0x02, 0xDC.toByte(), 0x00, 0x5A)
        val out = d.feed(noise + apexFrame(speed01Kmh = 100) + apexFrame(speed01Kmh = 200), 0)
        assertEquals(listOf(10.0f, 20.0f), out.map { it.speedKmh })
    }

    @Test fun veteranHandlesEveryPossibleSplitPoint() {
        val frame = apexFrame()
        for (cut in 1 until frame.size) {
            val d = VeteranDecoder()
            val out = d.feed(frame.copyOfRange(0, cut), 0) + d.feed(frame.copyOfRange(cut, frame.size), 5)
            assertEquals(1, out.size, "corte em $cut")
        }
    }

    @Test fun veteranDropsStaleHalfFrame() {
        val d = VeteranDecoder()
        val f = apexFrame()
        d.feed(f.copyOfRange(0, 40), 0)
        // 5 s depois chega um frame inteiro: o pedaço velho não deve contaminá-lo
        assertEquals(1, d.feed(f, 5000).size)
    }

    @Test fun veteranIgnoresNegativeSpeedSign() {
        val t = VeteranDecoder().feed(apexFrame(speed01Kmh = (-150) and 0xFFFF), 0).single()
        assertEquals(15.0f, t.speedKmh, 0.001f)
    }

    @Test fun veteranIgnoresRandomBytes() {
        val d = VeteranDecoder()
        val rnd = java.util.Random(42)
        repeat(200) { d.feed(ByteArray(20).also { rnd.nextBytes(it) }, it * 10L) }
        assertEquals(1, d.feed(apexFrame(), 99999).size)
    }

    // ---------------- Gotway / Begode ----------------

    // REAL (placa 2020, 16S): 65,93 V, temperatura IMU 24 °C, corrente de fase 1,4 A, parada.
    private val gotway2020 = "55AA19C1000000000000008CF0000001FFF800185A5A5A5A".hex()

    @Test fun gotwayRealFrameParsedFromAThreeWaySplit() {
        val d = GotwayDecoder()
        val out = d.feed(gotway2020.copyOfRange(0, 7), 0) + d.feed(gotway2020.copyOfRange(7, 20), 1) +
            d.feed(gotway2020.copyOfRange(20, 24), 2)
        val t = out.single()
        assertEquals(65.93f, t.voltage, 0.001f)
        assertEquals(0f, t.speedKmh)
        assertEquals(24.48f, t.temperatureC!!, 0.05f)
        assertTrue(t.pwmEstimated)
        assertEquals(0f, t.pwm!!, 0.0001f)
    }

    // REAL (placa nova, tensão enviada pela metade → voltageScale 2): 120,10 V, 48,1 km/h, fase −11,8 A.
    private val gotwayNew = "55aa17750538007602eefb64f494148100090018" + "5a5a5a5a"

    @Test fun gotwayRealMovingFrameWithVoltageScale() {
        val d = GotwayDecoder(GotwayConfig(voltageScale = 2f, calibration = PwmCalibration(100f, 120f, 1f)))
        val t = d.feed(gotwayNew.hex(), 0).single()
        assertEquals(120.10f, t.voltage, 0.001f)
        assertEquals(48.1f, t.speedKmh, 0.05f)
        assertEquals(27.93f, t.temperatureC!!, 0.05f)
        // PWM estimado = 48,1 / (100/120 × 120,1 × 1) ≈ 0,4806
        assertEquals(0.4806f, t.pwm!!, 0.005f)
        // corrente estimada = PWM × |fase| = 0,4806 × 11,8
        assertEquals(0.4806f * 11.8f, t.currentA!!, 0.1f)
    }

    private fun gotwayFrame(type: Int, setup: (ByteArray) -> Unit): ByteArray {
        val f = ByteArray(24)
        f[0] = 0x55; f[1] = 0xAA.toByte(); f[18] = type.toByte(); f[19] = 0x18
        for (i in 20..23) f[i] = 0x5A
        setup(f)
        return f
    }

    private fun ByteArray.put16(i: Int, v: Int) { this[i] = (v shr 8).toByte(); this[i + 1] = v.toByte() }

    @Test fun gotwayUsesHardwarePwmFromFrame07() {
        val d = GotwayDecoder()
        d.feed(gotwayFrame(0x07) { it.put16(2, -1250); it.put16(6, 38); it.put16(8, 64) }, 0)
        val a = gotwayFrame(0x00) { it.put16(2, 6700); it.put16(4, (30f / 0.036f).toInt()) }
        val t = d.feed(a, 10).single()
        assertEquals(0.64f, t.pwm!!, 0.0001f)
        assertEquals(false, t.pwmEstimated)
        assertEquals(12.5f, t.currentA!!, 0.001f)       // bateria: sinal invertido, 0,01 A
        assertEquals(38f, t.temperatureC!!, 0.001f)
        assertEquals(30f, t.speedKmh, 0.1f)
    }

    @Test fun gotwayFrame07AloneProducesNoTelemetryButOnlyFrameADoes() {
        val d = GotwayDecoder()
        assertTrue(d.feed(gotwayFrame(0x07) { it.put16(8, 10) }, 0).isEmpty())
        assertTrue(d.feed(gotwayFrame(0x04) { }, 1).isEmpty())
    }

    @Test fun gotwaySkipsGarbageBeforeAndBetweenFrames() {
        val d = GotwayDecoder()
        val a = gotwayFrame(0x00) { it.put16(2, 6700) }
        val junk = byteArrayOf(0x55, 0xAA.toByte(), 0x5A, 0x55, 0xAA.toByte(), 0x12)
        assertEquals(2, d.feed(junk + a + byteArrayOf(0x01, 0x02) + a, 0).size)
    }

    @Test fun gotwayHandlesEverySplitPoint() {
        val a = gotwayFrame(0x00) { it.put16(2, 6700) }
        for (cut in 1 until a.size) {
            val d = GotwayDecoder()
            val out = d.feed(a.copyOfRange(0, cut), 0) + d.feed(a.copyOfRange(cut, a.size), 3)
            assertEquals(1, out.size, "corte em $cut")
        }
    }

    @Test fun gotwayRejectsFrameWithBadFooter() {
        val a = gotwayFrame(0x00) { it.put16(2, 6700); it[22] = 0x00 }
        assertTrue(GotwayDecoder().feed(a, 0).isEmpty())
    }

    @Test fun gotwayFrameAHardwarePwmOnlyForCustomFirmware() {
        val a = gotwayFrame(0x00) { it.put16(2, 6700); it.put16(14, 800) }
        assertEquals(0.8f, GotwayDecoder(GotwayConfig(frameAPwmIsHardware = true)).feed(a, 0).single().pwm!!, 0.0001f)
        assertEquals(0f, GotwayDecoder().feed(a, 0).single().pwm!!, 0.0001f)   // padrão: estimado (parado → 0)
    }

    // ---------------- Auto ----------------

    @Test fun autoDecoderPicksProtocolByContent() {
        val vet = AutoDecoder()
        assertEquals(1, vet.feed(apexFrame(), 0).size)
        assertEquals("Nosfet Apex", vet.model)
        val got = AutoDecoder()
        assertEquals(1, got.feed(gotway2020, 0).size)
        assertNull(got.model)
    }
}
