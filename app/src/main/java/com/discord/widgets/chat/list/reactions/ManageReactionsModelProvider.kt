package com.discord.widgets.chat.list.reactions

import com.discord.api.channel.Channel
import com.discord.api.channel.isArchivedThread
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.message.reaction.MessageReaction
import com.discord.models.guild.Guild
import com.discord.models.message.Message
import com.discord.models.user.MeUser
import com.discord.stores.StoreStream
import com.discord.utilities.permissions.ManageMessageContext
import com.discord.utilities.rx.computationLatest
import rx.Observable
import rx.subjects.BehaviorSubject

class ManageReactionsModelProvider(
    private val channelId: Long,
    private val messageId: Long,
    initialReactionKey: String? = null,
) {
    private val targetedEmojiKey = BehaviorSubject.create(initialReactionKey as String?).toSerialized()

    fun get(): Observable<ManageReactionsModel?> = Observable.combineLatest(
        StoreStream.getMessages().observeMessagesForChannel(channelId, messageId),
        StoreStream.getChannels().observeChannel(channelId),
        StoreStream.getPermissions().observePermissionsForChannel(channelId),
        StoreStream.getUsers().observeMe(),
        StoreStream.getGuilds().observeFromChannelId(channelId),
        ::Context,
    ).switchMap { context ->
        val reactions = context.message?.reactions.orEmpty()
        if (context.message == null || context.channel == null || reactions.isEmpty()) {
            Observable.just(null)
        } else {
            targetedEmojiKey.distinctUntilChanged().switchMap { requestedKey ->
                val selected = reactions.firstOrNull { it.emoji.reactionKey == requestedKey }
                    ?: reactions.first()
                StoreStream.getMessageReactions()
                    .observeMessageReactions(channelId, messageId, selected.emoji)
                    .map { results -> createModel(context, reactions, selected, results) }
            }
        }
    }.computationLatest().distinctUntilChanged()

    fun onEmojiTargeted(emojiKey: String) = targetedEmojiKey.onNext(emojiKey)

    private fun createModel(
        context: Context,
        reactions: List<MessageReaction>,
        selected: MessageReaction,
        results: com.discord.stores.StoreMessageReactions.EmojiResults,
    ): ManageReactionsModel {
        val message = requireNotNull(context.message)
        val channel = requireNotNull(context.channel)
        val manage = ManageMessageContext.from(
            message,
            context.permissions,
            context.me,
            context.guild?.mfaLevel,
            channel.isPrivate(),
            channel.isSystemDM(),
            channel.isArchivedThread(),
        )
        val members = StoreStream.getGuilds().getMembers()[channel.guildId].orEmpty()
        return ManageReactionsModel(
            reactions.map { ReactionEmojiItem(it, it.emoji == selected.emoji) },
            ResultItem.from(results, manage.canManageMessages, context.me.id, members),
        )
    }

    private data class Context(
        val message: Message?,
        val channel: Channel?,
        val permissions: Long?,
        val me: MeUser,
        val guild: Guild?,
    )
}
