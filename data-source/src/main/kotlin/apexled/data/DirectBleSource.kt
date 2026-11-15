package apexled.data

import android.content.Context
import apexled.core.Clock
import apexled.core.LedLinkState
import apexled.core.SourceKind
import apexled.core.SourceStatus
import apexled.core.Telemetry
import apexled.core.WheelDataSource
import apexled.core.WheelProfile
import apexled.core.withBatteryFrom
import apexled.data.ble.GattClient
import apexled.wheel.WheelDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Fonte principal: o app conecta direto à roda por BLE (FFE0/FFE1) e decodifica o protocolo nativo
 * (Gotway/Begode ou Veteran/Nosfet) — menor latência e nenhuma dependência de outros apps.
 *
 * ⚠ A roda aceita UMA conexão central por vez. Se o WheelLog (ou outro app) estiver conectado, esta fonte não
 * conseguirá conectar: feche o WheelLog antes. Sem teste em hardware até este commit.
 */
class DirectBleSource(
    context: Context,
    private val scope: CoroutineScope,
    private val address: String,
    private val decoder: WheelDecoder,
    private val profile: () -> WheelProfile,
    private val clock: Clock = Clock.SYSTEM,
) : WheelDataSource {
    override val kind = SourceKind.DIRECT_BLE
    private val gatt = GattClient(context, scope, "wheel")

    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()
    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    override val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()
    private val _model = MutableStateFlow<String?>(null)
    override val detectedModel: StateFlow<String?> = _model.asStateFlow()

    val rssi: StateFlow<Int?> get() = gatt.rssi

    private var jobs = emptyList<Job>()

    override fun start() {
        if (jobs.isNotEmpty()) return
        decoder.reset()
        _status.value = SourceStatus.Connecting("Conectando à roda…")
        jobs = listOf(
            scope.launch {
                gatt.state.collect { s ->
                    _status.value = when (s) {
                        LedLinkState.CONNECTED -> SourceStatus.Connected(gatt.deviceName.value)
                        LedLinkState.CONNECTING -> SourceStatus.Connecting("Conectando à roda…")
                        LedLinkState.RECONNECTING -> SourceStatus.Connecting(
                            "Reconectando… ${gatt.lastError.value ?: "(a roda pode estar conectada ao WheelLog)"}"
                        )
                        LedLinkState.DISCONNECTED, LedLinkState.SCANNING -> SourceStatus.Idle
                    }
                    if (s == LedLinkState.RECONNECTING) decoder.reset()
                }
            },
            scope.launch {
                gatt.notifications.collect { bytes ->
                    val list = decoder.feed(bytes, clock.nowMs())
                    if (list.isNotEmpty()) {
                        _model.value = decoder.model ?: _model.value
                        _telemetry.value = list.last().withBatteryFrom(profile())
                    }
                }
            },
        )
        gatt.connect(address)
    }

    override fun stop() {
        jobs.forEach { it.cancel() }; jobs = emptyList()
        gatt.disconnect()
        _status.value = SourceStatus.Idle
        _telemetry.value = null
    }
}
