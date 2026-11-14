package app.apexled.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.apexled.android.R

/** An extra tab supplied by the host app. */
class LedTab(val title: String, val content: @Composable () -> Unit)

/**
 * All LED screens behind one tab bar. The host app decides where this lives
 * (a settings entry in the WheelLog fork, the whole window in the bench app).
 */
@Composable
fun LedApp(
    viewModel: LedViewModel,
    modifier: Modifier = Modifier,
    extraTabs: List<LedTab> = emptyList(),
    onAddSimulatedBoard: (() -> Unit)? = null,
) {
    val tabs = listOf(
        LedTab(stringResource(R.string.led_tab_patterns)) { PatternScreen(viewModel) },
        LedTab(stringResource(R.string.led_tab_boards)) { BoardsScreen(viewModel, onAddSimulated = onAddSimulatedBoard) },
        LedTab(stringResource(R.string.led_tab_manual)) { ManualScreen(viewModel) },
        LedTab(stringResource(R.string.led_tab_alert)) { SafetyScreen(viewModel) },
    ) + extraTabs

    var selected by rememberSaveable { mutableIntStateOf(0) }
    val index = selected.coerceIn(0, tabs.lastIndex)
    val critical by viewModel.critical.collectAsStateWithLifecycle()
    val notice by viewModel.notice.collectAsStateWithLifecycle()
    val saveError by viewModel.saveError.collectAsStateWithLifecycle()

    Surface(modifier.fillMaxSize()) {
        Column {
            if (critical.active) {
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.led_banner_critical),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            notice?.let { current ->
                Surface(color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        Text(noticeText(current), color = MaterialTheme.colorScheme.onSecondaryContainer)
                        TextButton(onClick = viewModel::dismissNotice) { Text(stringResource(R.string.led_close)) }
                    }
                }
            }
            saveError?.let { detail ->
                Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        stringResource(R.string.led_save_failed, detail),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            ScrollableTabRow(selectedTabIndex = index) {
                tabs.forEachIndexed { tabIndex, tab ->
                    Tab(selected = tabIndex == index, onClick = { selected = tabIndex }, text = { Text(tab.title) })
                }
            }
            tabs[index].content()
        }
    }
}

@Composable
private fun noticeText(notice: LedNotice): String = when (notice) {
    LedNotice.BoardLimitReached -> stringResource(R.string.led_boards_full)
    LedNotice.BoardAlreadyAdded -> stringResource(R.string.led_notice_duplicate)
    is LedNotice.UnknownProtocol -> stringResource(R.string.led_notice_protocol, notice.protocolId)
    is LedNotice.InvalidSetting -> stringResource(R.string.led_notice_invalid, notice.detail)
    is LedNotice.ScanFailed -> stringResource(R.string.led_notice_scan, notice.detail)
    is LedNotice.NotSupported -> stringResource(R.string.led_notice_unsupported, notice.boardNames.joinToString(", "))
}
