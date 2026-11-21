package com.discord.widgets.chat.managereactions

import com.discord.api.message.reaction.MessageReaction
import com.discord.api.message.reaction.MessageReactionEmoji
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.stores.StoreChannels
import com.discord.stores.StoreGuilds
import com.discord.stores.StoreMessageReactions
import com.discord.stores.StoreStream
import com.discord.stores.updates.ObservationDeck
import com.discord.stores.updates.ObservationDeckProvider
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.rx.computationLatest
import rx.Observable
import rx.subjects.BehaviorSubject
import rx.subjects.SerializedSubject

class ManageReactionsModelProvider(
    val channelId: Long,
    val messageId: Long,
    initTargetedReactionKey: String? = null,
    private val storeChannels: StoreChannels = StoreStream.getChannels(),
    private val storeGuilds: StoreGuilds = StoreStream.getGuilds(),
    private val observationDeck: ObservationDeck = ObservationDeckProvider.get(),
) {
    private val targetedEmojiKeySubject: SerializedSubject<String?, String?> =
        BehaviorSubject.create(initTargetedReactionKey as String?).toSerialized()

    fun get(): Observable<ManageReactionsModel?> {
        val reactions = StoreStream.getMessages()
            .observeMessagesForChannel(channelId, messageId)
            .map { message ->
                val reactions = message?.getReactionsMap()?.values.orEmpty()
                // A tab per kind, super reactions first as the chips put them, each by its own count.
                reactions.filter { it.burstCount > 0 }.sortedByDescending { it.burstCount }.map { Tab(it, burst = true) } +
                    reactions.filter { it.normalCount > 0 }.sortedByDescending { it.normalCount }.map { Tab(it, burst = false) }
            }
        val guildMembers = observationDeck.connectRx(storeChannels, storeGuilds) {
            storeChannels.getChannel(channelId)?.guildId
                ?.let { storeGuilds.getMembers()[it] }
                .orEmpty()
        }

        return StoreStream.getUsers().observeMe().switchMap { me ->
            val canManageMessages = Observable.combineLatest(
                StoreStream.getPermissions().observePermissionsForChannel(channelId),
                StoreStream.getGuilds().observeFromChannelId(channelId),
            ) { permissions: Long?, guild: Guild? ->
                guild != null && PermissionUtils.canAndIsElevated(
                    Permission.MANAGE_MESSAGES,
                    permissions,
                    me.mfaEnabled,
                    guild.mfaLevel,
                )
            }

            Observable.combineLatest(
                canManageMessages,
                reactions,
                guildMembers,
            ) { canManage: Boolean, currentReactions: List<Tab>, members: Map<Long, GuildMember> ->
                Triple(canManage, currentReactions, members)
            }.switchMap { (canManage, currentReactions, members) ->
                if (currentReactions.isEmpty()) {
                    Observable.just<ManageReactionsModel?>(null)
                } else {
                    targetedEmojiKeySubject.distinctUntilChanged()
                        .map { key ->
                            // A chip's key without the kind opens that emoji's first tab.
                            currentReactions.firstOrNull { it.key == key }
                                ?: currentReactions.firstOrNull { it.reaction.emoji.reactionKey == key }
                                ?: currentReactions.first()
                        }
                        .switchMap { tab ->
                            getUsersForReaction(tab).map { results ->
                                createModel(currentReactions, results, tab, canManage, me.id, members)
                            }
                        }
                }
            }
        }.computationLatest().distinctUntilChanged()
    }

    fun onEmojiTargeted(emojiKey: String) {
        targetedEmojiKeySubject.onNext(emojiKey)
    }

    /** One tab of the sheet: an emoji's normal reactions, or with [burst] its super reactions. */
    data class Tab(val reaction: MessageReaction, val burst: Boolean) {
        val key: String get() = ManageReactionsEmojisAdapter.ReactionEmojiItem.keyOf(reaction.emoji, burst)
        val count: Int get() = if (burst) reaction.burstCount else reaction.normalCount
    }

    private fun getUsersForReaction(tab: Tab) =
        StoreStream.getMessageReactions().observeMessageReactions(channelId, messageId, tab.reaction.emoji, burst = tab.burst)

    private fun createModel(
        reactions: List<Tab>,
        results: StoreMessageReactions.EmojiResults,
        targeted: Tab,
        canManageMessages: Boolean,
        myId: Long,
        guildMembers: Map<Long, GuildMember>,
    ): ManageReactionsModel {
        val users: List<MGRecyclerDataPayload> = when (results) {
            is StoreMessageReactions.EmojiResults.Users -> results.users.values.map { user ->
                ManageReactionsResultsAdapter.ReactionUserItem(
                    user,
                    channelId,
                    messageId,
                    results.emoji,
                    canManageMessages || user.id == myId,
                    guildMembers[user.id],
                    targeted.burst,
                )
            }
            StoreMessageReactions.EmojiResults.Loading ->
                listOf(ManageReactionsResultsAdapter.LoadingItem())
            is StoreMessageReactions.EmojiResults.Failure -> listOf(
                ManageReactionsResultsAdapter.ErrorItem(
                    results.channelId,
                    results.messageId,
                    results.emoji,
                ),
            )
        }
        return ManageReactionsModel(
            reactions.map { ManageReactionsEmojisAdapter.ReactionEmojiItem(it.reaction, it.burst, it.count, it.key == targeted.key) },
            users,
        )
    }
}
