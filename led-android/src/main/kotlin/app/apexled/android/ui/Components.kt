package app.apexled.android.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import app.apexled.android.R
import app.apexled.pattern.LedFrame
import app.apexled.pattern.Rgb
import kotlin.math.roundToInt

internal fun Rgb.toColor() = Color(r, g, b)

/** The colour as it looks at this frame's brightness, on a dark background. */
internal fun LedFrame.toColor() = Color(
    (color.r * brightness).roundToInt(),
    (color.g * brightness).roundToInt(),
    (color.b * brightness).roundToInt(),
)

@Composable
internal fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 20.dp, bottom = 4.dp),
    )
}

@Composable
internal fun Hint(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 4.dp),
    )
}

/** A slider with its label on the left and the current value, as text, on the right. */
@Composable
internal fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    steps: Int = 0,
) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(valueText, style = MaterialTheme.typography.bodyMedium)
        }
        Slider(
            value = value.coerceIn(range.start, range.endInclusive),
            onValueChange = onChange,
            valueRange = range,
            steps = steps,
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}

/** One-of-many choice shown as a row of chips. */
@Composable
internal fun <T> ChoiceRow(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((value, label) in options) {
            FilterChip(selected = value == selected, onClick = { onSelect(value) }, label = { Text(label) })
        }
    }
}

private val presets = listOf(
    Rgb.RED, Rgb(255, 128, 0), Rgb.YELLOW, Rgb.GREEN, Rgb.CYAN, Rgb.BLUE, Rgb(160, 0, 255), Rgb(255, 0, 160), Rgb.WHITE,
)

/** Colour picker: a swatch that opens presets and R, G, B sliders. */
@Composable
internal fun ColorEditor(label: String, color: Rgb, onChange: (Rgb) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Swatch(color.toColor(), 32)
            Spacer(Modifier.width(12.dp))
            Text(label, modifier = Modifier.weight(1f))
            Text(color.toHex(), style = MaterialTheme.typography.bodySmall)
        }
        if (expanded) {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                for (preset in presets) {
                    Box(Modifier.clickable { onChange(preset) }) { Swatch(preset.toColor(), 36) }
                }
            }
            LabeledSlider(stringResource(R.string.led_red), color.r.toString(), color.r.toFloat(), 0f..255f, {
                onChange(color.copy(r = it.roundToInt()))
            })
            LabeledSlider(stringResource(R.string.led_green), color.g.toString(), color.g.toFloat(), 0f..255f, {
                onChange(color.copy(g = it.roundToInt()))
            })
            LabeledSlider(stringResource(R.string.led_blue), color.b.toString(), color.b.toFloat(), 0f..255f, {
                onChange(color.copy(b = it.roundToInt()))
            })
        }
    }
}

@Composable
internal fun Swatch(color: Color, sizeDp: Int) {
    Box(
        Modifier
            .size(sizeDp.dp)
            .clip(CircleShape)
            .background(color)
            .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
    )
}

/**
 * The colour band of a pattern from 0 % (left) to 100 % (right), with a marker at the
 * current input position.
 */
@Composable
internal fun PreviewStrip(colors: List<Rgb>, position: Double, description: String, modifier: Modifier = Modifier) {
    val markerColor = MaterialTheme.colorScheme.onSurface
    val outline = MaterialTheme.colorScheme.outline
    Canvas(
        modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .border(1.dp, outline, RoundedCornerShape(8.dp))
            .semantics { contentDescription = description },
    ) {
        if (colors.isEmpty()) return@Canvas
        val band = size.width / colors.size
        colors.forEachIndexed { index, rgb ->
            // One extra pixel of width hides the seams between bands.
            drawRect(rgb.toColor(), topLeft = Offset(index * band, 0f), size = Size(band + 1f, size.height))
        }
        val x = (position.coerceIn(0.0, 1.0) * size.width).toFloat()
        drawRect(Color.Black, topLeft = Offset(x - 3f, 0f), size = Size(6f, size.height))
        drawRect(markerColor, topLeft = Offset(x - 1.5f, 0f), size = Size(3f, size.height))
    }
}

/** What each board is showing right now, one dot per board. */
@Composable
internal fun LiveBoards(frames: List<LedFrame>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(Color.Black)
            .padding(8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (frames.isEmpty()) {
            Text(stringResource(R.string.led_live_none), color = Color.White, style = MaterialTheme.typography.bodySmall)
        }
        for (frame in frames) {
            Box(
                Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(frame.toColor())
                    .border(1.dp, Color.DarkGray, CircleShape),
            )
        }
    }
}
