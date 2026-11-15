package apexled.core

/** Ponto da curva de bateria: tensão total do pack → percentual. */
data class BatteryPoint(val voltage: Float, val percent: Float)

/**
 * Parâmetros do cálculo de PWM estimado, com a mesma semântica das configurações do WheelLog
 * ("Rotation speed", "Rotation voltage", "Power factor"), para você poder copiar os valores de lá:
 *
 * `pwm = velocidade / (rotationSpeedKmh / rotationVoltage * tensão * powerFactor)`
 */
data class PwmCalibration(
    val rotationSpeedKmh: Float,
    val rotationVoltage: Float,
    val powerFactor: Float,
) {
    fun estimate(speedKmh: Float, voltage: Float): Float? {
        val denom = rotationSpeedKmh / rotationVoltage * voltage * powerFactor
        return if (denom > 0f) speedKmh / denom else null
    }
}

/** Degrau da escala de cores. [at] é a fração de uso (0..1) em que a cor é atingida. */
data class ColorStop(val at: Float, val color: Rgb)

enum class WheelModel(val displayName: String) {
    NOSFET_APEX("Nosfet Apex"),
    BEGODE_A2("Begode A2"),
    CUSTOM("Personalizada"),
}

/**
 * Perfil de uma roda. Tudo é editável pelo usuário; os valores iniciais em [Defaults] são
 * pontos de partida, não especificações de fábrica.
 */
data class WheelProfile(
    val id: String,
    val name: String,
    val model: WheelModel,
    /** Velocidade máxima da roda (km/h); referência para a escala de velocidade. */
    val maxSpeedKmh: Float,
    /** Velocidade em que o tiltback de velocidade costuma atuar (km/h); 0 = desconhecida. */
    val tiltbackSpeedKmh: Float,
    val nominalVoltage: Float,
    val fullVoltage: Float,
    val emptyVoltage: Float,
    /** Curva opcional; vazia = linear entre [emptyVoltage] e [fullVoltage]. */
    val batteryCurve: List<BatteryPoint> = emptyList(),
    val pwmCalibration: PwmCalibration,
    /** Escala de cores por PWM (fração 0..1). */
    val pwmStops: List<ColorStop> = DefaultStops.pwm,
    /** PWM a partir do qual o alerta passa a piscar (fração). */
    val flashAbovePwm: Float = 0.90f,
    /** Bateria (%) abaixo da qual o fator de bateria começa a pesar. */
    val lowBatteryWarnPercent: Float = 25f,
    /** Bateria (%) em que o fator de bateria chega a 100% de uso. */
    val lowBatteryCriticalPercent: Float = 8f,
) {
    fun batteryPercentFor(voltage: Float): Float {
        val curve = batteryCurve.sortedBy { it.voltage }
        if (curve.size >= 2) {
            if (voltage <= curve.first().voltage) return curve.first().percent
            if (voltage >= curve.last().voltage) return curve.last().percent
            val i = curve.indexOfLast { it.voltage <= voltage }
            val a = curve[i]
            val b = curve[i + 1]
            return a.percent + (b.percent - a.percent) * ((voltage - a.voltage) / (b.voltage - a.voltage))
        }
        if (fullVoltage <= emptyVoltage) return 100f
        return ((voltage - emptyVoltage) / (fullVoltage - emptyVoltage) * 100f).coerceIn(0f, 100f)
    }

    companion object Defaults {
        /**
         * Nosfet Apex: o WheelLog (VeteranAdapter, versão 42) usa pack de ~36S, com a faixa de percentual
         * entre ~115,2 V (0%) e ~150,3 V (100%). Velocidade máxima e calibração de PWM são PALPITES: a
         * Apex reporta PWM de hardware, então a calibração só é usada se esse campo faltar.
         */
        fun nosfetApex() = WheelProfile(
            id = "nosfet-apex",
            name = "Nosfet Apex",
            model = WheelModel.NOSFET_APEX,
            maxSpeedKmh = 100f,
            tiltbackSpeedKmh = 0f,
            nominalVoltage = 133.2f,
            fullVoltage = 151.2f,
            emptyVoltage = 115.2f,
            pwmCalibration = PwmCalibration(rotationSpeedKmh = 100f, rotationVoltage = 151.2f, powerFactor = 0.90f),
        )

        /**
         * Begode A2: valores de partida para pack de 16S (67,2 V cheio); o WheelLog usa ~52,9 V como 0%.
         * CONFIRA na sua roda. A Begode com firmware original não informa PWM, então o app estima com a
         * calibração abaixo (padrões do WheelLog: 50 km/h a 84 V, fator 0,90 → ajustar à sua tensão).
         */
        fun begodeA2() = WheelProfile(
            id = "begode-a2",
            name = "Begode A2",
            model = WheelModel.BEGODE_A2,
            maxSpeedKmh = 45f,
            tiltbackSpeedKmh = 0f,
            nominalVoltage = 59.2f,
            fullVoltage = 67.2f,
            emptyVoltage = 52.9f,
            pwmCalibration = PwmCalibration(rotationSpeedKmh = 50f, rotationVoltage = 84f, powerFactor = 0.90f),
        )

        fun all() = listOf(nosfetApex(), begodeA2())
    }
}

object DefaultStops {
    /** Verde (<60%) → verde-amarelado → amarelo (60–70%) → laranja (70–80%) → vermelho (80–90%). */
    val pwm: List<ColorStop> = listOf(
        ColorStop(0.00f, Rgb(0, 220, 60)),
        ColorStop(0.50f, Rgb(0, 220, 60)),
        ColorStop(0.60f, Rgb(160, 220, 0)),
        ColorStop(0.70f, Rgb(255, 210, 0)),
        ColorStop(0.80f, Rgb(255, 110, 0)),
        ColorStop(0.90f, Rgb(255, 0, 0)),
    )
}
