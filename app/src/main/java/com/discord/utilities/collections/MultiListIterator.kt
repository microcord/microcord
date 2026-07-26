package com.discord.utilities.collections

/** Read-only iterator that walks several lists back to back without copying them. */
class MultiListIterator<T>(vararg val lists: List<T>) : Iterator<T> {

    private var listIndex = 0
    private var currentIterator: Iterator<T> = lists[listIndex].iterator()

    override fun hasNext(): Boolean {
        if (currentIterator.hasNext()) return true
        while (!currentIterator.hasNext()) {
            listIndex += 1
            if (listIndex >= lists.size) return false
            currentIterator = lists[listIndex].iterator()
        }
        return true
    }

    override fun next(): T = currentIterator.next()
}
