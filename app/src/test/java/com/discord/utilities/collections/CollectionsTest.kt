package com.discord.utilities.collections

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LeastRecentlyAddedSetTest {

    @Test
    fun `add reports whether the element was new`() {
        val set = LeastRecentlyAddedSet<String>(maxSize = 4)
        assertTrue(set.add("a"))
        assertFalse(set.add("a"))
    }

    @Test
    fun `evicts the least recently added element when full`() {
        val set = LeastRecentlyAddedSet<String>(maxSize = 3)
        set.add("a")
        set.add("b")
        set.add("c")
        assertEquals(listOf("a", "b", "c"), set.toList())

        set.add("d")
        assertEquals(listOf("b", "c", "d"), set.toList())
        assertFalse(set.contains("a"))
    }

    @Test
    fun `re-adding an element refreshes its eviction order`() {
        val set = LeastRecentlyAddedSet<String>(maxSize = 3)
        set.add("a")
        set.add("b")
        set.add("c")
        set.add("a")
        assertEquals(listOf("b", "c", "a"), set.toList())

        set.add("d")
        assertEquals(listOf("c", "a", "d"), set.toList())
    }

    @Test
    fun `size never exceeds maxSize`() {
        val set = LeastRecentlyAddedSet<Int>(maxSize = 2)
        repeat(20) { set.add(it) }
        assertEquals(2, set.size)
        assertEquals(listOf(18, 19), set.toList())
    }
}

class ShallowPartitionMapTest {

    @Test
    fun `behaves like a map across partitions`() {
        val map = ShallowPartitionMap.create<String, Int>(mapSize = 500)
        repeat(500) { map["key-$it"] = it }

        assertEquals(500, map.size)
        assertEquals(250, map["key-250"])
        assertTrue(map.containsKey("key-499"))
        assertFalse(map.containsKey("missing"))
        assertTrue(map.containsValue(17))

        assertEquals(250, map.remove("key-250"))
        assertNull(map.remove("key-250"))
        assertEquals(499, map.size)

        assertEquals(499, map.keys.size)
        assertEquals(499, map.entries.size)
        assertEquals(499, map.values.size)

        map.clear()
        assertEquals(0, map.size)
        assertTrue(map.isEmpty())
    }

    @Test
    fun `hash partition strategy stays within bounds`() {
        val strategy = ShallowPartitionMap.getHashCodePartitionStrategy<String>(8)
        repeat(1000) {
            val partition = strategy("key-$it")
            assertTrue("partition $partition out of range", partition in 0..7)
        }
    }

    @Test
    fun `key views reject mutation`() {
        val map = ShallowPartitionMap.create<String, Int>(mapSize = 4)
        map["a"] = 1
        try {
            map.keys.add("b")
            throw AssertionError("expected UnsupportedOperationException")
        } catch (expected: UnsupportedOperationException) {
            // Views over a partitioned map are read-only by design.
        }
    }

    @Test
    fun `fastCopy snapshots the current contents and detaches from later writes`() {
        val map = ShallowPartitionMap.CopiablePartitionMap<String, Int>(
            8,
            ShallowPartitionMap.getHashCodePartitionStrategy(8),
        )
        map["a"] = 1
        map["b"] = 2

        val snapshot = map.fastCopy()
        assertEquals(2, snapshot.size)
        assertEquals(1, snapshot["a"])

        map["c"] = 3
        map["a"] = 99
        assertEquals(2, snapshot.size)
        assertEquals(1, snapshot["a"])
        assertNull(snapshot["c"])
        assertEquals(3, map.size)
    }
}

class SnowflakePartitionMapTest {

    private val snowflakeA = 852_892_297_661_906_993L
    private val snowflakeB = 852_892_297_661_906_994L

    @Test
    fun `stores and retrieves snowflake keys`() {
        val map = SnowflakePartitionMap<String>(256)
        map[snowflakeA] = "a"
        map[snowflakeB] = "b"

        assertEquals("a", map[snowflakeA])
        assertEquals("b", map[snowflakeB])
        assertEquals(2, map.size)
        assertNull(map[1L])
    }

    @Test
    fun `partitions by the snowflake timestamp component`() {
        val map = SnowflakePartitionMap<String>(256)
        // Ids created in the same millisecond share a partition; distant ids generally do not.
        repeat(1000) { map[snowflakeA + it] = "v$it" }
        assertEquals(1000, map.size)
        assertEquals("v999", map[snowflakeA + 999])
    }

    @Test
    fun `copiable variant produces detached snapshots`() {
        val map = SnowflakePartitionMap.CopiablePartitionMap<String>()
        map[snowflakeA] = "a"
        val snapshot = map.fastCopy()

        map[snowflakeB] = "b"
        assertEquals(1, snapshot.size)
        assertEquals(2, map.size)
    }
}

class SparseMutableListTest {

    @Test
    fun `unset indices read back as null without materialising them`() {
        val list = SparseMutableList<String>(initialSize = 100)
        list[10] = "ten"
        list[11] = "eleven"

        assertEquals(100, list.size)
        assertEquals("ten", list[10])
        assertEquals("eleven", list[11])
        assertNull(list[0])
        assertNull(list[99])
    }

    @Test
    fun `growing past the end extends the size`() {
        val list = SparseMutableList<String>()
        list[5] = "five"
        assertEquals(6, list.size)
        assertEquals("five", list[5])
    }

    @Test
    fun `shrinking drops values beyond the new size`() {
        val list = SparseMutableList<String>()
        list[0] = "a"
        list[1] = "b"
        list[2] = "c"
        list.size = 2
        assertEquals(2, list.size)
        assertEquals("a", list[0])
        assertEquals("b", list[1])
    }

    @Test
    fun `indexOf finds materialised values through chunks`() {
        val list = SparseMutableList<String>(initialSize = 50)
        list[3] = "x"
        list[20] = "y"
        list[21] = "x"

        assertEquals(3, list.indexOf("x"))
        assertEquals(21, list.lastIndexOf("x"))
        assertEquals(20, list.indexOf("y"))
        assertEquals(-1, list.indexOf("missing"))
    }

    @Test
    fun `removeAt shifts later chunks down`() {
        val list = SparseMutableList<String>(initialSize = 10)
        list[5] = "a"
        list[6] = "b"

        assertEquals("a", list.removeAt(5))
        assertEquals(9, list.size)
        assertEquals("b", list[5])
    }

    @Test
    fun `adjacent chunks merge`() {
        val list = SparseMutableList<String>(initialSize = 10)
        list[2] = "a"
        list[4] = "c"
        list[3] = "b"

        assertEquals("a", list[2])
        assertEquals("b", list[3])
        assertEquals("c", list[4])
        assertNull(list[5])
    }

    @Test
    fun `deepCopy is independent of the original`() {
        val list = SparseMutableList<String>(initialSize = 10)
        list[1] = "a"
        val copy = list.deepCopy()

        list[1] = "changed"
        assertEquals("a", copy[1])
        assertEquals("changed", list[1])
    }

    @Test
    fun `clear empties the list`() {
        val list = SparseMutableList<String>(initialSize = 10)
        list[1] = "a"
        list.clear()
        assertEquals(0, list.size)
        assertTrue(list.isEmpty())
    }
}

class HistogramTest {

    @Test
    fun `rounds samples to the nearest bucket`() {
        val histogram = Histogram(precision = 10)
        assertEquals(0L, histogram.roundValue(4))
        assertEquals(0L, histogram.roundValue(5))
        assertEquals(10L, histogram.roundValue(6))
        assertEquals(10L, histogram.roundValue(14))
        assertEquals(20L, histogram.roundValue(16))
    }

    @Test
    fun `report tracks min max average and bucket count`() {
        val histogram = Histogram(precision = 10)
        listOf(10L, 20L, 30L, 40L).forEach(histogram::addSample)

        val report = histogram.getReport()
        assertEquals(10L, report.min)
        assertEquals(40L, report.max)
        assertEquals(25.0, report.average, 0.0)
        assertEquals(4L, report.count)
        assertEquals(4, report.bucketCount)
    }

    @Test
    fun `empty histogram reports zeroes`() {
        val report = Histogram(precision = 10).getReport()
        assertEquals(0L, report.min)
        assertEquals(0L, report.max)
        assertEquals(0.0, report.average, 0.0)
        assertEquals(0L, report.count)
    }
}

class FixedSizeLineBufferTest {

    @Test
    fun `keeps the most recent lines within the character budget`() {
        val buffer = FixedSizeLineBuffer(maxCharacterCount = 12)
        buffer.addLine("aaaa")
        buffer.addLine("bbbb")
        buffer.addLine("cccc")
        buffer.addLine("dddd")

        val output = buffer.getString()
        assertFalse(output.contains("aaaa"))
        assertTrue(output.contains("dddd"))
    }

    @Test
    fun `appendString joins with newlines`() {
        val buffer = FixedSizeLineBuffer(maxCharacterCount = 1000)
        buffer.addLine("one")
        buffer.addLine("two")

        val sb = StringBuilder()
        buffer.appendString(sb)
        assertEquals("one\ntwo\n", sb.toString())
        assertEquals("one\ntwo", buffer.getString())
    }
}

class MultiListIteratorTest {

    @Test
    fun `walks every list in order`() {
        val iterator = MultiListIterator(listOf(1, 2), emptyList(), listOf(3))
        assertEquals(listOf(1, 2, 3), iterator.asSequence().toList())
    }

    @Test
    fun `handles trailing empty lists`() {
        val iterator = MultiListIterator(listOf("a"), emptyList<String>(), emptyList())
        assertEquals(listOf("a"), iterator.asSequence().toList())
    }
}

class MutablePartitionedIteratorTest {

    @Test
    fun `flattens partitions and skips empty ones`() {
        val partitions = listOf(mutableListOf(1, 2), mutableListOf(), mutableListOf(3))
        val iterator = MutablePartitionedIterator(partitions.iterator())
        assertEquals(listOf(1, 2, 3), iterator.asSequence().toList())
    }

    @Test
    fun `remove deletes from the underlying partition`() {
        val partition = mutableListOf(1, 2, 3)
        val iterator = MutablePartitionedIterator(listOf(partition).iterator())
        while (iterator.hasNext()) {
            if (iterator.next() == 2) iterator.remove()
        }
        assertEquals(listOf(1, 3), partition)
    }
}

class ShallowPartitionCollectionTest {

    @Test
    fun `adds removes and iterates across partitions`() {
        val collection = ShallowPartitionCollection.withArrayListPartitions<Int>(4) { it % 4 }
        collection.addAll(listOf(0, 1, 2, 3, 4, 5))

        assertEquals(6, collection.size)
        assertTrue(collection.contains(5))
        assertTrue(collection.containsAll(listOf(0, 3, 5)))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), collection.toSet())

        assertTrue(collection.remove(4))
        assertFalse(collection.remove(4))
        assertEquals(5, collection.size)

        collection.clear()
        assertTrue(collection.isEmpty())
    }
}

class CollectionExtensionsTest {

    @Test
    fun `equals compares positionally with a custom comparator`() {
        val a = listOf("A", "B")
        val b = listOf("a", "b")
        assertTrue(a.equals(b) { x, y -> x.equals(y, ignoreCase = true) })
        assertFalse(a.equals(listOf("a")) { x, y -> x.equals(y, ignoreCase = true) })
        assertFalse(a.equals(listOf("a", "c")) { x, y -> x.equals(y, ignoreCase = true) })
    }

    @Test
    fun `filterNonNullValues drops null values`() {
        val filtered = mapOf("a" to 1, "b" to null, "c" to 3).filterNonNullValues()
        assertEquals(mapOf("a" to 1, "c" to 3), filtered)
    }

    @Test
    fun `snapshot detaches from the source map`() {
        val source = mutableMapOf("a" to 1)
        val snapshot = source.snapshot()
        source["b"] = 2
        assertEquals(mapOf("a" to 1), snapshot)
    }
}

class ListenerCollectionSubjectTest {

    @Test
    fun `notifies every registered listener once`() {
        val subject = ListenerCollectionSubject<StringBuilder>()
        val first = StringBuilder()
        val second = StringBuilder()
        subject.add(first)
        subject.add(second)

        subject.notify { it.append("x") }
        assertEquals("x", first.toString())
        assertEquals("x", second.toString())

        assertTrue(subject.remove(first))
        subject.notify { it.append("y") }
        assertEquals("x", first.toString())
        assertEquals("xy", second.toString())

        subject.clear()
        subject.notify { it.append("z") }
        assertEquals("xy", second.toString())
    }
}
