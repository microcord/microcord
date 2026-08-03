@file:JvmName("GuildJoinHelperKt")

package com.discord.widgets.guilds.join

import android.content.Context
import com.discord.api.guild.Guild
import com.discord.restapi.RestAPIParams
import com.discord.utilities.error.RestApiError
import com.discord.utilities.rest.RestAPI
import com.discord.utilities.rx.appSubscribe
import com.discord.utilities.rx.restSubscribeOn
import com.discord.utilities.rx.ui
import rx.Subscription

fun joinGuild(
    context: Context,
    guildId: Long,
    isLurker: Boolean,
    sessionId: String? = null,
    directoryChannelId: Long? = null,
    source: String? = null,
    errorClass: Class<*>,
    subscriptionHandler: ((Subscription) -> Unit)? = null,
    errorHandler: ((RestApiError) -> Unit)? = null,
    onNext: (Guild) -> Unit,
) {
    val api = RestAPI.apiOrNull ?: return
    api.joinGuild(
        guildId,
        isLurker,
        sessionId,
        directoryChannelId,
        RestAPIParams.InviteCode(),
        source,
    )
        .restSubscribeOn(false)
        .ui()
        .appSubscribe(
            errorClass,
            context = context,
            subscriptionHandler = subscriptionHandler,
            errorHandler = errorHandler,
            onNext = onNext,
        )
}
