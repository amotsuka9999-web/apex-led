package app.apexled.device

/**
 * Timing rules for one board link. Defaults are conservative starting points to be tuned
 * on hardware (docs/checklist-campo.md).
 */
data class ConnectionPolicy(
    /** Wait before the first reconnection attempt; doubles on every further failure. */
    val initialBackoffMs: Long = 500,
    val maxBackoffMs: Long = 30_000,
    /** A link that held at least this long counts as good: the next retry starts from the initial backoff again. */
    val stableSessionMs: Long = 5_000,
    val connectTimeoutMs: Long = 10_000,
    val writeTimeoutMs: Long = 2_000,
    /** Minimum gap between two writes, so a slow controller is not flooded. */
    val minWriteIntervalMs: Long = 30,
    /** An unchanged cosmetic frame is sent again after this long, to recover from a lost write. */
    val refreshIntervalMs: Long = 2_000,
    /** Same, for the critical indicator: a lost write must not leave the alert unshown for long. */
    val criticalRefreshIntervalMs: Long = 500,
    /** Board commands waiting for a link. When full, the oldest is dropped. */
    val maxQueuedCommands: Int = 32,
) {
    init {
        require(initialBackoffMs > 0 && maxBackoffMs >= initialBackoffMs) { "invalid backoff" }
        require(connectTimeoutMs > 0 && writeTimeoutMs > 0) { "timeouts must be positive" }
        require(minWriteIntervalMs >= 0) { "minWriteIntervalMs must be >= 0" }
        require(refreshIntervalMs > 0 && criticalRefreshIntervalMs > 0) { "refresh intervals must be positive" }
        require(maxQueuedCommands > 0) { "maxQueuedCommands must be positive" }
    }

    /** Delay before reconnection attempt number [attempt] (1 = first retry). */
    fun backoffMs(attempt: Int): Long {
        require(attempt >= 1) { "attempt starts at 1" }
        val shift = (attempt - 1).coerceAtMost(30)
        val delay = initialBackoffMs * (1L shl shift)
        return if (delay <= 0) maxBackoffMs else delay.coerceAtMost(maxBackoffMs)
    }
}
