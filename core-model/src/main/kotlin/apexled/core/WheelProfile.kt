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

/** Protocolo BLE da roda. [AUTO] escolhe pelo serviço BLE encontrado e pelo formato dos frames. */
enum class WheelProtocol(val label: String) {
    AUTO("Automático"),
    GOTWAY("Begode/Gotway"),
    VETERAN("Leaperkim/Veteran/Nosfet"),
    KINGSONG("Kingsong"),
    INMOTION_V1("Inmotion V5/V8/V10"),
    INMOTION_V2("Inmotion V9/V11–V14/P6"),
}

/**
 * Modelos pré-cadastrados. [detects] são os nomes que o protocolo informa (Veteran: versão do firmware;
 * Kingsong: nome "KS-…"; Inmotion: tipo do carro) e escolhem o perfil sozinhos; "*" no fim = prefixo.
 * Tensões dos packs: tabelas de bateria do WheelLog (VeteranAdapter, KingsongAdapter, InMotionAdapter).
 */
enum class WheelModel(val displayName: String, val protocol: WheelProtocol, val detects: List<String> = emptyList()) {
    NOSFET_APEX("Nosfet Apex", WheelProtocol.VETERAN, listOf("Nosfet Apex")),
    NOSFET_AERO("Nosfet Aero", WheelProtocol.VETERAN, listOf("Nosfet Aero")),
    NOSFET_AEON("Nosfet Aeon", WheelProtocol.VETERAN, listOf("Nosfet Aeon")),
    LEAPERKIM_24S("Leaperkim Sherman/Abrams (100 V)", WheelProtocol.VETERAN, listOf("Sherman", "Abrams", "Sherman S")),
    LEAPERKIM_30S("Leaperkim Patton (126 V)", WheelProtocol.VETERAN, listOf("Patton", "Patton S")),
    LEAPERKIM_36S("Leaperkim Lynx/Sherman L (151 V)", WheelProtocol.VETERAN, listOf("Lynx", "Lynx S", "Sherman L")),
    LEAPERKIM_42S("Leaperkim Oryx (176 V)", WheelProtocol.VETERAN, listOf("Oryx")),
    BEGODE_A2("Begode A2", WheelProtocol.GOTWAY),
    BEGODE_100V("Begode 100 V", WheelProtocol.GOTWAY),
    BEGODE_134V("Begode 134 V", WheelProtocol.GOTWAY),
    BEGODE_151V("Begode 151 V", WheelProtocol.GOTWAY),
    BEGODE_168V("Begode 168 V", WheelProtocol.GOTWAY),
    KINGSONG("Kingsong", WheelProtocol.KINGSONG, listOf("KS-*")),
    INMOTION_V1("Inmotion V5/V8/V10", WheelProtocol.INMOTION_V1, listOf("Inmotion V5*", "Inmotion V8*", "Inmotion V10*", "Inmotion Glide*")),
    INMOTION_V2("Inmotion V9/V11–V14", WheelProtocol.INMOTION_V2, listOf("Inmotion V*")),
    CUSTOM("Personalizada", WheelProtocol.AUTO),
    ;

    /** O nome informado pela roda corresponde a este modelo? ("*" no fim = prefixo.) */
    fun matches(reported: String): Boolean = detects.any { d ->
        if (d.endsWith("*")) reported.startsWith(d.dropLast(1), ignoreCase = true) else reported.equals(d, ignoreCase = true)
    }
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
    /** Protocolo usado ao conectar; [WheelProtocol.AUTO] detecta sozinho. */
    val protocol: WheelProtocol = model.protocol,
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

        /** Perfil genérico a partir do pack (S = células em série; 4,2 V cheia, ~3,3 V vazia por célula). */
        private fun pack(
            model: WheelModel, cells: Int, maxSpeed: Float, rotationKmh: Float, id: String = model.name.lowercase().replace('_', '-'),
        ) = WheelProfile(
            id = id, name = model.displayName, model = model,
            maxSpeedKmh = maxSpeed, tiltbackSpeedKmh = 0f,
            nominalVoltage = cells * 3.7f, fullVoltage = cells * 4.2f, emptyVoltage = cells * 3.3f,
            pwmCalibration = PwmCalibration(rotationSpeedKmh = rotationKmh, rotationVoltage = cells * 4.2f, powerFactor = 0.90f),
        )

        /**
         * Valores de partida: velocidade máxima e calibração de PWM são estimativas para o tamanho do pack, só
         * usadas quando a roda não informa PWM (Begode de fábrica e Inmotion V5/V8/V10). Ajuste à sua roda.
         */
        fun presets(): List<WheelProfile> = listOf(
            nosfetApex(),
            pack(WheelModel.NOSFET_AERO, 30, 80f, 90f),
            pack(WheelModel.NOSFET_AEON, 36, 90f, 100f),
            pack(WheelModel.LEAPERKIM_24S, 24, 70f, 80f),
            pack(WheelModel.LEAPERKIM_30S, 30, 80f, 90f),
            pack(WheelModel.LEAPERKIM_36S, 36, 90f, 100f),
            pack(WheelModel.LEAPERKIM_42S, 42, 100f, 115f),
            begodeA2(),
            pack(WheelModel.BEGODE_100V, 24, 70f, 80f),
            pack(WheelModel.BEGODE_134V, 32, 80f, 95f),
            pack(WheelModel.BEGODE_151V, 36, 90f, 105f),
            pack(WheelModel.BEGODE_168V, 40, 100f, 115f),
            pack(WheelModel.KINGSONG, 20, 50f, 60f),
            pack(WheelModel.INMOTION_V1, 20, 45f, 55f),
            pack(WheelModel.INMOTION_V2, 30, 80f, 90f),
        )

        fun forModel(model: WheelModel): WheelProfile =
            presets().firstOrNull { it.model == model } ?: begodeA2().copy(model = WheelModel.CUSTOM, protocol = WheelProtocol.AUTO)

        fun all() = presets()
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
