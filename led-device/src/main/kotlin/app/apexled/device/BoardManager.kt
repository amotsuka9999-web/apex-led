package app.apexled.device

import app.apexled.device.protocol.ProtocolRegistry
import app.apexled.pattern.LedFrame
import app.apexled.pattern.PatternEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The set of boards the app drives: up to [MAX_BOARDS], each with its own name, address and link.
 * Board order matters: it is the order frames are assigned in, and so the order of the stagger.
 */
class BoardManager(
    private val scope: CoroutineScope,
    private val transportFactory: (BoardInfo) -> LedTransport,
    private val protocols: ProtocolRegistry = ProtocolRegistry(),
    private val policy: ConnectionPolicy = ConnectionPolicy(),
    private val clock: () -> Long = System::currentTimeMillis,
) {
    class Entry internal constructor(val info: BoardInfo, val connection: BoardConnection)

    sealed interface AddResult {
        data class Added(val entry: Entry) : AddResult
        data object LimitReached : AddResult
        data object DuplicateId : AddResult
        data object DuplicateAddress : AddResult
        data class UnknownProtocol(val protocolId: String) : AddResult
    }

    private val lock = Any()
    private val _boards = MutableStateFlow<List<Entry>>(emptyList())

    /** Registered boards, in order. */
    val boards: StateFlow<List<Entry>> = _boards.asStateFlow()

    val size: Int get() = _boards.value.size

    /** Registers a board and starts connecting to it. */
    fun add(info: BoardInfo): AddResult {
        val entry = synchronized(lock) {
            val current = _boards.value
            if (current.size >= MAX_BOARDS) return AddResult.LimitReached
            if (current.any { it.info.id == info.id }) return AddResult.DuplicateId
            if (current.any { it.info.address.equals(info.address, ignoreCase = true) }) return AddResult.DuplicateAddress
            val protocol = protocols[info.protocolId] ?: return AddResult.UnknownProtocol(info.protocolId)
            val connection = BoardConnection(transportFactory(info), protocol, scope, policy, clock)
            Entry(info, connection).also { _boards.value = current + it }
        }
        entry.connection.start()
        return AddResult.Added(entry)
    }

    /** Disconnects and forgets a board. Returns false when there is no such board. */
    suspend fun remove(id: String): Boolean {
        val entry = synchronized(lock) {
            val current = _boards.value
            val found = current.firstOrNull { it.info.id == id } ?: return false
            _boards.value = current - found
            found
        }
        entry.connection.stop()
        return true
    }

    /** Changes a board's name. The link is untouched. Returns false when there is no such board or the name is blank. */
    fun rename(id: String, name: String): Boolean {
        if (name.isBlank()) return false
        synchronized(lock) {
            val current = _boards.value
            val index = current.indexOfFirst { it.info.id == id }
            if (index < 0) return false
            val old = current[index]
            _boards.value = current.toMutableList().also { it[index] = Entry(old.info.copy(name = name), old.connection) }
            return true
        }
    }

    /** Reorders the boards. [ids] must be exactly the registered ids. Returns false otherwise. */
    fun reorder(ids: List<String>): Boolean {
        synchronized(lock) {
            val current = _boards.value
            if (ids.size != current.size || ids.toSet() != current.map { it.info.id }.toSet()) return false
            _boards.value = ids.map { id -> current.first { it.info.id == id } }
            return true
        }
    }

    /** Gives frame `i` to board `i`. Extra frames or boards are left alone. */
    fun showFrames(frames: List<LedFrame>) {
        val current = _boards.value
        for (index in 0 until minOf(frames.size, current.size)) {
            current[index].connection.showFrame(frames[index])
        }
    }

    /** Stops driving colours on every board (the boards' own effects take over). */
    fun clearFrames() {
        _boards.value.forEach { it.connection.clearFrame() }
    }

    /** Queues [command] on every board. Returns the ids of the boards whose protocol does not support it. */
    fun sendToAll(command: BoardCommand): List<String> =
        _boards.value.filterNot { it.connection.send(command) }.map { it.info.id }

    /** Disconnects every board and forgets them all. */
    suspend fun removeAll() {
        val entries = synchronized(lock) { _boards.value.also { _boards.value = emptyList() } }
        entries.forEach { it.connection.stop() }
    }

    companion object {
        const val MAX_BOARDS = PatternEngine.MAX_BOARDS
    }
}
