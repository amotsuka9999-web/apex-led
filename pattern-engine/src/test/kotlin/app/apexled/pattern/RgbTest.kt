package app.apexled.pattern

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RgbTest {

    @Test
    fun `hue anchors are the pure colours`() {
        assertEquals(Rgb(255, 0, 0), Rgb.fromHue(0.0))
        assertEquals(Rgb(255, 255, 0), Rgb.fromHue(60.0))
        assertEquals(Rgb(0, 255, 0), Rgb.fromHue(120.0))
        assertEquals(Rgb(0, 255, 255), Rgb.fromHue(180.0))
        assertEquals(Rgb(0, 0, 255), Rgb.fromHue(240.0))
        assertEquals(Rgb(255, 0, 255), Rgb.fromHue(300.0))
        assertEquals(Rgb(255, 0, 0), Rgb.fromHue(360.0))
    }

    @Test
    fun `hue wraps for negative and large angles`() {
        assertEquals(Rgb.fromHue(240.0), Rgb.fromHue(-120.0))
        assertEquals(Rgb.fromHue(30.0), Rgb.fromHue(750.0))
    }

    @Test
    fun `lerp hits both ends and the midpoint`() {
        val from = Rgb(0, 100, 200)
        val to = Rgb(200, 0, 100)
        assertEquals(from, Rgb.lerp(from, to, 0.0))
        assertEquals(to, Rgb.lerp(from, to, 1.0))
        assertEquals(Rgb(100, 50, 150), Rgb.lerp(from, to, 0.5))
    }

    @Test
    fun `lerp clamps outside 0 to 1`() {
        assertEquals(Rgb.BLACK, Rgb.lerp(Rgb.BLACK, Rgb.WHITE, -3.0))
        assertEquals(Rgb.WHITE, Rgb.lerp(Rgb.BLACK, Rgb.WHITE, 7.0))
    }

    @Test
    fun `hex round trip`() {
        val color = Rgb(18, 171, 255)
        assertEquals("#12ABFF", color.toHex())
        assertEquals(color, Rgb.fromHex("#12abff"))
        assertEquals(color, Rgb.fromHex("12ABFF"))
    }

    @Test
    fun `invalid values are rejected`() {
        assertFailsWith<IllegalArgumentException> { Rgb(256, 0, 0) }
        assertFailsWith<IllegalArgumentException> { Rgb(0, -1, 0) }
        assertFailsWith<IllegalArgumentException> { Rgb.fromHex("#12345") }
        assertFailsWith<IllegalArgumentException> { Rgb.fromHex("#GG0000") }
    }
}
