package apexled.app.data

import apexled.led.FanoutLedController
import apexled.core.BatteryPoint
import apexled.core.ColorOrder
import apexled.core.ColorStop
import apexled.core.LedState
import apexled.core.PwmCalibration
import apexled.core.Rgb
import apexled.core.SourceKind
import apexled.core.StripIc
import apexled.core.WheelModel
import apexled.core.WheelProfile
import apexled.safety.BrakeConfig
import apexled.safety.FaultConfig
import apexled.safety.FaultStyle
import apexled.safety.FixedColorConfig
import apexled.safety.RestPulseConfig
import apexled.safety.SafetyConfig
import apexled.safety.SafetyMode
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persistência em JSON (org.json). Tolerante: campo ausente ou inválido volta ao padrão, nunca derruba o app.
 * Versão do esquema em "v" para migrações futuras.
 */
object SettingsCodec {
    private const val VERSION = 1

    fun encode(s: AppSettings): String = JSONObject().apply {
        put("v", VERSION)
        put("profiles", JSONArray().also { a -> s.profiles.forEach { a.put(profile(it)) } })
        put("activeProfileId", s.activeProfileId)
        put("autoDetectProfile", s.autoDetectProfile)
        put("safety", safety(s.safety))
        put("source", s.source.name)
        put("wheelAddress", s.wheelAddress ?: JSONObject.NULL)
        put("wheelName", s.wheelName ?: JSONObject.NULL)
        put("ledAddress", s.ledAddress ?: JSONObject.NULL)
        put("ledName", s.ledName ?: JSONObject.NULL)
        put("extraLeds", JSONArray().also { a -> s.extraLeds.forEach { a.put(JSONObject().put("address", it.address).put("name", it.name ?: JSONObject.NULL)) } })
        put("ledSimulated", s.ledSimulated)
        put("technicalMode", s.technicalMode)
        put("theme", s.theme.name)
        put("disclaimerAccepted", s.disclaimerAccepted)
        put("led", led(s.led))
    }.toString()

    fun decode(json: String?): AppSettings {
        if (json.isNullOrBlank()) return AppSettings()
        return try {
            val o = JSONObject(json)
            val d = AppSettings()
            val profiles = o.optJSONArray("profiles")?.let { a ->
                (0 until a.length()).mapNotNull { runCatching { profile(a.getJSONObject(it)) }.getOrNull() }
            }?.takeIf { it.isNotEmpty() }
                // perfis pré-cadastrados novos (versões mais novas do app) entram no fim da lista
                ?.let { saved -> saved + d.profiles.filter { p -> saved.none { it.id == p.id } } }
                ?: d.profiles
            AppSettings(
                profiles = profiles,
                activeProfileId = o.optString("activeProfileId", d.activeProfileId),
                autoDetectProfile = o.optBoolean("autoDetectProfile", d.autoDetectProfile),
                safety = o.optJSONObject("safety")?.let(::safety) ?: d.safety,
                source = enumOr(o.optString("source"), d.source),
                wheelAddress = o.optStringOrNull("wheelAddress"),
                wheelName = o.optStringOrNull("wheelName"),
                ledAddress = o.optStringOrNull("ledAddress"),
                ledName = o.optStringOrNull("ledName"),
                technicalMode = o.optBoolean("technicalMode", false),
                extraLeds = o.optJSONArray("extraLeds")?.let { a ->
                    (0 until a.length()).mapNotNull { i ->
                        a.optJSONObject(i)?.let { e -> e.optStringOrNull("address")?.let { SavedLed(it, e.optStringOrNull("name")) } }
                    }.distinctBy { it.address }.take(FanoutLedController.MAX_DEVICES - 1)
                } ?: emptyList(),
                ledSimulated = o.optBoolean("ledSimulated", d.ledSimulated),
                theme = enumOr(o.optString("theme"), d.theme),
                disclaimerAccepted = o.optBoolean("disclaimerAccepted", false),
                led = o.optJSONObject("led")?.let(::led) ?: d.led,
            )
        } catch (e: Exception) {
            AppSettings()
        }
    }

    // ---- helpers ------------------------------------------------------------------------------

    private fun JSONObject.optStringOrNull(k: String): String? = if (isNull(k) || !has(k)) null else optString(k).ifBlank { null }
    private inline fun <reified E : Enum<E>> enumOr(name: String, default: E): E =
        enumValues<E>().firstOrNull { it.name == name } ?: default

    private fun rgb(c: Rgb) = JSONArray(listOf(c.r, c.g, c.b))
    private fun rgb(a: JSONArray?, d: Rgb): Rgb = try {
        if (a == null) d else Rgb(a.getInt(0).coerceIn(0, 255), a.getInt(1).coerceIn(0, 255), a.getInt(2).coerceIn(0, 255))
    } catch (e: Exception) { d }

    private fun profile(p: WheelProfile) = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("model", p.model.name); put("protocol", p.protocol.name)
        put("maxSpeedKmh", p.maxSpeedKmh.toDouble()); put("tiltbackSpeedKmh", p.tiltbackSpeedKmh.toDouble())
        put("nominalVoltage", p.nominalVoltage.toDouble()); put("fullVoltage", p.fullVoltage.toDouble())
        put("emptyVoltage", p.emptyVoltage.toDouble())
        put("batteryCurve", JSONArray().also { a -> p.batteryCurve.forEach { a.put(JSONArray(listOf(it.voltage.toDouble(), it.percent.toDouble()))) } })
        put("rotationSpeedKmh", p.pwmCalibration.rotationSpeedKmh.toDouble())
        put("rotationVoltage", p.pwmCalibration.rotationVoltage.toDouble())
        put("powerFactor", p.pwmCalibration.powerFactor.toDouble())
        put("stops", JSONArray().also { a -> p.pwmStops.forEach { a.put(JSONObject().put("at", it.at.toDouble()).put("c", rgb(it.color))) } })
        put("flashAbovePwm", p.flashAbovePwm.toDouble())
        put("lowBatteryWarnPercent", p.lowBatteryWarnPercent.toDouble())
        put("lowBatteryCriticalPercent", p.lowBatteryCriticalPercent.toDouble())
    }

    private fun profile(o: JSONObject): WheelProfile {
        val model = enumOr(o.optString("model"), WheelModel.CUSTOM)
        val base = WheelProfile.forModel(model)
        fun f(k: String, d: Float) = if (o.has(k)) o.optDouble(k, d.toDouble()).toFloat() else d
        val stops = o.optJSONArray("stops")?.let { a ->
            (0 until a.length()).mapNotNull {
                runCatching { a.getJSONObject(it).let { s -> ColorStop(s.getDouble("at").toFloat().coerceIn(0f, 1f), rgb(s.getJSONArray("c"), Rgb.WHITE)) } }.getOrNull()
            }
        }?.takeIf { it.size >= 2 } ?: base.pwmStops
        val curve = o.optJSONArray("batteryCurve")?.let { a ->
            (0 until a.length()).mapNotNull { runCatching { a.getJSONArray(it).let { p -> BatteryPoint(p.getDouble(0).toFloat(), p.getDouble(1).toFloat()) } }.getOrNull() }
        } ?: emptyList()
        return base.copy(
            id = o.optString("id", base.id).ifBlank { base.id },
            name = o.optString("name", base.name).ifBlank { base.name },
            model = model,
            protocol = enumOr(o.optString("protocol"), base.protocol),
            maxSpeedKmh = f("maxSpeedKmh", base.maxSpeedKmh).coerceAtLeast(1f),
            tiltbackSpeedKmh = f("tiltbackSpeedKmh", base.tiltbackSpeedKmh).coerceAtLeast(0f),
            nominalVoltage = f("nominalVoltage", base.nominalVoltage),
            fullVoltage = f("fullVoltage", base.fullVoltage),
            emptyVoltage = f("emptyVoltage", base.emptyVoltage),
            batteryCurve = curve,
            pwmCalibration = PwmCalibration(
                f("rotationSpeedKmh", base.pwmCalibration.rotationSpeedKmh).coerceAtLeast(1f),
                f("rotationVoltage", base.pwmCalibration.rotationVoltage).coerceAtLeast(1f),
                f("powerFactor", base.pwmCalibration.powerFactor).coerceIn(0.1f, 2f),
            ),
            pwmStops = stops,
            flashAbovePwm = f("flashAbovePwm", base.flashAbovePwm).coerceIn(0.3f, 1f),
            lowBatteryWarnPercent = f("lowBatteryWarnPercent", base.lowBatteryWarnPercent),
            lowBatteryCriticalPercent = f("lowBatteryCriticalPercent", base.lowBatteryCriticalPercent),
        )
    }

    private fun safety(c: SafetyConfig) = JSONObject().apply {
        put("enabled", c.enabled); put("mode", c.mode.name); put("stepped", c.stepped)
        put("speedLimitKmh", c.speedLimitKmh?.toDouble() ?: JSONObject.NULL)
        put("speedStops", c.speedStops?.let { st -> JSONArray().also { a -> st.forEach { a.put(JSONObject().put("at", it.at.toDouble()).put("c", rgb(it.color))) } } } ?: JSONObject.NULL)
        put("speedFlashKmh", c.speedFlashKmh?.toDouble() ?: JSONObject.NULL)
        put("brightness", c.brightness); put("attackSeconds", c.attackSeconds.toDouble())
        put("releaseSeconds", c.releaseSeconds.toDouble()); put("hysteresis", c.hysteresis.toDouble())
        put("signalTimeoutMs", c.signalTimeoutMs); put("flashPeriodMs", c.flashPeriodMs)
        put("batteryFactor", c.batteryFactor); put("batteryFilterSeconds", c.batteryFilterSeconds.toDouble())
        put("faultStyle", c.fault.style.name); put("faultColor", rgb(c.fault.color)); put("faultPeriodMs", c.fault.periodMs)
        put("restPulse", c.restPulse.enabled); put("restBelow", c.restPulse.belowKmh.toDouble())
        put("fixedColor", c.fixedColor.enabled); put("fixedBelow", c.fixedColor.belowKmh.toDouble()); put("fixedRgb", rgb(c.fixedColor.color))
        put("brake", c.brake.enabled); put("brakeRegenA", c.brake.regenCurrentA.toDouble()); put("brakeDecel", c.brake.decelKmhPerS.toDouble())
    }

    private fun safety(o: JSONObject): SafetyConfig {
        val d = SafetyConfig()
        fun f(k: String, x: Float) = if (o.has(k)) o.optDouble(k, x.toDouble()).toFloat() else x
        return SafetyConfig(
            enabled = o.optBoolean("enabled", d.enabled),
            mode = enumOr(o.optString("mode"), d.mode),
            stepped = o.optBoolean("stepped", d.stepped),
            speedStops = o.optJSONArray("speedStops")?.let { a ->
                (0 until a.length()).mapNotNull {
                    runCatching { a.getJSONObject(it).let { s -> ColorStop(s.getDouble("at").toFloat().coerceIn(0f, 400f), rgb(s.getJSONArray("c"), Rgb.WHITE)) } }.getOrNull()
                }.takeIf { it.size >= 2 }
            },
            speedFlashKmh = if (o.isNull("speedFlashKmh") || !o.has("speedFlashKmh")) null else o.optDouble("speedFlashKmh").toFloat().takeIf { it > 0f },
            speedLimitKmh = if (o.isNull("speedLimitKmh") || !o.has("speedLimitKmh")) null else o.optDouble("speedLimitKmh").toFloat().takeIf { it > 0f },
            brightness = o.optInt("brightness", d.brightness).coerceIn(0, 255),
            attackSeconds = f("attackSeconds", d.attackSeconds).coerceIn(0f, 10f),
            releaseSeconds = f("releaseSeconds", d.releaseSeconds).coerceIn(0f, 30f),
            hysteresis = f("hysteresis", d.hysteresis).coerceIn(0f, 0.2f),
            signalTimeoutMs = o.optLong("signalTimeoutMs", d.signalTimeoutMs).coerceIn(300L, 10_000L),
            flashPeriodMs = o.optLong("flashPeriodMs", d.flashPeriodMs).coerceIn(200L, 2000L),
            batteryFactor = o.optBoolean("batteryFactor", d.batteryFactor),
            batteryFilterSeconds = f("batteryFilterSeconds", d.batteryFilterSeconds).coerceIn(0f, 30f),
            fault = FaultConfig(enumOr(o.optString("faultStyle"), d.fault.style), rgb(o.optJSONArray("faultColor"), d.fault.color), o.optLong("faultPeriodMs", d.fault.periodMs).coerceIn(300L, 5000L)),
            restPulse = RestPulseConfig(o.optBoolean("restPulse", false), f("restBelow", d.restPulse.belowKmh)),
            fixedColor = FixedColorConfig(o.optBoolean("fixedColor", false), f("fixedBelow", d.fixedColor.belowKmh), rgb(o.optJSONArray("fixedRgb"), d.fixedColor.color)),
            brake = BrakeConfig(o.optBoolean("brake", false), f("brakeRegenA", d.brake.regenCurrentA), f("brakeDecel", d.brake.decelKmhPerS)),
        )
    }

    private fun led(s: LedState) = JSONObject().apply {
        put("power", s.power); put("color", rgb(s.color)); put("brightness", s.brightness); put("effectId", s.effectId)
        put("speed", s.effectSpeed); put("length", s.effectLength); put("order", s.order.name)
        put("pixels", s.pixelCount); put("ic", s.ic.name)
    }

    private fun led(o: JSONObject): LedState {
        val d = LedState()
        return d.copy(
            power = o.optBoolean("power", d.power),
            color = rgb(o.optJSONArray("color"), d.color),
            brightness = o.optInt("brightness", d.brightness).coerceIn(0, 255),
            effectId = o.optInt("effectId", d.effectId).coerceIn(0, 255),
            effectSpeed = o.optInt("speed", d.effectSpeed).coerceIn(1, 10),
            effectLength = o.optInt("length", d.effectLength).coerceIn(1, 150),
            order = enumOr(o.optString("order"), ColorOrder.RGB),
            pixelCount = o.optInt("pixels", d.pixelCount).coerceIn(1, 2048),
            ic = enumOr(o.optString("ic"), StripIc.WS2811),
        )
    }
}
