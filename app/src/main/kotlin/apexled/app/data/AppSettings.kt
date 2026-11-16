package apexled.app.data

import apexled.core.Rgb
import apexled.core.LedState
import apexled.core.SourceKind
import apexled.core.WheelProfile
import apexled.safety.SafetyConfig

enum class ThemeMode(val label: String) { DARK("Escuro"), LIGHT("Claro"), SYSTEM("Sistema") }

/** Tudo que é persistido entre execuções. */
data class AppSettings(
    val profiles: List<WheelProfile> = WheelProfile.all(),
    val activeProfileId: String = "begode-a2",
    /** Detectar o perfil pelo modelo que a roda informa (Veteran informa; Begode/Gotway não). */
    val autoDetectProfile: Boolean = true,
    val safety: SafetyConfig = SafetyConfig(),
    val source: SourceKind = SourceKind.DIRECT_BLE,
    val wheelAddress: String? = null,
    val wheelName: String? = null,
    val ledAddress: String? = null,
    val ledName: String? = null,
    /** Fitas extras que recebem os mesmos comandos da principal (até 9; 10 no total, como o BanlanX). */
    val extraLeds: List<SavedLed> = emptyList(),
    /** true = usa o controlador simulado (sem Bluetooth); false = SP621E real. */
    val ledSimulated: Boolean = true,
    val theme: ThemeMode = ThemeMode.DARK,
    val disclaimerAccepted: Boolean = false,
    /** Mostra ferramentas de teste: simulador, fita simulada, ponte WheelLog, diagnóstico e modo Barra. */
    val technicalMode: Boolean = false,
    /** Configuração da fita principal. */
    val ledStrip: StripConfig = StripConfig(),
    /** A seta desliga sozinha depois deste tempo (s). */
    val turnSignalSeconds: Int = 12,
    val dayNight: DayNightConfig = DayNightConfig(),
    val voice: VoiceConfig = VoiceConfig(),
    /** Grava cada passeio em CSV enquanto a roda está conectada. */
    val tripLog: Boolean = false,
    val led: LedState = LedState(),
) {
    val activeProfile: WheelProfile
        get() = profiles.firstOrNull { it.id == activeProfileId } ?: profiles.first()
}

data class SavedLed(val address: String, val name: String?, val strip: StripConfig = StripConfig())

/** O que cada fita mostra. */
enum class StripShow(val label: String, val hint: String) {
    SAFETY("Segurança", "Cor pelo PWM/velocidade. Com a segurança desligada, segue as abas Cor e Efeitos."),
    COLOR("Cor fixa", "Sempre na cor escolhida."),
    EFFECT("Efeito", "Sempre no efeito escolhido."),
    OFF("Desligada", "Apagada (as setas e o freio ainda funcionam, se ligados)."),
}

/** Configuração de uma fita do grupo. */
data class StripConfig(
    val side: LedSide = LedSide.BOTH,
    val show: StripShow = StripShow.SAFETY,
    val color: Rgb = Rgb(0, 120, 255),
    val effectId: Int = 1,
    val effectSpeed: Int = 6,
    /** Acende vermelho forte ao frear, por cima do que estiver mostrando. */
    val brakeLight: Boolean = false,
    /** Pisca nas setas (só vale para Esquerda, Direita e Geral). */
    val turnSignals: Boolean = true,
)

/** Onde a fita está montada. Seta: pisca nas do lado pedido e nas "Geral"; capacete e roda só no pisca-alerta. */
enum class LedSide(val label: String) {
    BOTH("Geral"), LEFT("Esquerda"), RIGHT("Direita"), HELMET("Capacete"), WHEEL("Na roda (gira)"),
}

/** Seta pedida pelo piloto. HAZARD = pisca-alerta (todas as fitas). */
enum class TurnSide { LEFT, RIGHT, HAZARD }

/** Brilho automático de dia/noite (só nos modos automáticos e setas, não nas cores manuais). */
data class DayNightConfig(
    val enabled: Boolean = false,
    /** Hora em que começa o "dia" e a "noite" (0..23). */
    val dayStartsHour: Int = 7,
    val nightStartsHour: Int = 18,
    /** Brilho de dia e de noite, em fração do máximo (0..1). */
    val dayLevel: Float = 1f,
    val nightLevel: Float = 0.45f,
) {
    fun levelAt(hour: Int): Float {
        if (!enabled) return 1f
        val isDay = if (dayStartsHour <= nightStartsHour) hour in dayStartsHour until nightStartsHour
        else hour >= dayStartsHour || hour < nightStartsHour
        return (if (isDay) dayLevel else nightLevel).coerceIn(0.05f, 1f)
    }
}

/** Alertas falados (fone/Bluetooth do celular). */
data class VoiceConfig(
    val enabled: Boolean = false,
    /** Fala "PWM 80" etc. ao cruzar estes limites (fração). */
    val pwmSteps: List<Float> = listOf(0.70f, 0.80f, 0.90f),
    val batterySteps: List<Int> = listOf(30, 20, 10),
    val signalLost: Boolean = true,
    /** Avisa quando a bateria do CELULAR fica abaixo deste % (0 = desligado). */
    val phoneBatteryBelow: Int = 20,
)

