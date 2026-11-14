package app.apexled.core

import app.apexled.device.BoardInfo
import app.apexled.device.LightMode
import app.apexled.pattern.BrightnessConfig
import app.apexled.pattern.CriticalStyle
import app.apexled.pattern.EngineConfig
import app.apexled.pattern.IdleLight
import app.apexled.pattern.InputConfig
import app.apexled.pattern.InputSource
import app.apexled.pattern.Pattern
import app.apexled.pattern.PulseItem
import app.apexled.pattern.PulseShape
import app.apexled.pattern.Rgb
import app.apexled.pattern.SyncMode
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SettingsPersistenceTest {

    private val directory: File = Files.createTempDirectory("apexled-settings").toFile()
    private val file = File(directory, "led-settings.json")

    @AfterTest
    fun cleanUp() {
        directory.deleteRecursively()
    }

    private val everyPattern = listOf(
        Pattern.ContinuousGradient,
        Pattern.ThreeColorGradient(Rgb(1, 2, 3), Rgb(40, 50, 60), Rgb(250, 251, 252), midpoint = 0.3),
        Pattern.Pulse(
            items = listOf(
                PulseItem(Rgb.GREEN, 0.5, 0.0, 40.0, PulseShape.BREATHE, 0.1, 0.9, 0.25),
                PulseItem(Rgb.YELLOW, 3.0, 70.0, 100.0),
            ),
            idle = IdleLight.Steady(Rgb.CYAN, 0.3),
        ),
        Pattern.Pulse(listOf(PulseItem())),
        Pattern.ColorSequence(listOf(Rgb.GREEN, Rgb.RED, Rgb.GREEN, Rgb.BLUE), smooth = false),
    )

    private fun customised(pattern: Pattern) = LedSettings(
        mode = ControlMode.MANUAL,
        engine = EngineConfig(
            pattern = pattern,
            input = InputConfig(InputSource.SPEED, 5.0, 70.0),
            brightness = BrightnessConfig(master = 0.6, max = 0.8),
            sync = SyncMode.Staggered(320),
            criticalStyle = CriticalStyle.FLASH,
        ),
        safety = SafetyConfig(85.0, 65.0, 75.0, 120.0, 118.5, 2_000),
        manual = ManualLight(false, LightMode.DYNAMIC, Rgb(9, 8, 7), 100, 42, 7, 99),
        boards = listOf(
            BoardInfo("a", "Front", "AA:BB:CC:DD:EE:01", "sp63x"),
            BoardInfo("b", "Rear \"left\"", "AA:BB:CC:DD:EE:02", "sp61x-pwm"),
        ),
        smoothingMs = 400,
        telemetryLoss = TelemetryLossBehavior.HOLD_LAST,
        telemetryTimeoutMs = 5_000,
    )

    @Test
    fun `every setting survives a save and a load`() {
        for (pattern in everyPattern) {
            val settings = customised(pattern)
            assertEquals(settings, SettingsJson.decode(SettingsJson.encode(settings)), "pattern $pattern")
        }
    }

    @Test
    fun `defaults round trip`() {
        assertEquals(LedSettings(), SettingsJson.decode(SettingsJson.encode(LedSettings())))
    }

    @Test
    fun `settings persist between sessions through the file store`() {
        val settings = customised(everyPattern[2])
        FileSettingsStore(file).save(settings)

        // A new store instance stands for the app being restarted.
        assertEquals(settings, FileSettingsStore(file).load())
    }

    @Test
    fun `no file means defaults`() {
        assertEquals(LedSettings(), FileSettingsStore(file).load())
    }

    @Test
    fun `saving replaces the previous file and leaves no temp file`() {
        val store = FileSettingsStore(file)
        store.save(customised(everyPattern[0]))
        store.save(LedSettings(smoothingMs = 123))
        assertEquals(123, store.load().smoothingMs)
        assertEquals(listOf(file.name), directory.list()!!.toList())
    }

    @Test
    fun `the store creates missing folders`() {
        val nested = File(directory, "a/b/settings.json")
        FileSettingsStore(nested).save(LedSettings())
        assertTrue(nested.isFile)
    }

    @Test
    fun `a corrupt file falls back to defaults and is kept aside`() {
        file.writeText("{ this is not json")
        assertEquals(LedSettings(), FileSettingsStore(file).load())
        assertEquals("{ this is not json", File(file.path + ".corrupt").readText())
    }

    @Test
    fun `out of range values are rejected as a whole`() {
        val tampered = SettingsJson.encode(LedSettings()).replace("\"max\": 1.0", "\"max\": 0.0")
        assertFalse(tampered == SettingsJson.encode(LedSettings()), "the edit must have applied")
        assertFailsWith<IllegalArgumentException> { SettingsJson.decode(tampered) }

        file.writeText(tampered)
        assertEquals(LedSettings(), FileSettingsStore(file).load())
    }

    @Test
    fun `unknown keys are ignored and missing keys take defaults`() {
        val decoded = SettingsJson.decode("""{ "version": 1, "futureOption": true, "smoothingMs": 100 }""")
        assertEquals(LedSettings(smoothingMs = 100), decoded)
    }

    @Test
    fun `a file from a newer version is refused`() {
        assertFailsWith<IllegalArgumentException> { SettingsJson.decode("""{ "version": 99 }""") }
    }

    @Test
    fun `the file is human readable`() {
        val text = SettingsJson.encode(customised(everyPattern[1]))
        assertTrue("\"type\": \"three-color-gradient\"" in text, text)
        assertTrue("\"start\": \"#010203\"" in text, text)
        assertTrue("\"staggerDelayMs\": 320" in text, text)
    }

    @Test
    fun `settings validate board limits`() {
        val eleven = (1..11).map { BoardInfo("id$it", "Board $it", "AA:00:00:00:00:%02X".format(it), "sp63x") }
        assertFailsWith<IllegalArgumentException> { LedSettings(boards = eleven) }
        assertFailsWith<IllegalArgumentException> { LedSettings(boards = listOf(eleven[0], eleven[0])) }
    }
}
