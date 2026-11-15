package apexled.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.ui.components.StatusPill
import apexled.app.ui.components.StripPreview
import apexled.app.ui.screens.ColorScreen
import apexled.app.ui.screens.EffectsScreen
import apexled.app.ui.screens.HomeScreen
import apexled.app.ui.screens.SafetyScreen
import apexled.app.ui.screens.StripScreen
import apexled.core.LedLinkState
import apexled.core.SourceStatus
import java.util.Locale

private enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Início", Icons.Filled.Home),
    COLOR("Cor", Icons.Filled.Create),
    EFFECTS("Efeitos", Icons.Filled.Star),
    STRIP("Config", Icons.Filled.Settings),
    SAFETY("Segurança", Icons.Filled.Warning),
}

val OkGreen = Color(0xFF3DDC84)
val WarnAmber = Color(0xFFFFB300)
val BadRed = Color(0xFFFF5252)

@Composable
fun MainScreen(graph: AppGraph) {
    val settings by graph.settings.collectAsState()
    val loaded by graph.loaded.collectAsState()
    val ledState by graph.ledState.collectAsState()
    val ledLink by graph.ledLink.collectAsState()
    val srcStatus by graph.sourceStatus.collectAsState()
    val safety by graph.safety.collectAsState()
    var tab by rememberSaveable { mutableIntStateOf(0) }

    if (loaded && !settings.disclaimerAccepted) DisclaimerDialog { graph.update { it.copy(disclaimerAccepted = true) } }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column(Modifier.windowInsetsPadding(WindowInsets.statusBars).padding(top = 8.dp)) {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Apex LED", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(end = 4.dp))
                    val (ledText, ledColor) = when {
                        settings.ledSimulated -> "Fita simulada" to WarnAmber
                        ledLink == LedLinkState.CONNECTED -> "Fita ok" to OkGreen
                        ledLink == LedLinkState.DISCONNECTED -> "Fita desconectada" to BadRed
                        else -> "Fita reconectando" to WarnAmber
                    }
                    StatusPill(ledText, ledColor)
                    val (wText, wColor) = when (srcStatus) {
                        is SourceStatus.Connected -> "Roda ok" to OkGreen
                        is SourceStatus.Connecting -> "Roda…" to WarnAmber
                        is SourceStatus.Failed -> "Roda falhou" to BadRed
                        SourceStatus.Idle -> "Roda off" to MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    StatusPill(wText, wColor)
                }
                val caption = safety?.let {
                    String.format(Locale.US, "Segurança: %s · margem %.0f%%", it.level.label, it.margin * 100)
                } ?: "Controle manual"
                StripPreview(ledState, caption = caption)
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surfaceContainer) {
                Tab.entries.forEachIndexed { i, t ->
                    NavigationBarItem(
                        selected = tab == i, onClick = { tab = i },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label, style = MaterialTheme.typography.labelMedium) },
                    )
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            when (Tab.entries[tab]) {
                Tab.HOME -> HomeScreen(graph)
                Tab.COLOR -> ColorScreen(graph)
                Tab.EFFECTS -> EffectsScreen(graph)
                Tab.STRIP -> StripScreen(graph)
                Tab.SAFETY -> SafetyScreen(graph)
            }
        }
    }
}

@Composable
private fun DisclaimerDialog(onAccept: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text("Antes de usar") },
        text = {
            Text(
                "Este app mostra, com as cores da fita, quanto de margem a roda ainda tem. É um indicador visual " +
                    "ADICIONAL: ele NÃO substitui os alarmes, o tiltback nem a atenção de quem pilota.\n\n" +
                    "A leitura depende de Bluetooth e do protocolo da roda; pode falhar, atrasar ou ficar desatualizada. " +
                    "Se a fita ficar âmbar, o app perdeu os dados da roda. Nunca confie só nas luzes.\n\n" +
                    "Ainda não foi validado em roda nem em controlador reais: teste parado e em baixa velocidade primeiro.",
                style = MaterialTheme.typography.bodyLarge,
            )
        },
        confirmButton = { TextButton(onAccept) { Text("Entendi e aceito") } },
    )
}
