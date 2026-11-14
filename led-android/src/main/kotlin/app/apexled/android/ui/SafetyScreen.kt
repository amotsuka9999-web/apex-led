package app.apexled.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.apexled.android.R
import app.apexled.core.CriticalCause
import app.apexled.core.SafetyConfig
import app.apexled.pattern.CriticalStyle
import kotlin.math.roundToInt

/** When the boards turn red. Deliberately separate from the pattern settings. */
@Composable
fun SafetyScreen(viewModel: LedViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val critical by viewModel.critical.collectAsStateWithLifecycle()
    val safety = settings.safety

    fun updateSafety(transform: (SafetyConfig) -> SafetyConfig) =
        viewModel.update { it.copy(safety = transform(it.safety)) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(stringResource(R.string.led_alert_now))
        if (critical.active) {
            val causes = critical.causes.map { causeText(it) }
            Text(
                text = if (causes.isEmpty()) {
                    stringResource(R.string.led_alert_holding)
                } else {
                    stringResource(R.string.led_alert_active, causes.joinToString(", "))
                },
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.titleMedium,
            )
        } else {
            Text(stringResource(R.string.led_alert_clear))
        }

        SectionTitle(stringResource(R.string.led_alert_causes))
        Hint(stringResource(R.string.led_alert_app_alarm))

        Threshold(
            label = stringResource(R.string.led_alert_pwm),
            value = safety.pwmPercent,
            valueText = { stringResource(R.string.led_percent, it.roundToInt()) },
            range = 50f..100f,
            initial = 90.0,
            onChange = { value -> updateSafety { it.copy(pwmPercent = value) } },
        )
        Threshold(
            label = stringResource(R.string.led_alert_speed),
            value = safety.speedKmh,
            valueText = { stringResource(R.string.led_kmh, it.roundToInt()) },
            range = 10f..120f,
            initial = 50.0,
            onChange = { value -> updateSafety { it.copy(speedKmh = value) } },
        )
        Threshold(
            label = stringResource(R.string.led_alert_temperature),
            value = safety.temperatureC,
            valueText = { stringResource(R.string.led_celsius, it.roundToInt()) },
            range = 40f..110f,
            initial = 70.0,
            onChange = { value -> updateSafety { it.copy(temperatureC = value) } },
        )
        Threshold(
            label = stringResource(R.string.led_alert_current),
            value = safety.currentA,
            valueText = { stringResource(R.string.led_amps, it.roundToInt()) },
            range = 20f..400f,
            initial = 150.0,
            onChange = { value -> updateSafety { it.copy(currentA = value) } },
        )
        Threshold(
            label = stringResource(R.string.led_alert_voltage),
            value = safety.voltageBelowV,
            valueText = { stringResource(R.string.led_volts, it.roundToInt()) },
            range = 40f..180f,
            initial = 120.0,
            onChange = { value -> updateSafety { it.copy(voltageBelowV = value) } },
        )
        Hint(stringResource(R.string.led_alert_defaults_hint))

        SectionTitle(stringResource(R.string.led_alert_look))
        ChoiceRow(
            options = listOf(
                CriticalStyle.SOLID to stringResource(R.string.led_alert_solid),
                CriticalStyle.FLASH to stringResource(R.string.led_alert_flash),
            ),
            selected = settings.engine.criticalStyle,
            onSelect = { style -> viewModel.update { it.copy(engine = it.engine.copy(criticalStyle = style)) } },
        )
        LabeledSlider(
            label = stringResource(R.string.led_alert_hold),
            valueText = stringResource(R.string.led_ms, safety.holdMs.toInt()),
            value = safety.holdMs.toFloat(),
            range = 0f..SafetyConfig.MAX_HOLD_MS.toFloat(),
            onChange = { value ->
                updateSafety { it.copy(holdMs = ((value / 100).roundToInt() * 100L).coerceIn(0, SafetyConfig.MAX_HOLD_MS)) }
            },
        )
        Hint(stringResource(R.string.led_alert_disclaimer))
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun causeText(cause: CriticalCause) = stringResource(
    when (cause) {
        CriticalCause.APP_ALARM -> R.string.led_cause_app
        CriticalCause.PWM -> R.string.led_cause_pwm
        CriticalCause.SPEED -> R.string.led_cause_speed
        CriticalCause.TEMPERATURE -> R.string.led_cause_temperature
        CriticalCause.CURRENT -> R.string.led_cause_current
        CriticalCause.VOLTAGE -> R.string.led_cause_voltage
    },
)

/** An optional threshold: a switch to use it and, when on, a slider for its value. */
@Composable
private fun Threshold(
    label: String,
    value: Double?,
    valueText: @Composable (Double) -> String,
    range: ClosedFloatingPointRange<Float>,
    initial: Double,
    onChange: (Double?) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = value != null, onCheckedChange = { on -> onChange(if (on) initial else null) })
    }
    if (value != null) {
        LabeledSlider(
            label = stringResource(R.string.led_alert_threshold),
            valueText = valueText(value),
            value = value.toFloat(),
            range = range,
            onChange = { onChange(it.roundToInt().toDouble()) },
        )
    }
}
