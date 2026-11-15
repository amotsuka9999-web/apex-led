package apexled.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import apexled.core.Hsv
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.min

/**
 * Roda de cores (matiz pelo ângulo, saturação pelo raio). O brilho é um controle separado, como no BanlanX.
 * [hsv].v é ignorado na roda (sempre exibida a 100%).
 */
@Composable
fun ColorWheel(hsv: Hsv, onChange: (Hsv) -> Unit, modifier: Modifier = Modifier) {
    fun pick(o: Offset, w: Float, h: Float) {
        val cx = w / 2f; val cy = h / 2f
        val r = min(cx, cy)
        val dx = o.x - cx; val dy = o.y - cy
        val dist = hypot(dx, dy)
        var angle = Math.toDegrees(atan2(dy, dx).toDouble()).toFloat()
        if (angle < 0) angle += 360f
        onChange(Hsv(angle, (dist / r).coerceIn(0f, 1f), hsv.v))
    }
    Canvas(
        modifier.fillMaxWidth().aspectRatio(1f)
            .pointerInput(hsv.v) { detectTapGestures { pick(it, size.width.toFloat(), size.height.toFloat()) } }
            .pointerInput(hsv.v) { detectDragGestures(onDragStart = { pick(it, size.width.toFloat(), size.height.toFloat()) }) { c, _ ->
                pick(c.position, size.width.toFloat(), size.height.toFloat())
            } },
    ) {
        val r = min(size.width, size.height) / 2f
        val center = Offset(size.width / 2f, size.height / 2f)
        val hues = (0..360 step 30).map { Color(Hsv(it.toFloat(), 1f, 1f).toRgb().toArgbInt()) }
        drawCircle(Brush.sweepGradient(hues, center), r, center)
        drawCircle(Brush.radialGradient(listOf(Color.White, Color.Transparent), center, r), r, center)
        // marcador
        val a = Math.toRadians(hsv.h.toDouble())
        val mp = Offset(center.x + (Math.cos(a) * hsv.s * r).toFloat(), center.y + (Math.sin(a) * hsv.s * r).toFloat())
        drawCircle(Color.White, 16.dp.toPx(), mp, style = Stroke(3.dp.toPx()))
        drawCircle(Color.Black.copy(alpha = 0.5f), 18.dp.toPx(), mp, style = Stroke(1.dp.toPx()))
    }
}

