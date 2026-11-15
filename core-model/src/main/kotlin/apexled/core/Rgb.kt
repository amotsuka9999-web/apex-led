package apexled.core

import kotlin.math.abs
import kotlin.math.roundToInt

/** Cor RGB de 8 bits por canal. */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "canal fora de 0..255: $r,$g,$b" }
    }

    /** Escurece/clareia multiplicando os canais por [factor] (0..1). */
    fun scaled(factor: Float): Rgb {
        val f = factor.coerceIn(0f, 1f)
        return Rgb((r * f).roundToInt(), (g * f).roundToInt(), (b * f).roundToInt())
    }

    fun toArgbInt(): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    fun toHex(): String = "%02X%02X%02X".format(r, g, b)

    /** Matiz 0..360, saturação 0..1, valor 0..1. */
    fun toHsv(): Hsv {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f
        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val d = max - min
        val h = when {
            d == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / d) % 6f)
            max == gf -> 60f * (((bf - rf) / d) + 2f)
            else -> 60f * (((rf - gf) / d) + 4f)
        }
        return Hsv(if (h < 0f) h + 360f else h, if (max == 0f) 0f else d / max, max)
    }

    companion object {
        val BLACK = Rgb(0, 0, 0)
        val WHITE = Rgb(255, 255, 255)
        val RED = Rgb(255, 0, 0)
        val GREEN = Rgb(0, 255, 0)
        val BLUE = Rgb(0, 0, 255)

        fun fromHex(hex: String): Rgb? {
            val clean = hex.trim().removePrefix("#")
            if (clean.length != 6) return null
            val v = clean.toIntOrNull(16) ?: return null
            return Rgb((v shr 16) and 0xFF, (v shr 8) and 0xFF, v and 0xFF)
        }

        /** Interpolação linear canal a canal; [t] em 0..1. */
        fun lerp(a: Rgb, b: Rgb, t: Float): Rgb {
            val k = t.coerceIn(0f, 1f)
            return Rgb(
                (a.r + (b.r - a.r) * k).roundToInt(),
                (a.g + (b.g - a.g) * k).roundToInt(),
                (a.b + (b.b - a.b) * k).roundToInt(),
            )
        }
    }
}

data class Hsv(val h: Float, val s: Float, val v: Float) {
    fun toRgb(): Rgb {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r1, g1, b1) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        return Rgb(
            ((r1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((g1 + m) * 255f).roundToInt().coerceIn(0, 255),
            ((b1 + m) * 255f).roundToInt().coerceIn(0, 255),
        )
    }
}
