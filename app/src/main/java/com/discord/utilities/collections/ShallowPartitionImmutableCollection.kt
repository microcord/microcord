package com.discord.utilities.collections

/**
 * Read-only view over already partitioned backing collections.
 *
 * Mutation is rejected. [readOnlyPartitionStrategy] is optional: when the caller knows how an
 * element maps to a partition, [contains] is a single partition lookup; otherwise every partition
 * is scanned. The strategy handed to the mutable superclass is deliberately invalid (-1) because
 * no write path may ever reach it.
 */
open class ShallowPartitionImmutableCollection<E, T : MutableCollection<E>>(
    partitions: List<T>,
    private val readOnlyPartitionStrategy: ((E) -> Int)? = null,
) : ShallowPartitionCollection<E, T>(partitions, { -1 }) {

    override fun contains(element: E): Boolean {
        val partitionIndex = readOnlyPartitionStrategy?.invoke(element)
            ?: return partitions.any { it.contains(element) }
        return partitions[partitionIndex].contains(element)
    }

    override fun add(element: E): Boolean = throw UnsupportedOperationException()

    override fun addAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()

    override fun remove(element: E): Boolean = throw UnsupportedOperationException()

    override fun removeAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()

    override fun retainAll(elements: Collection<E>): Boolean = throw UnsupportedOperationException()

    override fun clear(): Unit = throw UnsupportedOperationException()

    /** Read-only partitioned [MutableSet], used for `keys`/`entries` views of a partitioned map. */
    class Set<E, T : MutableSet<E>>(
        partitions: List<T>,
        partitionStrategy: (E) -> Int,
    ) : ShallowPartitionImmutableCollection<E, T>(partitions, partitionStrategy), MutableSet<E>
}
