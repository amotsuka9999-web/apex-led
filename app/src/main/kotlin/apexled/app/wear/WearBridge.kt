package apexled.app.wear

import android.content.Context
import apexled.app.AppGraph
import apexled.core.LedCommand
import apexled.core.LedLinkState
import apexled.core.Rgb
import apexled.core.SourceStatus
import apexled.led.Sp621eEffects
import apexled.safety.SafetyLevel
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Ponte com o app do relógio (Wear OS). O relógio é só um controle remoto: quem fala com a roda e com o
 * LED é o celular.
 *
 * Relógio → celular (MessageClient, caminhos em [WearPaths]): ligar/desligar a segurança, cor fixa, um dos 6
 * modos prontos e "ping" (tela aberta). Celular → relógio: o estado ([WearPaths.STATE], JSON) 2× por
 * segundo, só enquanto o relógio deu sinal de vida nos últimos 30 s, para não gastar bateria à toa.
 */
class WearBridge(private val context: Context, private val graph: AppGraph) {
    private val messages by lazy { Wearable.getMessageClient(context) }
    private val nodes by lazy { Wearable.getNodeClient(context) }

    @Volatile private var lastPingMs = 0L
    @Volatile private var pushing = false

    fun onMessage(path: String, data: ByteArray, fromNode: String) {
        lastPingMs = System.currentTimeMillis()
        when (path) {
            WearPaths.PING -> Unit
            WearPaths.SAFETY -> {
                val on = data.firstOrNull()?.toInt() == 1
                graph.update { it.copy(safety = it.safety.copy(enabled = on)) }
            }
            WearPaths.COLOR -> if (data.size >= 3) manual {
                val c = Rgb(data[0].toInt() and 0xFF, data[1].toInt() and 0xFF, data[2].toInt() and 0xFF)
                val led = graph.led.value
                if (c == Rgb(0, 0, 0)) led.send(LedCommand.Power(false))
                else { led.send(LedCommand.Power(true)); led.send(LedCommand.Solid(c, graph.ledState.value.brightness.coerceAtLeast(40))) }
            }
            WearPaths.PRESET -> {
                val i = data.firstOrNull()?.toInt() ?: return
                val name = PRESETS.getOrNull(i) ?: return
                val fx = Sp621eEffects.all.firstOrNull { it.name.equals(name, ignoreCase = true) } ?: return
                manual {
                    val led = graph.led.value
                    led.send(LedCommand.Power(true))
                    led.send(LedCommand.Effect(fx.id))
                }
            }
        }
        startPushing()
        sendStateTo(fromNode)
    }

    /** Pausa a segurança e só então manda o comando (o motor devolve o estado manual ao soltar a fita). */
    private fun manual(action: () -> Unit) {
        val wasOn = graph.settings.value.safety.enabled
        if (wasOn) graph.update { it.copy(safety = it.safety.copy(enabled = false)) }
        graph.scope.launch {
            if (wasOn) delay(AppGraph.TICK_MS * 3)
            action()
        }
    }

    private fun startPushing() {
        if (pushing) return
        pushing = true
        graph.scope.launch {
            try {
                while (isActive && System.currentTimeMillis() - lastPingMs < 30_000) {
                    delay(500)
                    val all = runCatching { com.google.android.gms.tasks.Tasks.await(nodes.connectedNodes) }.getOrNull().orEmpty()
                    all.forEach { sendStateTo(it.id) }
                }
            } finally { pushing = false }
        }
    }

    private fun sendStateTo(node: String) {
        val bytes = stateJson().toString().toByteArray()
        runCatching { messages.sendMessage(node, WearPaths.STATE, bytes) }
    }

    private fun stateJson(): JSONObject {
        val s = graph.settings.value
        val t = graph.telemetry.value
        val out = graph.safety.value
        val src = graph.sourceStatus.value
        val led = graph.ledState.value
        val color = out?.frame?.color ?: if (led.power) led.color else Rgb(0, 0, 0)
        return JSONObject()
            .put("safety", s.safety.enabled)
            .put("driving", out != null)
            .put("wheel", src is SourceStatus.Connected)
            .put("led", s.ledSimulated || graph.ledLink.value == LedLinkState.CONNECTED)
            .put("speed", t?.speedKmh?.toDouble() ?: JSONObject.NULL)
            .put("pwm", t?.pwm?.toDouble() ?: JSONObject.NULL)
            .put("battery", t?.batteryPercent?.toDouble() ?: JSONObject.NULL)
            .put("level", out?.level?.name ?: JSONObject.NULL)
            .put("critical", out?.level == SafetyLevel.CRITICAL || out?.flashing == true)
            .put("color", JSONArray().put(color.r).put(color.g).put(color.b))
            .put("presets", JSONArray(PRESETS))
    }

    companion object {
        /** Os 6 modos prontos do relógio (nomes da lista de efeitos do SP621E). */
        val PRESETS = listOf(
            "Arco-íris", "Fogo vermelho/amarelo", "Cometa azul",
            "Onda vermelho-azul", "Respiração verde", "Arco-íris estrelas",
        )
    }
}

object WearPaths {
    const val PING = "/apexled/ping"
    const val SAFETY = "/apexled/safety"
    const val COLOR = "/apexled/color"
    const val PRESET = "/apexled/preset"
    const val STATE = "/apexled/state"
}
