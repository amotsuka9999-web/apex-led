package app.apexled.bench

import android.app.Application
import app.apexled.android.ble.BleLedTransport
import app.apexled.android.ble.BoardScanner
import app.apexled.core.FileSettingsStore
import app.apexled.core.LedController
import app.apexled.core.WheelTelemetry
import app.apexled.device.BoardManager
import app.apexled.device.sim.SimulatedBoard
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** The wheel readings set by hand on the bench screen. */
data class BenchWheel(
    val feeding: Boolean = true,
    val speedKmh: Double = 0.0,
    val pwmPercent: Double = 0.0,
    val appAlarm: Boolean = false,
)

/**
 * Wires the LED stack for the bench app. In the WheelLog fork the same wiring lives next to
 * the wheel connection service, and the telemetry comes from the wheel instead of [wheel].
 */
class BenchApplication : Application() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    lateinit var manager: BoardManager
        private set
    lateinit var controller: LedController
        private set
    lateinit var scanner: BoardScanner
        private set

    val wheel = MutableStateFlow(BenchWheel())

    override fun onCreate() {
        super.onCreate()
        manager = BoardManager(
            scope = scope,
            transportFactory = { info ->
                if (info.address.startsWith(SIMULATED_PREFIX)) SimulatedBoard() else BleLedTransport(this, info.address)
            },
        )
        controller = LedController(scope, manager, FileSettingsStore(File(filesDir, "led-settings.json")))
        scanner = BoardScanner(this)

        scope.launch(Dispatchers.IO) {
            controller.start()
            // Stands in for the wheel: ten readings a second while "feeding" is on.
            while (true) {
                val current = wheel.value
                if (current.feeding) {
                    controller.onTelemetry(
                        WheelTelemetry(current.speedKmh, current.pwmPercent, appAlarm = current.appAlarm),
                    )
                }
                delay(TELEMETRY_PERIOD_MS)
            }
        }
    }

    companion object {
        const val SIMULATED_PREFIX = "SIM:"
        private const val TELEMETRY_PERIOD_MS = 100L
    }
}
