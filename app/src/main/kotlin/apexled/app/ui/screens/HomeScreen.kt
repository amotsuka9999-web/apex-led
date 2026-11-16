package apexled.app.ui.screens

import apexled.app.data.TurnSide
import apexled.app.data.LedSide
import androidx.compose.ui.Alignment
import apexled.led.FanoutLedController
import android.Manifest
import android.content.Context
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.ui.BadRed
import apexled.app.ui.OkGreen
import apexled.app.ui.WarnAmber
import apexled.app.ui.components.BigButton
import apexled.app.ui.components.ChipRow
import apexled.app.ui.components.DangerButton
import apexled.app.ui.components.SafetyMeter
import apexled.app.ui.components.ScanDialog
import apexled.app.ui.components.ScanTarget
import apexled.app.ui.components.SectionCard
import apexled.app.ui.components.SimulatorPanel
import apexled.app.ui.components.StripsCard
import apexled.app.ui.components.StatusPill
import apexled.app.ui.components.SwitchRow
import apexled.core.LedLinkState
import apexled.core.SourceKind
import apexled.core.SourceStatus
import apexled.core.Telemetry
import apexled.data.ble.BlePermissions
import java.util.Locale

private fun SourceKind.label() = when (this) {
    SourceKind.SIMULATOR -> "Simulador"
    SourceKind.DIRECT_BLE -> "BLE direto"
    SourceKind.WHEELLOG_BRIDGE -> "WheelLog"
}

@Composable
fun HomeScreen(graph: AppGraph) {
    val ctx = LocalContext.current
    val settings by graph.settings.collectAsState()
    val ledLink by graph.ledLink.collectAsState()
    val ledName by graph.ledName.collectAsState()
    val srcStatus by graph.sourceStatus.collectAsState()
    val telemetry by graph.telemetry.collectAsState()
    val safety by graph.safety.collectAsState()
    val model by graph.detectedModel.collectAsState()
    val source by graph.source.collectAsState()
    val notice by graph.notice.collectAsState()
    val rssi by graph.gattRssi.collectAsState()

    var hasPerm by remember { mutableStateOf(BlePermissions.hasAll(ctx)) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPerm = BlePermissions.hasAll(ctx)
    }
    var scan by remember { mutableStateOf<ScanTarget?>(null) }
    var addingExtra by remember { mutableStateOf(false) }
    val extras by graph.extraLeds.collectAsState()

    // Perfil automático pelo modelo que a própria roda informa (Veteran/Nosfet, Kingsong e Inmotion informam).
    LaunchedEffect(model, settings.autoDetectProfile) {
        val m = model ?: return@LaunchedEffect
        if (!settings.autoDetectProfile) return@LaunchedEffect
        (settings.profiles.firstOrNull { it.name.equals(m, true) } ?: settings.profiles.firstOrNull { it.model.matches(m) })
            ?.let { p -> if (p.id != settings.activeProfileId) graph.update { it.copy(activeProfileId = p.id) } }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        notice?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(it, style = MaterialTheme.typography.bodyMedium)
                    BigButton("OK", { graph.clearNotice() }, primary = false)
                }
            }
        }

        if (!hasPerm) {
            SectionCard("Permissões", "O Bluetooth precisa de autorização para buscar e conectar.") {
                BigButton("Conceder permissões", {
                    val perms = BlePermissions.required().toMutableList()
                    if (Build.VERSION.SDK_INT >= 33) perms += Manifest.permission.POST_NOTIFICATIONS
                    permLauncher.launch(perms.toTypedArray())
                }, Modifier.fillMaxWidth())
            }
        }

        // ---- Fitas -------------------------------------------------------------------------
        if (settings.ledAddress == null && !settings.ledSimulated) {
            SectionCard("Fitas de LED", "Conecte a primeira placa SP621E. Depois dá para adicionar até 10.") {
                BigButton("Buscar fita", { scan = ScanTarget.LED }, Modifier.fillMaxWidth(), enabled = hasPerm)
            }
        } else {
            StripsCard(graph, hasPerm) { addingExtra = true; scan = ScanTarget.LED }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (!settings.ledSimulated) {
                    if (ledLink != LedLinkState.DISCONNECTED) BigButton("Desconectar fitas", { graph.disconnectLed() }, Modifier.weight(1f), primary = false)
                    else BigButton("Reconectar fitas", { graph.connectLed(settings.ledAddress!!, settings.ledName) }, Modifier.weight(1f), primary = false, enabled = hasPerm)
                    BigButton("Trocar principal", { scan = ScanTarget.LED }, Modifier.weight(1f), primary = false, enabled = hasPerm)
                }
            }
        }
        if (settings.technicalMode || settings.ledSimulated) SwitchRow("Fita simulada (sem Bluetooth)", settings.ledSimulated, { graph.setLedSimulated(it) },
            hint = "Mostra a fita na tela e registra os comandos. Útil para testar sem o hardware.")

        SectionCard("Setas", "Esquerda e direita piscam nas fitas do lado; o alerta, em todas. Desligam sozinhas em ${settings.turnSignalSeconds} s.") {
            TurnSignalRow(graph)
        }

        // ---- Roda --------------------------------------------------------------------------
        SectionCard("Roda", "Fonte da telemetria e perfil") {
            val running = source != null
            val kinds = if (settings.technicalMode) SourceKind.entries else listOf(SourceKind.DIRECT_BLE).let { if (settings.source in it) it else it + settings.source }
            ChipRow(kinds, settings.source, { it.label() }, { k -> if (!running) graph.update { s -> s.copy(source = k) } })
            if (running) Text("Pare a conexão para trocar a fonte.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Perfil", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ChipRow(settings.profiles, settings.activeProfile, { it.name }, { p -> graph.update { it.copy(activeProfileId = p.id) } })

            when (settings.source) {
                SourceKind.SIMULATOR -> Text("Telemetria falsa para testar a fita e a segurança sem a roda.", style = MaterialTheme.typography.bodyMedium)
                SourceKind.DIRECT_BLE -> {
                    Text("O app conecta direto à roda (recomendado: menor latência). A roda só aceita UMA conexão: feche o WheelLog antes.",
                        style = MaterialTheme.typography.bodyMedium)
                    Text((settings.wheelName ?: "Nenhuma roda escolhida") + (settings.wheelAddress?.let { "  ·  $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    BigButton("Escolher roda", { scan = ScanTarget.WHEEL }, Modifier.fillMaxWidth(), primary = false, enabled = hasPerm && !running)
                }
                SourceKind.WHEELLOG_BRIDGE -> Text(
                    "Lê os broadcasts que o WheelLog já emite (com.cooper.wheellog.wheelDataAvailable). Abra o WheelLog e conecte a roda por lá. " +
                        "Não é uma API oficial e pode mudar. Ainda não testado em aparelho.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val (t, c) = when (val s = srcStatus) {
                    is SourceStatus.Connected -> ("Conectada" + (model?.let { " · $it" } ?: "")) to OkGreen
                    is SourceStatus.Connecting -> "Conectando…" to WarnAmber
                    is SourceStatus.Failed -> "Falha" to BadRed
                    SourceStatus.Idle -> "Parada" to MaterialTheme.colorScheme.onSurfaceVariant
                }
                StatusPill(t, c)
            }
            (srcStatus as? SourceStatus.Connecting)?.detail?.takeIf { it.isNotBlank() }?.let {
                Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            (srcStatus as? SourceStatus.Failed)?.let { Text(it.reason, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error) }
            if (settings.technicalMode) (source as? apexled.data.DirectBleSource)?.let { d ->
                val diag by d.diagnostics.collectAsState()
                diag?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }

            if (!running) BigButton("Conectar / iniciar", { graph.startSource() }, Modifier.fillMaxWidth(), enabled = settings.source == SourceKind.SIMULATOR || hasPerm || settings.source == SourceKind.WHEELLOG_BRIDGE)
            else DangerButton("Parar", { graph.stopSource() }, Modifier.fillMaxWidth())
        }

        // ---- Telemetria --------------------------------------------------------------------
        SectionCard("Telemetria") { TelemetryGrid(telemetry) }

        safety?.let { s ->
            SectionCard("Margem de segurança") {
                SafetyMeter(settings.activeProfile.pwmStops, s.usage)
                Text(
                    String.format(Locale.US, "%s · margem %.0f%%", s.level.label, s.margin * 100) +
                        (s.limiting?.let { " · limita: ${it.label}" } ?: "") +
                        if (s.status == apexled.safety.SafetyStatus.NO_SIGNAL) " · SEM DADOS DA RODA" else "",
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (s.status == apexled.safety.SafetyStatus.NO_SIGNAL) WarnAmber else MaterialTheme.colorScheme.onBackground,
                )
            }
        }

        graph.simulator?.let { sim -> if (source != null) SectionCard("Simulador") { SimulatorPanel(sim) } }

        scan?.let { target ->
            ScanDialog(target, onDismiss = { scan = null; addingExtra = false }, onPick = { d ->
                scan = null
                if (target == ScanTarget.LED && addingExtra) { addingExtra = false; graph.addExtraLed(d.address, d.name) }
                else if (target == ScanTarget.LED) graph.connectLed(d.address, d.name)
                else graph.update { it.copy(wheelAddress = d.address, wheelName = d.name) }
            })
        }
    }
}

@Composable
fun TelemetryGrid(t: Telemetry?) {
    val now by produceState(0L) { while (true) { value = System.nanoTime() / 1_000_000L; kotlinx.coroutines.delay(250) } }
    fun f(v: Float?, fmt: String) = if (v == null) "—" else String.format(Locale.US, fmt, v)
    val stale = t != null && now - t.timestampMs > 1500
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Velocidade", f(t?.speedKmh, "%.0f"), "km/h", Modifier.weight(1f))
            Tile("PWM", f(t?.pwm?.times(100), "%.0f"), if (t?.pwmEstimated == true) "% (estimado)" else "%", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Bateria", f(t?.batteryPercent, "%.0f"), "%", Modifier.weight(1f))
            Tile("Tensão", f(t?.voltage, "%.1f"), "V", Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("Corrente", f(t?.currentA, "%.1f"), "A", Modifier.weight(1f))
            Tile("Temperatura", f(t?.temperatureC, "%.0f"), "°C", Modifier.weight(1f))
        }
        Text(
            when {
                t == null -> "Sem dados da roda."
                stale -> String.format(Locale.US, "Dados parados há %.1f s", (now - t.timestampMs) / 1000f)
                else -> "Dados em tempo real"
            },
            style = MaterialTheme.typography.labelMedium, color = if (stale || t == null) WarnAmber else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun Tile(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh), shape = MaterialTheme.shapes.medium) {
        Column(Modifier.padding(14.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = androidx.compose.ui.Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(value, style = MaterialTheme.typography.headlineMedium)
                Text(unit, style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(bottom = 4.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Botões de seta e pisca-alerta (também no relógio). */
@Composable
fun TurnSignalRow(graph: AppGraph) {
    val turn by graph.turn.collectAsState()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        BigButton("◀ Esquerda", { graph.signal(TurnSide.LEFT) }, Modifier.weight(1f), primary = turn?.side == TurnSide.LEFT)
        BigButton("⚠", { graph.signal(TurnSide.HAZARD) }, Modifier.weight(0.6f), primary = turn?.side == TurnSide.HAZARD)
        BigButton("Direita ▶", { graph.signal(TurnSide.RIGHT) }, Modifier.weight(1f), primary = turn?.side == TurnSide.RIGHT)
    }
}
