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
import apexled.core.LedFrame
import apexled.core.Rgb
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
import apexled.app.data.StripConfig
import apexled.app.data.StripShow
import apexled.app.extras.TripRecorder
import apexled.app.extras.VoiceAlerts
import apexled.app.data.LedSide
import apexled.app.data.TurnSide
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
    private val _safetyActive = MutableStateFlow(false)
    val led: StateFlow<LedController> = _led.asStateFlow()

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    /** Estado mostrado na prévia e nas abas Cor/Efeitos: o manual do grupo, ou o da segurança enquanto ela controla. */
    val ledState: StateFlow<LedState> = _led.flatMapLatest { c ->
        if (c is FanoutLedController) combine(c.manual, c.state, _safetyActive) { m, st, sf -> if (sf) st else m } else c.state
    }.stateIn(scope, SharingStarted.Eagerly, LedState())
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ledLink: StateFlow<LedLinkState> = _led.flatMapLatest { it.link }.stateIn(scope, SharingStarted.Eagerly, LedLinkState.CONNECTED)
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    val ledName: StateFlow<String?> = _led.flatMapLatest { it.deviceName }.stateIn(scope, SharingStarted.Eagerly, null)
    val gattRssi get() = gattLed.rssi

    /** Último erro de conexão da fita ligada a este controlador (para mostrar na lista). */
    fun ledError(c: LedController): StateFlow<String?> =
        _extraLeds.value.firstOrNull { it.controller === c }?.gatt?.lastError ?: gattLed.lastError

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
            Sp621eController(scope, BleLedTransport(gattLed), initial = _settings.value.led, minFlushIntervalMs = TICK_MS, interPacketDelayMs = 120).also {
            realLed = it
            scope.launch { it.notices.collect { msg -> _notices.value = msg } }
        }
        val group = realGroup ?: FanoutLedController(
            real, { _extraLeds.value.map { it.controller } }, { c -> stripOf(c).show == StripShow.SAFETY }, _settings.value.led,
        ).also { realGroup = it }
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

    /** Muda a configuração de uma fita (address null = principal). */
    fun updateStrip(address: String?, f: (StripConfig) -> StripConfig) = update { s ->
        if (address == null || address == s.ledAddress) s.copy(ledStrip = f(s.ledStrip))
        else s.copy(extraLeds = s.extraLeds.map { e -> if (e.address == address) e.copy(strip = f(e.strip)) else e })
    }

    /** Configuração da fita ligada a este controlador. */
    fun stripOf(c: LedController): StripConfig {
        val s = _settings.value
        val e = _extraLeds.value.firstOrNull { it.controller === c } ?: return s.ledStrip
        return s.extraLeds.firstOrNull { it.address == e.address }?.strip ?: StripConfig()
    }

    /** Backup: todas as configurações em JSON. */
    fun exportSettings(): String = apexled.app.data.SettingsCodec.encode(_settings.value)

    /** Restaura um backup; false se o arquivo não for um backup válido. */
    fun importSettings(json: String): Boolean {
        val ok = runCatching { org.json.JSONObject(json).has("profiles") }.getOrDefault(false)
        if (!ok) return false
        val restored = apexled.app.data.SettingsCodec.decode(json)
        update { restored.copy(disclaimerAccepted = true) }
        return true
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
        val ctrl = Sp621eController(scope, BleLedTransport(gatt), initial = realLed?.state?.value ?: _settings.value.led, minFlushIntervalMs = TICK_MS, interPacketDelayMs = 120)
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
        if (s.tripLog) tripRecorder = runCatching { TripRecorder(context) }.getOrNull()
        ConnectionService.sync(context, this)
    }

    fun stopSource() {
        tripRecorder?.close(); tripRecorder = null
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

    // ---- setas -------------------------------------------------------------------------------

    @Volatile private var tripRecorder: TripRecorder? = null
    private var voice: VoiceAlerts? = null

    private val brakeDetector = BrakeDetector()

    data class TurnState(val side: TurnSide, val untilMs: Long)
    private val _turn = MutableStateFlow<TurnState?>(null)
    /** Seta ligada agora (null = nenhuma). */
    val turn: StateFlow<TurnState?> = _turn.asStateFlow()
    private var turnSnapshot: LedState? = null

    /** Liga a seta (ou troca de lado); a mesma seta de novo, ou null, desliga. */
    fun signal(side: TurnSide?) {
        val cur = _turn.value
        _turn.value = if (side == null || cur?.side == side) null
        else TurnState(side, clock.nowMs() + _settings.value.turnSignalSeconds.coerceIn(2, 120) * 1000L)
    }

    /** Cada fita com a sua configuração (a principal é a primeira). */
    private fun targets(): List<Pair<LedController, StripConfig>> {
        val c = _led.value
        if (c !is FanoutLedController) return listOf(c to _settings.value.ledStrip)
        return c.all().map { it to stripOf(it) }
    }

    /** O que já foi aplicado em cada fita com conteúdo próprio (para não reenviar todo ciclo). */
    private val applied = java.util.WeakHashMap<LedController, String>()

    private fun manualState(): LedState = (_led.value as? FanoutLedController)?.manual?.value ?: manualSnapshot ?: _settings.value.led

    private fun blinks(turn: TurnSide, side: LedSide) = when (side) {
        LedSide.BOTH -> true
        LedSide.HELMET, LedSide.WHEEL -> turn == TurnSide.HAZARD
        LedSide.LEFT -> turn != TurnSide.RIGHT
        LedSide.RIGHT -> turn != TurnSide.LEFT
    }

    private fun dimmed(f: LedFrame, level: Float) = if (level >= 0.999f) f else f.copy(brightness = (f.brightness * level).toInt())

    private suspend fun safetyLoop() {
        while (scope.isActive) {
            delay(TICK_MS)
            val now = clock.nowMs()
            val set = _settings.value
            val src = _source.value
            val level = set.dayNight.levelAt(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY))
            val active = set.safety.enabled && src != null && sourceStatus.value !is SourceStatus.Idle

            // seta: expira sozinha
            var turn = _turn.value
            if (turn != null && now >= turn.untilMs) { _turn.value = null; turn = null }
            val wasTurning = turnSnapshot != null
            if (turn != null && !wasTurning) turnSnapshot = _led.value.state.value

            _safetyActive.value = active
            var safetyFrame: LedFrame? = null
            if (active) {
                val controller = _led.value
                if (!driving) { manualSnapshot = turnSnapshot ?: controller.state.value; driving = true; engine.reset() }
                val out = engine.update(telemetry.value, now, set.activeProfile, set.safety)
                _safety.value = out
                safetyFrame = dimmed(out.frame, level)
            } else if (driving) {
                driving = false
                _safety.value = null
                engine.reset()
                // as fitas "Segurança" voltam ao estado manual no laço abaixo
            }

            // Freio: vermelho forte nas fitas com "luz de freio" ligada, sempre que a roda estiver conectada.
            val wheelOn = src != null && sourceStatus.value is SourceStatus.Connected
            val braking = wheelOn && brakeDetector.update(telemetry.value, now, set.safety.brake)
            val on = (now % TURN_PERIOD_MS) < TURN_PERIOD_MS / 2
            val amber = LedFrame(TURN_COLOR, if (on) (255 * level).toInt() else 0)

            for ((c, cfg) in targets()) {
                val overlay = when {
                    turn != null && cfg.turnSignals && blinks(turn.side, cfg.side) -> amber
                    cfg.brakeLight && braking -> LedFrame(Rgb(255, 0, 0), (255 * level).toInt())
                    else -> null
                }
                if (overlay != null) { c.applyFrame(overlay); applied[c] = "overlay"; continue }
                when (cfg.show) {
                    StripShow.SAFETY -> if (safetyFrame != null) { c.applyFrame(safetyFrame); applied[c] = "safety" }
                        else if (applied[c] != null && applied[c] != "manual") { restoreManual(c, manualState()); applied[c] = "manual" }
                    StripShow.COLOR -> {
                        val key = "color:${cfg.color}:$level"
                        if (applied[c] != key) { c.applyFrame(LedFrame(cfg.color, (255 * level).toInt())); applied[c] = key }
                    }
                    StripShow.EFFECT -> {
                        val key = "fx:${cfg.effectId}:${cfg.effectSpeed}:$level"
                        if (applied[c] != key) {
                            c.send(LedCommand.Power(true)); c.send(LedCommand.Effect(cfg.effectId))
                            c.send(LedCommand.EffectSpeed(cfg.effectSpeed)); c.send(LedCommand.Brightness((255 * level).toInt()))
                            applied[c] = key
                        }
                    }
                    StripShow.OFF -> if (applied[c] != "off") { c.send(LedCommand.Power(false)); applied[c] = "off" }
                }
            }
            if (turn == null && wasTurning) turnSnapshot = null
            tripRecorder?.onTick(now, telemetry.value, _safety.value)
            voice?.onTick(now, telemetry.value, sourceStatus.value, set.voice)
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
        voice = VoiceAlerts(context) { msg -> _notices.value = msg }
        scope.launch { safetyLoop() }
        // guarda o estado manual da fita nas configurações (só quando a segurança não está controlando)
        scope.launch {
            ledState.debounce(800).collect { st ->
                if (!driving && turnSnapshot == null && _loaded.value) update { it.copy(led = st.copy(bar = null)) }
            }
        }
    }

    fun stopAll() { stopSource(); disconnectLed() }

    companion object {
        /** Ciclo do motor de segurança: 50 ms = 20 atualizações por segundo na fita. */
        const val TICK_MS = 50L
        const val TURN_PERIOD_MS = 700L
        /** Brilho da lanterna do capacete sem frear (0..255). */
        const val TAIL_LEVEL = 45
        val TURN_COLOR = Rgb(255, 140, 0)
    }
}

/** Detecta frenagem pela corrente de regeneração ou pela desaceleração (limites de [BrakeConfig]). */
private class BrakeDetector {
    private var prevTs = -1L
    private var prevSpeed = 0f
    private var holdUntil = 0L

    fun update(t: Telemetry?, now: Long, cfg: apexled.safety.BrakeConfig): Boolean {
        if (t != null && t.timestampMs != prevTs) {
            var hit = (t.currentA ?: 0f) <= -cfg.regenCurrentA
            if (prevTs >= 0) {
                val dt = (t.timestampMs - prevTs) / 1000f
                if (dt in 0.03f..0.6f && (prevSpeed - t.speedKmh) / dt >= cfg.decelKmhPerS) hit = true
            }
            prevTs = t.timestampMs; prevSpeed = t.speedKmh
            if (hit) holdUntil = now + cfg.holdMs.coerceAtLeast(400)
        }
        return now < holdUntil
    }
}
