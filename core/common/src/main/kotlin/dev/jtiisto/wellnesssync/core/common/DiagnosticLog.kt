package dev.jtiisto.wellnesssync.core.common

import android.util.Log

data class LogEntry(
    val timestampMs: Long,
    val tag: String,
    val message: String,
)

/**
 * Thread-safe in-memory ring buffer of diagnostic events, uploadable to the
 * server for post-hoc analysis of failures that happen away from ADB (gym BLE
 * issues, Polar field tests). Entries also mirror to logcat.
 *
 * In-memory only by design: the failure windows of interest occur while the
 * app is alive and in the foreground.
 */
class DiagnosticLog(private val capacity: Int = DEFAULT_CAPACITY) {

    companion object {
        const val DEFAULT_CAPACITY = 1000
        private const val LOGCAT_TAG = "WellnessDiag"
    }

    private val entries = ArrayDeque<LogEntry>()
    private val lock = Any()

    fun log(tag: String, message: String) {
        val entry = LogEntry(System.currentTimeMillis(), tag, message)
        synchronized(lock) {
            entries.addLast(entry)
            while (entries.size > capacity) {
                entries.removeFirst()
            }
        }
        Log.d("$LOGCAT_TAG/$tag", message)
    }

    /** Immutable snapshot, oldest first. */
    fun snapshot(): List<LogEntry> = synchronized(lock) { entries.toList() }

    val size: Int get() = synchronized(lock) { entries.size }

    fun clear() {
        synchronized(lock) { entries.clear() }
    }
}
