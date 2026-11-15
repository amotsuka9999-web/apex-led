package apexled.data

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import apexled.core.Clock
import apexled.core.SourceKind
import apexled.core.SourceStatus
import apexled.core.Telemetry
import apexled.core.WheelDataSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ponte com o WheelLog por broadcast.
 *
 * ACHADO (leitura do código, Wheellog.Android commit f44e39e): `WheelData.java` envia, a cada frame decodificado,
 * um broadcast IMPLÍCITO e sem permissão com a ação `com.cooper.wheellog.wheelDataAvailable`. Os extras usados:
 *   - "Speed"   Int, 0,01 km/h        - "PWM"     Double, fração 0..1 (medido ou estimado pelo WheelLog)
 *   - "Voltage" Int, 0,01 V           - "Current" Int, 0,01 A (corrente de bateria, WheelLog)
 *   - "Battery" Int, %                - "Temp1"   Int, 0,01 °C
 * Qualquer app com um receiver registrado em tempo de execução recebe esse broadcast. NÃO é uma API pública
 * documentada: pode mudar sem aviso. Ainda não foi testado em aparelho (só leitura de código).
 *
 * Vantagem: o WheelLog continua dono da conexão BLE com a roda (que só aceita um central por vez).
 */
class WheelLogBridgeSource(
    private val context: Context,
    private val clock: Clock = Clock.SYSTEM,
) : WheelDataSource {
    override val kind = SourceKind.WHEELLOG_BRIDGE
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()
    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    override val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()
    private val _model = MutableStateFlow<String?>(null)
    override val detectedModel: StateFlow<String?> = _model.asStateFlow()

    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context, intent: Intent) {
            when (intent.action) {
                ACTION_DATA -> {
                    _status.value = SourceStatus.Connected("WheelLog")
                    _telemetry.value = parseIntent(
                        speed = intent.getIntExtra("Speed", 0),
                        pwm = intent.getDoubleExtra("PWM", Double.NaN),
                        voltage = intent.getIntExtra("Voltage", 0),
                        current = intent.getIntExtra("Current", 0),
                        battery = intent.getIntExtra("Battery", -1),
                        temp = intent.getIntExtra("Temp1", Int.MIN_VALUE),
                        nowMs = clock.nowMs(),
                    )
                }
                ACTION_CONNECTION -> {
                    // O extra "connection_state" é um inteiro do WheelLog; só usamos para avisar quando cai.
                    val state = intent.getIntExtra("connection_state", -1)
                    if (state == 0) _status.value = SourceStatus.Connecting("WheelLog sem roda conectada")
                }
            }
        }
    }

    override fun start() {
        if (registered) return
        val filter = IntentFilter().apply { addAction(ACTION_DATA); addAction(ACTION_CONNECTION) }
        if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
        else @Suppress("UnspecifiedRegisterReceiverFlag") context.registerReceiver(receiver, filter)
        registered = true
        _status.value = SourceStatus.Connecting("Aguardando dados do WheelLog (abra o WheelLog e conecte a roda)")
    }

    override fun stop() {
        if (registered) runCatching { context.unregisterReceiver(receiver) }
        registered = false
        _status.value = SourceStatus.Idle
        _telemetry.value = null
    }

    companion object {
        const val ACTION_DATA = "com.cooper.wheellog.wheelDataAvailable"
        const val ACTION_CONNECTION = "com.cooper.wheellog.bluetoothConnectionState"

        /** Conversão das unidades do WheelLog para as do app. Pública para teste. */
        fun parseIntent(speed: Int, pwm: Double, voltage: Int, current: Int, battery: Int, temp: Int, nowMs: Long) = Telemetry(
            timestampMs = nowMs,
            speedKmh = kotlin.math.abs(speed) / 100f,
            pwm = if (pwm.isNaN()) null else kotlin.math.abs(pwm).toFloat(),
            voltage = voltage / 100f,
            currentA = current / 100f,
            batteryPercent = if (battery in 0..100) battery.toFloat() else null,
            temperatureC = if (temp == Int.MIN_VALUE) null else temp / 100f,
            pwmEstimated = false,
        )
    }
}
