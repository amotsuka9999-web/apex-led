package apexled.data

import apexled.core.Clock
import apexled.core.SourceKind
import apexled.core.SourceStatus
import apexled.core.Telemetry
import apexled.core.WheelDataSource
import apexled.core.WheelProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

enum class SimScenario(val label: String) {
    MANUAL("Manual (controles)"),
    ACCELERATION("Aceleração até o limite"),
    BRAKING("Frenagem forte"),
    CLIMB("Subida (PWM sobe, velocidade cai)"),
    SIGNAL_LOSS("Perda de sinal da roda"),
    CRUISE_WAVES("Cruzeiro com ondulações"),
}

/** Controles manuais do simulador. */
data class SimManual(
    val speedKmh: Float = 20f,
    val pwm: Float = 0.35f,
    val batteryPercent: Float = 80f,
)

/** Amostra do modelo; [signal] false = a roda "não enviou" nada neste instante. */
data class SimSample(val speedKmh: Float, val pwm: Float, val batteryPercent: Float, val currentA: Float, val signal: Boolean)

/**
 * Modelo determinístico dos cenários (função do tempo desde o início do cenário). Separado do emissor
 * para ser testável sem relógio nem corrotinas.
 */
object SimulatorModel {
    fun sample(scenario: SimScenario, tSec: Float, manual: SimManual, maxSpeedKmh: Float): SimSample = when (scenario) {
        SimScenario.MANUAL -> SimSample(manual.speedKmh, manual.pwm, manual.batteryPercent, manual.pwm * 40f, true)
        SimScenario.ACCELERATION -> {
            // 0 → vmax em 14 s, mantém 4 s, volta a 0 em 6 s (ciclo de 24 s)
            val c = tSec % 24f
            val v = when {
                c < 14f -> maxSpeedKmh * (c / 14f)
                c < 18f -> maxSpeedKmh
                else -> maxSpeedKmh * (1f - (c - 18f) / 6f)
            }
            val accel = c < 14f
            SimSample(v, (v / maxSpeedKmh * 1.0f).coerceIn(0f, 1.05f), manual.batteryPercent, if (accel) 45f else 10f, true)
        }
        SimScenario.BRAKING -> {
            // 25 s: roda a 80% da vmax, freia forte em 3 s (regeneração = corrente negativa), repete
            val c = tSec % 12f
            val top = maxSpeedKmh * 0.8f
            val v = if (c < 6f) top else (top * (1f - (c - 6f) / 3f)).coerceAtLeast(0f)
            val braking = c in 6f..9f
            SimSample(v, (v / maxSpeedKmh * 0.9f).coerceIn(0f, 1f), manual.batteryPercent, if (braking) -35f else 20f, true)
        }
        SimScenario.CLIMB -> {
            // velocidade cai de 30 para 12 km/h enquanto o PWM sobe de 0,45 até 0,97 em 20 s, depois alivia
            val c = tSec % 30f
            val k = if (c < 20f) c / 20f else (1f - (c - 20f) / 10f)
            SimSample(30f - 18f * k, 0.45f + 0.52f * k, manual.batteryPercent, 20f + 60f * k, true)
        }
        SimScenario.SIGNAL_LOSS -> {
            // 6 s normais, 6 s sem dados
            val c = tSec % 12f
            SimSample(manual.speedKmh, manual.pwm, manual.batteryPercent, 15f, c < 6f)
        }
        SimScenario.CRUISE_WAVES -> {
            val v = maxSpeedKmh * 0.55f + maxSpeedKmh * 0.25f * sin(2f * PI.toFloat() * tSec / 20f)
            val pwm = 0.55f + 0.30f * sin(2f * PI.toFloat() * tSec / 20f + 0.4f)
            SimSample(v, pwm, manual.batteryPercent, pwm * 50f, true)
        }
    }
}

/**
 * Fonte simulada: gera telemetria falsa a 10 Hz. Obrigatória para testar toda a lógica de LED e a UI sem
 * roda e sem o controlador.
 */
class SimulatorSource(
    private val scope: CoroutineScope,
    private val profile: () -> WheelProfile,
    private val clock: Clock = Clock.SYSTEM,
    private val periodMs: Long = 100,
) : WheelDataSource {
    override val kind = SourceKind.SIMULATOR
    private val _status = MutableStateFlow<SourceStatus>(SourceStatus.Idle)
    override val status: StateFlow<SourceStatus> = _status.asStateFlow()
    private val _telemetry = MutableStateFlow<Telemetry?>(null)
    override val telemetry: StateFlow<Telemetry?> = _telemetry.asStateFlow()
    private val _model = MutableStateFlow<String?>(null)
    override val detectedModel: StateFlow<String?> = _model.asStateFlow()

    private val _scenario = MutableStateFlow(SimScenario.MANUAL)
    val scenario: StateFlow<SimScenario> = _scenario.asStateFlow()
    private val _manual = MutableStateFlow(SimManual())
    val manual: StateFlow<SimManual> = _manual.asStateFlow()

    private var job: Job? = null
    private var scenarioStartMs = clock.nowMs()

    fun setScenario(s: SimScenario) { scenarioStartMs = clock.nowMs(); _scenario.value = s }
    fun setManual(m: SimManual) { _manual.value = m }

    override fun start() {
        if (job?.isActive == true) return
        scenarioStartMs = clock.nowMs()
        _status.value = SourceStatus.Connected("Simulador")
        _model.value = profile().name
        job = scope.launch {
            while (isActive) {
                emitOnce()
                delay(periodMs)
            }
        }
    }

    internal fun emitOnce() {
        val now = clock.nowMs()
        val p = profile()
        val s = SimulatorModel.sample(_scenario.value, (now - scenarioStartMs) / 1000f, _manual.value, p.maxSpeedKmh)
        if (!s.signal) return   // simula a roda calada: o timestamp da última amostra envelhece
        val volts = p.emptyVoltage + (p.fullVoltage - p.emptyVoltage) * (s.batteryPercent / 100f)
        _telemetry.value = Telemetry(
            timestampMs = now,
            speedKmh = s.speedKmh,
            pwm = s.pwm,
            voltage = volts,
            currentA = s.currentA,
            batteryPercent = s.batteryPercent,
            temperatureC = 35f + 25f * s.pwm,
            pwmEstimated = false,
        )
    }

    override fun stop() {
        job?.cancel(); job = null
        _status.value = SourceStatus.Idle
        _telemetry.value = null
    }
}
