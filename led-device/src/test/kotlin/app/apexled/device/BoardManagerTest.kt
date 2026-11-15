package app.apexled.device

import app.apexled.device.sim.SimulatedBoard
import app.apexled.device.sim.Sp61xPwmFirmware
import app.apexled.pattern.LedFrame
import app.apexled.pattern.Rgb
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoardManagerTest {

    private class Rig(scope: TestScope) {
        val sims = mutableMapOf<String, SimulatedBoard>()
        val manager = BoardManager(
            scope = scope.backgroundScope,
            transportFactory = { info ->
                val firmware = if (info.protocolId == "sp61x-pwm") Sp61xPwmFirmware() else app.apexled.device.sim.Sp63xFirmware()
                SimulatedBoard(firmware) { scope.testScheduler.currentTime }.also { sims[info.id] = it }
            },
            clock = { scope.testScheduler.currentTime },
        )

        fun info(index: Int, protocolId: String = "sp63x") =
            BoardInfo(id = "board-$index", name = "Board $index", address = "AA:BB:CC:00:00:%02X".format(index), protocolId = protocolId)

        fun addBoards(count: Int) = (1..count).map { manager.add(info(it)) }
    }

    @Test
    fun `ten boards can be registered and the eleventh is refused`() = runTest {
        val rig = Rig(this)
        val results = rig.addBoards(10)
        assertTrue(results.all { it is BoardManager.AddResult.Added })
        assertEquals(10, rig.manager.size)

        assertEquals(BoardManager.AddResult.LimitReached, rig.manager.add(rig.info(11)))
        assertEquals(10, rig.manager.size)
    }

    @Test
    fun `duplicates and unknown protocols are refused`() = runTest {
        val rig = Rig(this)
        rig.addBoards(1)
        assertEquals(BoardManager.AddResult.DuplicateId, rig.manager.add(rig.info(1).copy(address = "11:22:33:44:55:66")))
        assertEquals(
            BoardManager.AddResult.DuplicateAddress,
            rig.manager.add(rig.info(2).copy(address = rig.info(1).address.lowercase())),
        )
        assertEquals(
            BoardManager.AddResult.UnknownProtocol("mystery"),
            rig.manager.add(rig.info(3, protocolId = "mystery")),
        )
        assertEquals(1, rig.manager.size)
    }

    @Test
    fun `every board connects and gets its own frame`() = runTest {
        val rig = Rig(this)
        rig.addBoards(10)
        val frames = (1..10).map { LedFrame(Rgb(it * 20, 0, 255 - it * 20), 1.0) }

        rig.manager.showFrames(frames)
        advanceTimeBy(1_000)

        rig.manager.boards.value.forEachIndexed { index, entry ->
            assertEquals(BoardState.Ready, entry.connection.state.value, entry.info.name)
            val light = rig.sims.getValue(entry.info.id).firmware.light
            assertTrue(light.showsSolidColor)
            assertEquals(frames[index].color, light.color, entry.info.name)
        }
    }

    @Test
    fun `boards of different protocols work side by side`() = runTest {
        val rig = Rig(this)
        rig.manager.add(rig.info(1, "sp63x"))
        rig.manager.add(rig.info(2, "sp61x-pwm"))

        rig.manager.showFrames(List(2) { LedFrame(Rgb.YELLOW, 0.5) })
        advanceTimeBy(1_000)

        for (sim in rig.sims.values) {
            assertTrue(sim.firmware.light.showsSolidColor)
            assertEquals(Rgb.YELLOW, sim.firmware.light.color)
            assertEquals(128, sim.firmware.light.level)
            assertEquals(0, sim.firmware.rejected)
        }
    }

    @Test
    fun `one board failing does not disturb the others`() = runTest {
        val rig = Rig(this)
        rig.addBoards(3)
        rig.manager.showFrames(List(3) { LedFrame(Rgb.GREEN, 1.0) })
        advanceTimeBy(1_000)

        rig.sims.getValue("board-2").powerOff()
        rig.manager.showFrames(List(3) { LedFrame(Rgb.BLUE, 1.0) })
        advanceTimeBy(1_000)

        val states = rig.manager.boards.value.associate { it.info.id to it.connection.state.value }
        assertEquals(BoardState.Ready, states["board-1"])
        assertIs<BoardState.Reconnecting>(states["board-2"])
        assertEquals(BoardState.Ready, states["board-3"])
        assertEquals(Rgb.BLUE, rig.sims.getValue("board-1").firmware.light.color)
        assertEquals(Rgb.GREEN, rig.sims.getValue("board-2").firmware.light.color)
        assertEquals(Rgb.BLUE, rig.sims.getValue("board-3").firmware.light.color)

        // When it comes back it catches up with the others.
        rig.sims.getValue("board-2").powerOn()
        advanceTimeBy(60_000)
        assertEquals(Rgb.BLUE, rig.sims.getValue("board-2").firmware.light.color)
    }

    @Test
    fun `rename keeps the link and the address`() = runTest {
        val rig = Rig(this)
        rig.addBoards(2)
        runCurrent()
        val before = rig.manager.boards.value[0]

        assertTrue(rig.manager.rename("board-1", "Front"))
        assertFalse(rig.manager.rename("board-1", "  "))
        assertFalse(rig.manager.rename("missing", "X"))

        val after = rig.manager.boards.value[0]
        assertEquals("Front", after.info.name)
        assertEquals(before.info.address, after.info.address)
        assertTrue(before.connection === after.connection)
        assertEquals(1, rig.sims.getValue("board-1").connectCount)
    }

    @Test
    fun `reorder changes which frame each board gets`() = runTest {
        val rig = Rig(this)
        rig.addBoards(3)
        assertTrue(rig.manager.reorder(listOf("board-3", "board-1", "board-2")))
        assertFalse(rig.manager.reorder(listOf("board-3", "board-1")))
        assertFalse(rig.manager.reorder(listOf("board-3", "board-1", "board-9")))

        rig.manager.showFrames(listOf(LedFrame(Rgb.RED, 1.0), LedFrame(Rgb.GREEN, 1.0), LedFrame(Rgb.BLUE, 1.0)))
        advanceTimeBy(1_000)

        assertEquals(Rgb.RED, rig.sims.getValue("board-3").firmware.light.color)
        assertEquals(Rgb.GREEN, rig.sims.getValue("board-1").firmware.light.color)
        assertEquals(Rgb.BLUE, rig.sims.getValue("board-2").firmware.light.color)
    }

    @Test
    fun `removing a board disconnects it and frees a slot`() = runTest {
        val rig = Rig(this)
        rig.addBoards(10)
        advanceTimeBy(500)

        assertTrue(rig.manager.remove("board-4"))
        assertFalse(rig.manager.remove("board-4"))
        assertFalse(rig.sims.getValue("board-4").connected)
        assertEquals(9, rig.manager.size)
        assertIs<BoardManager.AddResult.Added>(rig.manager.add(rig.info(11)))
    }

    @Test
    fun `a command goes to every board and unsupported boards are reported`() = runTest {
        val rig = Rig(this)
        rig.manager.add(rig.info(1, "sp63x"))
        rig.manager.add(rig.info(2, "sp61x-pwm"))
        runCurrent()

        assertEquals(emptyList(), rig.manager.sendToAll(BoardCommand.Brightness(99)))
        assertEquals(listOf("board-2"), rig.manager.sendToAll(BoardCommand.EffectLength(30)))
        advanceTimeBy(1_000)

        assertEquals(99, rig.sims.getValue("board-1").firmware.light.level)
        assertEquals(99, rig.sims.getValue("board-2").firmware.light.level)
        assertEquals(30, rig.sims.getValue("board-1").firmware.light.effectLength)
    }

    @Test
    fun `removeAll disconnects everything`() = runTest {
        val rig = Rig(this)
        rig.addBoards(5)
        advanceTimeBy(500)
        rig.manager.removeAll()
        assertEquals(0, rig.manager.size)
        assertTrue(rig.sims.values.none { it.connected })
    }
}
