package apexled.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.ui.components.ColorWheel
import apexled.app.ui.components.LabeledSlider
import apexled.app.ui.components.PresetRow
import apexled.app.ui.components.SectionCard
import apexled.app.ui.components.SwitchRow
import apexled.core.Hsv
import apexled.core.LedCommand
import apexled.core.Rgb

/** Aba Cor: roda HSV + RGB + presets, brilho e liga/desliga (como no BanlanX). */
@Composable
fun ColorScreen(graph: AppGraph) {
    val state by graph.ledState.collectAsState()
    val locked by graph.safetyDriving.collectAsState()
    val led by graph.led.collectAsState()
    val hsv = state.color.toHsv()
    var hex by remember(state.color) { mutableStateOf(state.color.toHex()) }

    fun setColor(c: Rgb) = led.send(LedCommand.Solid(c, state.brightness))

    Column(Modifier.verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ManualLockBanner(graph, locked)
        SectionCard("Fita") {
            SwitchRow("Ligada", state.power, { led.send(LedCommand.Power(it)) }, enabled = !locked)
            LabeledSlider("Brilho", state.brightness.toFloat(), 0f..255f, { led.send(LedCommand.Brightness(it.toInt())) },
                "${(state.brightness * 100 / 255)} %", enabled = !locked)
        }
        SectionCard("Cor") {
            if (!locked) ColorWheel(Hsv(hsv.h, hsv.s, 1f), { setColor(Hsv(it.h, it.s, 1f).toRgb()) }, Modifier.padding(horizontal = 24.dp))
            PresetRow(state.color) { if (!locked) setColor(it) }
        }
        SectionCard("RGB") {
            LabeledSlider("Vermelho", state.color.r.toFloat(), 0f..255f, { if (!locked) setColor(state.color.copy(r = it.toInt())) }, "${state.color.r}")
            LabeledSlider("Verde", state.color.g.toFloat(), 0f..255f, { if (!locked) setColor(state.color.copy(g = it.toInt())) }, "${state.color.g}")
            LabeledSlider("Azul", state.color.b.toFloat(), 0f..255f, { if (!locked) setColor(state.color.copy(b = it.toInt())) }, "${state.color.b}")
            Row(Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    hex, { v -> hex = v.uppercase().take(7); Rgb.fromHex(v)?.let { if (!locked) setColor(it) } },
                    label = { Text("Hexadecimal") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = Rgb.fromHex(hex) == null,
                )
            }
        }
    }
}
