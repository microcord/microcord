package com.discord.utilities.collections

import java.util.LinkedList

/**
 * Flattens an iterator of partitions into a single mutable iterator, skipping empty partitions.
 *
 * [remove] delegates to the partition currently being walked, so removal stays consistent with the
 * underlying partitioned collection.
 */
class MutablePartitionedIterator<T>(
    private val partitionIterator: Iterator<Iterable<T>>,
) : MutableIterator<T> {

    private var currentPartitionIterator: MutableIterator<T> = LinkedList<T>().iterator()

    override fun hasNext(): Boolean {
        if (currentPartitionIterator.hasNext()) return true
        while (partitionIterator.hasNext()) {
            @Suppress("UNCHECKED_CAST")
            currentPartitionIterator = partitionIterator.next().iterator() as MutableIterator<T>
            if (currentPartitionIterator.hasNext()) return true
        }
        return false
    }

    override fun next(): T = currentPartitionIterator.next()

    override fun remove() = currentPartitionIterator.remove()
}
