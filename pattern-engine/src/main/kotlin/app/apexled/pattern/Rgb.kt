package app.apexled.pattern

import kotlin.math.roundToInt

/** An sRGB colour with 8-bit channels. Brightness is carried separately in [LedFrame]. */
data class Rgb(val r: Int, val g: Int, val b: Int) {
    init {
        require(r in 0..255 && g in 0..255 && b in 0..255) { "RGB channels must be 0..255, got ($r, $g, $b)" }
    }

    /** `#RRGGBB`, used by docs, logs and settings files. */
    fun toHex(): String = "#%02X%02X%02X".format(r, g, b)

    companion object {
        val BLACK = Rgb(0, 0, 0)
        val WHITE = Rgb(255, 255, 255)
        val RED = Rgb(255, 0, 0)
        val GREEN = Rgb(0, 255, 0)
        val BLUE = Rgb(0, 0, 255)
        val YELLOW = Rgb(255, 255, 0)
        val CYAN = Rgb(0, 255, 255)

        fun fromHex(hex: String): Rgb {
            val digits = hex.removePrefix("#")
            require(digits.length == 6 && digits.all { it.isDigit() || it.lowercaseChar() in 'a'..'f' }) {
                "Expected #RRGGBB, got '$hex'"
            }
            return Rgb(
                digits.substring(0, 2).toInt(16),
                digits.substring(2, 4).toInt(16),
                digits.substring(4, 6).toInt(16),
            )
        }

        /**
         * Per-channel linear interpolation in sRGB space. [t] is clamped to 0..1.
         * Not gamma-corrected on purpose: the mapping stays trivially predictable (see docs/padroes-cor.md).
         */
        fun lerp(from: Rgb, to: Rgb, t: Double): Rgb {
            val k = t.coerceIn(0.0, 1.0)
            fun channel(a: Int, b: Int) = (a + (b - a) * k).roundToInt()
            return Rgb(channel(from.r, to.r), channel(from.g, to.g), channel(from.b, to.b))
        }

        /**
         * Fully saturated, full-value colour for a hue in degrees.
         * 0 = red, 60 = yellow, 120 = green, 180 = cyan, 240 = blue.
         */
        fun fromHue(hueDegrees: Double): Rgb {
            val h = ((hueDegrees % 360.0) + 360.0) % 360.0
            val sector = h / 60.0
            val rising = ((sector % 1.0) * 255.0).roundToInt()
            val falling = 255 - rising
            return when (sector.toInt()) {
                0 -> Rgb(255, rising, 0)
                1 -> Rgb(falling, 255, 0)
                2 -> Rgb(0, 255, rising)
                3 -> Rgb(0, falling, 255)
                4 -> Rgb(rising, 0, 255)
                else -> Rgb(255, 0, falling)
            }
        }
    }
}
