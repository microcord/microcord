package com.discord.widgets.servers

import androidx.fragment.app.FragmentFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.jaredrummler.android.colorpicker.ColorPickerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        assertNotNull(type.getConstructor())
        assertEquals(
            type,
            FragmentFactory().instantiate(type.classLoader!!, type.name).javaClass,
        )

        val arguments = RoleColorPickerDialog.newInstance(0xFF123456.toInt()).requireArguments()
        assertEquals(0xFF123456.toInt(), arguments.getInt("color"))
        assertEquals(ColorPickerDialog.TYPE_CUSTOM, arguments.getInt("dialogType"))
        assertFalse(arguments.getBoolean("alpha"))
        assertFalse(arguments.getBoolean("allowPresets"))
    }
}
