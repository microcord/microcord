package com.discord.widgets.servers

import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WidgetServerSettingsRolesTest {
    @Test
    fun `only an unlocked manageable elevated role can move`() {
        fun item(
            everyone: Boolean = false,
            locked: Boolean = false,
            manageable: Boolean = true,
            elevated: Boolean = true,
        ) = WidgetServerSettingsRolesAdapter.RoleItem(
            role(), everyone, locked, manageable, elevated, GUILD_ID,
        )

        assertTrue(item().canReorder())
        assertFalse(item(everyone = true).canReorder())
        assertFalse(item(locked = true).canReorder())
        assertFalse(item(manageable = false).canReorder())
        assertFalse(item(elevated = false).canReorder())
    }

    @Test
    fun `reorder positions are reversed and exclude everyone`() {
        val high = WidgetServerSettingsRolesAdapter.RoleItem(
            role(id = 20, position = 2), false, false, true, true, GUILD_ID,
        )
        val low = WidgetServerSettingsRolesAdapter.RoleItem(
            role(id = 21, position = 1), false, false, true, true, GUILD_ID,
        )
        val everyone = WidgetServerSettingsRolesAdapter.RoleItem(
            role(id = GUILD_ID, position = 0), true, false, true, true, GUILD_ID,
        )

        assertEquals(
            mapOf(high.key to 1, low.key to 2),
            WidgetServerSettingsRolesAdapter.changedPositions(
                listOf(WidgetServerSettingsRolesAdapter.HelpItem(GUILD_ID), low, high, everyone),
                mapOf(high.key to 2, low.key to 1, everyone.key to 0),
            ),
        )
    }

    @Test
    fun `manage status follows ownership hierarchy permission and mfa order`() {
        val target = role(id = 20, position = 2)
        val above = role(id = 21, position = 3)
        val below = role(id = 22, position = 1)
        val manageRoles = Permission.MANAGE_ROLES

        assertEquals(
            WidgetServerSettingsEditRole.ManageStatus.CAN_MANAGE_ADMIN,
            WidgetServerSettingsEditRole.Model.manageStatus(true, false, null, null, target),
        )
        assertEquals(
            WidgetServerSettingsEditRole.ManageStatus.LOCKED_HIGHER,
            WidgetServerSettingsEditRole.Model.manageStatus(false, true, manageRoles, below, target),
        )
        assertEquals(
            WidgetServerSettingsEditRole.ManageStatus.LOCKED_HIGHEST,
            WidgetServerSettingsEditRole.Model.manageStatus(false, true, manageRoles, target, target),
        )
        assertEquals(
            WidgetServerSettingsEditRole.ManageStatus.USER_NOT_ELEVATED,
            WidgetServerSettingsEditRole.Model.manageStatus(false, false, manageRoles, above, target),
        )
        assertEquals(
            WidgetServerSettingsEditRole.ManageStatus.CAN_MANAGE_CONDITIONAL,
            WidgetServerSettingsEditRole.Model.manageStatus(false, true, manageRoles, above, target),
        )
    }

    @Test
    fun `singular permission calculation excludes the edited role but keeps everyone`() {
        val everyone = role(GUILD_ID, permissions = Permission.VIEW_CHANNEL)
        val edited = role(20, permissions = Permission.MANAGE_ROLES)
        val other = role(21, permissions = Permission.SEND_MESSAGES)

        assertEquals(
            Permission.VIEW_CHANNEL or Permission.SEND_MESSAGES,
            WidgetServerSettingsEditRole.Model.permissionsWithoutRole(
                listOf(edited.id, other.id),
                listOf(everyone, edited, other).associateBy { it.id },
                edited.id,
                GUILD_ID,
            ),
        )
    }

    private fun role(
        id: Long = 10,
        position: Int = 1,
        permissions: Long = 0,
    ) = GuildRole(
        position = position,
        name = "role-$id",
        id = id,
        color = 0,
        hoist = false,
        permissions = permissions,
        mentionable = false,
        managed = false,
        icon = null,
        tags = null,
        unicodeEmoji = null,
    )

    private companion object {
        const val GUILD_ID = 1L
    }
}
