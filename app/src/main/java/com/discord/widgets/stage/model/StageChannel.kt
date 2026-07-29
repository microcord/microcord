package com.discord.widgets.stage.model

import com.discord.api.channel.Channel
import com.discord.api.stageinstance.StageInstance
import com.discord.models.guild.UserGuildMember
import com.discord.models.user.User
import com.discord.widgets.stage.StageRoles

data class StageChannel(
    val channel: Channel,
    val participants: List<User>,
    val myRoles: StageRoles?,
    val speakerIds: Set<Long>,
    val speakers: List<UserGuildMember>,
    val audienceSize: Int,
    val stageInstance: StageInstance?,
    val canAccess: Boolean,
    val containsMe: Boolean,
) {
    val hasActiveStageInstance: Boolean
        get() = stageInstance != null
}
