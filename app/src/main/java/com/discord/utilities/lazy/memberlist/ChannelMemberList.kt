package com.discord.utilities.lazy.memberlist

import com.discord.api.guildmember.GuildMember
import com.discord.models.domain.ModelGuildMemberListUpdate.Group

sealed class MemberListRow {
    data class GroupHeader(val group: Group, val name: String? = null) : MemberListRow()
    data class Member(val member: GuildMember) : MemberListRow()
}

data class ChannelMemberList(
    val listId: String,
    val groups: List<Group>,
    val groupIndices: Map<Int, String>,
    val onlineCount: Int,
    val memberCount: Int,
    val rows: List<MemberListRow?>,
)
