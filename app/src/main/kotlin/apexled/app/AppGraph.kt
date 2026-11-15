package apexled.app

import android.content.Context
import apexled.app.data.AppSettings
import apexled.app.data.SettingsRepository
import apexled.app.service.ConnectionService
import apexled.core.Clock
import apexled.core.LedCommand
import apexled.core.LedController
import apexled.core.LedLinkState
import apexled.core.LedState
import apexled.core.SourceKind
import apexled.core.SourceStatus
import apexled.core.Telemetry
import apexled.core.WheelDataSource
import apexled.core.WheelModel
import apexled.core.WheelProfile
import apexled.core.withBatteryFrom
import apexled.data.BleLedTransport
import apexled.data.DirectBleSource
import apexled.data.SimulatorSource
import apexled.data.WheelLogBridgeSource
import apexled.data.ble.BlePermissions
import apexled.data.ble.GattClient
import apexled.led.FakeLedController
import apexled.led.Sp621eController
import apexled.safety.SafetyEngine
import apexled.safety.SafetyOutput
import apexled.wheel.AutoDecoder
import apexled.led.FanoutLedController
import apexled.app.data.SavedLed
import apexled.wheel.InmotionV1Decoder
import apexled.wheel.InmotionV2Decoder
import apexled.wheel.KingsongDecoder
import apexled.data.ble.GattLayout
import apexled.core.WheelProtocol
import apexled.wheel.GotwayConfig
import apexled.wheel.GotwayDecoder
import apexled.wheel.VeteranConfig
import apexled.wheel.VeteranDecoder
import apexled.wheel.WheelDecoder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Dono de todo o estado de execução. Vive no processo (não na Activity), então a roda e a fita continuam
 * conectadas e o motor de segurança continua rodando com a tela apagada, desde que o [ConnectionService]
 * (primeiro plano) mantenha o processo vivo.
 */
class AppGraph(private val context: Context) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock = Clock.SYSTEM
    private val repo = SettingsRepository(context)

    private val _settings = MutableStateFlow(AppSettings())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()
    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    fun update(transform: (AppSettings) -> AppSettings) { _settings.value = transform(_settings.value) }

    // ---- LED ----------------------------------------------------------------------------------

    val fakeLed = FakeLedController()
    private val gattLed = GattClient(context, scope, "led", alwaysWriteWithResponse = true)
    private var realLed: Sp621eController? = null
    /** Principal + extras recebendo os mesmos comandos. */
    private var realGroup: FanoutLedController? = null

    /** Uma fita extra do grupo (cada uma com sua própria conexão BLE). */
    class ExtraLed(val address: String, val name: String?, internal val gatt: GattClient, internal val controller: Sp621eController) {
        val link: StateFlow<LedLinkState> get() = gatt.state
        val rssi: StateFlow<Int?> get() = gatt.rssi
    }
    private val _extraLeds = MutableStateFlow<List<ExtraLed>>(emptyList())
    val extraLeds: StateFlow<List<ExtraLed>> = _extraLeds.asStateFlow()
    private val _led = MutableStateFlow<LedController>(fakeLed)
    val led: StateFlow<LedController> = _led.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ledState: StateFlow<LedState> = _led.flatMapLatest { it.state }.stateIn(scope, SharingStarted.Eagerly, LedState())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ledLink: StateFlow<LedLinkState> = _led.flatMapLatest { it.link }.stateIn(scope, SharingStarted.Eagerly, LedLinkState.CONNECTED)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ledName: StateFlow<String?> = _led.flatMapLatest { it.deviceName }.stateIn(scope, SharingStarted.Eagerly, null)
    val gattRssi get() = gattLed.rssi

    private val _notices = MutableStateFlow<String?>(null)
    /** Último aviso para o usuário (ex.: comando do SP621E sem protocolo conhecido). */
    val notice: StateFlow<String?> = _notices.asStateFlow()
    fun clearNotice() { _notices.value = null }

    fun setLedSimulated(simulated: Boolean) {
        update { it.copy(ledSimulated = simulated) }
        if (simulated) {
            gattLed.disconnect()
            _extraLeds.value.forEach { it.gatt.disconnect() }
            switchLed(fakeLed)
        } else {
            val addr = _settings.value.ledAddress
            if (addr != null) connectLed(addr, _settings.value.ledName)
        }
    }

    fun connectLed(address: String, name: String?) {
        if (!BlePermissions.hasAll(context)) { _notices.value = "Conceda a permissão de Bluetooth primeiro."; return }
        update { it.copy(ledAddress = address, ledName = name, ledSimulated = false) }
        val real = realLed ?: // 120 ms entre pacotes: o uniled espera 400 ms; rajadas rápidas (efeito + cor) fazem o SP621E perder comandos.
            Sp621eController(scope, BleLedTransport(gattLed), initial = _settings.value.led, interPacketDelayMs = 120).also {
            realLed = it
            scope.launch { it.notices.collect { msg -> _notices.value = msg } }
        }
        val group = realGroup ?: FanoutLedController(real) { _extraLeds.value.map { it.controller } }.also { realGroup = it }
        gattLed.connect(address)
        // fitas extras salvas: conecta junto com a principal
        _settings.value.extraLeds.forEach { e -> if (e.address != address) ensureExtra(e.address, e.name).gatt.connect(e.address) }
        switchLed(group)
        ConnectionService.sync(context, this)
    }

    fun disconnectLed() {
        gattLed.disconnect()
        _extraLeds.value.forEach { it.gatt.disconnect() }
        ConnectionService.sync(context, this)
    }

    /** Adiciona uma fita ao grupo (mesmos comandos da principal). Até [FanoutLedController.MAX_DEVICES] no total. */
    fun addExtraLed(address: String, name: String?) {
        if (!BlePermissions.hasAll(context)) { _notices.value = "Conceda a permissão de Bluetooth primeiro."; return }
        val s = _settings.value
        if (s.ledAddress == null || realGroup == null) { connectLed(address, name); return }
        if (address == s.ledAddress || s.extraLeds.any { it.address == address }) { _notices.value = "Essa fita já está no grupo."; return }
        if (s.extraLeds.size >= FanoutLedController.MAX_DEVICES - 1) {
            _notices.value = "Limite de ${FanoutLedController.MAX_DEVICES} fitas atingido."; return
        }
        update { it.copy(extraLeds = it.extraLeds + SavedLed(address, name)) }
        ensureExtra(address, name).gatt.connect(address)
        ConnectionService.sync(context, this)
    }

    fun removeExtraLed(address: String) {
        _extraLeds.value.firstOrNull { it.address == address }?.gatt?.disconnect()
        _extraLeds.value = _extraLeds.value.filter { it.address != address }
        update { it.copy(extraLeds = it.extraLeds.filter { e -> e.address != address }) }
    }

    private fun ensureExtra(address: String, name: String?): ExtraLed {
        _extraLeds.value.firstOrNull { it.address == address }?.let { return it }
        val gatt = GattClient(context, scope, "led-extra", alwaysWriteWithResponse = true)
        // começa no estado atual da principal; ao conectar, o controlador envia o estado completo
        val ctrl = Sp621eController(scope, BleLedTransport(gatt), initial = realLed?.state?.value ?: _settings.value.led, interPacketDelayMs = 120)
        return ExtraLed(address, name, gatt, ctrl).also { _extraLeds.value = _extraLeds.value + it }
    }

    private fun switchLed(target: LedController) {
        val previous = _led.value
        if (previous === target) return
        val s = previous.state.value
        _led.value = target
        // leva o estado atual para o novo controlador
        restoreManual(target, s)
    }

    private fun restoreManual(target: LedController, s: LedState) {
        target.send(LedCommand.Power(s.power))
        if (s.effectId == LedState.EFFECT_SOLID) target.send(LedCommand.Solid(s.color, s.brightness))
        else {
            target.send(LedCommand.Effect(s.effectId)); target.send(LedCommand.EffectSpeed(s.effectSpeed))
            target.send(LedCommand.EffectLength(s.effectLength)); target.send(LedCommand.Brightness(s.brightness))
        }
    }

    // ---- roda ---------------------------------------------------------------------------------

    private val _source = MutableStateFlow<WheelDataSource?>(null)
    val source: StateFlow<WheelDataSource?> = _source.asStateFlow()
    var simulator: SimulatorSource? = null
        private set

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val sourceStatus: StateFlow<SourceStatus> = _source.flatMapLatest { it?.status ?: flowOf(SourceStatus.Idle) }
        .stateIn(scope, SharingStarted.Eagerly, SourceStatus.Idle)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val detectedModel: StateFlow<String?> = _source.flatMapLatest { it?.detectedModel ?: flowOf(null) }
        .stateIn(scope, SharingStarted.Eagerly, null)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val telemetry: StateFlow<Telemetry?> = combine(
        _source.flatMapLatest { it?.telemetry ?: flowOf(null) }, _settings.map { it.activeProfile },
    ) { t, p -> t?.withBatteryFrom(p) }.stateIn(scope, SharingStarted.Eagerly, null)

    fun startSource() {
        stopSource()
        val s = _settings.value
        val profile = { _settings.value.activeProfile }
        val src: WheelDataSource? = when (s.source) {
            SourceKind.SIMULATOR -> SimulatorSource(scope, profile, clock).also { simulator = it }
            SourceKind.DIRECT_BLE -> {
                val addr = s.wheelAddress
                if (addr == null) { _notices.value = "Escolha a roda na lista antes de conectar."; null }
                else if (!BlePermissions.hasAll(context)) { _notices.value = "Conceda a permissão de Bluetooth primeiro."; null }
                else DirectBleSource(context, scope, addr, layoutsFor(s.activeProfile), { l -> decoderFor(s.activeProfile, l) }, profile, clock)
            }
            SourceKind.WHEELLOG_BRIDGE -> WheelLogBridgeSource(context, clock)
        }
        if (src == null) return
        _source.value = src
        src.start()
        ConnectionService.sync(context, this)
    }

    fun stopSource() {
        _source.value?.stop()
        _source.value = null
        simulator = null
        ConnectionService.sync(context, this)
    }

    private fun layoutsFor(p: WheelProfile): List<GattLayout> = when (p.protocol) {
        WheelProtocol.AUTO -> GattLayout.WHEELS
        WheelProtocol.INMOTION_V1 -> listOf(GattLayout.INMOTION_V1)
        WheelProtocol.INMOTION_V2 -> listOf(GattLayout.INMOTION_V2)
        else -> listOf(GattLayout.UART)
    }

    /** Begode envia a tensão como se o pack fosse 16S (67,2 V): escala pelo pack do perfil, como no WheelLog. */
    private fun gotwayScale(p: WheelProfile) = if (p.fullVoltage > 70f) p.fullVoltage / 67.2f else 1f

    private fun decoderFor(p: WheelProfile, layout: GattLayout): WheelDecoder {
        val gotway = { GotwayDecoder(GotwayConfig(voltageScale = gotwayScale(p), calibration = p.pwmCalibration)) }
        val veteran = { VeteranDecoder(VeteranConfig(calibration = p.pwmCalibration)) }
        return when (layout) {
            GattLayout.INMOTION_V2 -> InmotionV2Decoder()
            GattLayout.INMOTION_V1 -> InmotionV1Decoder(calibration = p.pwmCalibration)
            else -> when (p.protocol) {
                WheelProtocol.GOTWAY -> gotway()
                WheelProtocol.VETERAN -> veteran()
                WheelProtocol.KINGSONG -> KingsongDecoder()
                else -> AutoDecoder(gotway(), veteran(), KingsongDecoder())
            }
        }
    }

    /** Há algo conectado que exige manter o processo vivo? (usado pelo serviço em primeiro plano) */
    val needsForeground: StateFlow<Boolean> = combine(_source, sourceStatus, ledLink, _settings) { src, st, link, set ->
        val wheel = src != null && src.kind != SourceKind.SIMULATOR && st !is SourceStatus.Idle
        val ledReal = !set.ledSimulated && link != LedLinkState.DISCONNECTED
        wheel || ledReal
    }.stateIn(scope, SharingStarted.Eagerly, false)

    // ---- segurança ----------------------------------------------------------------------------

    private val engine = SafetyEngine()
    private val _safety = MutableStateFlow<SafetyOutput?>(null)
    /** Saída do motor de segurança; null quando ele não está controlando a fita. */
    val safety: StateFlow<SafetyOutput?> = _safety.asStateFlow()
    private var manualSnapshot: LedState? = null
    private var driving = false

    private suspend fun safetyLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            val set = _settings.value
            val src = _source.value
            val active = set.safety.enabled && src != null && sourceStatus.value !is SourceStatus.Idle
            if (active) {
                val controller = _led.value
                if (!driving) { manualSnapshot = controller.state.value; driving = true; engine.reset() }
                val out = engine.update(telemetry.value, clock.nowMs(), set.activeProfile, set.safety)
                _safety.value = out
                controller.applyFrame(out.frame)
            } else if (driving) {
                driving = false
                _safety.value = null
                engine.reset()
                manualSnapshot?.let { restoreManual(_led.value, it) }
            }
        }
    }

    /** true enquanto o motor de segurança está dono da fita (a UI manual fica bloqueada). */
    val safetyDriving: StateFlow<Boolean> = _safety.map { it != null }.stateIn(scope, SharingStarted.Eagerly, false)

    // ---- ciclo de vida ------------------------------------------------------------------------

    @OptIn(FlowPreview::class)
    fun start() {
        scope.launch {
            _settings.value = repo.load()
            val s = _settings.value
            fakeLed.let { f -> restoreManual(f, s.led) }
            _loaded.value = true
            if (!s.ledSimulated && s.ledAddress != null && BlePermissions.hasAll(context)) connectLed(s.ledAddress, s.ledName)
            _settings.drop(1).debounce(600).collect { repo.save(it) }
        }
        scope.launch { safetyLoop() }
        // guarda o estado manual da fita nas configurações (só quando a segurança não está controlando)
        scope.launch {
            ledState.debounce(800).collect { st ->
                if (!driving && _loaded.value) update { it.copy(led = st.copy(bar = null)) }
            }
        }
    }

    fun stopAll() { stopSource(); disconnectLed() }

    companion object { const val TICK_MS = 100L }
}
