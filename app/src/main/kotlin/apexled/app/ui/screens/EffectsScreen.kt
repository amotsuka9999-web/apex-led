package apexled.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.ui.components.ChipRow
import apexled.app.ui.components.LabeledSlider
import apexled.app.ui.components.SectionCard
import apexled.app.ui.components.toColor
import apexled.core.LedCommand
import apexled.core.LedState
import apexled.led.EffectCategory
import apexled.led.LedEffect
import apexled.led.Sp621eEffects

/** Aba Efeitos: lista dos efeitos dinâmicos do controlador + velocidade, comprimento e brilho. */
@Composable
fun EffectsScreen(graph: AppGraph) {
    val state by graph.ledState.collectAsState()
    val locked by graph.safetyDriving.collectAsState()
    val led by graph.led.collectAsState()
    var category by remember { mutableStateOf<EffectCategory?>(null) }
    val all = Sp621eEffects.all
    val list = all.filter { category == null || it.category == category }
    val cats: List<EffectCategory?> = listOf(null) + EffectCategory.entries

    LazyColumn(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 16.dp)) {
        item { ManualLockBanner(graph, locked) }
        item {
            SectionCard("Ajustes do efeito") {
                LabeledSlider("Velocidade", state.effectSpeed.toFloat(), 1f..10f, { led.send(LedCommand.EffectSpeed(it.toInt())) }, "${state.effectSpeed}", steps = 8, enabled = !locked)
                LabeledSlider("Comprimento", state.effectLength.toFloat(), 1f..150f, { led.send(LedCommand.EffectLength(it.toInt())) }, "${state.effectLength}", enabled = !locked)
                LabeledSlider("Brilho", state.brightness.toFloat(), 0f..255f, { led.send(LedCommand.Brightness(it.toInt())) }, "${state.brightness * 100 / 255} %", enabled = !locked)
            }
        }
        item { ChipRow(cats, category, { it?.label ?: "Todos" }, { category = it }) }
        items(list, key = { it.id }) { fx -> EffectRow(fx, selected = fx.id == state.effectId, enabled = !locked) { led.send(LedCommand.Effect(fx.id)) } }
    }
}

@Composable
private fun EffectRow(fx: LedEffect, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Row(
        Modifier.fillMaxWidth().heightIn(min = 60.dp).clip(RoundedCornerShape(16.dp)).background(bg)
            .clickable(enabled = enabled, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Canvas(Modifier.size(width = 72.dp, height = 22.dp).clip(RoundedCornerShape(8.dp))) {
            val cols = when {
                fx.id == LedState.EFFECT_SOLID -> listOf(Color.White, Color.White)
                fx.palette.size >= 2 -> fx.palette.map { it.toColor() }
                fx.palette.size == 1 -> listOf(Color.Black, fx.palette[0].toColor())
                else -> listOf(Color.Gray, Color.Gray)
            }
            drawRect(Brush.horizontalGradient(cols, 0f, size.width))
        }
        Column(Modifier.weight(1f)) {
            Text(fx.name, style = MaterialTheme.typography.titleMedium)
            Text(fx.category.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (selected) Text("●", color = MaterialTheme.colorScheme.primary)
    }
}
