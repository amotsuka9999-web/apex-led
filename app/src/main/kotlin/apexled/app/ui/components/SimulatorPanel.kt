package apexled.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apexled.data.SimScenario
import apexled.data.SimulatorSource
import java.util.Locale

/** Controles do simulador: cenários prontos e sliders de velocidade/PWM/bateria. */
@Composable
fun SimulatorPanel(sim: SimulatorSource, modifier: Modifier = Modifier) {
    val scenario by sim.scenario.collectAsState()
    val manual by sim.manual.collectAsState()
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Cenário", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChipRow(SimScenario.entries, scenario, { it.label }, sim::setScenario)
        val manualOn = scenario == SimScenario.MANUAL || scenario == SimScenario.SIGNAL_LOSS
        LabeledSlider("Velocidade", manual.speedKmh, 0f..80f, { sim.setManual(manual.copy(speedKmh = it)) },
            String.format(Locale.US, "%.0f km/h", manual.speedKmh), enabled = manualOn)
        LabeledSlider("PWM", manual.pwm, 0f..1.05f, { sim.setManual(manual.copy(pwm = it)) },
            String.format(Locale.US, "%.0f %%", manual.pwm * 100), enabled = manualOn)
        LabeledSlider("Bateria", manual.batteryPercent, 0f..100f, { sim.setManual(manual.copy(batteryPercent = it)) },
            String.format(Locale.US, "%.0f %%", manual.batteryPercent))
        if (!manualOn) Text("Os sliders de velocidade e PWM valem no cenário \"Manual\".", style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
