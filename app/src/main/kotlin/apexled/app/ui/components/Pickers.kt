package apexled.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import apexled.core.Rgb

/** Cores prontas usadas na aba Cor e nos seletores. */
val PresetColors: List<Rgb> = listOf(
    Rgb(255, 0, 0), Rgb(255, 110, 0), Rgb(255, 210, 0), Rgb(160, 220, 0), Rgb(0, 220, 60), Rgb(0, 255, 200),
    Rgb(0, 200, 255), Rgb(0, 120, 255), Rgb(0, 0, 255), Rgb(130, 0, 255), Rgb(255, 0, 200), Rgb(255, 255, 255),
)

@Composable
fun Swatch(color: Rgb, selected: Boolean = false, size: Int = 44, onClick: () -> Unit) {
    Box(
        Modifier.size(size.dp).clip(CircleShape).background(color.toColor())
            .border(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline, CircleShape)
            .clickable(onClick = onClick),
    )
}

@Composable
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
fun PresetRow(selected: Rgb?, onPick: (Rgb) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PresetColors.forEach { c -> Swatch(c, selected == c) { onPick(c) } }
    }
}

@Composable
fun ColorPickDialog(initial: Rgb, onPick: (Rgb) -> Unit, onDismiss: () -> Unit) {
    var hex by remember { mutableStateOf(initial.toHex()) }
    val parsed = Rgb.fromHex(hex)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Escolher cor") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                PresetRow(parsed) { hex = it.toHex() }
                OutlinedTextField(hex, { hex = it.uppercase().take(7) }, label = { Text("Hexadecimal (RRGGBB)") }, singleLine = true,
                    isError = parsed == null)
                Box(Modifier.size(width = 120.dp, height = 28.dp).clip(RoundedCornerShape(8.dp)).background(parsed?.toColor() ?: Color.Gray))
            }
        },
        confirmButton = { TextButton({ parsed?.let(onPick) }, enabled = parsed != null) { Text("Usar") } },
        dismissButton = { TextButton(onDismiss) { Text("Cancelar") } },
    )
}
