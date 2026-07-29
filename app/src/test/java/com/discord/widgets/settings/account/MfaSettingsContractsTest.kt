package com.discord.widgets.settings.account

import com.discord.api.auth.mfa.BackupCode
import com.discord.stores.StoreMFA
import com.discord.widgets.settings.account.mfa.WidgetEnableMFASteps
import com.discord.widgets.settings.account.mfa.WidgetEnableMFAViewModel
import com.discord.widgets.user.account.WidgetUserAccountVerifyBase
import com.discord.widgets.user.phone.WidgetUserPhoneManage
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale

class MfaSettingsContractsTest {
    @Test fun `mfa enum ordinals match 126 21 bytecode`() {
        assertArrayEquals(
            arrayOf("PENDING_ENABLED", "PENDING_DISABLED", "NONE"),
            StoreMFA.MFAActivationState.entries.map { it.name }.toTypedArray(),
        )
        assertArrayEquals(
            arrayOf("UNFORCED", "FORCED", "NO_HISTORY_FROM_USER_SETTINGS"),
            WidgetUserAccountVerifyBase.Mode.entries.map { it.name }.toTypedArray(),
        )
        assertArrayEquals(
            arrayOf(
                "USER_ACTION_REQUIRED",
                "USER_SETTINGS_UPDATE",
                "GUILD_PHONE_REQUIRED",
                "MFA_PHONE_UPDATE",
                "CONTACT_SYNC",
                "DEFAULT",
            ),
            WidgetUserPhoneManage.Source.entries.map { it.name }.toTypedArray(),
        )
    }

    @Test fun `mfa view state and screen constants match 126 21 bytecode`() {
        val state = WidgetEnableMFAViewModel.ViewState()
        assertFalse(state.isLoading)
        assertNull(state.screenIndex)
        assertEquals(WidgetEnableMFAViewModel.ViewState(false, 0), WidgetEnableMFAViewModel().viewState)
        assertEquals(2, WidgetEnableMFAViewModel.INPUT_SCREEN)
        assertEquals(3, WidgetEnableMFAViewModel.SUCCESS_SCREEN)
        assertEquals(2, WidgetEnableMFASteps.KEY_SCREEN)
        assertEquals(3, WidgetEnableMFASteps.SUCCESS_SCREEN)
    }

    @Test fun `totp secrets use reference display and request formats`() {
        assertEquals("MZXW6", WidgetEnableMFAViewModel.encodeTotpBase32("foo".toByteArray()))
        assertEquals("ABCD2345EFGH", WidgetEnableMFAViewModel.normalizeTotpSecret(" abcd 2345 efgh "))
        val displayed = WidgetEnableMFAViewModel.generateNewTotpKey(126_021L)
        assertEquals(4, displayed.split(' ').size)
        assertTrue(
            displayed.replace(" ", "").uppercase(Locale.getDefault())
                .all { it in 'A'..'Z' || it in '2'..'7' },
        )
        assertEquals(16, displayed.count { it != ' ' })
    }

    @Test fun `backup rows preserve available then consumed groups`() {
        val rows = WidgetSettingsAccountBackupCodes.buildRows(
            listOf(
                BackupCode("used0001", true),
                BackupCode("open0001", false),
                BackupCode("open0002", false),
                BackupCode("used0002", true),
            ),
            "Available",
            "Used",
        )

        assertEquals(
            listOf("Available", "open0001", "open0002", "Used", "used0001", "used0002"),
            rows.map { it.key },
        )
        assertEquals(listOf(0, 1, 1, 0, 1, 1), rows.map { it.type })
        assertFalse(rows.filterIsInstance<WidgetSettingsAccountBackupCodes.BackupCodeItem>().first().toString().contains("open0001"))
    }
}
