package com.discord.utilities.media

import com.discord.utilities.time.ClockFactory

/** Current Frecents ranking: lifetime frequency weighted by up to ten recent uses. */
class ExpressionFrecencyTracker {
    private data class Entry(var totalUses: Int = 0, var recentUses: List<Long> = emptyList())

    private val entries = LinkedHashMap<String, Entry>()

    @Synchronized
    fun track(key: String, now: Long = ClockFactory.get().currentTimeMillis()) {
        val entry = entries.getOrPut(key, ::Entry)
        entry.totalUses++
        entry.recentUses = (entry.recentUses + now).takeLast(MAX_SAMPLES)
    }

    @Synchronized
    fun getSortedKeys(now: Long = ClockFactory.get().currentTimeMillis()): List<String> =
        entries.entries
            .mapNotNull { (key, entry) -> score(entry, now)?.let { key to it } }
            .sortedByDescending { it.second }
            .map { it.first }

    private fun score(entry: Entry, now: Long): Double? {
        if (entry.totalUses <= 0) return null
        val weightedRecentUses = entry.recentUses.take(MAX_SAMPLES).sumOf { timestamp ->
            weight((now - timestamp) / MILLIS_PER_DAY)
        }
        return if (weightedRecentUses > 0 && entry.recentUses.isNotEmpty()) {
            entry.totalUses * (weightedRecentUses / (100.0 * entry.recentUses.size))
        } else {
            entry.totalUses.toDouble()
        }
    }

    private fun weight(daysAgo: Double): Int = when {
        daysAgo <= 3 -> 100
        daysAgo <= 15 -> 70
        daysAgo <= 30 -> 50
        daysAgo <= 45 -> 30
        daysAgo <= 80 -> 10
        else -> 0
    }

    private companion object {
        const val MAX_SAMPLES = 10
        const val MILLIS_PER_DAY = 86_400_000.0
    }
}
