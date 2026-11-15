package apexled.core

import kotlinx.coroutines.flow.StateFlow

/**
 * Quadro que o motor de segurança pede à fita.
 *
 * @property color cor de base (já com a cor do estado atual).
 * @property brightness brilho 0..255 (0 = apagado).
 * @property bar se não for null, a fita deve funcionar como barômetro: [Bar.fraction] dos pixels acesos.
 */
data class LedFrame(
    val color: Rgb,
    val brightness: Int,
    val bar: Bar? = null,
) {
    data class Bar(val fraction: Float, val ramp: List<ColorStop>)

    /** Cor efetiva de cada pixel para pré-visualização (já com brilho aplicado). */
    fun render(pixels: Int): List<Rgb> {
        val level = brightness.coerceIn(0, 255) / 255f
        if (bar == null) return List(pixels) { color.scaled(level) }
        val lit = (bar.fraction.coerceIn(0f, 1f) * pixels + 0.0001f).toInt().coerceAtMost(pixels)
        return List(pixels) { i ->
            if (i < lit) ColorScale.colorAt(bar.ramp, (i + 1f) / pixels, stepped = false).scaled(level) else Rgb.BLACK
        }
    }
}

object ColorScale {
    /** Cor da escala em [usage]; [stepped] usa a cor do degrau inferior em vez de interpolar. */
    fun colorAt(stops: List<ColorStop>, usage: Float, stepped: Boolean): Rgb {
        val s = stops.sortedBy { it.at }
        if (s.isEmpty()) return Rgb.WHITE
        val u = usage.coerceIn(0f, 1f)
        if (u <= s.first().at) return s.first().color
        if (u >= s.last().at) return s.last().color
        val i = s.indexOfLast { it.at <= u }
        if (stepped) return s[i].color
        val a = s[i]
        val b = s[i + 1]
        return Rgb.lerp(a.color, b.color, (u - a.at) / (b.at - a.at))
    }
}

enum class LedLinkState { DISCONNECTED, SCANNING, CONNECTING, CONNECTED, RECONNECTING }

/** Ordem dos canais de cor da fita (índices do BanlanX: ver docs/protocolo-sp621e.md). */
enum class ColorOrder(val label: String, val index: Int) {
    RGB("RGB", 0), RBG("RBG", 1), GRB("GRB", 2), GBR("GBR", 3), BRG("BRG", 4), BGR("BGR", 5);

    companion object {
        fun fromIndex(i: Int) = entries.firstOrNull { it.index == i } ?: RGB
    }
}

/** Comandos de alto nível aceitos por qualquer [LedController]. */
sealed interface LedCommand {
    data class Power(val on: Boolean) : LedCommand
    /** Cor sólida com brilho 0..255. */
    data class Solid(val color: Rgb, val brightness: Int) : LedCommand
    data class Brightness(val level: Int) : LedCommand
    data class Effect(val effectId: Int) : LedCommand
    /** 1..10. */
    data class EffectSpeed(val speed: Int) : LedCommand
    /** 1..150. */
    data class EffectLength(val length: Int) : LedCommand
    data class Order(val order: ColorOrder) : LedCommand
    data class PixelCount(val count: Int) : LedCommand
    data class IcType(val ic: StripIc) : LedCommand
}

enum class StripIc(val label: String) {
    WS2811("WS2811"), WS2812("WS2812B"), SK6812("SK6812"), WS2801("WS2801"), UCS1903("UCS1903"),
    TM1804("TM1804"), SM16703("SM16703"), LPD6803("LPD6803"),
}

/** Estado "desejado" da fita, usado pela prévia da UI e pelo diff de comandos. */
data class LedState(
    val power: Boolean = true,
    val color: Rgb = Rgb(0, 220, 60),
    val brightness: Int = 255,
    val effectId: Int = EFFECT_SOLID,
    val effectSpeed: Int = 5,
    val effectLength: Int = 50,
    val order: ColorOrder = ColorOrder.RGB,
    val pixelCount: Int = 30,
    val ic: StripIc = StripIc.WS2811,
    val bar: LedFrame.Bar? = null,
) {
    companion object {
        const val EFFECT_SOLID = 0xBE
    }
}

/**
 * Controlador de fita LED. Todas as chamadas são não bloqueantes: o controlador mantém o estado desejado
 * e envia apenas as diferenças, no máximo ~10 vezes por segundo.
 */
interface LedController {
    val link: StateFlow<LedLinkState>
    val state: StateFlow<LedState>
    val deviceName: StateFlow<String?>

    fun send(command: LedCommand)

    /** Aplica um quadro do motor de segurança (cor sólida + brilho, ou barra). */
    fun applyFrame(frame: LedFrame)
}
