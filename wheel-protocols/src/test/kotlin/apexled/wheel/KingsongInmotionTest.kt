package apexled.wheel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun String.hx(): ByteArray = chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Vetores REAIS dos testes do WheelLog (KingsongAdapterTest, InmotionAdapterV2Test, InmotionAdapterTest). */
class KingsongInmotionTest {

    // ---------------- Kingsong ----------------

    @Test fun kingsongRealS18() {
        val d = KingsongDecoder()
        assertTrue(d.feed("aa554b532d5331382d30323035000000bb1484fd".hx(), 0).isEmpty()) // nome
        assertEquals("KS-S18", d.model)
        d.feed("aa55000000000000000000000000400cf5145a5a".hx(), 1) // PWM 12 %
        val t = d.feed("aa556919030200009f36d700140500e0a9145a5a".hx(), 2).single()
        assertEquals(5.15f, t.speedKmh, 0.001f)
        assertEquals(65.05f, t.voltage, 0.001f)
        assertEquals(2.15f, t.currentA!!, 0.001f)
        assertEquals(13f, t.temperatureC!!, 0.001f)
        assertEquals(12f, t.batteryPercent!!, 0.001f)
        assertEquals(0.12f, t.pwm!!, 0.0001f)
    }

    @Test fun kingsongRequestsNameUntilKnownAtMostOncePerSecond() {
        val d = KingsongDecoder()
        val first = d.poll(0)
        assertNotNull(first)
        assertEquals(0x9B, first[16].toInt() and 0xFF)
        assertNull(d.poll(500))
        assertNotNull(d.poll(1000))
        d.feed("aa554b532d5331382d30323035000000bb1484fd".hx(), 1100)
        assertNull(d.poll(5000))
    }

    @Test fun autoDecoderPicksKingsongAndDoesNotPollBeforeDeciding() {
        val a = AutoDecoder()
        assertNull(a.poll(0))
        a.feed("aa556919030200009f36d700140500e0a9145a5a".hx(), 0)
        assertNotNull(a.poll(10)) // agora sabe que é Kingsong e pede o nome
    }

    // ---------------- Inmotion V2 ----------------

    private fun v2(vararg frames: String): Pair<InmotionV2Decoder, List<apexled.core.Telemetry>> {
        val d = InmotionV2Decoder()
        val out = frames.flatMapIndexed { i, f -> d.feed(f.hx(), i.toLong()) }
        return d to out
    }

    @Test fun inmotionV11OldFirmware() {
        val (d, out) = v2(
            "AAAA110882010206010201009C",
            "AAAA111D820622080004030F000602214000010110000602230D00010107000001F3",
            "AAAA143184E61EEB0561094A11AE04A004DF01402958CBB000CE004A010000D4FF7C15641900000000492B00000000000000000000C6",
        )
        assertEquals("Inmotion V11", d.model)
        val t = out.single()
        assertEquals(24.01f, t.speedKmh, 0.001f)
        assertEquals(79.10f, t.voltage, 0.001f)
        assertEquals(15.15f, t.currentA!!, 0.001f)
        assertEquals(88f, t.batteryPercent!!, 0.001f)
        assertEquals(27f, t.temperatureC!!, 0.001f)
    }

    @Test fun inmotionV11EscapedBytes() {
        val (_, out) = v2(
            "AAAA110882010206010201009C",
            "aaaa1431843020a5a50068025207870080009400882c5fc4b000d7001000f4ff2b037c1564190000d9d9492b00000000000000000000a5a5",
        )
        val t = out.single()
        assertEquals(6.16f, t.speedKmh, 0.001f)
        assertEquals(82.40f, t.voltage, 0.001f)
        assertEquals(1.65f, t.currentA!!, 0.001f)
        assertEquals(95f, t.batteryPercent!!, 0.001f)
    }

    @Test fun inmotionV12() {
        val (d, out) = v2(
            "aaaa110882010207010103009c",
            "aaaa144384cd26090000000e00040000000000000000000000eafb000062009d2450463b1b581b000000000000cdce00ced1d0b03d2828000000004900000000000000000000008c",
        )
        assertEquals("Inmotion V12 HS", d.model)
        val t = out.single()
        assertEquals(0f, t.speedKmh)
        assertEquals(99.33f, t.voltage, 0.001f)
        assertEquals(0.09f, t.currentA!!, 0.001f)
        assertEquals(29f, t.temperatureC!!, 0.001f)
    }

    @Test fun inmotionV13() {
        val (d, out) = v2(
            "aaaa1108820102080101010091",
            "aaaa145984092f3807000036003735000025130f27b108111d4203b00664fee703050000000000f225e225204e28233421401f401f204e401f709400000000cdccc9d1b0d10000b0286400000000004910000000000000001800000000b3",
        )
        assertEquals("Inmotion V13", d.model)
        val t = out.single()
        assertEquals(136.23f, t.speedKmh, 0.001f)
        assertEquals(120.41f, t.voltage, 0.001f)
        assertEquals(18.48f, t.currentA!!, 0.001f)
        assertEquals(97f, t.batteryPercent!!, 0.6f)
        assertEquals(29f, t.temperatureC!!, 0.001f)
        assertTrue(t.pwm!! in 0f..1.5f)
    }

    @Test fun inmotionV14AndV9() {
        val (_, a) = v2(
            "aaaa1108820102090201010093",
            "aaaa1459847c334000000000002c0800009900430866004f002700efff6400bfff5e0000000000a5aa26a4261027581b581b401f401f401f401fb88800000000cdcfcad0b0d00000b0cc640000000000491000000000000000000000000064",
        )
        assertEquals(20.92f, a.single().speedKmh, 0.001f)
        assertEquals(131.80f, a.single().voltage, 0.001f)
        val (_, b) = v2(
            "aaaa11088201020c0101010095",
            "aaaa1457843e1e0c000000000000000000afffc30000000000ffffd7fe000000000600000000009a17191670178510a00f401f401fa00fa00f983a00000000cdc900ceb0cec8ceb03a6400000000004900000000000000000000003f",
        )
        assertEquals(77.42f, b.single().voltage, 0.001f)
        assertEquals(0.12f, b.single().currentA!!, 0.001f)
    }

    @Test fun inmotionV2PollAsksCarTypeThenRealtime() {
        val d = InmotionV2Decoder()
        assertEquals("aaaa11020201", d.poll(0)!!.joinToString("") { "%02x".format(it) }.take(12))
        assertNull(d.poll(100))
        d.feed("aaaa1108820102080101010091".hx(), 200) // V13
        val rt = d.poll(300)!!
        assertEquals(0x14, rt[2].toInt()); assertEquals(0x04, rt[4].toInt())
    }

    // ---------------- Inmotion V1 ----------------

    @Test fun inmotionV5fRealFrames() {
        val d = InmotionV1Decoder()
        val slow = listOf(
            "AAAA1401A5550F7C000000B4720020FE0001001B", "0076BA5C28711200000000000000000100000000",
            "000000FA010301FA0103010402020100000000C2", "040001C2040001900302010000000000000000A8",
            "6100000010000000000000000000000000000000", "0000000100000000000000000000000000000000",
            "0000000200000500000000000000000000000004", "020301E35555",
        )
        val fast = listOf(
            "AAAA1301A5550F60000000B4720020FE000100FF", "3F00003A18DEFF5D01000029F0FFFF29F0FFFFEC",
            "FFFFFF15200000000000001A1A00000000000000", "0000001CE3130000000000000026061A03D20721",
            "0000006F0100006F010000F7010000420C00002B", "110000070000000000000000000000265555",
        )
        slow.forEach { d.feed(it.hx(), 0) }
        assertEquals("Inmotion V5F", d.model)
        val t = fast.flatMap { d.feed(it.hx(), 1) }.single()
        assertEquals(3.82f, t.speedKmh, 0.01f)
        assertEquals(82.13f, t.voltage, 0.001f)
        assertEquals(-0.2f, t.currentA!!, 0.001f)
        assertEquals(26f, t.temperatureC!!, 0.001f)
        assertTrue(t.pwmEstimated)
    }

    @Test fun inmotionV1SendsPasswordFirstInTwentyBytePieces() {
        val d = InmotionV1Decoder(password = "123456")
        assertEquals(20, d.writeChunk)
        val p = d.poll(0)!!
        assertEquals(0xAA, p[0].toInt() and 0xFF); assertEquals(0xAA, p[1].toInt() and 0xFF)
        assertEquals(0x55, p.last().toInt() and 0xFF)
        assertTrue(String(p, Charsets.US_ASCII).contains("123456"))
    }
}
