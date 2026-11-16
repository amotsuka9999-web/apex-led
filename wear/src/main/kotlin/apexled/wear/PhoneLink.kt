package apexled.wear

import android.content.Context
import androidx.compose.ui.graphics.Color
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.MessageClient
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/** O que o celular manda para o relógio. */
data class WatchState(
    val connected: Boolean = false,
    val safety: Boolean = false,
    val driving: Boolean = false,
    val wheel: Boolean = false,
    val led: Boolean = false,
    val speed: Float? = null,
    val pwm: Float? = null,
    val battery: Float? = null,
    val critical: Boolean = false,
    val ledColor: Color = Color(0xFF333333),
    val presets: List<String> = DEFAULT_PRESETS,
    /** LEFT, RIGHT, HAZARD ou null. */
    val turn: String? = null,
)

val DEFAULT_PRESETS = listOf("Arco-íris", "Fogo vermelho/amarelo", "Cometa azul", "Onda vermelho-azul", "Respiração verde", "Arco-íris estrelas")

/**
 * Conversa com o app do celular pela Wearable Data Layer (MessageClient). Os caminhos são os mesmos de
 * `apexled.app.wear.WearPaths` no celular.
 */
class PhoneLink(private val context: Context) : MessageClient.OnMessageReceivedListener {
    private val messages = Wearable.getMessageClient(context)
    private val nodes = Wearable.getNodeClient(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var job: Job? = null
    @Volatile private var lastStateMs = 0L

    private val _state = MutableStateFlow(WatchState())
    val state: StateFlow<WatchState> = _state.asStateFlow()

    fun start() {
        messages.addListener(this)
        job?.cancel()
        job = scope.launch {
            while (isActive) {
                send(PING, ByteArray(0))
                // sem notícias do celular há mais de 4 s: mostra "Sem celular"
                if (System.currentTimeMillis() - lastStateMs > 4000 && _state.value.connected) {
                    _state.value = _state.value.copy(connected = false, critical = false)
                }
                delay(if (_state.value.connected) 10_000 else 2_000)
            }
        }
    }

    fun stop() {
        job?.cancel(); job = null
        messages.removeListener(this)
    }

    fun setSafety(on: Boolean) {
        _state.value = _state.value.copy(safety = on)
        send(SAFETY, byteArrayOf(if (on) 1 else 0))
    }

    fun setColor(c: Color) {
        val r = (c.red * 255).toInt(); val g = (c.green * 255).toInt(); val b = (c.blue * 255).toInt()
        _state.value = _state.value.copy(safety = false, ledColor = c)
        send(COLOR, byteArrayOf(r.toByte(), g.toByte(), b.toByte()))
    }

    /** Mesmo lado de novo = desligar. */
    fun setTurn(side: String) {
        val off = _state.value.turn == side
        val code: Byte = if (off) 0 else when (side) { "LEFT" -> 1; "RIGHT" -> 2; else -> 3 }
        _state.value = _state.value.copy(turn = if (off) null else side)
        send(TURN, byteArrayOf(code))
    }

    fun setPreset(i: Int) {
        _state.value = _state.value.copy(safety = false)
        send(PRESET, byteArrayOf(i.toByte()))
    }

    private fun send(path: String, data: ByteArray) {
        scope.launch {
            val list = runCatching { Tasks.await(nodes.connectedNodes) }.getOrNull().orEmpty()
            list.forEach { n -> runCatching { messages.sendMessage(n.id, path, data) } }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != STATE) return
        val o = runCatching { JSONObject(String(event.data)) }.getOrNull() ?: return
        lastStateMs = System.currentTimeMillis()
        fun num(k: String): Float? = if (o.isNull(k)) null else o.optDouble(k).toFloat().takeIf { !it.isNaN() }
        val c = o.optJSONArray("color")
        val presets = o.optJSONArray("presets")?.let { a -> (0 until a.length()).map { a.optString(it) } }?.takeIf { it.size >= 6 }
        _state.value = WatchState(
            connected = true,
            safety = o.optBoolean("safety"),
            driving = o.optBoolean("driving"),
            wheel = o.optBoolean("wheel"),
            led = o.optBoolean("led"),
            speed = num("speed"),
            pwm = num("pwm"),
            battery = num("battery"),
            critical = o.optBoolean("critical"),
            ledColor = if (c != null && c.length() == 3) Color(c.optInt(0), c.optInt(1), c.optInt(2)) else Color(0xFF333333),
            presets = presets ?: DEFAULT_PRESETS,
            turn = if (o.isNull("turn")) null else o.optString("turn").takeIf { it.isNotEmpty() },
        )
        val st = _state.value
        if (st.safety != PhoneCommands.safety(context) || st.turn != PhoneCommands.turn(context)) {
            PhoneCommands.saveState(context, st.safety, st.turn)
            ApexTileService.refresh(context)
        }
    }

    companion object {
        const val PING = "/apexled/ping"
        const val SAFETY = "/apexled/safety"
        const val COLOR = "/apexled/color"
        const val PRESET = "/apexled/preset"
        const val STATE = "/apexled/state"
        const val TURN = "/apexled/turn"
    }
}
