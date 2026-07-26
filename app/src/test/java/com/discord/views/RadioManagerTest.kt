package com.discord.views

import android.widget.Checkable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioManagerTest {

    private class FakeCheckable(private var checked: Boolean = false) : Checkable {
        override fun isChecked() = checked

        override fun setChecked(checked: Boolean) {
            this.checked = checked
        }

        override fun toggle() {
            checked = !checked
        }
    }

    @Test
    fun `checks the first button when the group starts with nothing checked`() {
        val buttons = listOf(FakeCheckable(), FakeCheckable(), FakeCheckable())

        RadioManager(buttons)

        assertTrue(buttons[0].isChecked)
        assertFalse(buttons[1].isChecked)
        assertFalse(buttons[2].isChecked)
    }

    @Test
    fun `clears the group when it starts with a checked button`() {
        val buttons = listOf(FakeCheckable(), FakeCheckable(checked = true), FakeCheckable())

        val radioManager = RadioManager(buttons)

        // The reference deliberately leaves the group empty here; the owning screen applies the
        // selection from its model immediately after construction.
        assertTrue(buttons.none { it.isChecked })
        assertEquals(-1, radioManager.checkedButtonIndex)
    }

    @Test
    fun `checking one button unchecks every other button`() {
        val buttons = listOf(FakeCheckable(), FakeCheckable(), FakeCheckable())
        val radioManager = RadioManager(buttons)

        radioManager.setChecked(buttons[2])

        assertFalse(buttons[0].isChecked)
        assertFalse(buttons[1].isChecked)
        assertTrue(buttons[2].isChecked)
        assertEquals(2, radioManager.checkedButtonIndex)
    }

    @Test
    fun `switching selection keeps exactly one button checked`() {
        val buttons = listOf(FakeCheckable(), FakeCheckable(), FakeCheckable())
        val radioManager = RadioManager(buttons)

        radioManager.setChecked(buttons[1])
        radioManager.setChecked(buttons[0])

        assertEquals(1, buttons.count { it.isChecked })
        assertEquals(0, radioManager.checkedButtonIndex)
    }

    @Test
    fun `checking a button outside the group clears the group`() {
        val buttons = listOf(FakeCheckable(), FakeCheckable())
        val radioManager = RadioManager(buttons)

        radioManager.setChecked(FakeCheckable())

        assertTrue(buttons.none { it.isChecked })
        assertEquals(-1, radioManager.checkedButtonIndex)
    }

    @Test
    fun `checked index reports the first checked button`() {
        val buttons = listOf(FakeCheckable(), FakeCheckable())
        val radioManager = RadioManager(buttons)

        assertEquals(0, radioManager.checkedButtonIndex)
    }
}
