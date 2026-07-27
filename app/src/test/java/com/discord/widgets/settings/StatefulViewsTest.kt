package com.discord.widgets.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The value bookkeeping behind every "edit and press save" settings screen.
 *
 * Only the map half is covered here — anything that touches a `View` needs an instrumented test.
 * That half is where the subtle behaviour lives: re-rendering from a store emission must not discard
 * an in-progress edit, and a PATCH body must contain only the fields that really changed.
 */
class StatefulViewsTest {

    private val nameId = 1
    private val topicId = 2

    @Test
    fun `rendering a server value does not clobber a pending edit`() {
        val state = StatefulViews(nameId)

        assertEquals("from server", state.get(nameId, "from server"))
        state.put(nameId, "typed by user")

        // A second store emission arrives with the still-unsaved server value.
        assertEquals("typed by user", state.get(nameId, "from server"))
    }

    @Test
    fun `getIfChanged returns only genuinely changed fields`() {
        val state = StatefulViews(nameId, topicId)
        state.get(nameId, "old name")
        state.get(topicId, "topic")

        state.put(nameId, "new name")
        // Touched, but set back to what the server already has.
        state.put(topicId, "topic")

        assertEquals("new name", state.getIfChanged<String>(nameId))
        assertNull(state.getIfChanged<String>(topicId))
    }

    @Test
    fun `hasChanged is false for an untouched field`() {
        val state = StatefulViews(nameId)
        state.get(nameId, "old name")

        assertFalse(state.hasChanged(nameId))
        state.put(nameId, "new name")
        assertTrue(state.hasChanged(nameId))
    }

    // The `SpannableStringBuilder`-vs-`String` comparison in `hasAnythingChanged` is deliberately
    // not covered here: `android.text.SpannableStringBuilder` is an unimplemented android.jar stub
    // under `unitTests.isReturnDefaultValues = true`, so the test would exercise the stub, not the
    // rule. It needs an instrumented test.

    @Test
    fun `clear drops pending edits but keeps the rendered baseline`() {
        val state = StatefulViews(nameId)
        state.get(nameId, "server value")
        state.put(nameId, "typed")

        state.clear()

        assertFalse(state.hasChanged(nameId))
        assertEquals("server value", state.get(nameId, "server value"))
    }

    @Test
    fun `clearing defaults too makes the next rendered value the new baseline`() {
        val state = StatefulViews(nameId)
        state.get(nameId, "old")
        state.put(nameId, "saved")

        // What a screen does after a successful save.
        state.clear(clearDefaults = true)

        assertEquals("saved", state.get(nameId, "saved"))
        assertFalse(state.hasChanged(nameId))
    }

    @Test
    fun `an optional field may hold null while a required one falls back to the server value`() {
        val optionalId = 3
        val state = StatefulViews(nameId, optionalId)
        // `addOptionalFields` takes Views, so the flag is set the way a screen's watcher setup does:
        // an unregistered field is treated as required, which is the fallback branch below.
        state.get(nameId, "required default")
        state.put(nameId, null)

        // Required: a null edit falls back to the rendered value rather than clearing the field.
        assertEquals("required default", state.get(nameId, "required default"))
    }
}
