package app.apexled.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RangeSlider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.apexled.android.R
import app.apexled.core.LedSettings
import app.apexled.core.TelemetryLossBehavior
import app.apexled.pattern.BrightnessConfig
import app.apexled.pattern.EngineConfig
import app.apexled.pattern.IdleLight
import app.apexled.pattern.InputSource
import app.apexled.pattern.Pattern
import app.apexled.pattern.PatternEngine
import app.apexled.pattern.PulseItem
import app.apexled.pattern.PulseShape
import app.apexled.pattern.Rgb
import app.apexled.pattern.SPECTRUM_STOPS
import app.apexled.pattern.SyncMode
import kotlin.math.roundToInt

private enum class PatternKind { SPECTRUM, CUSTOM, SPEED_BLINK, OTHER }

private fun Pattern.kind() = when (this) {
    Pattern.PwmSpectrum -> PatternKind.SPECTRUM
    is Pattern.CustomSpectrum -> PatternKind.CUSTOM
    is Pattern.SpeedBlink -> PatternKind.SPEED_BLINK
    else -> PatternKind.OTHER
}

private const val PREVIEW_STEPS = 96

/** Pattern settings with a live preview of the colour band. */
@Composable
fun PatternScreen(viewModel: LedViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val frames by viewModel.frames.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle()
    val engine = settings.engine

    fun updateEngine(transform: (EngineConfig) -> EngineConfig) =
        viewModel.update { it.copy(engine = transform(it.engine)) }

    // Remembers the last edit of each kind, so switching kinds back and forth does not lose it.
    var lastCustom by remember { mutableStateOf(Pattern.CustomSpectrum()) }
    var lastBlink by remember { mutableStateOf(Pattern.SpeedBlink()) }
    when (val current = engine.pattern) {
        is Pattern.CustomSpectrum -> lastCustom = current
        is Pattern.SpeedBlink -> lastBlink = current
        else -> Unit
    }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(stringResource(R.string.led_preview))
        PreviewStrip(
            colors = PatternEngine.preview(engine.pattern, PREVIEW_STEPS),
            position = position,
            description = stringResource(R.string.led_preview_description, (position * 100).roundToInt()),
        )
        Spacer(Modifier.height(8.dp))
        LiveBoards(frames)

        SectionTitle(stringResource(R.string.led_input))
        ChoiceRow(
            options = listOf(
                InputSource.PWM to stringResource(R.string.led_input_pwm),
                InputSource.SPEED to stringResource(R.string.led_input_speed),
            ),
            selected = engine.input.source,
            onSelect = { source -> updateEngine { it.copy(input = it.input.copy(source = source)) } },
        )
        if (engine.input.source == InputSource.SPEED) {
            LabeledSlider(
                label = stringResource(R.string.led_speed_max),
                valueText = stringResource(R.string.led_kmh, engine.input.speedMaxKmh.roundToInt()),
                value = engine.input.speedMaxKmh.toFloat(),
                range = 10f..120f,
                onChange = { value ->
                    updateEngine { it.copy(input = it.input.copy(speedMinKmh = 0.0, speedMaxKmh = value.roundToInt().toDouble())) }
                },
            )
        }

        SectionTitle(stringResource(R.string.led_pattern))
        ChoiceRow(
            options = listOf(
                PatternKind.SPECTRUM to stringResource(R.string.led_mode_1),
                PatternKind.CUSTOM to stringResource(R.string.led_mode_2),
                PatternKind.SPEED_BLINK to stringResource(R.string.led_mode_3),
            ),
            selected = engine.pattern.kind(),
            onSelect = { kind ->
                // Each mode starts on its own input (PWM for 1 and 2, speed for 3); it can be changed above.
                when (kind) {
                    PatternKind.SPECTRUM -> updateEngine {
                        it.copy(pattern = Pattern.PwmSpectrum, input = it.input.copy(source = InputSource.PWM))
                    }
                    PatternKind.CUSTOM -> updateEngine {
                        it.copy(pattern = lastCustom, input = it.input.copy(source = InputSource.PWM))
                    }
                    PatternKind.SPEED_BLINK -> updateEngine {
                        it.copy(pattern = lastBlink, input = it.input.copy(source = InputSource.SPEED))
                    }
                    PatternKind.OTHER -> Unit
                }
            },
        )

        when (val pattern = engine.pattern) {
            Pattern.PwmSpectrum -> Hint(stringResource(R.string.led_mode_1_hint))
            is Pattern.CustomSpectrum -> {
                Hint(stringResource(R.string.led_mode_2_hint))
                pattern.colors.forEachIndexed { index, color ->
                    ColorEditor(stringResource(R.string.led_stop, (SPECTRUM_STOPS[index] * 100).roundToInt()), color) { new ->
                        updateEngine { it.copy(pattern = Pattern.CustomSpectrum(pattern.colors.toMutableList().also { list -> list[index] = new })) }
                    }
                }
            }
            is Pattern.SpeedBlink -> {
                Hint(stringResource(R.string.led_mode_3_hint))
                ColorEditor(stringResource(R.string.led_blink_first), pattern.first) { new -> updateEngine { it.copy(pattern = pattern.copy(first = new)) } }
                ColorEditor(stringResource(R.string.led_blink_second), pattern.second) { new -> updateEngine { it.copy(pattern = pattern.copy(second = new)) } }
                LabeledSlider(
                    label = stringResource(R.string.led_blink_max),
                    valueText = stringResource(R.string.led_hz, pattern.maxHz.toFloat()),
                    value = pattern.maxHz.toFloat(),
                    range = Pattern.SpeedBlink.MIN_HZ_LIMIT.toFloat()..Pattern.SpeedBlink.MAX_HZ_LIMIT.toFloat(),
                    onChange = { value ->
                        val hz = ((value * 2).roundToInt() / 2.0).coerceIn(Pattern.SpeedBlink.MIN_HZ_LIMIT, Pattern.SpeedBlink.MAX_HZ_LIMIT)
                        updateEngine { it.copy(pattern = pattern.copy(maxHz = hz)) }
                    },
                )
                Text(
                    stringResource(R.string.led_blink_warning),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Pattern.ContinuousGradient -> Hint(stringResource(R.string.led_pattern_continuous_hint))
            is Pattern.ThreeColorGradient -> ThreeColorEditor(pattern) { new -> updateEngine { it.copy(pattern = new) } }
            is Pattern.Pulse -> PulseEditor(pattern) { new -> updateEngine { it.copy(pattern = new) } }
            is Pattern.ColorSequence -> SequenceEditor(pattern) { new -> updateEngine { it.copy(pattern = new) } }
        }

        SectionTitle(stringResource(R.string.led_brightness))
        LabeledSlider(
            label = stringResource(R.string.led_brightness_master),
            valueText = percent(engine.brightness.master),
            value = engine.brightness.master.toFloat(),
            range = 0f..1f,
            onChange = { value -> updateEngine { it.copy(brightness = it.brightness.copy(master = value.toDouble())) } },
        )
        LabeledSlider(
            label = stringResource(R.string.led_brightness_max),
            valueText = percent(engine.brightness.max),
            value = engine.brightness.max.toFloat(),
            range = BrightnessConfig.MIN_MAX_BRIGHTNESS.toFloat()..1f,
            onChange = { value ->
                val max = value.toDouble().coerceIn(BrightnessConfig.MIN_MAX_BRIGHTNESS, 1.0)
                updateEngine { it.copy(brightness = it.brightness.copy(max = max)) }
            },
        )
        Hint(stringResource(R.string.led_brightness_max_hint))

        SectionTitle(stringResource(R.string.led_sync))
        ChoiceRow(
            options = listOf(
                false to stringResource(R.string.led_sync_together),
                true to stringResource(R.string.led_sync_staggered),
            ),
            selected = engine.sync is SyncMode.Staggered,
            onSelect = { staggered ->
                updateEngine { it.copy(sync = if (staggered) SyncMode.Staggered() else SyncMode.Synchronized) }
            },
        )
        (engine.sync as? SyncMode.Staggered)?.let { sync ->
            LabeledSlider(
                label = stringResource(R.string.led_sync_delay),
                valueText = stringResource(R.string.led_ms, sync.delayMs.toInt()),
                value = sync.delayMs.toFloat(),
                range = 0f..SyncMode.Staggered.MAX_DELAY_MS.toFloat(),
                onChange = { value ->
                    updateEngine { it.copy(sync = SyncMode.Staggered((value / 10).roundToInt() * 10L)) }
                },
            )
        }

        SectionTitle(stringResource(R.string.led_behaviour))
        LabeledSlider(
            label = stringResource(R.string.led_smoothing),
            valueText = stringResource(R.string.led_ms, settings.smoothingMs.toInt()),
            value = settings.smoothingMs.toFloat(),
            range = 0f..2_000f,
            onChange = { value ->
                viewModel.update { it.copy(smoothingMs = ((value / 50).roundToInt() * 50L).coerceIn(0, LedSettings.MAX_SMOOTHING_MS)) }
            },
        )
        Text(stringResource(R.string.led_telemetry_loss), style = MaterialTheme.typography.bodyMedium)
        ChoiceRow(
            options = listOf(
                TelemetryLossBehavior.ZERO_INPUT to stringResource(R.string.led_loss_zero),
                TelemetryLossBehavior.HOLD_LAST to stringResource(R.string.led_loss_hold),
                TelemetryLossBehavior.OFF to stringResource(R.string.led_loss_off),
            ),
            selected = settings.telemetryLoss,
            onSelect = { behaviour -> viewModel.update { it.copy(telemetryLoss = behaviour) } },
        )
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun percent(fraction: Double) = stringResource(R.string.led_percent, (fraction * 100).roundToInt())

@Composable
private fun ThreeColorEditor(pattern: Pattern.ThreeColorGradient, onChange: (Pattern) -> Unit) {
    ColorEditor(stringResource(R.string.led_color_start), pattern.start) { onChange(pattern.copy(start = it)) }
    ColorEditor(stringResource(R.string.led_color_middle), pattern.middle) { onChange(pattern.copy(middle = it)) }
    ColorEditor(stringResource(R.string.led_color_end), pattern.end) { onChange(pattern.copy(end = it)) }
    LabeledSlider(
        label = stringResource(R.string.led_midpoint),
        valueText = percent(pattern.midpoint),
        value = pattern.midpoint.toFloat(),
        range = 0.05f..0.95f,
        onChange = { onChange(pattern.copy(midpoint = it.toDouble().coerceIn(0.05, 0.95))) },
    )
}

@Composable
private fun PulseEditor(pattern: Pattern.Pulse, onChange: (Pattern) -> Unit) {
    Hint(stringResource(R.string.led_pulse_hint))
    pattern.items.forEachIndexed { index, item ->
        Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        stringResource(R.string.led_pulse_item, index + 1),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (pattern.items.size > 1) {
                        TextButton(onClick = { onChange(pattern.copy(items = pattern.items - item)) }) {
                            Text(stringResource(R.string.led_remove))
                        }
                    }
                }
                PulseItemEditor(item) { new ->
                    onChange(pattern.copy(items = pattern.items.toMutableList().also { it[index] = new }))
                }
            }
        }
    }
    if (pattern.items.size < Pattern.Pulse.MAX_ITEMS) {
        OutlinedButton(onClick = { onChange(pattern.copy(items = pattern.items + PulseItem())) }) {
            Text(stringResource(R.string.led_pulse_add))
        }
    }

    val steady = pattern.idle as? IdleLight.Steady
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.led_pulse_idle), modifier = Modifier.weight(1f))
        Switch(
            checked = steady != null,
            onCheckedChange = { on -> onChange(pattern.copy(idle = if (on) IdleLight.Steady(Rgb.WHITE, 0.3) else IdleLight.Off)) },
        )
    }
    if (steady != null) {
        ColorEditor(stringResource(R.string.led_color), steady.color) { onChange(pattern.copy(idle = steady.copy(color = it))) }
        LabeledSlider(
            label = stringResource(R.string.led_brightness),
            valueText = percent(steady.brightness),
            value = steady.brightness.toFloat(),
            range = 0f..1f,
            onChange = { onChange(pattern.copy(idle = steady.copy(brightness = it.toDouble()))) },
        )
    }
}

@Composable
private fun PulseItemEditor(item: PulseItem, onChange: (PulseItem) -> Unit) {
    ColorEditor(stringResource(R.string.led_color), item.color) { onChange(item.copy(color = it)) }
    ChoiceRow(
        options = listOf(
            PulseShape.BLINK to stringResource(R.string.led_pulse_blink),
            PulseShape.BREATHE to stringResource(R.string.led_pulse_breathe),
        ),
        selected = item.shape,
        onSelect = { onChange(item.copy(shape = it)) },
    )
    LabeledSlider(
        label = stringResource(R.string.led_pulse_frequency),
        valueText = stringResource(R.string.led_hz, item.frequencyHz.toFloat()),
        value = item.frequencyHz.toFloat(),
        range = PulseItem.MIN_HZ.toFloat()..PulseItem.MAX_HZ.toFloat(),
        onChange = { value ->
            val hz = ((value * 10).roundToInt() / 10.0).coerceIn(PulseItem.MIN_HZ, PulseItem.MAX_HZ)
            onChange(item.copy(frequencyHz = hz))
        },
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.led_pulse_range), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.led_range_percent, item.rangeStartPercent.roundToInt(), item.rangeEndPercent.roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    RangeSlider(
        value = item.rangeStartPercent.toFloat()..item.rangeEndPercent.toFloat(),
        onValueChange = { range ->
            val start = range.start.roundToInt().toDouble().coerceIn(0.0, 100.0)
            val end = range.endInclusive.roundToInt().toDouble().coerceIn(start, 100.0)
            onChange(item.copy(rangeStartPercent = start, rangeEndPercent = end))
        },
        valueRange = 0f..100f,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(stringResource(R.string.led_pulse_levels), style = MaterialTheme.typography.bodyMedium)
        Text(
            stringResource(R.string.led_range_percent, (item.minBrightness * 100).roundToInt(), (item.maxBrightness * 100).roundToInt()),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    RangeSlider(
        value = item.minBrightness.toFloat()..item.maxBrightness.toFloat(),
        onValueChange = { range ->
            val low = range.start.toDouble().coerceIn(0.0, 1.0)
            val high = range.endInclusive.toDouble().coerceIn(low, 1.0)
            onChange(item.copy(minBrightness = low, maxBrightness = high))
        },
        valueRange = 0f..1f,
    )
    if (item.shape == PulseShape.BLINK) {
        LabeledSlider(
            label = stringResource(R.string.led_pulse_duty),
            valueText = percent(item.dutyCycle),
            value = item.dutyCycle.toFloat(),
            range = 0.1f..0.9f,
            onChange = { onChange(item.copy(dutyCycle = it.toDouble().coerceIn(0.1, 0.9))) },
        )
    }
}

@Composable
private fun SequenceEditor(pattern: Pattern.ColorSequence, onChange: (Pattern) -> Unit) {
    Hint(stringResource(R.string.led_sequence_hint))
    pattern.colors.forEachIndexed { index, color ->
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                ColorEditor(stringResource(R.string.led_sequence_color, index + 1), color) { new ->
                    onChange(pattern.copy(colors = pattern.colors.toMutableList().also { it[index] = new }))
                }
            }
            if (pattern.colors.size > 2) {
                TextButton(
                    onClick = { onChange(pattern.copy(colors = pattern.colors.toMutableList().also { it.removeAt(index) })) },
                ) { Text(stringResource(R.string.led_remove)) }
            }
        }
    }
    if (pattern.colors.size < Pattern.ColorSequence.MAX_COLORS) {
        OutlinedButton(onClick = { onChange(pattern.copy(colors = pattern.colors + Rgb.WHITE)) }) {
            Text(stringResource(R.string.led_sequence_add))
        }
    }
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.led_sequence_smooth), modifier = Modifier.weight(1f))
        Switch(checked = pattern.smooth, onCheckedChange = { onChange(pattern.copy(smooth = it)) })
    }
}
