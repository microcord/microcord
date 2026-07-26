package com.discord.utilities.collections

/**
 * A collection split across a fixed number of backing partitions.
 *
 * Every element is routed to exactly one partition by [partitionStrategy]. Splitting the storage
 * keeps copy-on-write snapshots cheap: only the partitions that were written have to be copied.
 */
open class ShallowPartitionCollection<E, T : MutableCollection<E>>(
    protected val partitions: List<T>,
    protected val partitionStrategy: (E) -> Int,
) : MutableCollection<E> {

    override var size: Int = partitions.sumOf { it.size }
        protected set

    private fun getPartition(key: E): T = partitions[partitionStrategy(key)]

    override fun isEmpty(): Boolean = size == 0

    override fun iterator(): MutablePartitionedIterator<E> =
        MutablePartitionedIterator(partitions.iterator())

    override fun contains(element: E): Boolean = getPartition(element).contains(element)

    override fun containsAll(elements: Collection<E>): Boolean {
        var remaining: Collection<E> = elements
        for (partition in partitions) {
            remaining = remaining.minus(partition.toSet())
        }
        return remaining.isEmpty()
    }

    override fun add(element: E): Boolean {
        val added = getPartition(element).add(element)
        if (added) size += 1
        return added
    }

    override fun addAll(elements: Collection<E>): Boolean {
        var changed = false
        for (element in elements) changed = add(element) || changed
        return changed
    }

    override fun remove(element: E): Boolean {
        val removed = getPartition(element).remove(element)
        if (removed) size -= 1
        return removed
    }

    override fun removeAll(elements: Collection<E>): Boolean {
        var changed = false
        for (element in elements) changed = remove(element) || changed
        return changed
    }

    override fun retainAll(elements: Collection<E>): Boolean {
        var changed = false
        for (partition in partitions) changed = partition.retainAll(elements.toSet()) || changed
        return changed
    }

    override fun clear() {
        for (partition in partitions) partition.clear()
        size = 0
    }

    companion object {
        private const val DEFAULT_PARTITION_COUNT = 40

        @JvmStatic
        fun <E> withArrayListPartitions(
            numPartitions: Int = DEFAULT_PARTITION_COUNT,
            partitionStrategy: (E) -> Int,
        ): ShallowPartitionCollection<E, ArrayList<E>> =
            ShallowPartitionCollection(
                List(numPartitions) { ArrayList() },
                partitionStrategy,
            )
    }
}
