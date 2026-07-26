package com.discord.utilities.collections

/**
 * A list that only materialises the ranges of indices that actually hold a value.
 *
 * Message history is loaded in disjoint windows around jump targets, so the backing store is a
 * sorted list of contiguous [Chunk]s. Indices outside every chunk read back as `null` without
 * costing memory, and adjacent chunks are merged as soon as they touch.
 */
class SparseMutableList<T>(
    initialSize: Int = 0,
    private val expectedChunkSize: Int = DEFAULT_CHUNK_SIZE,
) : MutableList<T?> {

    private val chunks: MutableList<Chunk<T>> = ArrayList()

    override var size: Int = initialSize
        set(newSize) {
            val currentSize = field
            when {
                newSize > currentSize -> field = newSize
                newSize < currentSize -> {
                    for (chunk in chunks) chunk.removeAfterInclusive(newSize)
                    field = newSize
                    resolveChunks()
                }
            }
        }

    // region chunk bookkeeping

    private fun addChunk(chunk: Chunk<T>) {
        chunks.add(chunk)
        chunks.sort()
    }

    private fun addChunk(index: Int, chunk: Chunk<T>) {
        chunks.add(index, chunk)
        chunks.sort()
    }

    private fun incrementChunksFromIndex(chunkIndex: Int) {
        if (chunkIndex !in chunks.indices) return
        for (index in chunkIndex..chunks.lastIndex) chunks[index].incrementStartIndex()
    }

    private fun decrementChunksFromIndex(chunkIndex: Int) {
        if (chunkIndex !in chunks.indices) return
        for (index in chunkIndex..chunks.lastIndex) chunks[index].decrementStartIndex()
    }

    /** Drops emptied chunks and merges any pair that became adjacent. */
    private fun resolveChunks() {
        chunks.sort()
        var index = 0
        while (index < chunks.size - 1) {
            val chunk = chunks[index]
            if (chunk.isEmpty) {
                chunks.removeAt(index)
                continue
            }
            val merged = Chunk.tryMergeChunks(chunk, chunks[index + 1])
            if (merged != null) {
                chunks[index] = merged
                chunks.removeAt(index + 1)
            } else {
                index += 1
            }
        }
    }

    private fun getChunkIndex(listIndex: Int): Int =
        chunks.indexOfFirst { it.containsListIndex(listIndex) }

    private fun getFirstChunkIndexAfter(listIndex: Int): Int =
        chunks.indexOfFirst { it.beginsAfterListIndex(listIndex) }

    // endregion

    override fun isEmpty(): Boolean = size == 0

    override fun get(index: Int): T? {
        if (index > lastIndex) {
            throw IndexOutOfBoundsException("index $index invalid in list of size $size")
        }
        val chunkIndex = getChunkIndex(index)
        return if (chunkIndex >= 0) chunks[chunkIndex].getAtListIndex(index) else null
    }

    override fun set(index: Int, element: T?): T? {
        if (index >= size) size = index + 1
        val chunkIndex = getChunkIndex(index)
        return when {
            chunkIndex >= 0 -> chunks[chunkIndex].setAtListIndex(index, element).also { resolveChunks() }
            element != null -> {
                addChunk(Chunk.create(element, index, expectedChunkSize))
                resolveChunks()
                null
            }
            else -> null
        }
    }

    override fun add(element: T?): Boolean {
        set(size, element)
        return true
    }

    override fun add(index: Int, element: T?) {
        when {
            index == size -> add(element)
            index > size -> throw IndexOutOfBoundsException()
            else -> {
                val chunkIndex = getChunkIndex(index)
                if (element != null) {
                    if (chunkIndex >= 0) {
                        chunks[chunkIndex].addAtListIndex(index, element)
                        incrementChunksFromIndex(chunkIndex + 1)
                    } else {
                        val chunk = Chunk.create<T>(element, index, expectedChunkSize)
                        val firstAfter = getFirstChunkIndexAfter(index)
                        if (firstAfter == -1) {
                            addChunk(chunk)
                        } else {
                            addChunk(firstAfter, chunk)
                            incrementChunksFromIndex(firstAfter + 1)
                        }
                    }
                } else if (chunkIndex >= 0) {
                    chunks[chunkIndex].addAtListIndex(index, null)
                    incrementChunksFromIndex(chunkIndex + 1)
                } else {
                    val firstAfter = getFirstChunkIndexAfter(index)
                    if (firstAfter != -1) incrementChunksFromIndex(firstAfter)
                }
                size += 1
                resolveChunks()
            }
        }
    }

    override fun removeAt(index: Int): T? {
        if (index < 0 || index >= size) {
            throw ArrayIndexOutOfBoundsException("index: $index -- size: $size")
        }
        val chunkIndex = getChunkIndex(index)
        val removed: T?
        if (chunkIndex >= 0) {
            removed = chunks[chunkIndex].removeAtListIndex(index)
            decrementChunksFromIndex(chunkIndex + 1)
        } else {
            val firstAfter = getFirstChunkIndexAfter(index)
            if (firstAfter != -1) decrementChunksFromIndex(firstAfter)
            removed = null
        }
        size -= 1
        resolveChunks()
        return removed
    }

    override fun remove(element: T?): Boolean {
        val iterator = SparseMutableListIterator(this)
        while (iterator.hasNext()) {
            if (iterator.next() == element) {
                iterator.remove()
                return true
            }
        }
        return false
    }

    override fun removeAll(elements: Collection<T?>): Boolean {
        var removed = false
        val iterator = SparseMutableListIterator(this)
        while (iterator.hasNext()) {
            if (elements.contains(iterator.next())) {
                iterator.remove()
                removed = true
            }
        }
        return removed
    }

    override fun clear() {
        chunks.clear()
        size = 0
    }

    override fun contains(element: T?): Boolean {
        if (isEmpty()) return false
        return any { it == element }
    }

    override fun containsAll(elements: Collection<T?>): Boolean {
        val remaining = HashSet(elements)
        if (remaining.size > size) return false
        for (element in this) {
            if (remaining.contains(element)) {
                remaining.remove(element)
                if (remaining.isEmpty()) return true
            }
        }
        return false
    }

    override fun indexOf(element: T?): Int {
        if (element == null) return indexOfFirst { it == null }
        for (chunk in chunks) {
            val index = chunk.firstListIndexOf(element)
            if (index >= 0) return index
        }
        return -1
    }

    override fun lastIndexOf(element: T?): Int {
        if (element == null) {
            for (index in lastIndex downTo 0) if (get(index) == null) return index
            return -1
        }
        for (chunk in chunks.asReversed()) {
            val index = chunk.lastListIndexOf(element)
            if (index >= 0) return index
        }
        return -1
    }

    override fun iterator(): MutableIterator<T?> = SparseMutableListIterator(this)

    override fun listIterator(): MutableListIterator<T?> = SparseMutableListIterator(this)

    override fun listIterator(index: Int): MutableListIterator<T?> = SparseMutableListIterator(this)

    override fun addAll(elements: Collection<T?>): Boolean = addAll(size, elements)

    override fun addAll(index: Int, elements: Collection<T?>): Boolean =
        throw UnsupportedOperationException()

    override fun retainAll(elements: Collection<T?>): Boolean = throw UnsupportedOperationException()

    override fun subList(fromIndex: Int, toIndex: Int): MutableList<T?> =
        throw UnsupportedOperationException()

    fun deepCopy(): SparseMutableList<T> {
        val copy = SparseMutableList<T>(size)
        for (chunk in chunks) copy.chunks.add(chunk.deepCopy())
        return copy
    }

    fun <R> deepCopy(transform: (T) -> R): SparseMutableList<R> {
        val copy = SparseMutableList<R>(size)
        for (chunk in chunks) copy.chunks.add(chunk.deepCopy(transform))
        return copy
    }

    /** A contiguous run of materialised values beginning at `startIndex` in list coordinates. */
    class Chunk<T>(private var startIndex: Int, var list: MutableList<T?>) : Comparable<Chunk<T>> {

        override fun toString(): String = "Chunk(startIndex=$startIndex, list=$list)"

        val size: Int get() = list.size

        val isEmpty: Boolean get() = list.isEmpty()

        override fun compareTo(other: Chunk<T>): Int = startIndex - other.startIndex

        fun containsListIndex(listIndex: Int): Boolean =
            startIndex <= listIndex && startIndex + list.lastIndex >= listIndex

        fun beginsAfterListIndex(listIndex: Int): Boolean = startIndex > listIndex

        fun incrementStartIndex() {
            startIndex += 1
        }

        fun decrementStartIndex() {
            startIndex -= 1
        }

        fun getAtListIndex(listIndex: Int): T? = list[listIndex - startIndex]

        fun setAtListIndex(listIndex: Int, element: T?): T? {
            val localIndex = listIndex - startIndex
            if (element != null || localIndex != 0) return list.set(localIndex, element)
            incrementStartIndex()
            return list.removeAt(0)
        }

        fun addAtListIndex(listIndex: Int, element: T?) {
            val localIndex = listIndex - startIndex
            if (localIndex == 0 && element == null) {
                incrementStartIndex()
            } else {
                list.add(localIndex, element)
            }
        }

        fun removeAtListIndex(listIndex: Int): T? = list.removeAt(listIndex - startIndex)

        fun removeAfterInclusive(listIndex: Int) {
            val localIndex = listIndex - startIndex
            if (localIndex > list.lastIndex) return
            if (localIndex <= 0) list.clear() else list = list.subList(0, localIndex)
        }

        fun firstListIndexOf(element: T?): Int {
            val index = list.indexOf(element)
            return if (index < 0) -1 else startIndex + index
        }

        fun lastListIndexOf(element: T?): Int {
            val index = list.lastIndexOf(element)
            return if (index < 0) -1 else startIndex + index
        }

        fun deepCopy(): Chunk<T> = Chunk(startIndex, ArrayList<T?>(list))

        fun <R> deepCopy(transform: (T) -> R): Chunk<R> {
            val copied = ArrayList<R?>(list.size)
            for (element in list) copied.add(if (element == null) null else transform(element))
            return Chunk(startIndex, copied)
        }

        companion object {
            fun <T> create(element: T?, startIndex: Int, initialCapacity: Int): Chunk<T> {
                val list = ArrayList<T?>(initialCapacity)
                list.add(element)
                return Chunk(startIndex, list)
            }

            /**
             * Joins two non-overlapping chunks when they are exactly adjacent, otherwise returns
             * null. Overlapping or identically-anchored chunks are a programming error.
             */
            fun <T> tryMergeChunks(chunk1: Chunk<T>, chunk2: Chunk<T>): Chunk<T>? {
                val first = when {
                    chunk1.startIndex < chunk2.startIndex -> chunk1
                    chunk2.startIndex < chunk1.startIndex -> chunk2
                    else -> throw IllegalArgumentException("both chunks begin at the same index")
                }
                val second = if (first === chunk1) chunk2 else chunk1
                val firstLastIndex = first.startIndex + first.size - 1
                require(firstLastIndex < second.startIndex) { "chunks overlap: $chunk1, $chunk2" }
                if (firstLastIndex != second.startIndex - 1) return null
                val merged = first.list
                merged.addAll(second.list)
                return Chunk(first.startIndex, merged)
            }
        }
    }

    private class SparseMutableListIterator<T>(
        private val sparseMutableList: SparseMutableList<T>,
        private var index: Int = -1,
    ) : MutableListIterator<T?> {

        override fun hasNext(): Boolean = nextIndex() < sparseMutableList.lastIndex

        override fun hasPrevious(): Boolean = index > 0

        override fun next(): T? {
            index += 1
            return sparseMutableList[index]
        }

        override fun nextIndex(): Int = index + 1

        override fun previous(): T? {
            index -= 1
            return sparseMutableList[index]
        }

        override fun previousIndex(): Int = index - 1

        override fun remove() {
            sparseMutableList.removeAt(index)
        }

        override fun set(element: T?) {
            sparseMutableList[index] = element
        }

        override fun add(element: T?) =
            throw UnsupportedOperationException("add not supported during iteration")
    }

    private companion object {
        private const val DEFAULT_CHUNK_SIZE = 5
    }
}
