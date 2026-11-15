package apexled.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Amostra de telemetria já normalizada para unidades SI "de painel".
 *
 * @property timestampMs relógio monotônico (ms) do instante em que a amostra foi recebida/gerada.
 *   O motor de segurança usa isto para detectar perda de dados; use sempre o mesmo relógio.
 * @property speedKmh velocidade em km/h (sempre ≥ 0).
 * @property pwm carga do motor, fração 0..1 (pode passar de 1 em frames ruidosos). null = desconhecido.
 * @property pwmEstimated true quando o PWM foi calculado (velocidade ÷ velocidade máxima na tensão atual),
 *   false quando veio medido da controladora da roda.
 * @property currentA corrente de bateria em A. Convenção: positivo = descarga, negativo = regeneração.
 *   null = a roda não informou.
 */
data class Telemetry(
    val timestampMs: Long,
    val speedKmh: Float,
    val pwm: Float?,
    val voltage: Float,
    val currentA: Float?,
    val batteryPercent: Float?,
    val temperatureC: Float?,
    val pwmEstimated: Boolean = false,
)

enum class SourceKind { SIMULATOR, DIRECT_BLE, WHEELLOG_BRIDGE }

sealed interface SourceStatus {
    data object Idle : SourceStatus
    data class Connecting(val detail: String = "") : SourceStatus
    data class Connected(val deviceName: String?) : SourceStatus
    data class Failed(val reason: String) : SourceStatus
}

/**
 * Fonte de telemetria da roda. Implementações: simulador, BLE direto e ponte com o WheelLog.
 * [telemetry] guarda a última amostra; null enquanto nada chegou.
 */
interface WheelDataSource {
    val kind: SourceKind
    val status: StateFlow<SourceStatus>
    val telemetry: StateFlow<Telemetry?>

    /** Nome do modelo detectado pela própria fonte (ex.: "Nosfet Apex"), se houver. */
    val detectedModel: StateFlow<String?>

    fun start()
    fun stop()
}

/** Relógio monotônico em ms, injetável para testes. Telemetria e motor de segurança devem usar o MESMO relógio. */
fun interface Clock {
    fun nowMs(): Long

    companion object {
        val SYSTEM = Clock { System.nanoTime() / 1_000_000L }
    }
}

/** Preenche o percentual de bateria pela curva do perfil quando a roda não o informa. */
fun Telemetry.withBatteryFrom(profile: WheelProfile): Telemetry =
    if (batteryPercent != null) this else copy(batteryPercent = profile.batteryPercentFor(voltage))
