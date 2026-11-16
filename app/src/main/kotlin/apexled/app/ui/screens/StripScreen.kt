package apexled.app.ui.screens

import java.io.File
import android.content.Intent
import android.content.Context
import androidx.core.content.FileProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import apexled.app.extras.TripRecorder
import apexled.app.ui.components.SwitchRow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.data.ThemeMode
import apexled.app.ui.components.BigButton
import apexled.app.ui.components.ChipRow
import apexled.app.ui.components.LabeledSlider
import apexled.app.ui.components.NumberField
import apexled.app.ui.components.SectionCard
import apexled.core.ColorOrder
import apexled.core.LedCommand
import apexled.core.Rgb
import apexled.core.StripIc
import kotlinx.coroutines.delay

/** Aba Config: tipo de CI, ordem de cor, número de pixels e teste de fita. */
@Composable
fun StripScreen(graph: AppGraph) {
    val state by graph.ledState.collectAsState()
    val locked by graph.safetyDriving.collectAsState()
    val led by graph.led.collectAsState()
    val settings by graph.settings.collectAsState()
    val log by graph.fakeLed.log.collectAsState()
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(testing) {
        if (testing) {
            for (c in listOf(Rgb(255, 0, 0), Rgb(0, 255, 0), Rgb(0, 0, 255), Rgb(255, 255, 255))) {
                led.send(LedCommand.Solid(c, 255)); delay(900)
            }
            testing = false
        }
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ManualLockBanner(graph, locked)

        SectionCard("Ordem de cor", "Se o vermelho do teste aparece verde, troque a ordem.") {
            ChipRow(ColorOrder.entries, state.order, { it.label }, { if (!locked) led.send(LedCommand.Order(it)) })
            Text("Envia o comando de ordem de cor ao SP621E (A0 64). Valor padrão de fábrica da sua fita: confirme no BanlanX.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Tipo de CI", "WS2811 / WS2812 etc.") {
            ChipRow(StripIc.entries, state.ic, { it.label }, { led.send(LedCommand.IcType(it)) })
            Text("⚠ O comando do SP621E para o tipo de CI ainda não foi capturado (veja docs/captura-ble.md): por enquanto este valor só fica salvo no app " +
                "e NÃO é enviado ao controlador. Use o BanlanX para configurar o CI.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Número de pixels") {
            var density by remember { mutableStateOf(200f) }
            var meters by remember { mutableStateOf(1f) }
            Text("Calculadora: LEDs por metro × comprimento da fita.", style = MaterialTheme.typography.bodyMedium)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                NumberField("LEDs por metro", density, { density = it.coerceAtLeast(1f) }, Modifier.weight(1f), 0)
                NumberField("Comprimento", meters, { meters = it.coerceAtLeast(0.1f) }, Modifier.weight(1f), 2, "m")
            }
            val calc = (density * meters).toInt().coerceIn(1, 2048)
            BigButton("Usar $calc pixels", { led.send(LedCommand.PixelCount(calc)) }, Modifier.fillMaxWidth(), primary = false)
            LabeledSlider("Pixels", state.pixelCount.toFloat(), 1f..1000f, { led.send(LedCommand.PixelCount(it.toInt())) }, "${state.pixelCount}")
            Text("⚠ Comando do SP621E para a quantidade de pixels ainda não capturado: aqui só ajusta a prévia (que desenha no máximo 48 pontos). " +
                "Configure o valor real no BanlanX.",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        SectionCard("Teste de fita") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Vermelho", { led.send(LedCommand.Solid(Rgb(255, 0, 0), 255)) }, Modifier.weight(1f), enabled = !locked, primary = false)
                BigButton("Verde", { led.send(LedCommand.Solid(Rgb(0, 255, 0), 255)) }, Modifier.weight(1f), enabled = !locked, primary = false)
                BigButton("Azul", { led.send(LedCommand.Solid(Rgb(0, 0, 255), 255)) }, Modifier.weight(1f), enabled = !locked, primary = false)
            }
            BigButton(if (testing) "Testando…" else "Sequência R → G → B → branco", { testing = true }, Modifier.fillMaxWidth(), enabled = !locked && !testing)
        }

        SectionCard("Aparência") {
            ChipRow(ThemeMode.entries, settings.theme, { it.label }, { m -> graph.update { it.copy(theme = m) } })
            SwitchRow("Modo técnico", settings.technicalMode, { v -> graph.update { it.copy(technicalMode = v) } },
                hint = "Mostra as ferramentas de teste: simulador, fita simulada, ponte WheelLog, diagnóstico da conexão e o modo Barra.")
        }

        if (settings.ledSimulated && settings.technicalMode) {
            SectionCard("Comandos (fita simulada)", "Bytes que o app enviaria ao SP621E") {
                Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                    if (log.isEmpty()) Text("Nada enviado ainda.", style = MaterialTheme.typography.bodyMedium)
                    log.takeLast(60).reversed().forEach { Text(it, style = MaterialTheme.typography.labelMedium.copy(fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)) }
                }
            }
        }

        // ---- passeios ---------------------------------------------------------------------
        SectionCard("Passeios", "CSV com velocidade, PWM, bateria e temperatura (abre no Excel/Planilhas).") {
            val ctx = LocalContext.current
            var trips by remember { mutableStateOf(TripRecorder.list(ctx)) }
            SwitchRow("Gravar os passeios", settings.tripLog, { v -> graph.update { it.copy(tripLog = v) } },
                hint = "Começa a gravar na próxima conexão com a roda e para ao desconectar.")
            if (trips.isEmpty()) Text("Nenhum passeio gravado ainda.", style = MaterialTheme.typography.bodyMedium)
            trips.take(8).forEach { f ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(f.nameWithoutExtension.removePrefix("passeio_").replace('_', ' '), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    BigButton("Enviar", { shareFile(ctx, f, "text/csv") }, primary = false)
                    BigButton("✕", { f.delete(); trips = TripRecorder.list(ctx) }, primary = false)
                }
            }
            LaunchedEffect(settings.tripLog) { trips = TripRecorder.list(ctx) }
        }

        // ---- backup -----------------------------------------------------------------------
        SectionCard("Backup das configurações", "Perfis, cores, grupo de fitas, setas e alertas num arquivo.") {
            val ctx = LocalContext.current
            var msg by remember { mutableStateOf<String?>(null) }
            val save = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
                if (uri != null) msg = runCatching {
                    ctx.contentResolver.openOutputStream(uri)!!.use { it.write(graph.exportSettings().toByteArray()) }; "Backup salvo."
                }.getOrElse { "Não consegui salvar: ${it.message}" }
            }
            val open = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                if (uri != null) msg = runCatching {
                    val json = ctx.contentResolver.openInputStream(uri)!!.use { it.readBytes().decodeToString() }
                    if (graph.importSettings(json)) "Configurações restauradas." else "Esse arquivo não é um backup do Apex LED."
                }.getOrElse { "Não consegui abrir: ${it.message}" }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Salvar backup", { save.launch("apex-led-backup.json") }, Modifier.weight(1f), primary = false)
                BigButton("Restaurar", { open.launch(arrayOf("application/json", "*/*")) }, Modifier.weight(1f), primary = false)
            }
            msg?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
        }

        SectionCard("Sobre") {
            Text("Apex LED ${apexled.app.BuildConfig.VERSION_NAME} · GPL-3.0. Indicador visual adicional: não substitui alarmes da roda. Protocolo do SP621E baseado em engenharia reversa " +
                "da comunidade (uniled, MIT) e ainda não validado em hardware.",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Compartilha um arquivo do app (WhatsApp, e-mail, Drive…). */
fun shareFile(ctx: Context, f: File, mime: String) {
    val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
    val send = Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    ctx.startActivity(Intent.createChooser(send, f.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}
