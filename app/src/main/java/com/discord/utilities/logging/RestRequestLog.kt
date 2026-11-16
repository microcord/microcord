package com.discord.utilities.logging

/**
 * The last [MAX_ENTRIES] REST calls, in memory only, for the developer REST log screen.
 *
 * Every call is recorded with its sanitized path, status and timing. Headers and bodies are only
 * captured, already redacted, while the "Log REST Requests and Responses" switch is on.
 */
object RestRequestLog {

    data class Entry(
        val id: Long,
        val timeMillis: Long,
        val method: String,
        val path: String,
        /** Null when the call failed before a response. */
        val status: Int?,
        val error: String?,
        val durationMs: Long,
        val requestHeaders: String?,
        val requestBody: String?,
        val responseHeaders: String?,
        val responseBody: String?,
    )

    private const val MAX_ENTRIES = 300

    private val entries = ArrayDeque<Entry>()
    private val listeners = mutableListOf<(List<Entry>) -> Unit>()
    private var nextId = 0L

    fun record(entry: (id: Long) -> Entry) {
        val (snapshot, toNotify) = synchronized(this) {
            entries.addFirst(entry(nextId++))
            if (entries.size > MAX_ENTRIES) entries.removeLast()
            entries.toList() to listeners.toList()
        }
        toNotify.forEach { it(snapshot) }
    }

    fun clear() {
        val toNotify = synchronized(this) {
            entries.clear()
            listeners.toList()
        }
        toNotify.forEach { it(emptyList()) }
    }

    /** Calls [listener] now and on every change, on whichever thread made it. Returns the unsubscribe. */
    fun observe(listener: (List<Entry>) -> Unit): () -> Unit {
        val snapshot = synchronized(this) {
            listeners += listener
            entries.toList()
        }
        listener(snapshot)
        return { synchronized(this) { listeners -= listener } }
    }
}
