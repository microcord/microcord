package com.discord.utilities.frecency

import com.discord.utilities.time.ClockFactory

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
    private val history = HashMap<T, List<Long>>()
    private var sortedKeys: List<T> = emptyList()
    private var dirty: Boolean = true

    /** The score for one key, given the timestamps of its recorded uses. */
    protected abstract fun List<Long>.computeScore(currentTime: Long): Int

    @Synchronized
    fun track(key: T, now: Long = ClockFactory.get().currentTimeMillis()) {
        history[key] = (history[key].orEmpty() + now).takeLast(maxSamples)
        dirty = true
    }

    @Synchronized
    fun removeEntry(key: T) {
        history.remove(key)
        dirty = true
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
