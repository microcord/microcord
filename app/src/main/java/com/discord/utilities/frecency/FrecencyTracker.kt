package com.discord.utilities.frecency

import com.discord.utilities.time.ClockFactory
import kotlin.math.ceil

/**
 * Ranks keys by "frecency" — frequency weighted by recency.
 *
 * Each key keeps the timestamps of its last [maxSamples] uses; a subclass turns those into a score.
 * Keys scoring at or below [minScoreThreshold] are evicted as they are scored, so the tracker prunes
 * itself instead of growing without bound.
 *
 * Scores are recomputed lazily: [track] only marks the tracker dirty, and the work happens on the
 * next [getSortedKeys].
 */
abstract class FrecencyTracker<T : Any>(
    val minScoreThreshold: Int,
    val maxSamples: Int,
) {
    /** One key as the account stores it. */
    data class Entry(
        val totalUses: Int,
        val recentUses: List<Long>,
        val frecency: Int = 0,
        val score: Int = 0,
    )

    private val history = HashMap<T, List<Long>>()
    private val totalUses = HashMap<T, Int>()
    private var sortedKeys: List<T> = emptyList()
    private var dirty: Boolean = true

    protected abstract fun List<Long>.computeScore(currentTime: Long): Int

    @Synchronized
    fun track(key: T, now: Long = ClockFactory.get().currentTimeMillis()) {
        history[key] = (history[key].orEmpty() + now).takeLast(maxSamples)
        totalUses[key] = (totalUses[key] ?: 0) + 1
        dirty = true
    }

    @Synchronized
    fun removeEntry(key: T) {
        history.remove(key)
        totalUses.remove(key)
        dirty = true
    }

    /** Scores are recomputed, not restored: the stored ones have since decayed. */
    @Synchronized
    fun restore(entries: Map<T, Entry>) {
        history.clear()
        totalUses.clear()
        entries.forEach { (key, entry) ->
            history[key] = entry.recentUses.takeLast(maxSamples)
            totalUses[key] = entry.totalUses
        }
        dirty = true
    }

    /** Trimmed by last use, not by score, as the reference does. */
    @Synchronized
    fun toEntries(limit: Int, now: Long = ClockFactory.get().currentTimeMillis()): Map<T, Entry> =
        history.entries
            .sortedByDescending { it.value.lastOrNull() ?: 0L }
            .take(limit)
            .associateTo(LinkedHashMap()) { (key, uses) ->
                val score = uses.computeScore(now)
                val lifetime = totalUses[key] ?: uses.size
                key to Entry(
                    totalUses = lifetime,
                    recentUses = uses,
                    frecency = if (uses.isEmpty()) lifetime else ceil(lifetime * score.toDouble() / uses.size).toInt(),
                    score = score,
                )
            }

    @Synchronized
    fun getSortedKeys(now: Long = ClockFactory.get().currentTimeMillis()): Collection<T> {
        if (dirty) computeScores(now)
        return sortedKeys
    }

    /** Highest score first; ties broken by the more recent last use. */
    private fun computeScores(currentTime: Long) {
        val scores = HashMap<T, Int>(history.size)
        val iterator = history.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            val score = entry.value.computeScore(currentTime)
            if (score > minScoreThreshold) scores[entry.key] = score else iterator.remove()
        }
        sortedKeys = scores.keys.sortedWith(
            compareByDescending<T> { scores[it] }.thenByDescending { history[it]?.lastOrNull() },
        )
        dirty = false
    }
}
