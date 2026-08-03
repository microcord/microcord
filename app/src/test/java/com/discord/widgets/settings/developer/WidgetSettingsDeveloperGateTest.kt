package com.discord.widgets.settings.developer

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetSettingsDeveloperGateTest {

    @Test
    fun `developer screen matches staff gate`() {
        assertTrue(WidgetSettingsDeveloper.canAccess(isStaff = true))
        assertFalse(WidgetSettingsDeveloper.canAccess(isStaff = false))
    }
}
