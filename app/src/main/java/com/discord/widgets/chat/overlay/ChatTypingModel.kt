package com.discord.widgets.chat.overlay

import com.discord.api.guild.GuildVerificationLevel
import com.discord.models.member.GuildMember
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreSlowMode
import com.discord.stores.StoreStream
import com.discord.utilities.guilds.GuildVerificationLevelUtils
import rx.Observable

sealed class ChatTypingModel {
    object Hide : ChatTypingModel()

    data class Typing(
        val typingUsers: List<CharSequence>,
        val channelRateLimit: Int,
        val cooldownSecs: Int,
    ) : ChatTypingModel()

    companion object {
        fun get(): Observable<ChatTypingModel> = StoreStream.getChannelsSelected()
            .observeResolvedSelectedChannel()
            .switchMap { selected ->
                when (selected) {
                    is StoreChannelsSelected.ResolvedSelectedChannel.Channel -> forChannel(selected.channel)
                    is StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft -> forDraft(selected.parentChannel)
                    else -> Observable.just(Hide)
                }
            }
            .distinctUntilChanged()

        private fun forChannel(channel: com.discord.api.channel.Channel): Observable<ChatTypingModel> =
            GuildVerificationLevelUtils.observeVerificationLevelTriggered(channel.guildId)
                .switchMap { level ->
                    if (level > GuildVerificationLevel.NONE) {
                        Observable.just(Hide)
                    } else {
                        Observable.combineLatest(
                            typingUsers(channel),
                            StoreStream.getSlowMode()
                                .observeCooldownSecs(channel.id, StoreSlowMode.Type.MessageSend),
                        ) { users, cooldown -> Typing(users, channel.rateLimitPerUser, cooldown) }
                    }
                }

        private fun forDraft(channel: com.discord.api.channel.Channel): Observable<ChatTypingModel> =
            StoreStream.getSlowMode()
                .observeCooldownSecs(channel.id, StoreSlowMode.Type.ThreadCreate)
                .map { Typing(emptyList(), channel.rateLimitPerUser, it) }

        private fun typingUsers(channel: com.discord.api.channel.Channel): Observable<List<CharSequence>> =
            StoreStream.getUsersTyping().observeTypingUsers(channel.id).switchMap { userIds ->
                Observable.combineLatest(
                    StoreStream.getUsers().observeUsers(userIds),
                    StoreStream.getGuilds().observeComputed(channel.guildId, userIds),
                ) { users, members ->
                    users.values.map { user ->
                        GuildMember.getNickOrUsername(members[user.id], user) as CharSequence
                    }.take(4)
                }
            }.distinctUntilChanged()
    }
}
