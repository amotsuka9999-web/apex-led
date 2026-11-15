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
import apexled.data.ble.GattLayout
import apexled.wheel.WheelDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Fonte principal: o app conecta direto à roda por BLE e decodifica o protocolo nativo — Begode/Gotway,
 * Leaperkim/Veteran/Nosfet e Kingsong (FFE0/FFE1), Inmotion V5/V8/V10 (FFE0/FFE4 + FFE5/FFE9) e Inmotion
 * V9/V11–V14 (Nordic UART). O decodificador é escolhido pelo serviço BLE encontrado ([decoderFor]); rodas
 * que só respondem a pedidos (Kingsong, Inmotion) recebem as consultas de [WheelDecoder.poll].
 *
 * ⚠ A roda aceita UMA conexão central por vez. Se o WheelLog (ou outro app) estiver conectado, esta fonte não
 * conseguirá conectar: feche o WheelLog antes. Sem teste em hardware até este commit.
 */
class DirectBleSource(
    context: Context,
    private val scope: CoroutineScope,
    private val address: String,
    /** Layouts de serviço aceitos, na ordem de preferência. */
    private val layouts: List<GattLayout>,
    /** Cria o decodificador para o layout encontrado na conexão. */
    private val decoderFor: (GattLayout) -> WheelDecoder,
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
    private val lock = Any()
    @Volatile private var decoder: WheelDecoder? = null

    override fun start() {
        if (jobs.isNotEmpty()) return
        decoder = null
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
                    if (s == LedLinkState.CONNECTED) {
                        val l = gatt.layout.value
                        synchronized(lock) { decoder = l?.let(decoderFor)?.also { it.reset() } }
                    }
                    if (s == LedLinkState.RECONNECTING) synchronized(lock) { decoder?.reset() }
                }
            },
            scope.launch {
                gatt.notifications.collect { bytes ->
                    val (list, model) = synchronized(lock) {
                        val d = decoder ?: return@collect
                        d.feed(bytes, clock.nowMs()) to d.model
                    }
                    if (model != null) _model.value = model
                    if (list.isNotEmpty()) {
                        _telemetry.value = list.last().withBatteryFrom(profile())
                    }
                }
            },
            // Consultas/keep-alive para Kingsong e Inmotion.
            scope.launch {
                while (isActive) {
                    delay(WheelDecoder.POLL_INTERVAL_MS)
                    if (gatt.state.value != LedLinkState.CONNECTED) continue
                    val (req, chunk) = synchronized(lock) {
                        val d = decoder ?: return@synchronized Pair<ByteArray?, Int>(null, 0)
                        Pair<ByteArray?, Int>(d.poll(clock.nowMs()), d.writeChunk)
                    }
                    if (req == null) continue
                    if (chunk <= 0 || req.size <= chunk) gatt.write(req)
                    else req.toList().chunked(chunk).forEach { part -> gatt.write(part.toByteArray()); delay(20) }
                }
            },
        )
        gatt.connect(address, layouts)
    }

    override fun stop() {
        jobs.forEach { it.cancel() }; jobs = emptyList()
        gatt.disconnect()
        _status.value = SourceStatus.Idle
        _telemetry.value = null
    }
}
