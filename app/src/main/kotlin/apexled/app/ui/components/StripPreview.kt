package apexled.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import apexled.core.LedFrame
import apexled.core.LedState
import apexled.core.Rgb
import apexled.led.EffectCategory
import apexled.led.Sp621eEffects
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.sin

/**
 * Cores de cada pixel da fita como o usuário a veria. Para efeitos dinâmicos é uma aproximação animada
 * (o controlador real roda o efeito sozinho; esta animação só dá uma ideia).
 */
fun previewPixels(state: LedState, pixels: Int, tMs: Long): List<Rgb> {
    if (!state.power) return List(pixels) { Rgb(20, 20, 20) }
    if (state.effectId == LedState.EFFECT_SOLID) {
        val bar = state.bar
        return if (bar != null) LedFrame(state.color, state.barBrightness, bar).render(pixels)
        else List(pixels) { state.color.scaled(state.brightness / 255f) }
    }
    val fx = Sp621eEffects.find(state.effectId)
    val palette = fx?.palette?.takeIf { it.isNotEmpty() } ?: listOf(Rgb.WHITE)
    val speed = state.effectSpeed / 5f
    val phase = tMs / 1000f * speed
    val k = state.brightness / 255f
    val lengthPx = (pixels * state.effectLength / 150f).coerceAtLeast(2f)
    return List(pixels) { i ->
        val pos = (i / lengthPx + phase * 0.6f)
        val c: Rgb = when (fx?.category) {
            EffectCategory.COMET, EffectCategory.METEOR, EffectCategory.SPIN -> {
                val head = (phase * pixels * 0.5f) % pixels
                val d = ((head - i) + pixels) % pixels
                palette[0].scaled((1f - d / lengthPx).coerceIn(0f, 1f))
            }
            EffectCategory.BREATH -> palette[0].scaled(0.5f + 0.5f * sin(phase * 2f * PI.toFloat()))
            EffectCategory.STARS, EffectCategory.BG_STARS -> {
                val h = ((i * 7919 + floor(phase * 3f).toInt() * 104729) and 0xFF) / 255f
                if (h > 0.85f) palette[0] else (palette.getOrNull(1) ?: Rgb(10, 10, 10)).scaled(0.25f)
            }
            else -> cycle(palette, pos)
        }
        c.scaled(k)
    }
}

private fun cycle(p: List<Rgb>, pos: Float): Rgb {
    if (p.size == 1) return p[0]
    val x = ((pos % 1f) + 1f) % 1f * p.size
    val i = floor(x).toInt() % p.size
    return Rgb.lerp(p[i], p[(i + 1) % p.size], x - floor(x))
}

/** Faixa de LED sempre visível no topo. */
@Composable
fun StripPreview(state: LedState, modifier: Modifier = Modifier, caption: String? = null, flashingHint: Boolean = false) {
    var tick by remember { mutableLongStateOf(0L) }
    val animated = state.power && state.effectId != LedState.EFFECT_SOLID
    LaunchedEffect(animated) {
        while (animated) { tick = System.currentTimeMillis(); delay(50) }
    }
    val shown = minOf(state.pixelCount, 48).coerceAtLeast(8)
    val px = previewPixels(state, shown, tick)
    Column(modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Canvas(
            Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(12.dp)).background(Color(0xFF05070A)),
        ) {
            val gap = 3.dp.toPx()
            val w = (size.width - gap * (shown + 1)) / shown
            px.forEachIndexed { i, c ->
                val x = gap + i * (w + gap)
                // brilho suave ao redor do pixel aceso
                if (c.r + c.g + c.b > 60) drawRoundRect(c.toColor().copy(alpha = 0.25f), Offset(x - 2f, 2f), Size(w + 4f, size.height - 4f), CornerRadius(8f))
                drawRoundRect(c.toColor(), Offset(x, 6.dp.toPx()), Size(w, size.height - 12.dp.toPx()), CornerRadius(6f))
            }
        }
        if (caption != null) Text(caption, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 4.dp))
    }
}
