package apexled.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import apexled.app.AppGraph
import apexled.app.data.LedSide
import apexled.app.data.StripConfig
import apexled.app.data.StripShow
import apexled.app.ui.BadRed
import apexled.app.ui.OkGreen
import apexled.app.ui.WarnAmber
import apexled.core.LedController
import apexled.core.LedLinkState
import apexled.core.LedState
import apexled.core.Rgb
import apexled.led.FanoutLedController
import apexled.led.Sp621eEffects

/**
 * Lista das fitas do grupo. Cada linha mostra a cor ao vivo, a posição e o que a fita faz; um toque abre a
 * configuração dela ali mesmo.
 */
@Composable
fun StripsCard(graph: AppGraph, hasPerm: Boolean, onAdd: () -> Unit) {
    val settings by graph.settings.collectAsState()
    val led by graph.led.collectAsState()
    val extras by graph.extraLeds.collectAsState()
    var open by remember { mutableStateOf<String?>(null) }
    val primaryKey = "principal"

    val group = led as? FanoutLedController
    val rows = buildList {
        add(Triple(primaryKey, settings.ledName ?: "Fita principal", group?.primary ?: led))
        extras.forEach { e -> add(Triple(e.address, e.name ?: e.address.takeLast(5), e.controller as LedController)) }
    }

    SectionCard("Fitas", "${rows.size} de ${FanoutLedController.MAX_DEVICES} · toque numa fita para configurar") {
        rows.forEach { (key, name, ctrl) ->
            val cfg = if (key == primaryKey) settings.ledStrip else settings.extraLeds.firstOrNull { it.address == key }?.strip ?: StripConfig()
            val link by ctrl.link.collectAsState()
            val st by ctrl.state.collectAsState()
            val address = if (key == primaryKey) null else key
            StripRow(name, cfg, st, if (settings.ledSimulated) LedLinkState.CONNECTED else link, open == key) { open = if (open == key) null else key }
            AnimatedVisibility(open == key) {
                StripEditor(cfg, { f -> graph.updateStrip(address, f) },
                    onRemove = address?.let { a -> { graph.removeExtraLed(a); open = null } })
            }
        }
        if (settings.ledAddress != null && rows.size < FanoutLedController.MAX_DEVICES) {
            BigButton("+ Adicionar fita", onAdd, Modifier.fillMaxWidth(), primary = false, enabled = hasPerm)
        }
        if (extras.size >= 6) Text("Muitos celulares só mantêm ~7 conexões Bluetooth ao mesmo tempo (a roda conta uma).",
            style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StripRow(name: String, cfg: StripConfig, st: LedState, link: LedLinkState, expanded: Boolean, onClick: () -> Unit) {
    val dot = when (link) {
        LedLinkState.CONNECTED -> OkGreen
        LedLinkState.DISCONNECTED -> BadRed
        else -> WarnAmber
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
            .background(if (expanded) MaterialTheme.colorScheme.surfaceContainerHighest else Color.Transparent)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Swatch(st, 40)
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(summary(cfg), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Box(Modifier.size(10.dp).clip(CircleShape).background(dot))
        Text(if (expanded) "▴" else "▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun summary(c: StripConfig): String = buildList {
    add(c.side.label)
    add(when (c.show) {
        StripShow.EFFECT -> Sp621eEffects.all.firstOrNull { it.id == c.effectId }?.name ?: "Efeito"
        else -> c.show.label
    })
    if (c.brakeLight) add("freio")
}.joinToString(" · ")

/** Bolinha com a cor ao vivo da fita (gradiente da paleta quando é efeito). */
@Composable
private fun Swatch(st: LedState, size: Int) {
    val mod = Modifier.size(size.dp).clip(CircleShape)
    val fx = Sp621eEffects.all.firstOrNull { it.id == st.effectId }
    when {
        !st.power -> Box(mod.background(MaterialTheme.colorScheme.surfaceVariant))
        st.effectId != LedState.EFFECT_SOLID && fx != null && fx.palette.size > 1 ->
            Box(mod.background(Brush.sweepGradient(fx.palette.map { it.toColor() } + fx.palette.first().toColor())))
        else -> Box(mod.background(st.color.toColor()).border(1.dp, Color.White.copy(alpha = 0.15f), CircleShape))
    }
}

private val QUICK = listOf(
    Rgb(255, 0, 0), Rgb(255, 120, 0), Rgb(255, 210, 0), Rgb(0, 220, 60),
    Rgb(0, 210, 255), Rgb(0, 60, 255), Rgb(170, 0, 255), Rgb(255, 255, 255),
)

@Composable
private fun StripEditor(cfg: StripConfig, update: ((StripConfig) -> StripConfig) -> Unit, onRemove: (() -> Unit)?) {
    var pickColor by remember { mutableStateOf(false) }
    var pickEffect by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(start = 10.dp, end = 10.dp, bottom = 8.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Label("Onde está")
        ChipRow(LedSide.entries, cfg.side, { it.label }, { v -> update { it.copy(side = v) } })
        Label("O que mostra")
        ChipRow(StripShow.entries, cfg.show, { it.label }, { v -> update { it.copy(show = v) } })
        Text(cfg.show.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        when (cfg.show) {
            StripShow.COLOR -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                QUICK.forEach { c ->
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(c.toColor())
                            .border(2.dp, if (c == cfg.color) MaterialTheme.colorScheme.onSurface else Color.Transparent, CircleShape)
                            .clickable { update { it.copy(color = c) } },
                    )
                }
                Box(Modifier.size(30.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.outline, CircleShape).clickable { pickColor = true },
                    contentAlignment = Alignment.Center) { Text("+") }
            }
            StripShow.EFFECT -> {
                val fx = Sp621eEffects.all.firstOrNull { it.id == cfg.effectId }
                BigButton(fx?.name ?: "Escolher efeito", { pickEffect = true }, Modifier.fillMaxWidth(), primary = false)
                LabeledSlider("Velocidade", cfg.effectSpeed.toFloat(), 1f..10f, { v -> update { it.copy(effectSpeed = v.toInt()) } }, "${cfg.effectSpeed}")
            }
            else -> Unit
        }

        SwitchRow("Luz de freio", cfg.brakeLight, { v -> update { it.copy(brakeLight = v) } },
            hint = "Vermelho forte ao frear, por cima do que estiver mostrando.")
        if (cfg.side == LedSide.LEFT || cfg.side == LedSide.RIGHT || cfg.side == LedSide.BOTH) {
            SwitchRow("Pisca nas setas", cfg.turnSignals, { v -> update { it.copy(turnSignals = v) } })
        }
        if (onRemove != null) TextButton(onRemove) { Text("Remover esta fita", color = BadRed) }
    }
    if (pickColor) ColorPickDialog(cfg.color, { c -> update { it.copy(color = c) }; pickColor = false }, { pickColor = false })
    if (pickEffect) EffectPickDialog(cfg.effectId, { id -> update { it.copy(effectId = id) }; pickEffect = false }, { pickEffect = false })
}

@Composable
private fun Label(t: String) = Text(t, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun EffectPickDialog(selected: Int, onPick: (Int) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Efeito da fita") },
        text = {
            LazyColumn(Modifier.heightIn(max = 420.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(Sp621eEffects.all) { fx ->
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (fx.id == selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
                            .clickable { onPick(fx.id) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Box(Modifier.size(22.dp).clip(CircleShape).background(
                            if (fx.palette.size > 1) Brush.sweepGradient(fx.palette.map { it.toColor() } + fx.palette.first().toColor())
                            else Brush.linearGradient(listOf((fx.palette.firstOrNull() ?: Rgb.WHITE).toColor(), (fx.palette.firstOrNull() ?: Rgb.WHITE).toColor())),
                        ))
                        Text(fx.name, style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        },
        confirmButton = { TextButton(onDismiss) { Text("Fechar") } },
    )
}
