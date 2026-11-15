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
import androidx.compose.material3.OutlinedButton
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
import app.apexled.core.ControlMode
import app.apexled.core.ManualLight
import app.apexled.device.BoardCommand
import app.apexled.device.LightMode
import app.apexled.device.PowerOnState
import kotlin.math.roundToInt

/** The boards' own functions: who controls the light, and the hand-picked light of manual mode. */
@Composable
fun ManualScreen(viewModel: LedViewModel, modifier: Modifier = Modifier) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val manual = settings.manual

    fun updateManual(transform: (ManualLight) -> ManualLight) =
        viewModel.update { it.copy(manual = transform(it.manual)) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(stringResource(R.string.led_control))
        ChoiceRow(
            options = listOf(
                ControlMode.PATTERN to stringResource(R.string.led_control_pattern),
                ControlMode.MANUAL to stringResource(R.string.led_control_manual),
            ),
            selected = settings.mode,
            onSelect = { mode -> viewModel.update { it.copy(mode = mode) } },
        )
        Hint(stringResource(R.string.led_control_hint))

        if (settings.mode == ControlMode.MANUAL) {
            SectionTitle(stringResource(R.string.led_manual_light))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.led_power), modifier = Modifier.weight(1f))
                Switch(checked = manual.power, onCheckedChange = { on -> updateManual { it.copy(power = on) } })
            }
            ChoiceRow(
                options = listOf(
                    LightMode.STATIC to stringResource(R.string.led_mode_static),
                    LightMode.DYNAMIC to stringResource(R.string.led_mode_dynamic),
                    LightMode.MUSIC to stringResource(R.string.led_mode_music),
                    LightMode.CUSTOM to stringResource(R.string.led_mode_custom),
                ),
                selected = manual.mode,
                onSelect = { mode -> updateManual { it.copy(mode = mode) } },
            )
            if (manual.mode == LightMode.STATIC) {
                ColorEditor(stringResource(R.string.led_color), manual.color) { color -> updateManual { it.copy(color = color) } }
            } else {
                LabeledSlider(
                    label = stringResource(R.string.led_effect),
                    valueText = manual.effect.toString(),
                    value = manual.effect.toFloat(),
                    range = 1f..MAX_EFFECT.toFloat(),
                    steps = MAX_EFFECT - 2,
                    onChange = { value -> updateManual { it.copy(effect = value.roundToInt().coerceIn(1, MAX_EFFECT)) } },
                )
                Hint(stringResource(R.string.led_effect_hint))
                LabeledSlider(
                    label = stringResource(R.string.led_effect_speed),
                    valueText = manual.effectSpeed.toString(),
                    value = manual.effectSpeed.toFloat(),
                    range = 1f..10f,
                    steps = 8,
                    onChange = { value -> updateManual { it.copy(effectSpeed = value.roundToInt().coerceIn(1, 10)) } },
                )
                LabeledSlider(
                    label = stringResource(R.string.led_effect_length),
                    valueText = manual.effectLength.toString(),
                    value = manual.effectLength.toFloat(),
                    range = 1f..150f,
                    onChange = { value -> updateManual { it.copy(effectLength = value.roundToInt().coerceIn(1, 150)) } },
                )
            }
            LabeledSlider(
                label = stringResource(R.string.led_brightness),
                valueText = stringResource(R.string.led_percent, (manual.level * 100 / 255.0).roundToInt()),
                value = manual.level.toFloat(),
                range = 0f..255f,
                onChange = { value -> updateManual { it.copy(level = value.roundToInt().coerceIn(0, 255)) } },
            )
        }

        SectionTitle(stringResource(R.string.led_board_options))
        Hint(stringResource(R.string.led_board_options_hint))
        Text(stringResource(R.string.led_power_on_state))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { viewModel.sendToBoards(BoardCommand.SetPowerOnState(PowerOnState.OFF)) }) {
                Text(stringResource(R.string.led_power_on_off))
            }
            OutlinedButton(onClick = { viewModel.sendToBoards(BoardCommand.SetPowerOnState(PowerOnState.ON)) }) {
                Text(stringResource(R.string.led_power_on_on))
            }
            OutlinedButton(onClick = { viewModel.sendToBoards(BoardCommand.SetPowerOnState(PowerOnState.LAST_STATE)) }) {
                Text(stringResource(R.string.led_power_on_last))
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Highest effect number offered. The real catalogue depends on the board model and is still to be surveyed. */
private const val MAX_EFFECT = 30
