package com.discord.widgets.settings.account

import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccountSettingsContractsTest {

    @Test fun `disable and delete ordinals match 126 21 bytecode`() {
        assertEquals(0, WidgetDisableDeleteAccountDialog.Mode.DISABLE.ordinal)
        assertEquals(1, WidgetDisableDeleteAccountDialog.Mode.DELETE.ordinal)
        assertEquals(R.string.disable_account, WidgetDisableDeleteAccountDialog.Mode.DISABLE.headerStringId)
        assertEquals(R.string.delete_account, WidgetDisableDeleteAccountDialog.Mode.DELETE.headerStringId)
    }

    @Test fun `confirmed email flow requires verified user in treatment bucket`() {
        assertTrue(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(true, 1))
        assertFalse(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(false, 1))
        assertFalse(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(true, 0))
        assertFalse(WidgetSettingsAccount.shouldUseConfirmedEmailFlow(true, null))
    }

    @Test fun `contact name trims and clears with null`() {
        assertEquals("Manti Kafasi", WidgetSettingsAccountContactsNameEdit.normalizeContactName("  Manti Kafasi  "))
        assertNull(WidgetSettingsAccountContactsNameEdit.normalizeContactName("   "))
    }
}
