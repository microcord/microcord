package com.discord.widgets.guilds.join

import com.discord.models.domain.ModelInvite
import com.discord.utilities.error.RestApiError

data class InviteArgs(
    val invite: ModelInvite,
    val javaClass: Class<*>,
    val location: String,
    val onInvitePostError: suspend (RestApiError?) -> Unit,
    val onInvitePostSuccess: suspend (ModelInvite) -> Unit,
    val onInviteFlowFinished: suspend (ModelInvite) -> Unit,
) : CaptchaArgs()
