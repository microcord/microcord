package com.discord.widgets.guildscheduledevent

import com.discord.api.guildscheduledevent.GuildScheduledEventStatus
import com.discord.restapi.RestAPIParams
import com.discord.utilities.rest.RestAPI
import rx.Observable

/**
 * Starting and ending an event are both a status PATCH; nothing else in the body changes.
 *
 * They live here rather than on a store because neither has any local state to keep: the gateway
 * pushes the resulting `GUILD_SCHEDULED_EVENT_UPDATE` back and `StoreGuildScheduledEvents` folds
 * it in.
 */
object GuildScheduledEventAPI {

    fun startEvent(guildId: Long, guildScheduledEventId: Long): Observable<Unit> =
        RestAPI.api.updateGuildScheduledEvent(
            guildId,
            guildScheduledEventId,
            RestAPIParams.UpdateGuildScheduledEventBody(status = GuildScheduledEventStatus.ACTIVE),
        )

    fun endEvent(guildId: Long, guildScheduledEventId: Long): Observable<Unit> =
        RestAPI.api.updateGuildScheduledEvent(
            guildId,
            guildScheduledEventId,
            RestAPIParams.UpdateGuildScheduledEventBody(status = GuildScheduledEventStatus.COMPLETED),
        )
}
