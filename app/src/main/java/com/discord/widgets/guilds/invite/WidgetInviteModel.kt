package com.discord.widgets.guilds.invite

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelInvite
import com.discord.models.user.User

data class WidgetInviteModel(
    val targetChannel: Channel?,
    val settings: ModelInvite.Settings?,
    val invite: ModelInvite?,
    val isGeneratingInvite: Boolean = false,
    val isValidInvite: Boolean = false,
    val invitableChannels: List<Channel>,
    val me: User,
    val dms: List<Channel>,
    val isInviteFromStore: Boolean = false,
)
