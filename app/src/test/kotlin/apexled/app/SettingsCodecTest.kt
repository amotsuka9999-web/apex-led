package apexled.app

import apexled.app.data.AppSettings
import apexled.app.data.SettingsCodec
import apexled.app.data.ThemeMode
import apexled.core.ColorOrder
import apexled.core.ColorStop
import apexled.core.Rgb
import apexled.core.SourceKind
import apexled.core.WheelModel
import apexled.safety.FaultStyle
import apexled.safety.SafetyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsCodecTest {
    @Test fun roundTripPreservesEditedValues() {
        val d = AppSettings()
        val edited = d.copy(
            profiles = d.profiles.map {
                if (it.id == "nosfet-apex") it.copy(maxSpeedKmh = 88f, pwmStops = listOf(ColorStop(0f, Rgb(1, 2, 3)), ColorStop(1f, Rgb(250, 0, 9))), flashAbovePwm = 0.8f) else it
            },
            activeProfileId = "nosfet-apex",
            safety = d.safety.copy(mode = SafetyMode.BAR, stepped = true, speedLimitKmh = 42f, releaseSeconds = 3f,
                fault = d.safety.fault.copy(style = FaultStyle.FAST_FLASH)),
            source = SourceKind.WHEELLOG_BRIDGE, wheelAddress = "AA:BB", ledAddress = "CC:DD", ledSimulated = false,
            extraLeds = listOf(apexled.app.data.SavedLed("EE:FF", "Fita 2"), apexled.app.data.SavedLed("11:22", null)),
            theme = ThemeMode.LIGHT, disclaimerAccepted = true,
            led = d.led.copy(order = ColorOrder.GRB, pixelCount = 77, color = Rgb(9, 8, 7)),
        )
        val back = SettingsCodec.decode(SettingsCodec.encode(edited))
        assertEquals(edited.activeProfileId, back.activeProfileId)
        assertEquals(88f, back.profiles.first { it.id == "nosfet-apex" }.maxSpeedKmh)
        assertEquals(2, back.profiles.first { it.id == "nosfet-apex" }.pwmStops.size)
        assertEquals(0.8f, back.profiles.first { it.id == "nosfet-apex" }.flashAbovePwm)
        assertEquals(SafetyMode.BAR, back.safety.mode)
        assertEquals(42f, back.safety.speedLimitKmh)
        assertEquals(FaultStyle.FAST_FLASH, back.safety.fault.style)
        assertEquals(SourceKind.WHEELLOG_BRIDGE, back.source)
        assertEquals("CC:DD", back.ledAddress)
        assertEquals(edited.extraLeds, back.extraLeds)
        assertEquals(false, back.ledSimulated)
        assertEquals(ThemeMode.LIGHT, back.theme)
        assertEquals(ColorOrder.GRB, back.led.order)
        assertEquals(77, back.led.pixelCount)
        assertEquals(Rgb(9, 8, 7), back.led.color)
    }

    @Test fun garbageFallsBackToDefaults() {
        for (bad in listOf(null, "", "   ", "{", "[1,2]", "{\"profiles\":\"x\",\"safety\":5}")) {
            val s = SettingsCodec.decode(bad)
            assertTrue(s.profiles.isNotEmpty())
            assertEquals(false, s.disclaimerAccepted)
        }
    }

    @Test fun invalidValuesAreClamped() {
        val json = """{"v":1,"safety":{"releaseSeconds":999,"signalTimeoutMs":1,"brightness":9999,"speedLimitKmh":-3},"led":{"pixels":0,"effectSpeed":99}}"""
        val s = SettingsCodec.decode(json)
        assertEquals(30f, s.safety.releaseSeconds)
        assertEquals(300L, s.safety.signalTimeoutMs)
        assertEquals(255, s.safety.brightness)
        assertNull(s.safety.speedLimitKmh)
        assertEquals(1, s.led.pixelCount)
    }

    @Test fun profileModelsKeepDefaultsForMissingFields() {
        val s = SettingsCodec.decode("""{"profiles":[{"id":"x","name":"Minha","model":"CUSTOM"}]}""")
        val p = s.profiles.first { it.id == "x" }
        assertEquals("Minha", p.name)
        assertEquals(WheelModel.CUSTOM, p.model)
        assertEquals(apexled.core.WheelProtocol.AUTO, p.protocol)
        assertTrue(p.pwmStops.size >= 2)
        // perfis pré-cadastrados que faltavam entram depois dos salvos
        assertTrue(s.profiles.any { it.model == WheelModel.KINGSONG })
        assertTrue(s.profiles.any { it.model == WheelModel.INMOTION_V2 })
    }
}
