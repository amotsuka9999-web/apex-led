package apexled.app.ui.components

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import apexled.data.ble.BleScanner
import apexled.data.ble.ScannedDevice

enum class ScanTarget(val title: String) { LED("Escolher a fita (SP621E)"), WHEEL("Escolher a roda") }

/** Lista de dispositivos BLE encontrados; o provável alvo aparece primeiro. */
@Composable
fun ScanDialog(target: ScanTarget, onPick: (ScannedDevice) -> Unit, onDismiss: () -> Unit) {
    val context: Context = LocalContext.current
    val found = remember { mutableStateMapOf<String, ScannedDevice>() }
    var error by remember { mutableStateOf<String?>(null) }
    var showAll by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        try {
            BleScanner(context).scan().collect { found[it.address] = it }
        } catch (e: Exception) {
            error = e.message ?: "Falha na busca"
        }
    }

    val list = found.values
        // Mostra também aparelhos sem nome que tenham cara de SP6xx ou de roda (o SP621E às vezes anuncia sem nome).
        .filter { showAll || it.name != null || it.looksLikeSpLed || it.hasFfe0 }
        .sortedWith(
            compareByDescending<ScannedDevice> { if (target == ScanTarget.LED) it.looksLikeSpLed else it.hasFfe0 }
                .thenByDescending { it.rssi },
        )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(target.title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (error == null) CircularProgressIndicator(Modifier.padding(end = 4.dp), strokeWidth = 2.dp)
                    Text(error ?: "Procurando… aproxime-se do dispositivo.", style = MaterialTheme.typography.bodyMedium,
                        color = if (error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (target == ScanTarget.LED) Text(
                    "Feche o BanlanX por completo antes (o SP621E some da busca enquanto outro app está conectado). Não aparece? Toque em \"Mostrar todos\".",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                if (target == ScanTarget.WHEEL) Text(
                    "A roda só aceita uma conexão por vez: feche o WheelLog e o app da marca (Begode, Leaperkim, Kingsong, Inmotion) antes, senão ela não aparecerá/conectará.",
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
                )
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(list, key = { it.address }) { d ->
                        val likely = if (target == ScanTarget.LED) d.looksLikeSpLed else d.hasFfe0
                        Column(Modifier.fillMaxWidth().clickable { onPick(d) }.padding(vertical = 10.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text(d.name ?: "(sem nome)", style = MaterialTheme.typography.titleMedium)
                                Text("${d.rssi} dBm", style = MaterialTheme.typography.labelMedium)
                            }
                            Text(
                                d.address + if (likely) "  ·  provável" else "",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (likely) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton({ showAll = !showAll }) { Text(if (showAll) "Só com nome" else "Mostrar todos") } },
        dismissButton = { TextButton(onDismiss) { Text("Fechar") } },
    )
}
