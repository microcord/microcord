package com.discord.widgets.servers

import com.discord.models.guild.Guild
import com.discord.utilities.permissions.ManageGuildContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetServerSettingsViewModelTest {
    @Test
    fun `a role manager can reach the landing screen`() {
        val state = WidgetServerSettingsViewModel.toViewState(
            storeState(context(canManageRoles = true)),
        ) as WidgetServerSettingsViewModel.ViewState.Loaded

        assertTrue(state.manageGuildContext.canManageRoles)
        assertFalse(state.manageGuildContext.canManageServer)
    }

    @Test
    fun `a user with no management permission is unauthorized`() {
        assertEquals(
            WidgetServerSettingsViewModel.ViewState.Unauthorized,
            WidgetServerSettingsViewModel.toViewState(storeState(context())),
        )
    }

    @Test
    fun `role subscription state is preserved for section visibility`() {
        val state = WidgetServerSettingsViewModel.toViewState(
            storeState(
                manage = context(canManageServer = true),
                canManageSubscriptions = true,
                hasSubscriptions = true,
            ),
        ) as WidgetServerSettingsViewModel.ViewState.Loaded

        assertTrue(state.canManageGuildRoleSubscriptions)
        assertTrue(state.hasGuildRoleSubscriptions)
    }

    private fun storeState(
        manage: ManageGuildContext,
        canManageSubscriptions: Boolean = false,
        hasSubscriptions: Boolean = false,
    ) = WidgetServerSettingsViewModel.StoreState(
        guild = Guild(id = 1, name = "Guild", ownerId = 2),
        manageGuildContext = manage,
        canManageGuildRoleSubscriptions = canManageSubscriptions,
        hasGuildRoleSubscriptions = hasSubscriptions,
    )

    private fun context(
        canManageServer: Boolean = false,
        canManageRoles: Boolean = false,
    ) = ManageGuildContext(
        canManageServer = canManageServer,
        canManageChannels = false,
        canManageRoles = canManageRoles,
        canManageBans = false,
        canManageNicknames = false,
        canManageEmojisAndStickers = false,
        canViewAuditLogs = false,
        canManageEvents = false,
        isOwnerWithRequiredMFALevel = false,
    )
}
