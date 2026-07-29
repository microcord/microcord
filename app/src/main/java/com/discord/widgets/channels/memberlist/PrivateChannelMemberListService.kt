package com.discord.widgets.channels.memberlist

import com.discord.api.channel.Channel
import com.discord.models.domain.ModelApplicationStream
import com.discord.models.presence.Presence
import com.discord.models.user.User
import com.discord.stores.StoreApplicationStreaming
import com.discord.stores.StoreStream
import com.discord.stores.StoreUser
import com.discord.stores.StoreUserPresence
import java.util.concurrent.TimeUnit
import rx.Observable

class PrivateChannelMemberListService(
    private val storeUser: StoreUser = StoreStream.getUsers(),
    private val storePresences: StoreUserPresence = StoreStream.getPresences(),
    private val storeApplicationStreaming: StoreApplicationStreaming = StoreStream.getApplicationStreaming(),
) {
    fun observeStateForGroupDm(channel: Channel): Observable<State> = Observable.merge(
        storeUser.observeMeId().take(1).timeout(5_000L, TimeUnit.MILLISECONDS),
        Observable.from(channel.recipients.orEmpty()).map { it.id },
    ).toList().flatMap { userIds ->
        Observable.combineLatest(
            storeUser.observeUsers(userIds),
            storePresences.observePresencesForUsers(userIds),
            storeApplicationStreaming.observeStreamsByUser(),
        ) { users, presences, applicationStreams ->
            State(channel, users, presences, applicationStreams)
        }
    }

    data class State(
        val channel: Channel,
        val users: Map<Long, User>,
        val presences: Map<Long, Presence>,
        val applicationStreams: Map<Long, ModelApplicationStream>,
    )
}
