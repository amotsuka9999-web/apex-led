package apexled.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.ui.components.BigButton

/** Aviso mostrado nas abas manuais quando o motor de segurança está controlando a fita. */
@Composable
fun ManualLockBanner(graph: AppGraph, locked: Boolean) {
    if (!locked) return
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Text("A Segurança está controlando a fita agora; estes controles ficam travados.", style = MaterialTheme.typography.bodyMedium)
            BigButton("Pausar segurança e controlar manualmente", {
                graph.update { it.copy(safety = it.safety.copy(enabled = false)) }
            }, Modifier.fillMaxWidth(), primary = false)
        }
    }
}
