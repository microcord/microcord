package com.discord.widgets.roles

import org.junit.Assert.assertEquals
import org.junit.Test

class RolesListViewTest {
    @Test
    fun usesOpaqueRoleColorOrDefault() {
        assertEquals(0xff123456.toInt(), roleChipColor(0x123456, 0xff654321.toInt()))
        assertEquals(0xff654321.toInt(), roleChipColor(0, 0xff654321.toInt()))
    }
}
