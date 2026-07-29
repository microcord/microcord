package com.discord.widgets.channels.permissions

import com.discord.api.permission.Permission
import com.discord.api.permission.PermissionOverwrite
import com.discord.api.role.GuildRole
import com.discord.widgets.servers.WidgetServerSettingsEditMemberRolesAdapter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PermissionEditorTest {
    @Test fun editorCoversEveryRecoveredPermissionControl() {
        assertEquals(30, PermissionEditor.permissionByViewId.size)
        assertEquals(30, PermissionEditor.permissionByViewId.values.distinct().size)
    }

    @Test fun triStateRoundTripsWithoutAllowDenyOverlap() {
        val statuses = mapOf(
            Permission.VIEW_CHANNEL to 1,
            Permission.SEND_MESSAGES to -1,
            Permission.MANAGE_CHANNELS to 0,
        )
        val (allow, deny) = PermissionEditor.bits(statuses)
        val overwrite = PermissionOverwrite(1, PermissionOverwrite.Type.ROLE, allow, deny)

        assertEquals(1, PermissionEditor.status(overwrite, Permission.VIEW_CHANNEL))
        assertEquals(-1, PermissionEditor.status(overwrite, Permission.SEND_MESSAGES))
        assertEquals(0, PermissionEditor.status(overwrite, Permission.MANAGE_CHANNELS))
        assertEquals(0L, allow and deny)
    }

    @Test fun removingStageModeratorKeepsUnrelatedBitsAndDeletesEmptyOverwrite() {
        val unrelated = Permission.VIEW_CHANNEL
        val updated = ConfirmRemovePermissionDialogViewModel.removeModerator(
            PermissionOverwrite(1, PermissionOverwrite.Type.MEMBER, Permission.MODERATE_STAGE_CHANNEL or unrelated, Permission.SEND_MESSAGES),
        )
        assertEquals(unrelated, updated?.allow)
        assertEquals(Permission.SEND_MESSAGES, updated?.deny)
        assertNull(
            ConfirmRemovePermissionDialogViewModel.removeModerator(
                PermissionOverwrite(1, PermissionOverwrite.Type.MEMBER, Permission.MODERATE_STAGE_CHANNEL, 0),
            ),
        )
    }

    @Test fun memberRoleSelectorHonorsManagedAndHierarchyRules() {
        val role = GuildRole(1, "Staff", 10, 0, false, 5, false, false, null, null, null)
        val higher = role.copy(id = 20, position = 6)
        val managed = role.copy(id = 30, managed = true)

        assertTrue(WidgetServerSettingsEditMemberRolesAdapter.RoleItem(role, false, higher, false, true).isManageable)
        assertFalse(WidgetServerSettingsEditMemberRolesAdapter.RoleItem(higher, false, role, false, true).isManageable)
        assertFalse(WidgetServerSettingsEditMemberRolesAdapter.RoleItem(managed, false, higher, true, true).isManageable)
        assertTrue(WidgetServerSettingsEditMemberRolesAdapter.RoleItem(higher, false, null, true, true).isManageable)
    }

    @Test fun stageOverviewStartsOnModeratorAndOrdinaryChannelsOnlyExposeAdvanced() {
        val stage = WidgetChannelSettingsPermissionsOverviewViewModel(true).requireViewState()
        val ordinary = WidgetChannelSettingsPermissionsOverviewViewModel(false).requireViewState()
        assertEquals(WidgetChannelSettingsPermissionsOverviewViewModel.Tab.MODERATOR, stage.selectedTab)
        assertEquals(listOf(WidgetChannelSettingsPermissionsOverviewViewModel.Tab.ADVANCED), ordinary.availableTabs)
    }
}
