package com.discord.utilities.logging

import android.os.SystemClock
import android.util.Log
import java.util.Locale

object StartupMetrics {
    enum class Kind { BOOT, GATEWAY }

    data class Entry(
        val kind: Kind,
        val name: String,
        val atMs: Double,
        val waitMs: Double? = null,
        val decodeMs: Double? = null,
        val parseMs: Double? = null,
    )

    private const val TAG = "StartupMetrics"
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val listeners = LinkedHashSet<(List<Entry>) -> Unit>()
    private var processStartNanos = SystemClock.elapsedRealtimeNanos()

    fun beginProcess(name: String) {
        synchronized(this) {
            processStartNanos = SystemClock.elapsedRealtimeNanos()
            entries.clear()
        }
        markBoot(name)
    }

    fun markBoot(name: String) {
        val entry = Entry(Kind.BOOT, name, elapsedMs(SystemClock.elapsedRealtimeNanos()))
        append(entry)
        Log.d(TAG, "${format(entry.atMs)}ms boot $name")
    }

    fun recordGatewayFrame(
        type: String,
        receivedNanos: Long,
        waitNanos: Long?,
        decodeNanos: Long,
        parseNanos: Long,
    ) {
        val entry = Entry(
            kind = Kind.GATEWAY,
            name = type,
            atMs = elapsedMs(receivedNanos),
            waitMs = waitNanos?.toMs(),
            decodeMs = decodeNanos.toMs(),
            parseMs = parseNanos.toMs(),
        )
        append(entry)
        Log.d(
            TAG,
            "${format(entry.atMs)}ms gateway $type wait=${format(entry.waitMs)} " +
                "decode=${format(entry.decodeMs)} parse=${format(entry.parseMs)}",
        )
    }

    fun snapshot(): List<Entry> = synchronized(this) { entries.toList() }

    fun observe(listener: (List<Entry>) -> Unit): () -> Unit {
        val snapshot = synchronized(this) {
            listeners += listener
            entries.toList()
        }
        listener(snapshot)
        return { synchronized(this) { listeners -= listener } }
    }

    fun summary(): String = formatStartupSummary(snapshot(), elapsedMs(SystemClock.elapsedRealtimeNanos()))

    fun timeline(): String = buildString {
        appendLine("at (ms)   kind     event / timing")
        snapshot().forEach { entry ->
            append(format(entry.atMs).padStart(8)).append("  ")
            append(entry.kind.name.lowercase(Locale.ROOT).padEnd(8)).append(' ')
            append(entry.name)
            if (entry.kind == Kind.GATEWAY) {
                append("  wait=").append(format(entry.waitMs))
                append(" decode=").append(format(entry.decodeMs))
                append(" parse=").append(format(entry.parseMs))
            }
            appendLine()
        }
    }

    private fun append(entry: Entry) {
        val update = synchronized(this) {
            if (entries.size == MAX_ENTRIES) entries.removeFirst()
            entries.addLast(entry)
            entries.toList() to listeners.toList()
        }
        update.second.forEach { listener -> runCatching { listener(update.first) } }
    }

    private fun elapsedMs(nanos: Long) = (nanos - synchronized(this) { processStartNanos }).toMs()
    private fun Long.toMs() = this / 1_000_000.0

    internal fun format(value: Double?): String =
        value?.let { String.format(Locale.US, "%.3f", it) } ?: "-"
}

internal fun formatStartupSummary(entries: List<StartupMetrics.Entry>, uptimeMs: Double): String = buildString {
    val boot = entries.filter { it.kind == StartupMetrics.Kind.BOOT }
    val gateway = entries.filter { it.kind == StartupMetrics.Kind.GATEWAY }
    val ready = gateway.firstOrNull { it.name == "READY" }
    val ordinary = gateway.filterNot { it.name == "READY" }

    appendLine("Process uptime: ${StartupMetrics.format(uptimeMs)} ms")
    appendLine("Latest milestone: ${boot.lastOrNull()?.name ?: "none"}")
    appendLine()
    appendLine("Cold boot milestones")
    boot.forEach { appendLine("${StartupMetrics.format(it.atMs).padStart(9)} ms  ${it.name}") }
    appendLine()
    appendLine("Gateway")
    appendLine("Frames: ${gateway.size}")
    appendLine("READY arrival: ${StartupMetrics.format(ready?.atMs)} ms")
    appendLine("READY wait: ${StartupMetrics.format(ready?.waitMs)} ms")
    appendLine("READY decode: ${StartupMetrics.format(ready?.decodeMs)} ms")
    appendLine("READY parse: ${StartupMetrics.format(ready?.parseMs)} ms")
    appendLine("Ordinary decode avg: ${StartupMetrics.format(ordinary.mapNotNull { it.decodeMs }.averageOrNull())} ms")
    appendLine("Ordinary parse avg: ${StartupMetrics.format(ordinary.mapNotNull { it.parseMs }.averageOrNull())} ms")
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
