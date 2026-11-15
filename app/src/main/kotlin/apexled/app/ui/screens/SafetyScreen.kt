package apexled.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.ui.BadRed
import apexled.app.ui.OkGreen
import apexled.app.ui.WarnAmber
import apexled.app.ui.components.BigButton
import apexled.app.ui.components.ChipRow
import apexled.app.ui.components.ColorPickDialog
import apexled.app.ui.components.LabeledSlider
import apexled.app.ui.components.NumberField
import apexled.app.ui.components.SafetyMeter
import apexled.app.ui.components.SectionCard
import apexled.app.ui.components.SimulatorPanel
import apexled.app.ui.components.SwitchRow
import apexled.app.ui.components.toColor
import apexled.core.ColorStop
import apexled.core.DefaultStops
import apexled.core.PwmCalibration
import apexled.core.Rgb
import apexled.core.WheelModel
import apexled.core.WheelProfile
import apexled.safety.FaultStyle
import apexled.safety.SafetyConfig
import apexled.safety.SafetyMode
import apexled.safety.SafetyStatus
import java.util.Locale

/** Aba Segurança: modos automáticos ligados à telemetria da roda. */
@Composable
fun SafetyScreen(graph: AppGraph) {
    val settings by graph.settings.collectAsState()
    val out by graph.safety.collectAsState()
    val src by graph.source.collectAsState()
    val telemetry by graph.telemetry.collectAsState()
    val cfg = settings.safety
    val profile = settings.activeProfile
    var editProfile by remember { mutableStateOf(false) }

    fun upd(f: (SafetyConfig) -> SafetyConfig) = graph.update { it.copy(safety = f(it.safety)) }
    fun updProfile(f: (WheelProfile) -> WheelProfile) = graph.update { s ->
        s.copy(profiles = s.profiles.map { if (it.id == s.activeProfileId) f(it) else it })
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {

        // ---- estado -----------------------------------------------------------------------
        SectionCard("Segurança automática", "A fita muda de cor conforme a margem que a roda ainda tem.") {
            SwitchRow("Ativar", cfg.enabled, { v -> upd { it.copy(enabled = v) } },
                hint = "Só assume a fita enquanto a roda estiver conectada (ou o simulador rodando).")
            SafetyMeter(profile.pwmStops, out?.usage)
            val o = out
            when {
                o == null && src == null -> Text("Conecte a roda (aba Início) para a segurança assumir a fita.", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                o == null -> Text("Segurança pausada.", style = MaterialTheme.typography.bodyMedium)
                o.status == SafetyStatus.NO_SIGNAL -> Text("SEM DADOS DA RODA — fita em estado de falha (âmbar).", style = MaterialTheme.typography.titleMedium, color = WarnAmber)
                else -> {
                    val c = when (o.level) {
                        apexled.safety.SafetyLevel.OK -> OkGreen
                        apexled.safety.SafetyLevel.ATTENTION, apexled.safety.SafetyLevel.WARNING -> WarnAmber
                        else -> BadRed
                    }
                    Text(String.format(Locale.US, "%s · margem %.0f%%", o.level.label, o.margin * 100), style = MaterialTheme.typography.titleLarge, color = c)
                    Text(
                        (o.limiting?.let { "Fator limitante: ${it.label}" } ?: "") + (if (o.flashing) " · PISCANDO: margem esgotada" else "") +
                            (if (o.braking) " · frenagem" else ""),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    telemetry?.let {
                        Text(String.format(Locale.US, "%.0f km/h · PWM %s · %.0f%% bateria", it.speedKmh,
                            it.pwm?.let { p -> String.format(Locale.US, "%.0f%%", p * 100) + if (it.pwmEstimated) " (est.)" else "" } ?: "—",
                            it.batteryPercent ?: 0f), style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }

        // ---- modo -------------------------------------------------------------------------
        SectionCard("Modo") {
            ChipRow(SafetyMode.entries, cfg.mode, { it.label }, { m -> upd { it.copy(mode = m) } })
            Text(
                when (cfg.mode) {
                    SafetyMode.PWM -> "Cor por faixa de PWM: verde até 60%, depois amarelo, laranja, vermelho e vermelho piscando acima de ${(profile.flashAbovePwm * 100).toInt()}%."
                    SafetyMode.SPEED -> "Cor pela velocidade em relação ao seu limite."
                    SafetyMode.COMBINED -> "Usa o PIOR entre PWM, velocidade e bateria baixa. Recomendado."
                    SafetyMode.BAR -> "A fita vira um barômetro: pixels acesos = % de PWM usado. ⚠ O SP621E não tem comando conhecido para pixels individuais: na fita real aparece como cor sólida; a barra completa só aparece na prévia."
                },
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Cores em degraus (em vez de gradiente)", cfg.stepped, { v -> upd { it.copy(stepped = v) } })
            if (cfg.mode == SafetyMode.SPEED || cfg.mode == SafetyMode.COMBINED) {
                val limit = cfg.speedLimitKmh ?: (profile.tiltbackSpeedKmh.takeIf { it > 0f } ?: profile.maxSpeedKmh)
                LabeledSlider("Limite de velocidade", limit, 10f..(profile.maxSpeedKmh * 1.2f).coerceAtLeast(30f),
                    { v -> upd { it.copy(speedLimitKmh = v) } }, String.format(Locale.US, "%.0f km/h", limit),
                    hint = "A cor chega ao vermelho piscando perto deste valor.")
            }
            if (cfg.mode == SafetyMode.COMBINED) SwitchRow("Considerar bateria baixa", cfg.batteryFactor, { v -> upd { it.copy(batteryFactor = v) } })
        }

        // ---- perfil -----------------------------------------------------------------------
        SectionCard("Perfil da roda", "Valores iniciais são palpites — ajuste para a sua roda.") {
            ChipRow(settings.profiles, profile, { it.name }, { p -> graph.update { it.copy(activeProfileId = p.id) } })
            SwitchRow("Detectar pelo modelo informado pela roda", settings.autoDetectProfile, { v -> graph.update { it.copy(autoDetectProfile = v) } },
                hint = "Só o protocolo Veteran/Nosfet informa o modelo.")
            BigButton(if (editProfile) "Fechar edição" else "Editar perfil", { editProfile = !editProfile }, Modifier.fillMaxWidth(), primary = false)
            if (editProfile) ProfileEditor(profile, ::updProfile, graph)
        }

        // ---- faixas -----------------------------------------------------------------------
        SectionCard("Faixas de cor (PWM)", "Fração de PWM em que cada cor é atingida.") {
            SafetyMeter(profile.pwmStops, null)
            profile.pwmStops.sortedBy { it.at }.forEachIndexed { i, stop ->
                StopRow(stop, onChange = { n -> updProfile { p -> p.copy(pwmStops = p.pwmStops.sortedBy { it.at }.toMutableList().also { it[i] = n }) } },
                    onDelete = if (profile.pwmStops.size > 2) ({ updProfile { p -> p.copy(pwmStops = p.pwmStops.sortedBy { it.at }.filterIndexed { j, _ -> j != i }) } }) else null)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("Adicionar faixa", { updProfile { p -> p.copy(pwmStops = p.pwmStops + ColorStop(0.95f, Rgb(255, 0, 0))) } }, Modifier.weight(1f), primary = false)
                BigButton("Restaurar padrão", { updProfile { p -> p.copy(pwmStops = DefaultStops.pwm, flashAbovePwm = 0.90f) } }, Modifier.weight(1f), primary = false)
            }
            LabeledSlider("Pisca rápido acima de", profile.flashAbovePwm, 0.5f..1f, { v -> updProfile { p -> p.copy(flashAbovePwm = v) } },
                String.format(Locale.US, "%.0f%%", profile.flashAbovePwm * 100), hint = "Margem esgotada: tiltback/cutout próximo.")
        }

        // ---- dinâmica ---------------------------------------------------------------------
        SectionCard("Suavização e robustez") {
            LabeledSlider("Descida do alerta (lenta)", cfg.releaseSeconds, 0f..8f, { v -> upd { it.copy(releaseSeconds = v) } },
                String.format(Locale.US, "%.1f s", cfg.releaseSeconds), hint = "O alerta SOBE na hora e relaxa devagar, para não cintilar.")
            LabeledSlider("Histerese", cfg.hysteresis, 0f..0.10f, { v -> upd { it.copy(hysteresis = v) } },
                String.format(Locale.US, "%.0f%%", cfg.hysteresis * 100), hint = "Folga para mudar de faixa e parar de piscar.")
            LabeledSlider("Brilho da segurança", cfg.brightness.toFloat(), 20f..255f, { v -> upd { it.copy(brightness = v.toInt()) } },
                "${cfg.brightness * 100 / 255} %")
            LabeledSlider("Pisca: período", cfg.flashPeriodMs.toFloat(), 200f..1000f, { v -> upd { it.copy(flashPeriodMs = v.toLong()) } },
                "${cfg.flashPeriodMs} ms", hint = "A fita é atualizada no máximo a 10 Hz (a cada 100 ms).")
            LabeledSlider("Tempo sem dados → falha", cfg.signalTimeoutMs.toFloat(), 500f..5000f, { v -> upd { it.copy(signalTimeoutMs = v.toLong()) } },
                String.format(Locale.US, "%.1f s", cfg.signalTimeoutMs / 1000f))
        }

        SectionCard("Falha de sinal", "O que a fita mostra se a roda parar de enviar dados.") {
            ChipRow(FaultStyle.entries, cfg.fault.style, { it.label }, { s -> upd { it.copy(fault = it.fault.copy(style = s)) } })
            var pick by remember { mutableStateOf(false) }
            Row(Modifier.fillMaxWidth().clickable { pick = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(cfg.fault.color.toColor()))
                Text("Cor da falha", style = MaterialTheme.typography.bodyLarge)
            }
            if (pick) ColorPickDialog(cfg.fault.color, { c -> upd { it.copy(fault = it.fault.copy(color = c)) }; pick = false }, { pick = false })
        }

        SectionCard("Extras") {
            SwitchRow("Pulso lento quando parado", cfg.restPulse.enabled, { v -> upd { it.copy(restPulse = it.restPulse.copy(enabled = v)) } })
            SwitchRow("Cor fixa em baixa velocidade", cfg.fixedColor.enabled, { v -> upd { it.copy(fixedColor = it.fixedColor.copy(enabled = v)) } },
                hint = "Só vale enquanto houver folga (uso abaixo de 60%).")
            if (cfg.fixedColor.enabled) {
                LabeledSlider("Abaixo de", cfg.fixedColor.belowKmh, 1f..20f, { v -> upd { it.copy(fixedColor = it.fixedColor.copy(belowKmh = v)) } },
                    String.format(Locale.US, "%.0f km/h", cfg.fixedColor.belowKmh))
                var pick by remember { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().clickable { pick = true }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(Modifier.size(36.dp).clip(CircleShape).background(cfg.fixedColor.color.toColor()))
                    Text("Cor fixa", style = MaterialTheme.typography.bodyLarge)
                }
                if (pick) ColorPickDialog(cfg.fixedColor.color, { c -> upd { it.copy(fixedColor = it.fixedColor.copy(color = c)) }; pick = false }, { pick = false })
            }
            SwitchRow("Flash de freio (regeneração forte)", cfg.brake.enabled, { v -> upd { it.copy(brake = it.brake.copy(enabled = v)) } },
                hint = "Detecta por desaceleração ou corrente negativa. Em Gotway/Veteran a corrente de bateria é estimada: prefira testar.")
        }

        graph.simulator?.let { sim -> if (src != null) SectionCard("Simulador", "Use para ver as cores sem a roda") { SimulatorPanel(sim) } }

        Text(
            "Este indicador NÃO substitui os alarmes nativos da roda nem a sua atenção.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun StopRow(stop: ColorStop, onChange: (ColorStop) -> Unit, onDelete: (() -> Unit)?) {
    var pick by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(stop.color.toColor()).clickable { pick = true })
        Column(Modifier.weight(1f)) {
            LabeledSlider("Em", stop.at, 0f..1f, { onChange(stop.copy(at = it)) }, String.format(Locale.US, "%.0f%%", stop.at * 100))
        }
        if (onDelete != null) BigButton("✕", onDelete, Modifier.size(56.dp), primary = false)
    }
    if (pick) ColorPickDialog(stop.color, { c -> onChange(stop.copy(color = c)); pick = false }, { pick = false })
}

@Composable
private fun ProfileEditor(profile: WheelProfile, update: ((WheelProfile) -> WheelProfile) -> Unit, graph: AppGraph) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Velocidade", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Máxima", profile.maxSpeedKmh, { v -> update { it.copy(maxSpeedKmh = v.coerceAtLeast(5f)) } }, Modifier.weight(1f), 0, "km/h")
            NumberField("Tiltback (0 = ?)", profile.tiltbackSpeedKmh, { v -> update { it.copy(tiltbackSpeedKmh = v.coerceAtLeast(0f)) } }, Modifier.weight(1f), 0, "km/h")
        }
        Text("Bateria (tensão do pack)", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Cheia", profile.fullVoltage, { v -> update { it.copy(fullVoltage = v) } }, Modifier.weight(1f), 1, "V")
            NumberField("Vazia", profile.emptyVoltage, { v -> update { it.copy(emptyVoltage = v) } }, Modifier.weight(1f), 1, "V")
        }
        NumberField("Nominal", profile.nominalVoltage, { v -> update { it.copy(nominalVoltage = v) } }, Modifier.fillMaxWidth(), 1, "V")
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Aviso abaixo de", profile.lowBatteryWarnPercent, { v -> update { it.copy(lowBatteryWarnPercent = v) } }, Modifier.weight(1f), 0, "%")
            NumberField("Crítico em", profile.lowBatteryCriticalPercent, { v -> update { it.copy(lowBatteryCriticalPercent = v) } }, Modifier.weight(1f), 0, "%")
        }
        Text("PWM estimado (só se a roda não informar)", style = MaterialTheme.typography.titleMedium)
        Text("Mesma ideia das opções do WheelLog: PWM = velocidade ÷ (velocidade de rotação ÷ tensão de rotação × tensão atual × fator). " +
            "Mude e reconecte a roda para valer. Dica: com a roda no limite do tiltback, ajuste até o PWM mostrado chegar perto de 100%.",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val cal = profile.pwmCalibration
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NumberField("Rotação", cal.rotationSpeedKmh, { v -> update { it.copy(pwmCalibration = cal.copy(rotationSpeedKmh = v.coerceAtLeast(1f))) } }, Modifier.weight(1f), 0, "km/h")
            NumberField("a", cal.rotationVoltage, { v -> update { it.copy(pwmCalibration = cal.copy(rotationVoltage = v.coerceAtLeast(1f))) } }, Modifier.weight(1f), 0, "V")
        }
        NumberField("Fator de calibração", cal.powerFactor, { v -> update { it.copy(pwmCalibration = cal.copy(powerFactor = v.coerceIn(0.1f, 2f))) } }, Modifier.fillMaxWidth(), 2)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (profile.model == WheelModel.CUSTOM) BigButton("Excluir perfil", {
                graph.update { s -> val rest = s.profiles.filter { it.id != profile.id }; s.copy(profiles = rest, activeProfileId = rest.first().id) }
            }, Modifier.weight(1f), primary = false)
            BigButton("Novo perfil", {
                graph.update { s ->
                    val n = s.profiles.count { it.model == WheelModel.CUSTOM } + 1
                    val copy = profile.copy(id = "custom-${System.currentTimeMillis()}", name = "Roda $n", model = WheelModel.CUSTOM)
                    s.copy(profiles = s.profiles + copy, activeProfileId = copy.id)
                }
            }, Modifier.weight(1f), primary = false)
        }
        if (profile.model != WheelModel.CUSTOM) BigButton("Restaurar valores iniciais", {
            val def = if (profile.model == WheelModel.NOSFET_APEX) WheelProfile.nosfetApex() else WheelProfile.begodeA2()
            update { def }
        }, Modifier.fillMaxWidth(), primary = false)
    }
}
