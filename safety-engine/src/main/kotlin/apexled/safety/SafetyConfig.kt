package apexled.safety

import apexled.core.Rgb

/** Como a cor de segurança é calculada. */
enum class SafetyMode(val label: String) {
    /** Cor por faixa de PWM (modo principal). */
    PWM("PWM"),
    /** Cor por velocidade em relação ao limite do piloto. */
    SPEED("Velocidade"),
    /** Usa o PIOR entre PWM, velocidade e bateria baixa. Padrão recomendado. */
    COMBINED("Combinado"),
    /** A fita vira um barômetro: pixels acesos = % de PWM usado. */
    BAR("Barra de margem"),
}

/** Estado exibido quando a roda deixa de enviar dados. */
enum class FaultStyle(val label: String) {
    PULSE("Âmbar pulsando"),
    SOLID("Âmbar fixo"),
    FAST_FLASH("Âmbar piscando rápido"),
}

/**
 * Configuração do motor de segurança (independente da roda). Os limites de PWM por faixa ficam no
 * [apexled.core.WheelProfile].
 */
data class SafetyConfig(
    val enabled: Boolean = true,
    val mode: SafetyMode = SafetyMode.COMBINED,
    /** false = gradiente contínuo; true = cores em degraus (com histerese). */
    val stepped: Boolean = false,
    /** Limite de velocidade do piloto (km/h). null = usa tiltback do perfil, ou a velocidade máxima. */
    val speedLimitKmh: Float? = null,
    /** Brilho máximo da fita nos estados de segurança (0..255). */
    val brightness: Int = 255,
    /** Constante de tempo da SUBIDA do alerta (s). 0 = imediata (padrão). */
    val attackSeconds: Float = 0f,
    /** Constante de tempo da DESCIDA do alerta (s): quanto maior, mais lento o relaxamento. */
    val releaseSeconds: Float = 1.5f,
    /** Folga (fração de uso) exigida para descer de faixa/parar de piscar. */
    val hysteresis: Float = 0.03f,
    /** Sem amostra nova por este tempo → estado de falha. */
    val signalTimeoutMs: Long = 1500,
    val fault: FaultConfig = FaultConfig(),
    /** Período do pisca de alerta crítico (ms). 400 = 2,5 Hz; com a taxa BLE de 10 Hz dá 2 quadros por fase. */
    val flashPeriodMs: Long = 400,
    /** Inclui o fator de bateria baixa no modo Combinado. */
    val batteryFactor: Boolean = true,
    /** Filtro lento da bateria (s), para a queda de tensão sob carga não parecer bateria baixa. */
    val batteryFilterSeconds: Float = 5f,
    val restPulse: RestPulseConfig = RestPulseConfig(),
    val fixedColor: FixedColorConfig = FixedColorConfig(),
    val brake: BrakeConfig = BrakeConfig(),
)

data class FaultConfig(
    val style: FaultStyle = FaultStyle.PULSE,
    val color: Rgb = Rgb(255, 150, 0),
    val periodMs: Long = 1200,
)

/** Pulso lento quando parado. */
data class RestPulseConfig(
    val enabled: Boolean = false,
    val belowKmh: Float = 1f,
    val periodMs: Long = 3000,
    val minBrightnessFraction: Float = 0.35f,
)

/** Cor fixa abaixo de uma velocidade (só vale enquanto o uso estiver abaixo do "atenção"). */
data class FixedColorConfig(
    val enabled: Boolean = false,
    val belowKmh: Float = 5f,
    val color: Rgb = Rgb(0, 120, 255),
)

/** Flash branco quando a regeneração é forte (frenagem). */
data class BrakeConfig(
    val enabled: Boolean = false,
    /** Corrente de regeneração (A, valor absoluto) que dispara o flash. */
    val regenCurrentA: Float = 20f,
    /** Ou desaceleração (km/h por segundo). */
    val decelKmhPerS: Float = 12f,
    val holdMs: Long = 300,
    val flashPeriodMs: Long = 160,
)
