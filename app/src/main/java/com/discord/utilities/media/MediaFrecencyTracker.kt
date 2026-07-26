package com.discord.utilities.media

import com.discord.utilities.frecency.FrecencyTracker

/**
 * Frecency over emoji and sticker keys — what fills the "Frequently Used" rows in the pickers.
 *
 * Unlike the quick switcher's channel tracker, this one decays in coarse day-bands rather than
 * continuously: everything used in the last three days counts fully, and a use older than eighty
 * days counts for nothing at all. That keeps a burst of reaction spam from pinning an emoji to the
 * top for months while still surviving a week of not opening the app.
 *
 * Keys are strings because the two pickers store different things in the same shape:
 * [com.discord.stores.StoreEmoji] tracks `Emoji.getUniqueId()` (a custom emoji id or a unicode
 * surrogate) and [com.discord.stores.StoreStickers] tracks the decimal sticker id.
 */
class MediaFrecencyTracker(
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
