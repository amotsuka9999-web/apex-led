package apexled.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import apexled.core.ColorStop

/** Barra com a escala de cores do perfil e um marcador na posição de uso atual (0..1). */
@Composable
fun SafetyMeter(stops: List<ColorStop>, usage: Float?, modifier: Modifier = Modifier) {
    Canvas(modifier.fillMaxWidth().height(28.dp)) {
        val sorted = stops.sortedBy { it.at }
        val colors = if (sorted.size >= 2) sorted.map { it.color.toColor() } else listOf(Color.Green, Color.Red)
        val fractions = if (sorted.size >= 2) sorted.map { it.at } else listOf(0f, 1f)
        drawRoundRect(
            Brush.horizontalGradient(colorStops = fractions.zip(colors).toTypedArray(), startX = 0f, endX = size.width),
            Offset(0f, 6.dp.toPx()), Size(size.width, 16.dp.toPx()), CornerRadius(8.dp.toPx()),
        )
        if (usage != null) {
            val x = usage.coerceIn(0f, 1f) * size.width
            drawRoundRect(Color.White, Offset(x - 3.dp.toPx(), 0f), Size(6.dp.toPx(), size.height), CornerRadius(3.dp.toPx()))
            drawRoundRect(Color.Black.copy(alpha = 0.6f), Offset(x - 4.5f.dp.toPx(), -0f), Size(1.dp.toPx(), size.height))
        }
    }
}
