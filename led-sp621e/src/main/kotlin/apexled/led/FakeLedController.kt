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
 * Controlador simulado: não fala com hardware, só guarda o estado (a UI desenha a fita a partir dele) e
 * registra um log dos comandos — incluindo os bytes que o [Sp621eController] enviaria.
 */
class FakeLedController(initial: LedState = LedState(), private val maxLog: Int = 200) : LedController {
    private val _state = MutableStateFlow(initial)
    override val state: StateFlow<LedState> = _state.asStateFlow()
    private val _link = MutableStateFlow(LedLinkState.CONNECTED)
    override val link: StateFlow<LedLinkState> = _link.asStateFlow()
    private val _name = MutableStateFlow<String?>("SP621E (simulado)")
    override val deviceName: StateFlow<String?> = _name.asStateFlow()
    private val _log = MutableStateFlow<List<String>>(emptyList())
    val log: StateFlow<List<String>> = _log.asStateFlow()

    private var mirror = DeviceMirror()

    override fun send(command: LedCommand) {
        var s = _state.value
        s = when (command) {
            is LedCommand.Power -> s.copy(power = command.on)
            is LedCommand.Solid -> s.copy(color = command.color, brightness = command.brightness.coerceIn(0, 255), effectId = LedState.EFFECT_SOLID, bar = null)
            is LedCommand.Brightness -> s.copy(brightness = command.level.coerceIn(0, 255))
            is LedCommand.Effect -> s.copy(effectId = command.effectId, bar = null)
            is LedCommand.EffectSpeed -> s.copy(effectSpeed = command.speed.coerceIn(1, 10))
            is LedCommand.EffectLength -> s.copy(effectLength = command.length.coerceIn(1, 150))
            is LedCommand.Order -> s.copy(order = command.order)
            is LedCommand.PixelCount -> s.copy(pixelCount = command.count.coerceIn(1, 2048))
            is LedCommand.IcType -> s.copy(ic = command.ic)
        }
        _state.value = s
        record(command.toString())
        logPackets(s)
    }

    override fun applyFrame(frame: LedFrame) {
        val level = frame.brightness.coerceIn(0, 255) / 255f
        val s = _state.value.copy(
            power = true, effectId = LedState.EFFECT_SOLID, color = frame.color.scaled(level), brightness = 255, bar = frame.bar, barBrightness = frame.brightness,
        )
        _state.value = s
        logPackets(s)
    }

    private fun logPackets(s: LedState) {
        val (packets, m) = planPackets(mirror, s)
        mirror = m
        packets.forEach { record("→ " + it.joinToString(" ") { b -> "%02X".format(b) }) }
    }

    private fun record(line: String) {
        _log.value = (_log.value + line).takeLast(maxLog)
    }
}
