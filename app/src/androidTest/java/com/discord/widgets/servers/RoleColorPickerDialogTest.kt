package com.discord.widgets.servers

import androidx.fragment.app.FragmentFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.discord.app.AppDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.lang.reflect.Modifier

@RunWith(AndroidJUnit4::class)
class RoleColorPickerDialogTest {
    @Test
    fun dialogIsPublicRecreatableAndKeepsPickerArguments() {
        val type = RoleColorPickerDialog::class.java
        assertTrue(Modifier.isPublic(type.modifiers))
        assertTrue(AppDialog::class.java.isAssignableFrom(type))
        assertNotNull(type.getConstructor())
        assertEquals(
            type,
            FragmentFactory().instantiate(type.classLoader!!, type.name).javaClass,
        )

        val arguments = RoleColorPickerDialog.newInstance(0xFF123456.toInt()).requireArguments()
        assertEquals(0x123456, arguments.getInt("ARG_INITIAL_COLOR"))
        assertEquals(0x12ABEF, RoleColorPickerDialog.parseColor("#12abef"))
        assertNull(RoleColorPickerDialog.parseColor("#12xxef"))
    }
}
