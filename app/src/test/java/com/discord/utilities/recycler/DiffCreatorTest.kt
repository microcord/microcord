package com.discord.utilities.recycler

import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.RecyclerView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** A minimal keyed row: [key] is the identity, [body] is the content. */
private data class Row(override val key: String, val body: String = "") : DiffKeyProvider

/** Records the update callbacks a diff would dispatch to an adapter. */
private class RecordingUpdateCallback : androidx.recyclerview.widget.ListUpdateCallback {
    val events = mutableListOf<String>()
    override fun onInserted(position: Int, count: Int) {
        events.add("insert@$position x$count")
    }

    override fun onRemoved(position: Int, count: Int) {
        events.add("remove@$position x$count")
    }

    override fun onMoved(fromPosition: Int, toPosition: Int) {
        events.add("move $fromPosition->$toPosition")
    }

    override fun onChanged(position: Int, count: Int, payload: Any?) {
        events.add("change@$position x$count")
    }
}

class DiffCreatorTest {

    private fun creator() = DiffCreator<List<Row>, RecyclerView.ViewHolder>(null)

    private fun dispatch(result: DiffUtil.DiffResult): List<String> {
        val callback = RecordingUpdateCallback()
        result.dispatchUpdatesTo(callback)
        return callback.events
    }

    @Test
    fun `identical lists produce no updates`() {
        val rows = listOf(Row("a"), Row("b"))
        val result = creator().calculateDiffResult(rows, rows)
        assertNotNull(result)
        assertEquals(emptyList<String>(), dispatch(result!!))
    }

    @Test
    fun `inserting a row reports a single insert`() {
        val result = creator().calculateDiffResult(
            listOf(Row("a"), Row("c")),
            listOf(Row("a"), Row("b"), Row("c")),
        )
        assertEquals(listOf("insert@1 x1"), dispatch(result!!))
    }

    @Test
    fun `removing a row reports a single remove`() {
        val result = creator().calculateDiffResult(
            listOf(Row("a"), Row("b"), Row("c")),
            listOf(Row("a"), Row("c")),
        )
        assertEquals(listOf("remove@1 x1"), dispatch(result!!))
    }

    @Test
    fun `same key with different contents reports a change, not a replace`() {
        val result = creator().calculateDiffResult(
            listOf(Row("a", "before")),
            listOf(Row("a", "after")),
        )
        assertEquals(listOf("change@0 x1"), dispatch(result!!))
    }

    @Test
    fun `moving a row is detected`() {
        val result = creator().calculateDiffResult(
            listOf(Row("a"), Row("b"), Row("c")),
            listOf(Row("c"), Row("a"), Row("b")),
        )
        val events = dispatch(result!!)
        assertEquals("expected a move, got $events", true, events.any { it.startsWith("move") })
    }

    @Test
    fun `a size change beyond the threshold is abandoned`() {
        val old = (0 until 10).map { Row("k$it") }
        val new = (0 until 400).map { Row("k$it") }
        assertNull(creator().calculateDiffResult(old, new))
    }

    @Test
    fun `a wholesale key replacement of equal size is abandoned`() {
        val old = (0 until 300).map { Row("old$it") }
        val new = (0 until 300).map { Row("new$it") }
        assertNull(creator().calculateDiffResult(old, new))
    }

    @Test
    fun `a large list with few edits is still diffed`() {
        val old = (0 until 1000).map { Row("k$it") }
        val new = old.toMutableList().apply { removeAt(500) }
        val result = creator().calculateDiffResult(old, new)
        assertNotNull(result)
        assertEquals(listOf("remove@500 x1"), dispatch(result!!))
    }

    @Test
    fun `diffing against an empty list still works`() {
        val result = creator().calculateDiffResult(emptyList(), listOf(Row("a"), Row("b")))
        assertEquals(listOf("insert@0 x2"), dispatch(result!!))
    }
}
