package com.discord.widgets.hubs

import com.discord.models.guild.Guild
import com.discord.stores.utilities.Default
import com.discord.stores.utilities.RestCallState

data class AddNameState(
    val nickname: String = "",
    val guild: Guild? = null,
    val changeNicknameAsync: RestCallState<Void?> = Default,
)
