package com.discord.utilities.collections

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A [MutableMap] whose entries are spread over a fixed list of backing maps.
 *
 * Stores use this so that publishing an immutable snapshot only has to copy the partitions that
 * were actually written since the previous snapshot (see [CopiablePartitionMap]).
 */
open class ShallowPartitionMap<K, V>(
    protected val partitions: List<MutableMap<K, V>>,
    protected val partitionStrategy: (K) -> Int,
) : MutableMap<K, V> {

    constructor(
        numPartitions: Int = DEFAULT_PARTITION_COUNT,
        partitionStrategy: (K) -> Int,
    ) : this(List(numPartitions) { HashMap<K, V>() }, partitionStrategy)

    val numPartitions: Int = partitions.size

    override var size: Int = partitions.sumOf { it.size }
        protected set

    private fun getPartition(key: K): MutableMap<K, V> = partitions[partitionStrategy(key)]

    /** Hook for subclasses that need to track which partitions were dirtied by a write. */
    protected open fun getPartitionForWrite(key: K): MutableMap<K, V> =
        partitions[partitionStrategy(key)]

    override fun isEmpty(): Boolean = size == 0

    override fun containsKey(key: K): Boolean = getPartition(key).containsKey(key)

    override fun containsValue(value: V): Boolean = partitions.any { it.containsValue(value) }

    override fun get(key: K): V? = getPartition(key)[key]

    override fun put(key: K, value: V): V? {
        val previous = getPartitionForWrite(key).put(key, value)
        if (previous == null) size += 1
        return previous
    }

    override fun putAll(from: Map<out K, V>) {
        for ((key, value) in from) put(key, value)
    }

    override fun remove(key: K): V? {
        val removed = getPartitionForWrite(key).remove(key) ?: return null
        size -= 1
        return removed
    }

    override fun clear() {
        for (partition in partitions) partition.clear()
        size = 0
    }

    override val entries: MutableSet<MutableMap.MutableEntry<K, V>>
        get() = ShallowPartitionImmutableCollection.Set(
            partitions.map { it.entries },
        ) { entry -> partitionStrategy(entry.key) }

    override val keys: MutableSet<K>
        get() = ShallowPartitionImmutableCollection.Set(partitions.map { it.keys }, partitionStrategy)

    override val values: MutableCollection<V>
        get() = ShallowPartitionImmutableCollection(partitions.map { it.values })

    /**
     * A partitioned map that can hand out a defensive copy in [fastCopy] while only duplicating
     * the partitions that changed since the previous copy.
     */
    open class CopiablePartitionMap<K, V>(
        numPartitions: Int,
        partitionStrategy: (K) -> Int,
    ) : ShallowPartitionMap<K, V>(numPartitions, partitionStrategy) {

        private val dirtyPartitionIndices: HashSet<Int> = partitions.indices.toHashSet()

        var defensiveCopyPartitions: List<Map<K, V>> = defensiveCopy(partitions)
            protected set(value) {
                field = value
                dirtyPartitionIndices.clear()
            }

        override fun getPartitionForWrite(key: K): MutableMap<K, V> {
            val index = partitionStrategy(key)
            dirtyPartitionIndices.add(index)
            return partitions[index]
        }

        override fun clear() {
            dirtyPartitionIndices.addAll(partitions.indices)
            super.clear()
        }

        protected fun defensiveCopy(source: List<Map<K, V>>): List<Map<K, V>> =
            source.mapIndexed { index, partition ->
                if (dirtyPartitionIndices.contains(index)) HashMap(partitions[index]) else partition
            }

        open fun fastCopy(): Map<K, V> {
            defensiveCopyPartitions = defensiveCopy(defensiveCopyPartitions)
            @Suppress("UNCHECKED_CAST")
            return ShallowPartitionMap(
                defensiveCopyPartitions as List<MutableMap<K, V>>,
                partitionStrategy,
            )
        }
    }

    companion object {
        private const val DEFAULT_PARTITION_COUNT = 200
        private const val DEFAULT_PARTITION_SIZE = 100

        fun <K> getHashCodePartitionStrategy(numPartitions: Int): (K) -> Int = { key ->
            val hashCode = key?.hashCode() ?: 0
            abs(hashCode xor (hashCode ushr 16)) % numPartitions
        }

        fun <K, V> create(
            mapSize: Int,
            partitionSize: Int = DEFAULT_PARTITION_SIZE,
            partitionCount: Int =
                ceil((if (mapSize > 0) mapSize else 1) / partitionSize.toFloat()).roundToInt(),
            partitionStrategy: (K) -> Int = getHashCodePartitionStrategy(partitionCount),
        ): ShallowPartitionMap<K, V> = ShallowPartitionMap(
            List(partitionCount + 1) { HashMap(partitionSize) },
            partitionStrategy,
        )
    }
}
