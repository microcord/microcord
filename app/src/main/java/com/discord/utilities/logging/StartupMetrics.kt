package com.discord.utilities.logging

import android.os.SystemClock
import android.util.Log
import java.util.Locale

object StartupMetrics {
    /** [RESTORE] is a cache restore step on the store thread, [DECODE] one cache file read. */
    enum class Kind { BOOT, GATEWAY, RESTORE, DECODE }

    data class Entry(
        val kind: Kind,
        val name: String,
        val atMs: Double,
        val waitMs: Double? = null,
        val decodeMs: Double? = null,
        val parseMs: Double? = null,
        val durationMs: Double? = null,
        val bytes: Long? = null,
    )

    private const val TAG = "StartupMetrics"
    private const val MAX_ENTRIES = 500
    private val entries = ArrayDeque<Entry>(MAX_ENTRIES)
    private val listeners = LinkedHashSet<(List<Entry>) -> Unit>()
    private var processStartNanos = SystemClock.elapsedRealtimeNanos()
    private var collectingGatewayFrames = true
    /** Time the current restore step has spent inside `Persister.get`, waiting on a decode. */
    private val cacheWait = ThreadLocal<LongArray?>()

    fun beginProcess(name: String) {
        synchronized(this) {
            processStartNanos = SystemClock.elapsedRealtimeNanos()
            entries.clear()
            collectingGatewayFrames = true
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
        val finishesStartup = type == "READY" || type == "RESUMED"
        if (synchronized(this) {
                if (!collectingGatewayFrames) true
                else {
                    if (finishesStartup) collectingGatewayFrames = false
                    false
                }
            }
        ) return
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

    /** Times one cache restore step, splitting out the part spent waiting on cache reads. */
    fun <T> restoreStep(name: String, block: () -> T): T {
        val wait = LongArray(1)
        cacheWait.set(wait)
        val start = SystemClock.elapsedRealtimeNanos()
        try {
            return block()
        } finally {
            val end = SystemClock.elapsedRealtimeNanos()
            cacheWait.set(null)
            val entry = Entry(Kind.RESTORE, name, elapsedMs(start), waitMs = wait[0].toMs(), durationMs = (end - start).toMs())
            append(entry)
            Log.d(TAG, "${format(entry.atMs)}ms restore $name took=${format(entry.durationMs)} cacheWait=${format(entry.waitMs)}")
        }
    }

    /** Called around a blocking cache read; only counts inside [restoreStep]. */
    fun cacheWaitStarted(): Long = if (cacheWait.get() == null) 0L else SystemClock.elapsedRealtimeNanos()

    fun cacheWaitFinished(startedNanos: Long) {
        if (startedNanos == 0L) return
        cacheWait.get()?.let { it[0] += SystemClock.elapsedRealtimeNanos() - startedNanos }
    }

    fun recordDecode(key: String, startedNanos: Long, bytes: Long) {
        val end = SystemClock.elapsedRealtimeNanos()
        if (!synchronized(this) { collectingGatewayFrames }) return
        val entry = Entry(Kind.DECODE, key, elapsedMs(startedNanos), durationMs = (end - startedNanos).toMs(), bytes = bytes)
        append(entry)
        Log.d(TAG, "${format(entry.atMs)}ms decode $key took=${format(entry.durationMs)} bytes=$bytes")
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
            when (entry.kind) {
                Kind.GATEWAY -> {
                    append("  wait=").append(format(entry.waitMs))
                    append(" decode=").append(format(entry.decodeMs))
                    append(" parse=").append(format(entry.parseMs))
                }
                Kind.RESTORE -> append("  took=").append(format(entry.durationMs)).append(" cacheWait=").append(format(entry.waitMs))
                Kind.DECODE -> append("  took=").append(format(entry.durationMs)).append(" bytes=").append(entry.bytes)
                Kind.BOOT -> Unit
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

    val steps = entries.filter { it.kind == StartupMetrics.Kind.RESTORE }
    if (steps.isNotEmpty()) {
        val queuedAfter = boot.firstOrNull { it.name == BootLogEvent.CACHE_RESTORE_STARTED.line }
        appendLine()
        appendLine("Cache restore (store thread)")
        queuedAfter?.let { appendLine("Queued before first step: ${StartupMetrics.format(steps.first().atMs - it.atMs)} ms") }
        appendLine("Total: ${StartupMetrics.format(steps.sumOf { it.durationMs ?: 0.0 })} ms, " +
            "waiting on cache reads: ${StartupMetrics.format(steps.sumOf { it.waitMs ?: 0.0 })} ms")
        appendLine("    took   waited  step")
        steps.forEach {
            appendLine("${StartupMetrics.format(it.durationMs).padStart(8)} ${StartupMetrics.format(it.waitMs).padStart(8)}  ${it.name}")
        }
    }
    val decodes = entries.filter { it.kind == StartupMetrics.Kind.DECODE }
    if (decodes.isNotEmpty()) {
        appendLine()
        appendLine("Cache decodes, slowest first (${decodes.size} files, ${StartupMetrics.format(decodes.sumOf { it.durationMs ?: 0.0 })} ms)")
        appendLine("    took  started       KB  key")
        decodes.sortedByDescending { it.durationMs ?: 0.0 }.forEach {
            appendLine(
                "${StartupMetrics.format(it.durationMs).padStart(8)} ${StartupMetrics.format(it.atMs).padStart(8)} " +
                    "${String.format(Locale.US, "%8.1f", (it.bytes ?: 0L) / 1024.0)}  ${it.name}",
            )
        }
    }
}

private fun List<Double>.averageOrNull(): Double? = if (isEmpty()) null else average()
