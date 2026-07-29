package com.discord.widgets.servers.role_members

import com.discord.models.member.GuildMember
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.widgets.channels.permissions.PermissionOwner

data class ServerSettingsRoleMemberAdapterItem(
    val guildMember: GuildMember,
    val permissionOwner: PermissionOwner.Member,
) : MGRecyclerDataPayload {
    override val type: Int get() = TYPE_MEMBER
    override val key: String get() = permissionOwner.user.id.toString()

    companion object {
        const val TYPE_MEMBER = 0
    }
}
