package app.apexled.android.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import app.apexled.android.ble.BoardScanner
import app.apexled.android.ble.FoundDevice
import app.apexled.core.CriticalState
import app.apexled.core.LedController
import app.apexled.core.LedSettings
import app.apexled.device.BoardCommand
import app.apexled.device.BoardInfo
import app.apexled.device.BoardManager
import app.apexled.device.BoardState
import app.apexled.pattern.LedFrame
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A registered board with its live link state. */
data class BoardRow(val info: BoardInfo, val state: BoardState)

/** What the user should be told about the last action that did not go through. */
sealed interface LedNotice {
    data object BoardLimitReached : LedNotice
    data object BoardAlreadyAdded : LedNotice
    data class UnknownProtocol(val protocolId: String) : LedNotice
    data class InvalidSetting(val detail: String) : LedNotice
    data class ScanFailed(val detail: String) : LedNotice
    data class NotSupported(val boardNames: List<String>) : LedNotice
}

/** Screen state for the LED screens. All logic lives in [LedController]; this only adapts it to the UI. */
@OptIn(ExperimentalCoroutinesApi::class)
class LedViewModel(
    private val controller: LedController,
    private val manager: BoardManager,
    private val scanner: BoardScanner?,
) : ViewModel() {

    val settings: StateFlow<LedSettings> = controller.settings
    val critical: StateFlow<CriticalState> = controller.critical
    val frames: StateFlow<List<LedFrame>> = controller.frames
    val position: StateFlow<Double> = controller.position
    val saveError: StateFlow<String?> = controller.saveError

    val boards: StateFlow<List<BoardRow>> = manager.boards
        .flatMapLatest { entries ->
            if (entries.isEmpty()) {
                flowOf(emptyList())
            } else {
                combine(entries.map { entry -> entry.connection.state.map { BoardRow(entry.info, it) } }) { it.toList() }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _notice = MutableStateFlow<LedNotice?>(null)
    val notice: StateFlow<LedNotice?> = _notice.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _found = MutableStateFlow<List<FoundDevice>>(emptyList())

    /** Devices seen by the current scan, likely LED boards first, then by signal strength. */
    val found: StateFlow<List<FoundDevice>> = _found.asStateFlow()

    val canScan: Boolean get() = scanner != null

    private var scanJob: Job? = null

    fun dismissNotice() {
        _notice.value = null
    }

    /** Applies a settings change. An invalid result is reported and nothing changes. */
    fun update(transform: (LedSettings) -> LedSettings) {
        try {
            controller.update(transform)
        } catch (e: IllegalArgumentException) {
            _notice.value = LedNotice.InvalidSetting(e.message.orEmpty())
        }
    }

    fun addBoard(info: BoardInfo) {
        _notice.value = when (val result = controller.addBoard(info)) {
            is BoardManager.AddResult.Added -> null
            BoardManager.AddResult.LimitReached -> LedNotice.BoardLimitReached
            BoardManager.AddResult.DuplicateId, BoardManager.AddResult.DuplicateAddress -> LedNotice.BoardAlreadyAdded
            is BoardManager.AddResult.UnknownProtocol -> LedNotice.UnknownProtocol(result.protocolId)
        }
    }

    fun removeBoard(id: String) {
        viewModelScope.launch { controller.removeBoard(id) }
    }

    fun renameBoard(id: String, name: String) {
        controller.renameBoard(id, name.trim())
    }

    /** Moves a board one place up (-1) or down (+1) in the order. */
    fun moveBoard(id: String, offset: Int) {
        val ids = settings.value.boards.map { it.id }.toMutableList()
        val from = ids.indexOf(id)
        val to = from + offset
        if (from < 0 || to !in ids.indices) return
        ids.add(to, ids.removeAt(from))
        controller.reorderBoards(ids)
    }

    /** Sends a board option to every board and reports the ones that do not have it. */
    fun sendToBoards(command: BoardCommand) {
        val unsupported = controller.sendToBoards(command).toSet()
        if (unsupported.isNotEmpty()) {
            val names = settings.value.boards.filter { it.id in unsupported }.map { it.name }
            _notice.value = LedNotice.NotSupported(names)
        }
    }

    fun startScan() {
        val source = scanner ?: return
        if (scanJob?.isActive == true) return
        _found.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            source.scan()
                .catch { error -> _notice.value = LedNotice.ScanFailed(error.message.orEmpty()) }
                .collect { device ->
                    _found.update { current ->
                        (current.filterNot { it.address == device.address } + device)
                            .sortedWith(compareByDescending<FoundDevice> { it.looksLikeLedBoard }.thenByDescending { it.rssi })
                    }
                }
            _scanning.value = false
        }
    }

    fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        _scanning.value = false
    }

    override fun onCleared() {
        stopScan()
    }

    class Factory(
        private val controller: LedController,
        private val manager: BoardManager,
        private val scanner: BoardScanner?,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = LedViewModel(controller, manager, scanner) as T
    }
}
