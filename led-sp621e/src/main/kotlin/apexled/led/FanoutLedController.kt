package apexled.led

import apexled.core.LedCommand
import apexled.core.LedController
import apexled.core.LedFrame
import apexled.core.LedLinkState
import apexled.core.LedState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Grupo de fitas (até 10, como o BanlanX). Os comandos manuais (abas Cor e Efeitos, relógio) vão só para as
 * fitas que seguem o grupo ([follows]); as que têm cor/efeito próprios ficam com o delas. Comandos de
 * configuração do hardware (ordem de cor, nº de pixels, tipo de CI) vão para todas.
 */
class FanoutLedController(
    val primary: LedController,
    private val others: () -> List<LedController>,
    private val follows: (LedController) -> Boolean = { true },
    initialManual: LedState = primary.state.value,
) : LedController {
    override val link: StateFlow<LedLinkState> get() = primary.link
    override val state: StateFlow<LedState> get() = primary.state
    override val deviceName: StateFlow<String?> get() = primary.deviceName

    private val _manual = MutableStateFlow(initialManual)
    /** Último estado manual pedido ao grupo (o que as fitas "Segurança" mostram com a segurança desligada). */
    val manual: StateFlow<LedState> = _manual.asStateFlow()

    fun all(): List<LedController> = listOf(primary) + others()

    override fun send(command: LedCommand) {
        _manual.value = reduce(_manual.value, command)
        val hardware = command is LedCommand.Order || command is LedCommand.PixelCount || command is LedCommand.IcType
        all().forEach { if (hardware || follows(it)) it.send(command) }
    }

    override fun applyFrame(frame: LedFrame) {
        all().forEach { if (follows(it)) it.applyFrame(frame) }
    }

    companion object {
        /** Limite do BanlanX. Muitos celulares Android aguentam ~7 conexões BLE ao mesmo tempo. */
        const val MAX_DEVICES = 10

        fun reduce(s: LedState, c: LedCommand): LedState = when (c) {
            is LedCommand.Power -> s.copy(power = c.on)
            is LedCommand.Solid -> s.copy(color = c.color, brightness = c.brightness.coerceIn(0, 255), effectId = LedState.EFFECT_SOLID, bar = null)
            is LedCommand.Brightness -> s.copy(brightness = c.level.coerceIn(0, 255))
            is LedCommand.Effect -> s.copy(effectId = c.effectId, bar = null)
            is LedCommand.EffectSpeed -> s.copy(effectSpeed = c.speed)
            is LedCommand.EffectLength -> s.copy(effectLength = c.length)
            is LedCommand.Order -> s.copy(order = c.order)
            is LedCommand.PixelCount -> s.copy(pixelCount = c.count)
            is LedCommand.IcType -> s.copy(ic = c.ic)
        }
    }
}
