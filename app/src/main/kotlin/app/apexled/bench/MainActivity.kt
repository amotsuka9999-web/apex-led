package app.apexled.bench

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
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
import app.apexled.android.LedPermissions
import app.apexled.android.ui.LedApp
import app.apexled.android.ui.LedTab
import app.apexled.android.ui.LedViewModel
import app.apexled.device.BoardInfo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private val bench get() = application as BenchApplication

    private val viewModel: LedViewModel by viewModels {
        LedViewModel.Factory(bench.controller, bench.manager, bench.scanner)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                var granted by remember { mutableStateOf(LedPermissions.granted(this)) }
                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions(),
                ) { granted = LedPermissions.granted(this) }

                val simulatedName = stringResource(R.string.bench_simulated_name, bench.manager.size + 1)
                LedApp(
                    viewModel = viewModel,
                    modifier = Modifier.safeDrawingPadding(),
                    extraTabs = listOf(
                        LedTab(stringResource(R.string.bench_tab)) {
                            BenchScreen(
                                wheel = bench.wheel,
                                permissionGranted = granted,
                                onRequestPermission = { launcher.launch(LedPermissions.required()) },
                            )
                        },
                    ),
                    onAddSimulatedBoard = {
                        val id = UUID.randomUUID().toString()
                        viewModel.addBoard(
                            BoardInfo(id, simulatedName, BenchApplication.SIMULATED_PREFIX + id.take(8), "sp63x"),
                        )
                    },
                )
            }
        }
    }
}

/** Hand-operated wheel: sliders for speed and PWM, and a switch that plays the app's alarm. */
@Composable
private fun BenchScreen(
    wheel: MutableStateFlow<BenchWheel>,
    permissionGranted: Boolean,
    onRequestPermission: () -> Unit,
) {
    val state by wheel.collectAsStateWithLifecycle()
    Column(
        Modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        Text(stringResource(R.string.bench_intro), style = MaterialTheme.typography.bodyMedium)

        if (!permissionGranted) {
            Text(stringResource(R.string.bench_permission), modifier = Modifier.padding(top = 16.dp))
            Button(onClick = onRequestPermission) { Text(stringResource(R.string.bench_permission_button)) }
        }

        SwitchRow(stringResource(R.string.bench_feed), state.feeding) { on -> wheel.update { it.copy(feeding = on) } }

        ValueRow(stringResource(R.string.bench_speed), stringResource(R.string.bench_kmh, state.speedKmh.roundToInt()))
        Slider(
            value = state.speedKmh.toFloat(),
            onValueChange = { value -> wheel.update { it.copy(speedKmh = value.roundToInt().toDouble()) } },
            valueRange = 0f..100f,
        )

        ValueRow(stringResource(R.string.bench_pwm), stringResource(R.string.bench_percent, state.pwmPercent.roundToInt()))
        Slider(
            value = state.pwmPercent.toFloat(),
            onValueChange = { value -> wheel.update { it.copy(pwmPercent = value.roundToInt().toDouble()) } },
            valueRange = 0f..100f,
        )

        SwitchRow(stringResource(R.string.bench_alarm), state.appAlarm) { on -> wheel.update { it.copy(appAlarm = on) } }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun ValueRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Text(label, modifier = Modifier.weight(1f))
        Text(value)
    }
}
