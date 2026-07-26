package com.discord.utilities.collections

/**
 * A [ShallowPartitionMap] keyed by Discord snowflake ids.
 *
 * The partition is derived from the snowflake's timestamp component, which spreads ids that were
 * created around the same time (and are therefore usually written together) into the same
 * partition, keeping snapshot copies small.
 */
class SnowflakePartitionMap<V>(
    partitions: List<MutableMap<Long, V>>,
) : ShallowPartitionMap<Long, V>(partitions, PARTITION_SNOWFLAKE_ID_STRATEGY) {

    constructor(numPartitions: Int) : this(List(numPartitions) { HashMap<Long, V>() })

    /** Snowflake-keyed variant of [ShallowPartitionMap.CopiablePartitionMap]. */
    class CopiablePartitionMap<V>(
        numPartitions: Int = PARTITION_SIZE,
    ) : ShallowPartitionMap.CopiablePartitionMap<Long, V>(
        numPartitions,
        PARTITION_SNOWFLAKE_ID_STRATEGY,
    ) {
        override fun fastCopy(): Map<Long, V> {
            defensiveCopyPartitions = defensiveCopy(defensiveCopyPartitions)
            @Suppress("UNCHECKED_CAST")
            return SnowflakePartitionMap(defensiveCopyPartitions as List<MutableMap<Long, V>>)
        }
    }

    companion object {
        private const val PARTITION_SIZE = 256

        private val PARTITION_SNOWFLAKE_ID_STRATEGY: (Long) -> Int =
            { snowflake -> ((snowflake ushr 22) % PARTITION_SIZE).toInt() }
    }
}
