package com.discord.utilities.collections

import androidx.annotation.VisibleForTesting
import java.util.TreeMap

/**
 * Bucketed distribution accumulator used by the performance/diagnostic counters.
 *
 * Samples are rounded to the nearest multiple of [precision] before being counted so the bucket
 * map stays small; once [bucketLimit] distinct buckets exist, samples that would create a new
 * bucket are still counted towards min/max/average but not towards the percentile buckets.
 */
class Histogram(
    private val precision: Int,
    private val bucketLimit: Int = DEFAULT_BUCKET_LIMIT,
) {

    private val histogram = TreeMap<Long, Long>()
    private val halfPrecision = precision / 2

    private var count = 0L
    private var total = 0L
    private var min: Long? = null
    private var max: Long? = null

    fun addSample(value: Long) {
        count += 1
        total += value

        val currentMin = min
        val currentMax = max
        if (currentMin == null || currentMin > value) min = value
        if (currentMax == null || currentMax < value) max = value

        val bucket = roundValue(value)
        val bucketCount = histogram[bucket]
        if (bucketCount != null || histogram.size <= bucketLimit) {
            histogram[bucket] = (bucketCount ?: 0L) + 1L
        }
    }

    @VisibleForTesting
    fun roundValue(value: Long): Long {
        val remainder = value % precision
        val floored = value - remainder
        return if (remainder <= halfPrecision) floored else floored + precision
    }

    fun getReport(): Report {
        val percentiles = LinkedHashMap<Int, Long>()
        val pending = ArrayDeque(listOf(25, 50, 75, 90, 95))
        var runningCount = 0L

        for ((bucket, bucketCount) in histogram) {
            if (pending.isEmpty()) break
            runningCount += bucketCount
            val percentageSoFar = (runningCount / count) * 100
            while (pending.isNotEmpty() && pending[0] <= percentageSoFar) {
                percentiles[pending.removeFirst()] = bucket
            }
        }

        return Report(
            min = min ?: 0L,
            max = max ?: 0L,
            average = if (count == 0L) 0.0 else (total / count).toDouble(),
            count = count,
            bucketCount = histogram.size,
            percentile25 = percentiles[25] ?: 0L,
            percentile50 = percentiles[50] ?: 0L,
            percentile75 = percentiles[75] ?: 0L,
            percentile90 = percentiles[90] ?: 0L,
            percentile95 = percentiles[95] ?: 0L,
        )
    }

    data class Report(
        val min: Long,
        val max: Long,
        val average: Double,
        val count: Long,
        val bucketCount: Int,
        val percentile25: Long,
        val percentile50: Long,
        val percentile75: Long,
        val percentile90: Long,
        val percentile95: Long,
    )

    private companion object {
        private const val DEFAULT_BUCKET_LIMIT = 1000
    }
}
