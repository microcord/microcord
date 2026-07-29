package com.discord.widgets.user

import com.discord.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetModerationContractsTest {
    @Test
    fun banHistoryIdsMapToApiDays() {
        assertEquals(0, deleteMessageDays(R.id.ban_user_delete_messages_none))
        assertEquals(1, deleteMessageDays(R.id.ban_user_delete_messages_1_day))
        assertEquals(7, deleteMessageDays(R.id.ban_user_delete_messages_7_days))
        assertEquals(0, deleteMessageDays(null))
    }

    @Test
    fun blankAuditLogReasonsAreOmittedWithoutChangingNonBlankInput() {
        assertNull(" \t".asAuditLogReason())
        assertEquals(" reason ", " reason ".asAuditLogReason())
    }

    @Test
    fun pruneContractsMatchTheReferenceWindowsAndButtonRule() {
        assertEquals(listOf(7, 30), WidgetPruneUsersViewModel.PruneDays.entries.map { it.count })
        assertFalse(
            WidgetPruneUsersViewModel.ViewState.Loaded(
                WidgetPruneUsersViewModel.PruneDays.Seven,
                0,
            ).pruneButtonEnabled,
        )
        assertTrue(
            WidgetPruneUsersViewModel.ViewState.Loaded(
                WidgetPruneUsersViewModel.PruneDays.Thirty,
                1,
            ).pruneButtonEnabled,
        )
    }
}
