package app.apexled.android.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.apexled.android.R
import app.apexled.android.ble.FoundDevice
import app.apexled.device.BoardInfo
import app.apexled.device.BoardManager
import app.apexled.device.BoardState
import app.apexled.device.protocol.ProtocolRegistry
import java.util.UUID

/**
 * The registered boards: state, name, order, add and remove.
 *
 * @param onAddSimulated when given, shows a button that adds an in-memory board (bench use).
 */
@Composable
fun BoardsScreen(
    viewModel: LedViewModel,
    modifier: Modifier = Modifier,
    protocols: ProtocolRegistry = ProtocolRegistry(),
    onAddSimulated: (() -> Unit)? = null,
) {
    val boards by viewModel.boards.collectAsStateWithLifecycle()
    var renaming by remember { mutableStateOf<BoardInfo?>(null) }
    var removing by remember { mutableStateOf<BoardInfo?>(null) }
    var scanOpen by remember { mutableStateOf(false) }
    var adding by remember { mutableStateOf<FoundDevice?>(null) }

    Column(
        modifier
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        SectionTitle(stringResource(R.string.led_boards_title, boards.size, BoardManager.MAX_BOARDS))
        if (boards.isEmpty()) Hint(stringResource(R.string.led_boards_empty))

        boards.forEachIndexed { index, row ->
            Card(Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(row.info.name, style = MaterialTheme.typography.titleMedium)
                    Text(row.info.address, style = MaterialTheme.typography.bodySmall)
                    Text(
                        protocols[row.info.protocolId]?.displayName ?: row.info.protocolId,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(stateText(row.state), style = MaterialTheme.typography.bodyMedium)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { viewModel.moveBoard(row.info.id, -1) }, enabled = index > 0) {
                            Text(stringResource(R.string.led_move_up))
                        }
                        TextButton(onClick = { viewModel.moveBoard(row.info.id, 1) }, enabled = index < boards.lastIndex) {
                            Text(stringResource(R.string.led_move_down))
                        }
                        TextButton(onClick = { renaming = row.info }) { Text(stringResource(R.string.led_rename)) }
                        TextButton(onClick = { removing = row.info }) { Text(stringResource(R.string.led_remove)) }
                    }
                }
            }
        }

        val full = boards.size >= BoardManager.MAX_BOARDS
        if (full) Hint(stringResource(R.string.led_boards_full))
        Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (viewModel.canScan) {
                Button(
                    onClick = {
                        scanOpen = true
                        viewModel.startScan()
                    },
                    enabled = !full,
                ) { Text(stringResource(R.string.led_scan)) }
            }
            if (onAddSimulated != null) {
                OutlinedButton(onClick = onAddSimulated, enabled = !full) { Text(stringResource(R.string.led_add_simulated)) }
            }
        }
    }

    if (scanOpen) {
        ScanDialog(
            viewModel = viewModel,
            registered = boards.map { it.info.address.uppercase() }.toSet(),
            onPick = { device ->
                viewModel.stopScan()
                scanOpen = false
                adding = device
            },
            onClose = {
                viewModel.stopScan()
                scanOpen = false
            },
        )
    }

    adding?.let { device ->
        AddBoardDialog(
            device = device,
            defaultName = device.name ?: stringResource(R.string.led_board_default_name, boards.size + 1),
            protocols = protocols,
            onConfirm = { name, protocolId ->
                viewModel.addBoard(BoardInfo(UUID.randomUUID().toString(), name, device.address, protocolId))
                adding = null
            },
            onCancel = { adding = null },
        )
    }

    renaming?.let { info ->
        var name by remember(info.id) { mutableStateOf(info.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text(stringResource(R.string.led_rename)) },
            text = {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.led_board_name)) },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.renameBoard(info.id, name)
                        renaming = null
                    },
                    enabled = name.isNotBlank(),
                ) { Text(stringResource(R.string.led_save)) }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.led_cancel)) } },
        )
    }

    removing?.let { info ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text(stringResource(R.string.led_remove_title, info.name)) },
            text = { Text(stringResource(R.string.led_remove_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.removeBoard(info.id)
                        removing = null
                    },
                ) { Text(stringResource(R.string.led_remove)) }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.led_cancel)) } },
        )
    }
}

private const val MAX_NAME_LENGTH = 30

@Composable
private fun stateText(state: BoardState): String = when (state) {
    BoardState.Disconnected -> stringResource(R.string.led_state_disconnected)
    BoardState.Connecting -> stringResource(R.string.led_state_connecting)
    BoardState.Ready -> stringResource(R.string.led_state_ready)
    is BoardState.Reconnecting ->
        stringResource(R.string.led_state_reconnecting, state.attempt, (state.retryInMs + 999) / 1000)
}

@Composable
private fun ScanDialog(
    viewModel: LedViewModel,
    registered: Set<String>,
    onPick: (FoundDevice) -> Unit,
    onClose: () -> Unit,
) {
    val found by viewModel.found.collectAsStateWithLifecycle()
    val scanning by viewModel.scanning.collectAsStateWithLifecycle()
    val candidates = found.filterNot { it.address.uppercase() in registered }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(stringResource(if (scanning) R.string.led_scanning else R.string.led_scan_stopped)) },
        text = {
            Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                if (candidates.isEmpty()) Text(stringResource(R.string.led_scan_empty))
                for (device in candidates) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(device) }
                            .padding(vertical = 8.dp),
                    ) {
                        Text(device.name ?: stringResource(R.string.led_unnamed), style = MaterialTheme.typography.bodyLarge)
                        Text(
                            stringResource(R.string.led_scan_row, device.address, device.rssi),
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (device.looksLikeLedBoard) {
                            Text(
                                stringResource(R.string.led_scan_likely),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text(stringResource(R.string.led_close)) } },
    )
}

@Composable
private fun AddBoardDialog(
    device: FoundDevice,
    defaultName: String,
    protocols: ProtocolRegistry,
    onConfirm: (name: String, protocolId: String) -> Unit,
    onCancel: () -> Unit,
) {
    var name by remember(device.address) { mutableStateOf(defaultName.take(MAX_NAME_LENGTH)) }
    var protocolId by remember(device.address) { mutableStateOf(protocols.all.first().id) }
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.led_add_board)) },
        text = {
            Column {
                Text(device.address, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(MAX_NAME_LENGTH) },
                    singleLine = true,
                    label = { Text(stringResource(R.string.led_board_name)) },
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                Text(stringResource(R.string.led_protocol), style = MaterialTheme.typography.bodyMedium)
                ChoiceRow(
                    options = protocols.all.map { it.id to it.displayName },
                    selected = protocolId,
                    onSelect = { protocolId = it },
                )
                Hint(stringResource(R.string.led_protocol_hint))
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name.trim(), protocolId) }, enabled = name.isNotBlank()) {
                Text(stringResource(R.string.led_add))
            }
        },
        dismissButton = { TextButton(onClick = onCancel) { Text(stringResource(R.string.led_cancel)) } },
    )
}
