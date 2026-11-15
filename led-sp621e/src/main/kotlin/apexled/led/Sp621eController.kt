package apexled.led

import apexled.core.LedCommand
import apexled.core.LedController
import apexled.core.LedFrame
import apexled.core.LedLinkState
import apexled.core.LedState
import apexled.core.Rgb
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Canal de bytes até o controlador (no Android: GATT FFE0/FFE1). */
interface LedTransport {
    val link: StateFlow<LedLinkState>
    val deviceName: StateFlow<String?>
    /** Notificações recebidas do controlador (status). */
    val notifications: SharedFlow<ByteArray>
    /** Escreve um pacote; false = falhou (o controlador faz nova sincronização completa depois). */
    suspend fun write(data: ByteArray): Boolean
}

/**
 * O que o controlador físico acredita ter agora (espelho do que já foi enviado). null = desconhecido.
 */
internal data class DeviceMirror(
    val power: Boolean? = null,
    val effect: Int? = null,
    val color: Rgb? = null,
    val level: Int? = null,
    val speed: Int? = null,
    val length: Int? = null,
    val brightness: Int? = null,
)

/** Calcula os pacotes necessários para levar o controlador de [mirror] a [want]. Função pura. */
internal fun planPackets(mirror: DeviceMirror, want: LedState): Pair<List<ByteArray>, DeviceMirror> {
    val out = ArrayList<ByteArray>()
    var m = mirror
    if (m.power != want.power) {
        out += Sp621ePackets.power(want.power)
        m = m.copy(power = want.power)
    }
    if (!want.power) return out to m
    if (m.effect != want.effectId) {
        out += Sp621ePackets.effect(want.effectId)
        m = m.copy(effect = want.effectId)
    }
    if (want.effectId == Sp621ePackets.EFFECT_SOLID) {
        if (m.color != want.color || m.level != want.brightness) {
            out += Sp621ePackets.color(want.color, want.brightness)
            m = m.copy(color = want.color, level = want.brightness, brightness = want.brightness)
        }
    } else {
        if (m.speed != want.effectSpeed) {
            out += Sp621ePackets.speed(want.effectSpeed); m = m.copy(speed = want.effectSpeed)
        }
        if (m.length != want.effectLength) {
            out += Sp621ePackets.length(want.effectLength); m = m.copy(length = want.effectLength)
        }
        if (m.brightness != want.brightness) {
            out += Sp621ePackets.brightness(want.brightness); m = m.copy(brightness = want.brightness)
        }
    }
    return out to m
}

/**
 * Controlador do SP621E.
 *
 * - Mantém o estado DESEJADO e só envia diferenças (cor/efeito mudou).
 * - Coalesce: comandos que chegam enquanto aguarda o próximo envio são fundidos; no máximo uma rodada de
 *   envio a cada [minFlushIntervalMs] (padrão 100 ms = 10 Hz), com [interPacketDelayMs] entre pacotes da mesma rodada.
 * - Ao (re)conectar, ou depois de uma escrita que falhou, reenvia o estado completo.
 * - Configuração da fita (ordem de cor etc.) só é enviada quando o usuário a muda explicitamente — nunca
 *   na sincronização automática, para não alterar a configuração persistente do controlador sem querer.
 */
class Sp621eController(
    private val scope: CoroutineScope,
    private val transport: LedTransport,
    initial: LedState = LedState(),
    private val minFlushIntervalMs: Long = 100,
    private val interPacketDelayMs: Long = 15,
) : LedController {

    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LedState> = _state.asReadOnly()
    override val link: StateFlow<LedLinkState> get() = transport.link
    override val deviceName: StateFlow<String?> get() = transport.deviceName

    private val _status = MutableStateFlow<Sp621eStatus?>(null)
    /** Último status lido do controlador (se ele responder à consulta). */
    val status: StateFlow<Sp621eStatus?> = _status.asReadOnly()

    private val _notices = MutableSharedFlow<String>(extraBufferCapacity = 16)
    /** Avisos para a UI, ex.: comandos ainda sem protocolo conhecido. */
    val notices: SharedFlow<String> = _notices

    private var mirror = DeviceMirror()
    private val oneShots = ArrayDeque<ByteArray>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private val parser = Sp621eStatusParser()

    /** Contadores para diagnóstico/teste. */
    @Volatile var packetsWritten: Long = 0; private set

    init {
        scope.launch {
            transport.link.collect { s ->
                if (s == LedLinkState.CONNECTED) {
                    synchronized(this@Sp621eController) { mirror = DeviceMirror() }
                    parser.reset()
                    synchronized(this@Sp621eController) { oneShots.addLast(Sp621ePackets.stateQuery()) }
                    wake.trySend(Unit)
                }
            }
        }
        scope.launch {
            transport.notifications.collect { data -> parser.feed(data)?.let { _status.value = it } }
        }
        scope.launch {
            for (ignored in wake) {
                if (transport.link.value == LedLinkState.CONNECTED) flush()
                delay(minFlushIntervalMs)
            }
        }
    }

    override fun send(command: LedCommand) {
        when (command) {
            is LedCommand.Power -> _state.value = _state.value.copy(power = command.on)
            is LedCommand.Solid -> _state.value = _state.value.copy(
                color = command.color, brightness = command.brightness.coerceIn(0, 255),
                effectId = LedState.EFFECT_SOLID, bar = null,
            )
            is LedCommand.Brightness -> _state.value = _state.value.copy(brightness = command.level.coerceIn(0, 255))
            is LedCommand.Effect -> _state.value = _state.value.copy(effectId = command.effectId, bar = null)
            is LedCommand.EffectSpeed -> _state.value = _state.value.copy(effectSpeed = command.speed.coerceIn(1, Sp621ePackets.MAX_SPEED))
            is LedCommand.EffectLength -> _state.value = _state.value.copy(effectLength = command.length.coerceIn(1, Sp621ePackets.MAX_LENGTH))
            is LedCommand.Order -> {
                _state.value = _state.value.copy(order = command.order)
                enqueueOneShot(Sp621ePackets.chipOrder(command.order))
            }
            is LedCommand.PixelCount -> {
                _state.value = _state.value.copy(pixelCount = command.count.coerceIn(1, 2048))
                val p = Sp621ePackets.pixelCount(command.count)
                if (p == null) _notices.tryEmit("Quantidade de pixels salva só no app: comando do SP621E ainda não capturado (docs/captura-ble.md).")
                else enqueueOneShot(p)
            }
            is LedCommand.IcType -> {
                _state.value = _state.value.copy(ic = command.ic)
                val p = Sp621ePackets.icType(command.ic.ordinal)
                if (p == null) _notices.tryEmit("Tipo de CI salvo só no app: comando do SP621E ainda não capturado (docs/captura-ble.md).")
                else enqueueOneShot(p)
            }
        }
        wake.trySend(Unit)
    }

    override fun applyFrame(frame: LedFrame) {
        // O brilho já é aplicado direto nos canais RGB: independe do significado exato do byte de nível,
        // e o quadro "apagado" do pisca fica realmente preto.
        val level = frame.brightness.coerceIn(0, 255) / 255f
        _state.value = _state.value.copy(
            power = true,
            effectId = LedState.EFFECT_SOLID,
            color = frame.color.scaled(level),
            brightness = 255,
            bar = frame.bar,
        )
        wake.trySend(Unit)
    }

    private fun enqueueOneShot(p: ByteArray) = synchronized(this) { oneShots.addLast(p) }

    private suspend fun flush() {
        val shots = synchronized(this) { val l = oneShots.toList(); oneShots.clear(); l }
        val (planned, newMirror) = planPackets(mirror, _state.value)
        val all = shots + planned
        if (all.isEmpty()) return
        for ((i, p) in all.withIndex()) {
            val ok = transport.write(p)
            if (!ok) {
                // Estado do controlador agora incerto: ressincroniza na próxima rodada.
                mirror = DeviceMirror()
                wake.trySend(Unit)
                return
            }
            packetsWritten++
            if (i < all.lastIndex) delay(interPacketDelayMs)
        }
        mirror = newMirror
    }

    private fun <T> MutableStateFlow<T>.asReadOnly(): StateFlow<T> = this.asStateFlow()
}
