package app.apexled.device

import app.apexled.device.protocol.Sp61xPwmProtocol
import app.apexled.device.protocol.Sp63xProtocol
import app.apexled.device.sim.SimulatedBoard
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
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class BoardConnectionTest {

    private val policy = ConnectionPolicy()

    private fun TestScope.board() = SimulatedBoard(clock = { testScheduler.currentTime })

    private fun TestScope.connect(board: SimulatedBoard, policy: ConnectionPolicy = this@BoardConnectionTest.policy) =
        BoardConnection(board, Sp63xProtocol, backgroundScope, policy) { testScheduler.currentTime }

    private fun SimulatedBoard.opcodes(): List<Int> = writes.map { it.payload[1].toInt() and 0xFF }

    private fun SimulatedBoard.frameWrites() = writes.filter { (it.payload[1].toInt() and 0xFF) == Sp63xProtocol.Opcode.STATIC_COLOR }

    private val green = LedFrame(Rgb.GREEN, 1.0)
    private val blue = LedFrame(Rgb.BLUE, 0.5)
    private val alert = LedFrame(Rgb.RED, 1.0, critical = true)

    @Test
    fun `connects and asks the board for its status`() = runTest {
        val board = board()
        val connection = connect(board)
        assertEquals(BoardState.Disconnected, connection.state.value)

        connection.start()
        runCurrent()

        assertEquals(BoardState.Ready, connection.state.value)
        assertEquals(listOf(Sp63xProtocol.Opcode.STATUS), board.opcodes())
        val status = assertNotNull(connection.status.value)
        assertFalse(status.power)
    }

    @Test
    fun `first frame sets the board up then shows the colour`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)

        assertEquals(
            listOf(
                Sp63xProtocol.Opcode.STATUS,
                Sp63xProtocol.Opcode.POWER,
                Sp63xProtocol.Opcode.MODE_EFFECT,
                Sp63xProtocol.Opcode.STATIC_COLOR,
            ),
            board.opcodes(),
        )
        assertTrue(board.firmware.light.showsSolidColor)
        assertEquals(Rgb.GREEN, board.firmware.light.color)
        assertEquals(255, board.firmware.light.level)
    }

    @Test
    fun `later frames are a single write each`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)
        board.clearWrites()

        connection.showFrame(blue)
        advanceTimeBy(100)

        assertEquals(listOf(Sp63xProtocol.Opcode.STATIC_COLOR), board.opcodes())
        assertEquals(Rgb.BLUE, board.firmware.light.color)
        assertEquals(128, board.firmware.light.level)
    }

    @Test
    fun `on a slow link only the newest frame matters`() = runTest {
        val board = board().apply { writeDelayMs = 200 }
        val connection = connect(board)
        connection.start()
        advanceTimeBy(1_000)
        board.clearWrites()

        // 100 frames in one second: far more than a 200 ms-per-write link can carry.
        for (step in 1..100) {
            connection.showFrame(LedFrame(Rgb(step, 0, 0), 1.0))
            advanceTimeBy(10)
        }
        advanceTimeBy(1_000)

        assertTrue(board.frameWrites().size <= 8, "sent ${board.frameWrites().size} frames")
        assertEquals(Rgb(100, 0, 0), board.firmware.light.color)
    }

    @Test
    fun `an unchanged frame is not sent again until the refresh interval`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(100)
        assertEquals(1, board.frameWrites().size)

        repeat(50) {
            connection.showFrame(green)
            advanceTimeBy(20)
        }
        assertEquals(1, board.frameWrites().size)

        advanceTimeBy(policy.refreshIntervalMs)
        assertEquals(2, board.frameWrites().size)
    }

    @Test
    fun `frames that differ only below one brightness step are the same light`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(LedFrame(Rgb.GREEN, 0.5000))
        advanceTimeBy(100)
        connection.showFrame(LedFrame(Rgb.GREEN, 0.5001))
        advanceTimeBy(100)
        assertEquals(1, board.frameWrites().size)
    }

    @Test
    fun `the critical indicator is refreshed faster than a cosmetic frame`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(alert)
        advanceTimeBy(200)
        assertEquals(1, board.frameWrites().size)

        advanceTimeBy(policy.criticalRefreshIntervalMs * 4)
        assertEquals(5, board.frameWrites().size)
    }

    @Test
    fun `the critical indicator jumps ahead of waiting commands`() = runTest {
        val board = board().apply { writeDelayMs = 50 }
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(2_000)
        board.clearWrites()

        repeat(10) { connection.send(BoardCommand.EffectSpeed(it + 1)) }
        connection.showFrame(alert)
        advanceTimeBy(5_000)

        val firstFrame = board.opcodes().indexOf(Sp63xProtocol.Opcode.STATIC_COLOR)
        assertTrue(firstFrame <= 1, "critical frame was write number $firstFrame")
        assertEquals(10, board.opcodes().count { it == Sp63xProtocol.Opcode.EFFECT_SPEED })
        assertEquals(Rgb.RED, board.firmware.light.color)
    }

    @Test
    fun `commands are delivered in order`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        runCurrent()
        board.clearWrites()

        for (speed in 1..10) connection.send(BoardCommand.EffectSpeed(speed))
        advanceTimeBy(2_000)

        assertEquals((1..10).toList(), board.writes.map { it.payload.last().toInt() })
    }

    @Test
    fun `writes are spaced by the minimum interval`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        for (speed in 1..10) connection.send(BoardCommand.EffectSpeed(speed))
        advanceTimeBy(2_000)

        val times = board.writes.map { it.timeMs }
        assertEquals(11, times.size)
        for ((earlier, later) in times.zipWithNext()) {
            assertTrue(later - earlier >= policy.minWriteIntervalMs, "gap of ${later - earlier} ms")
        }
    }

    @Test
    fun `a dropped link is re-established and the frame restored`() = runTest {
        val board = board().apply { resetOnConnect = true }
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)
        assertEquals(Rgb.GREEN, board.firmware.light.color)

        board.dropLink()
        runCurrent()
        assertEquals(BoardState.Reconnecting(1, policy.initialBackoffMs), connection.state.value)

        // The board forgets everything when the link comes back (resetOnConnect), like after a power cut.
        advanceTimeBy(policy.initialBackoffMs + 500)

        assertEquals(BoardState.Ready, connection.state.value)
        assertEquals(2, board.connectCount)
        assertTrue(board.firmware.light.showsSolidColor, "board was set up again after reconnecting")
        assertEquals(Rgb.GREEN, board.firmware.light.color)
    }

    @Test
    fun `a frame requested while disconnected is shown after reconnecting`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)

        board.powerOff()
        runCurrent()
        connection.showFrame(alert)
        advanceTimeBy(10_000)
        assertIs<BoardState.Reconnecting>(connection.state.value)

        board.powerOn()
        advanceTimeBy(policy.maxBackoffMs)

        assertEquals(BoardState.Ready, connection.state.value)
        assertEquals(Rgb.RED, board.firmware.light.color)
    }

    @Test
    fun `reconnection backs off exponentially up to the maximum`() = runTest {
        val board = board().apply { reachable = false }
        val connection = connect(board)
        connection.start()

        val seen = mutableListOf<Long>()
        repeat(10) {
            runCurrent()
            val state = assertIs<BoardState.Reconnecting>(connection.state.value)
            seen += state.retryInMs
            advanceTimeBy(state.retryInMs)
        }

        assertEquals(listOf(500L, 1_000L, 2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L, 30_000L, 30_000L), seen)
        assertEquals(0, board.connectCount)
    }

    @Test
    fun `a link that held resets the backoff, one that dies at once does not`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        runCurrent()

        // Dies immediately, three times in a row: the wait keeps growing.
        val waits = mutableListOf<Long>()
        repeat(3) {
            board.dropLink()
            runCurrent()
            val state = assertIs<BoardState.Reconnecting>(connection.state.value)
            waits += state.retryInMs
            advanceTimeBy(state.retryInMs)
            runCurrent()
            assertEquals(BoardState.Ready, connection.state.value)
        }
        assertEquals(listOf(500L, 1_000L, 2_000L), waits)

        // Holds for a while, then drops: back to the short wait.
        advanceTimeBy(policy.stableSessionMs)
        board.dropLink()
        runCurrent()
        assertEquals(BoardState.Reconnecting(1, 500), connection.state.value)
    }

    @Test
    fun `a connection attempt that hangs is abandoned`() = runTest {
        val board = board().apply { connectDelayMs = 60_000 }
        val connection = connect(board)
        connection.start()
        advanceTimeBy(policy.connectTimeoutMs - 1)
        assertEquals(BoardState.Connecting, connection.state.value)
        advanceTimeBy(2)
        assertIs<BoardState.Reconnecting>(connection.state.value)
    }

    @Test
    fun `commands queued while disconnected are delivered after reconnecting`() = runTest {
        val board = board().apply { reachable = false }
        val connection = connect(board)
        connection.start()
        runCurrent()

        connection.send(BoardCommand.Power(true))
        connection.send(BoardCommand.Brightness(40))
        board.powerOn()
        advanceTimeBy(5_000)

        assertEquals(
            listOf(Sp63xProtocol.Opcode.STATUS, Sp63xProtocol.Opcode.POWER, Sp63xProtocol.Opcode.BRIGHTNESS),
            board.opcodes(),
        )
        assertTrue(board.firmware.light.power)
        assertEquals(40, board.firmware.light.level)
    }

    @Test
    fun `the command queue is bounded and drops the oldest`() = runTest {
        val small = ConnectionPolicy(maxQueuedCommands = 3)
        val board = board().apply { reachable = false }
        val connection = connect(board, small)
        connection.start()
        runCurrent()

        for (speed in 1..10) connection.send(BoardCommand.EffectSpeed(speed))
        board.powerOn()
        advanceTimeBy(5_000)

        val speeds = board.writes
            .filter { (it.payload[1].toInt() and 0xFF) == Sp63xProtocol.Opcode.EFFECT_SPEED }
            .map { it.payload.last().toInt() }
        assertEquals(listOf(8, 9, 10), speeds)
    }

    @Test
    fun `a failed write triggers reconnection and the command is not lost`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        runCurrent()
        board.clearWrites()

        board.failNextWrites = 1
        connection.send(BoardCommand.Brightness(77))
        advanceTimeBy(policy.minWriteIntervalMs + 1)
        assertIs<BoardState.Reconnecting>(connection.state.value)

        advanceTimeBy(5_000)
        assertEquals(BoardState.Ready, connection.state.value)
        assertEquals(77, board.firmware.light.level)
    }

    @Test
    fun `a write that hangs is treated as a lost link`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        runCurrent()

        board.writeDelayMs = 60_000
        connection.showFrame(green)
        advanceTimeBy(policy.minWriteIntervalMs + policy.writeTimeoutMs + 10)
        assertIs<BoardState.Reconnecting>(connection.state.value)
    }

    @Test
    fun `a board command makes the next frame set the board up again`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)

        connection.send(BoardCommand.SelectEffect(LightMode.DYNAMIC, 5))
        advanceTimeBy(500)

        // The pattern is still being driven, so the board ends up back on the solid colour.
        assertTrue(board.firmware.light.showsSolidColor)
        assertEquals(Rgb.GREEN, board.firmware.light.color)
    }

    @Test
    fun `after clearFrame the board is left to its own effects`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)

        connection.clearFrame()
        connection.send(BoardCommand.SelectEffect(LightMode.DYNAMIC, 5))
        advanceTimeBy(500)
        board.clearWrites()
        advanceTimeBy(policy.refreshIntervalMs * 5)

        assertEquals(LightMode.DYNAMIC, board.firmware.light.mode)
        assertEquals(5, board.firmware.light.effect)
        assertEquals(emptyList(), board.writes)
    }

    @Test
    fun `stop closes the link and ends reconnection`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        connection.showFrame(green)
        advanceTimeBy(500)

        connection.stop()
        assertEquals(BoardState.Disconnected, connection.state.value)
        assertFalse(board.connected)

        board.clearWrites()
        advanceTimeBy(120_000)
        assertEquals(1, board.connectCount)
        assertEquals(emptyList(), board.writes)
    }

    @Test
    fun `it can be started again after a stop`() = runTest {
        val board = board()
        val connection = connect(board)
        connection.start()
        runCurrent()
        connection.stop()

        connection.start()
        connection.showFrame(blue)
        advanceTimeBy(500)
        assertEquals(BoardState.Ready, connection.state.value)
        assertEquals(Rgb.BLUE, board.firmware.light.color)
    }

    @Test
    fun `a command the protocol lacks is refused up front`() = runTest {
        val board = SimulatedBoard(app.apexled.device.sim.Sp61xPwmFirmware()) { testScheduler.currentTime }
        val connection = BoardConnection(board, Sp61xPwmProtocol, backgroundScope, policy) { testScheduler.currentTime }
        connection.start()
        runCurrent()
        board.clearWrites()

        assertFalse(connection.send(BoardCommand.EffectLength(20)))
        assertTrue(connection.send(BoardCommand.Brightness(20)))
        advanceTimeBy(500)
        assertEquals(1, board.writes.size)
    }

    @Test
    fun `backoff arithmetic`() {
        assertEquals(500, policy.backoffMs(1))
        assertEquals(1_000, policy.backoffMs(2))
        assertEquals(30_000, policy.backoffMs(7))
        assertEquals(30_000, policy.backoffMs(1_000))
    }
}
