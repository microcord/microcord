package com.discord.utilities.collections

/**
 * A bounded insertion-ordered set that evicts the least recently *added* element once [maxSize]
 * would be exceeded.
 *
 * Re-adding an existing element moves it to the end of the eviction order, which is why [add]
 * removes before inserting. Unlike an LRU cache, plain reads do not refresh an element.
 */
class LeastRecentlyAddedSet<E>(
    val maxSize: Int = 3,
    private val backingSet: LinkedHashSet<E> = LinkedHashSet(maxSize),
) : MutableSet<E> by backingSet {

    @Synchronized
    override fun add(element: E): Boolean {
        val wasPresent = backingSet.remove(element)
        val iterator = backingSet.iterator()
        while (backingSet.size >= maxSize) {
            iterator.next()
            iterator.remove()
        }
        backingSet.add(element)
        return !wasPresent
    }

    override fun toString(): String = "LRA Set ${backingSet.toList()}"
}
