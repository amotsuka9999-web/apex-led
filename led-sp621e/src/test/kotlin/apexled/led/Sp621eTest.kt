package apexled.led

import apexled.core.ColorOrder
import apexled.core.LedCommand
import apexled.core.LedFrame
import apexled.core.LedLinkState
import apexled.core.LedState
import apexled.core.Rgb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun hex(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
private fun ByteArray.h() = joinToString(" ") { "%02X".format(it) }

/** Formato dos pacotes: os bytes esperados vêm do uniled (banlanx2.py). NÃO foram confirmados em hardware. */
class Sp621ePacketsTest {
    @Test fun power() {
        assertContentEquals(hex(0xA0, 0x62, 0x01, 0x01), Sp621ePackets.power(true))
        assertContentEquals(hex(0xA0, 0x62, 0x01, 0x00), Sp621ePackets.power(false))
    }

    @Test fun solidColorWithLevel() {
        assertContentEquals(hex(0xA0, 0x69, 0x04, 0xFF, 0x80, 0x00, 0x64), Sp621ePackets.color(Rgb(255, 128, 0), 100))
    }

    @Test fun effectSelectionAndSolidEffectId() {
        assertContentEquals(hex(0xA0, 0x63, 0x01, 0xBE), Sp621ePackets.effect(Sp621ePackets.EFFECT_SOLID))
        assertContentEquals(hex(0xA0, 0x63, 0x01, 0x0B), Sp621ePackets.effect(0x0B))
    }

    @Test fun brightnessSpeedLengthAreClamped() {
        assertContentEquals(hex(0xA0, 0x66, 0x01, 0xFF), Sp621ePackets.brightness(999))
        assertContentEquals(hex(0xA0, 0x66, 0x01, 0x00), Sp621ePackets.brightness(-5))
        assertContentEquals(hex(0xA0, 0x67, 0x01, 0x0A), Sp621ePackets.speed(50))
        assertContentEquals(hex(0xA0, 0x67, 0x01, 0x01), Sp621ePackets.speed(0))
        assertContentEquals(hex(0xA0, 0x68, 0x01, 0x96), Sp621ePackets.length(500))
    }

    @Test fun chipOrderIndexesFollowPermutationsOfRgb() {
        assertContentEquals(hex(0xA0, 0x64, 0x01, 0x00), Sp621ePackets.chipOrder(ColorOrder.RGB))
        assertContentEquals(hex(0xA0, 0x64, 0x01, 0x02), Sp621ePackets.chipOrder(ColorOrder.GRB))
        assertContentEquals(hex(0xA0, 0x64, 0x01, 0x05), Sp621ePackets.chipOrder(ColorOrder.BGR))
    }

    @Test fun stateQueryAndLightMode() {
        assertContentEquals(hex(0xA0, 0x70, 0x00), Sp621ePackets.stateQuery())
        assertContentEquals(hex(0xA0, 0x6A, 0x01, 0x01), Sp621ePackets.lightMode(1))
    }

    @Test fun pixelCountAndIcTypeAreNotInventedWhileProtocolIsUnknown() {
        assertNull(Sp621ePackets.pixelCount(30))
        assertNull(Sp621ePackets.icType(3))
    }

    // ---- status ----

    // Exemplo de status comentado no uniled (SP621E): 23 bytes de dados. O cabeçalho "53 43 01 17 17" segue o
    // que o parser do uniled espera; não é uma captura própria.
    private val statusPayload = hex(0x01, 0x00, 0x0e, 0x02, 0x61, 0x0a, 0x1e, 0xff, 0x00, 0x00, 0x01, 0x10, 0x09, 0x04,
        0x0b, 0x14, 0x1a, 0x32, 0x37, 0x50, 0x53, 0x73, 0x00)

    @Test fun statusSinglePacket() {
        val pkt = hex(0x53, 0x43, 0x01, 0x17, 0x17) + statusPayload
        val s = Sp621eStatusParser().feed(pkt)!!
        assertTrue(s.power)
        assertEquals(0x0e, s.effect)
        assertEquals(ColorOrder.GRB, s.order)
        assertEquals(0x61, s.brightness)
        assertEquals(10, s.speed)
        assertEquals(30, s.length)
        assertEquals(Rgb(255, 0, 0), s.color)
    }

    @Test fun statusReassemblesTwoBlePackets() {
        val p = Sp621eStatusParser()
        val first = hex(0x53, 0x43, 0x01, 0x17, 0x0C) + statusPayload.copyOfRange(0, 12)
        val second = hex(0x53, 0x43, 0x02, 0x17, 0x0B) + statusPayload.copyOfRange(12, 23)
        assertNull(p.feed(first))
        assertNotNull(p.feed(second))
    }

    @Test fun statusIgnoresOutOfSequenceAndForeignNotifications() {
        val p = Sp621eStatusParser()
        assertNull(p.feed(hex(0x53, 0x43, 0x02, 0x17, 0x0B) + statusPayload.copyOfRange(12, 23)))
        assertNull(p.feed(hex(0x01, 0x02, 0x03)))
        assertNull(p.feed(hex()))
    }

    // ---- efeitos ----

    @Test fun effectCatalogCoversEveryIdFrom1To8EExactlyOnce() {
        val ids = Sp621eEffects.all.map { it.id }
        assertEquals((0x01..0x8E).toList(), ids.filter { it != Sp621ePackets.EFFECT_SOLID })
        assertEquals(1, ids.count { it == Sp621ePackets.EFFECT_SOLID })
        assertEquals("Arco-íris", Sp621eEffects.find(0x01)!!.name)
        assertEquals("Gradiente", Sp621eEffects.find(0x8E)!!.name)
        assertEquals("Cometa vermelho", Sp621eEffects.find(0x0B)!!.name)
        assertEquals("Respiração vermelho", Sp621eEffects.find(0x5E)!!.name)
    }
}

/** Transporte falso com relógio virtual para medir a taxa de escrita. */
private class FakeTransport(private val scope: TestScope) : LedTransport {
    val linkFlow = MutableStateFlow(LedLinkState.DISCONNECTED)
    override val link: StateFlow<LedLinkState> = linkFlow
    override val deviceName = MutableStateFlow<String?>("SP621E-TEST")
    val notif = MutableSharedFlow<ByteArray>(extraBufferCapacity = 8)
    override val notifications: SharedFlow<ByteArray> = notif
    val writes = ArrayList<Pair<Long, String>>()
    var failNext = false
    override suspend fun write(data: ByteArray): Boolean {
        if (failNext) { failNext = false; return false }
        writes += scope.currentTime to data.h()
        return true
    }
    fun packets() = writes.map { it.second }
}

@OptIn(ExperimentalCoroutinesApi::class)
class Sp621eControllerTest {

    private fun TestScope.setup(connected: Boolean = true): Pair<Sp621eController, FakeTransport> {
        val t = FakeTransport(this)
        val c = Sp621eController(backgroundScope, t, LedState(power = true))
        if (connected) { t.linkFlow.value = LedLinkState.CONNECTED; runCurrent() }
        return c to t
    }

    @Test fun fullSyncOnConnectThenOnlyChanges() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        // consulta de estado + power-on já era o espelho desconhecido → power, efeito sólido, cor
        assertEquals("A0 70 00", t.packets().first())
        assertTrue(t.packets().contains("A0 62 01 01"))
        assertTrue(t.packets().contains("A0 63 01 BE"))
        assertTrue(t.packets().any { it.startsWith("A0 69 04") })
        val before = t.writes.size
        advanceTimeBy(2000)
        assertEquals(before, t.writes.size, "sem mudança não deve escrever nada")
        c.send(LedCommand.Solid(Rgb(255, 0, 0), 200))
        advanceTimeBy(500)
        assertEquals("A0 69 04 FF 00 00 C8", t.packets().last())
        assertEquals(before + 1, t.writes.size)
    }

    @Test fun doesNotResendIdenticalFrames() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        val f = LedFrame(Rgb(0, 220, 60), 255)
        c.applyFrame(f); advanceTimeBy(300)
        val n = t.writes.size
        repeat(20) { c.applyFrame(f); advanceTimeBy(100) }
        assertEquals(n, t.writes.size)
    }

    @Test fun rateIsLimitedToTenFlushesPerSecondAndBurstsAreCoalesced() = runTest {
        val (c, t) = setup()
        advanceTimeBy(1000)
        val base = t.writes.size
        val start = currentTime
        // 100 quadros diferentes em 1 s (a cada 10 ms)
        repeat(100) { i ->
            c.applyFrame(LedFrame(Rgb(i, 255 - i, 0), 255))
            advanceTimeBy(10)
        }
        advanceTimeBy(200)
        val sent = t.writes.drop(base)
        assertTrue(sent.size <= 12, "escreveu demais: ${sent.size}")
        assertTrue(sent.size >= 5)
        // espaçamento mínimo entre escritas consecutivas
        sent.zipWithNext().forEach { (a, b) -> assertTrue(b.first - a.first >= 100 - 1, "intervalo ${b.first - a.first} ms") }
        // o último valor enviado é o último pedido
        assertEquals("A0 69 04 63 9C 00 FF", sent.last().second)
        assertTrue(currentTime - start >= 1000)
    }

    @Test fun reconnectResyncsEverything() = runTest {
        val (c, t) = setup()
        c.send(LedCommand.Solid(Rgb(10, 20, 30), 255))
        advanceTimeBy(500)
        t.linkFlow.value = LedLinkState.RECONNECTING; runCurrent()
        advanceTimeBy(1000)
        val mark = t.writes.size
        t.linkFlow.value = LedLinkState.CONNECTED
        advanceTimeBy(500)
        val resync = t.packets().drop(mark)
        assertTrue(resync.contains("A0 62 01 01"))
        assertTrue(resync.contains("A0 69 04 0A 14 1E FF"))
    }

    @Test fun nothingIsWrittenWhileDisconnectedAndLatestStateIsSentOnConnect() = runTest {
        val (c, t) = setup(connected = false)
        c.send(LedCommand.Solid(Rgb(1, 2, 3), 255))
        c.send(LedCommand.Solid(Rgb(4, 5, 6), 255))
        advanceTimeBy(1000)
        assertEquals(0, t.writes.size)
        t.linkFlow.value = LedLinkState.CONNECTED
        advanceTimeBy(500)
        assertTrue(t.packets().contains("A0 69 04 04 05 06 FF"))
        assertTrue(t.packets().none { it.contains("01 02 03") })
    }

    @Test fun failedWriteTriggersFullResync() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        t.failNext = true
        c.send(LedCommand.Solid(Rgb(9, 9, 9), 255))
        advanceTimeBy(1000)
        // depois da falha reenviou tudo (inclui power e efeito)
        assertTrue(t.packets().takeLast(4).any { it == "A0 62 01 01" })
        assertEquals("A0 69 04 09 09 09 FF", t.packets().last())
    }

    @Test fun effectsSendSpeedLengthAndBrightnessOnlyWhenChanged() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        c.send(LedCommand.Effect(0x0B)); c.send(LedCommand.EffectSpeed(7)); c.send(LedCommand.EffectLength(80))
        c.send(LedCommand.Brightness(128))
        advanceTimeBy(500)
        val p = t.packets().takeLast(4)
        assertEquals(listOf("A0 63 01 0B", "A0 67 01 07", "A0 68 01 50", "A0 66 01 80"), p)
        val n = t.writes.size
        c.send(LedCommand.EffectSpeed(7)); advanceTimeBy(500)
        assertEquals(n, t.writes.size)
    }

    @Test fun powerOffSendsOnlyPowerPacket() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        val n = t.writes.size
        c.send(LedCommand.Power(false)); c.send(LedCommand.Solid(Rgb(1, 1, 1), 5))
        advanceTimeBy(500)
        assertEquals(listOf("A0 62 01 00"), t.packets().drop(n))
    }

    @Test fun orderChangeIsOneShotAndNotPartOfAutoSync() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        assertTrue(t.packets().none { it.startsWith("A0 64") })
        c.send(LedCommand.Order(ColorOrder.GRB)); advanceTimeBy(500)
        assertEquals(1, t.packets().count { it == "A0 64 01 02" })
        t.linkFlow.value = LedLinkState.RECONNECTING; runCurrent()
        t.linkFlow.value = LedLinkState.CONNECTED; advanceTimeBy(500)
        assertEquals(1, t.packets().count { it == "A0 64 01 02" })   // não é reenviado sozinho
    }

    @Test fun pixelCountIsStoredLocallyAndUserIsNotified() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        val notices = ArrayList<String>()
        backgroundScope.launch { c.notices.collect { notices += it } }
        runCurrent()
        val n = t.writes.size
        c.send(LedCommand.PixelCount(60)); advanceTimeBy(500)
        assertEquals(60, c.state.value.pixelCount)
        assertEquals(n, t.writes.size)
        assertEquals(1, notices.size)
    }

    @Test fun frameBrightnessIsAppliedToRgbChannelsAndBlackFrameIsDark() = runTest {
        val (c, t) = setup()
        advanceTimeBy(500)
        c.applyFrame(LedFrame(Rgb(255, 0, 0), 0)); advanceTimeBy(300)
        assertEquals("A0 69 04 00 00 00 FF", t.packets().last())
        c.applyFrame(LedFrame(Rgb(200, 100, 0), 128)); advanceTimeBy(300)
        assertEquals("A0 69 04 64 32 00 FF", t.packets().last())
    }

    @Test fun statusNotificationsUpdateStatusFlow() = runTest {
        val (c, t) = setup()
        val payload = hex(0x01, 0x00, 0xBE, 0x00, 0xFF, 0x05, 0x32, 0x00, 0xFF, 0x00, 0, 0)
        t.notif.emit(hex(0x53, 0x43, 0x01, payload.size, payload.size) + payload)
        runCurrent()
        assertEquals(Rgb(0, 255, 0), c.status.value!!.color)
    }
}

class FakeLedControllerTest {
    @Test fun logsBytesThatWouldBeSentAndSkipsRepeats() {
        val f = apexled.led.FakeLedController()
        f.send(LedCommand.Solid(Rgb(255, 0, 0), 255))
        f.send(LedCommand.Solid(Rgb(255, 0, 0), 255))
        val packets = f.log.value.filter { it.startsWith("→") }
        assertEquals(1, packets.count { it.contains("A0 69 04 FF 00 00 FF") })
        assertEquals(Rgb(255, 0, 0), f.state.value.color)
    }
}
