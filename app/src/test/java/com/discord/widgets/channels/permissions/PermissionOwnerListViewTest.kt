package com.discord.widgets.channels.permissions

import com.discord.api.role.GuildRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class PermissionOwnerListViewTest {
    @Test
    fun itemAndRemovalStateContractsMatchReference() {
        val owner = PermissionOwner.Role(
            GuildRole(0, "Moderators", 42L, 0, false, 0L, false, false, null, null, null),
        )
        val removable = PermissionOwnerListView.Item(owner, PermissionOwnerListView.RemoveStatus.CanRemove)

        assertEquals(removable, removable.copy())
        assertNotEquals(removable, removable.copy(removeStatus = PermissionOwnerListView.RemoveStatus.IsRemoving))
        assertEquals(
            listOf("IS_GUILD_OWNER", "IS_ADMINISTRATOR", "IS_NOT_OVERRIDE", "HAS_NO_PERMISSION"),
            PermissionOwnerListView.RemoveStatus.CannotRemove.Reason.entries.map { it.name },
        )
    }
}
