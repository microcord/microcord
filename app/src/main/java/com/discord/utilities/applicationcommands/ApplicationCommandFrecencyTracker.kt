package com.discord.utilities.applicationcommands

import com.discord.utilities.frecency.FrecencyTracker

/**
 * Frecency over slash-command keys, feeding the "Frequently Used" application in the command
 * picker.
 *
 * Keys are `"<commandId>"` for a built-in/DM command and `"<commandId>:<guildId>"` for a
 * guild-scoped one — see `StoreApplicationCommandFrecency.key`. That is why this cannot simply
 * reuse `MediaFrecencyTracker` even though the decay curve is identical: the two trackers are
 * persisted under different [com.discord.utilities.persister.Persister] keys and must stay separate
 * types so Kryo can tell them apart.
 *
 * Decay is banded rather than continuous: anything used in the last three days counts fully, and a
 * use older than eighty days counts for nothing.
 */
class ApplicationCommandFrecencyTracker(
    maxSamples: Int = DEFAULT_MAX_SAMPLES,
    minScoreThreshold: Int = DEFAULT_MIN_SCORE_THRESHOLD,
) : FrecencyTracker<String>(minScoreThreshold = minScoreThreshold, maxSamples = maxSamples) {

    override fun List<Long>.computeScore(currentTime: Long): Int =
        sumOf { timestamp -> daysWeight(daysDiff(timestamp, currentTime)) }

    private fun daysDiff(earlierTime: Long, laterTime: Long): Int =
        ((laterTime - earlierTime) / MILLIS_PER_DAY).toInt()

    private fun daysWeight(daysDiff: Int): Int = when {
        daysDiff <= 3 -> 100
        daysDiff <= 15 -> 70
        daysDiff <= 30 -> 50
        daysDiff <= 45 -> 30
        daysDiff <= 80 -> 10
        else -> 0
    }

    private companion object {
        const val DEFAULT_MAX_SAMPLES = 10
        const val DEFAULT_MIN_SCORE_THRESHOLD = 70
        const val MILLIS_PER_DAY = 86_400_000L
    }
}
