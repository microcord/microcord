package com.discord.widgets.guilds.join

import com.discord.api.guild.Guild
import com.discord.utilities.error.RestApiError
import rx.Subscription

data class JoinArgs(
    val guildId: Long,
    val isLurker: Boolean,
    val sessionId: String?,
    val directoryChannelId: Long?,
    val errorClass: Class<*>,
    val subscriptionHandler: ((Subscription) -> Unit)? = null,
    val errorHandler: ((RestApiError) -> Unit)? = null,
    val onNext: (Guild) -> Unit,
) : CaptchaArgs() {
    override fun toString() =
        "JoinArgs(guildId=$guildId, isLurker=$isLurker, sessionId=<redacted>, " +
            "directoryChannelId=$directoryChannelId, errorClass=$errorClass)"
}
