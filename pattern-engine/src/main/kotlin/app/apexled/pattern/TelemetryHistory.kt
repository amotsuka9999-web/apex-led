package app.apexled.pattern

/**
 * Immutable, time-ordered window of recent telemetry. It is what lets a staggered board
 * replay what the first board showed a moment ago while the engine stays a pure function.
 */
class TelemetryHistory private constructor(private val entries: List<Entry>) {

    data class Entry(val timeMs: Long, val sample: WheelSample)

    val size: Int get() = entries.size
    val latest: Entry? get() = entries.lastOrNull()

    /**
     * Returns a new history with [sample] appended and everything older than [retentionMs] dropped.
     * One entry at or before the retention edge is kept so lookups at the edge still resolve.
     * A sample older than the newest entry is ignored: time never runs backwards here.
     */
    fun plus(timeMs: Long, sample: WheelSample, retentionMs: Long = DEFAULT_RETENTION_MS): TelemetryHistory {
        val newest = entries.lastOrNull()
        if (newest != null && timeMs < newest.timeMs) return this
        val cutoff = timeMs - retentionMs
        var firstKept = entries.indexOfLast { it.timeMs <= cutoff }
        if (firstKept < 0) firstKept = 0
        val kept = entries.subList(firstKept, entries.size)
        val base = if (newest != null && newest.timeMs == timeMs) kept.dropLast(1) else kept
        return TelemetryHistory(base + Entry(timeMs, sample))
    }

    /**
     * The sample in effect at [timeMs]: the newest entry at or before it.
     * Before the first entry this is the first entry; with no entries it is [WheelSample.ZERO].
     */
    fun sampleAt(timeMs: Long): WheelSample {
        if (entries.isEmpty()) return WheelSample.ZERO
        var low = 0
        var high = entries.size - 1
        if (entries[low].timeMs > timeMs) return entries[low].sample
        while (low < high) {
            val mid = (low + high + 1) ushr 1
            if (entries[mid].timeMs <= timeMs) low = mid else high = mid - 1
        }
        return entries[low].sample
    }

    companion object {
        val EMPTY = TelemetryHistory(emptyList())

        /** Covers the longest possible stagger: 9 follower boards at the maximum delay, plus slack. */
        const val DEFAULT_RETENTION_MS = 9 * SyncMode.Staggered.MAX_DELAY_MS + 2_000L

        fun of(timeMs: Long, sample: WheelSample): TelemetryHistory = EMPTY.plus(timeMs, sample)
    }
}
