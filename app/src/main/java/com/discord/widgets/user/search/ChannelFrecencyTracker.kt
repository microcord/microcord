package com.discord.widgets.user.search

import com.discord.utilities.frecency.FrecencyTracker
import java.util.concurrent.TimeUnit

/**
 * Frecency over channel ids — how the quick switcher knows which channels you actually use.
 *
 * Each visit inside the last week contributes a decaying amount, and visits older than a week
 * contribute nothing, so a channel you used constantly a month ago falls out on its own. Thirty-five
 * is the score a channel has to beat to stay tracked at all, and only the last ten visits count.
 */
class ChannelFrecencyTracker : FrecencyTracker<Long>(
    minScoreThreshold = MIN_SCORE_THRESHOLD,
    maxSamples = MAX_SAMPLES,
) {
    override fun List<Long>.computeScore(currentTime: Long): Int = sumOf { timestamp ->
        val age = currentTime - timestamp
        if (age < OLDEST_ALLOWED_DELTA_MS) {
            // Truncated before the subtraction, exactly as the reference does: `0.1^fraction` only
            // reaches 1 for an instantaneous visit, so in practice every in-window visit is worth
            // 100 and the decay only bites at the week boundary.
            100 - Math.pow(0.1, age.toDouble() / OLDEST_ALLOWED_DELTA_MS).toInt()
        } else {
            0
        }
    }

    private companion object {
        const val MIN_SCORE_THRESHOLD = 35
        const val MAX_SAMPLES = 10
        val OLDEST_ALLOWED_DELTA_MS: Long = TimeUnit.DAYS.toMillis(7)
    }
}
