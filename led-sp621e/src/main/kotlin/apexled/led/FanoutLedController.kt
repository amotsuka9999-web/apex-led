package apexled.led

import apexled.core.LedCommand
import apexled.core.LedController
import apexled.core.LedFrame
import apexled.core.LedLinkState
import apexled.core.LedState
import kotlinx.coroutines.flow.StateFlow

/**
 * Várias fitas com a mesma configuração, como o "grupo" do BanlanX (até 10 controladores): todo comando e
 * todo quadro do motor de segurança vão para a fita principal e para as extras. Estado, nome e conexão
 * mostrados são os da principal; o estado de cada extra fica em [others].
 */
class FanoutLedController(
    private val primary: LedController,
    private val others: () -> List<LedController>,
) : LedController {
    override val link: StateFlow<LedLinkState> get() = primary.link
    override val state: StateFlow<LedState> get() = primary.state
    override val deviceName: StateFlow<String?> get() = primary.deviceName

    override fun send(command: LedCommand) {
        primary.send(command)
        others().forEach { it.send(command) }
    }

    override fun applyFrame(frame: LedFrame) {
        primary.applyFrame(frame)
        others().forEach { it.applyFrame(frame) }
    }

    companion object {
        /** Limite do BanlanX. Muitos celulares Android aguentam ~7 conexões BLE ao mesmo tempo. */
        const val MAX_DEVICES = 10
    }
}
