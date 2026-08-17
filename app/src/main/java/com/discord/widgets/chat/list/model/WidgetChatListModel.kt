package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.channel.getDMRecipient
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isAnnouncementChannel
import com.discord.api.channel.isDirectory
import com.discord.api.channel.isDM
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.channel.isVoiceChannel
import com.discord.api.permission.Permission
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.domain.ModelUserRelationship
import com.discord.models.message.Message
import com.discord.stores.StoreChannelsSelected
import com.discord.stores.StoreMessagesLoader
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadDraft
import com.discord.utilities.permissions.PermissionUtils
import com.discord.widgets.chat.list.adapter.WidgetChatListAdapter
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ActivityMessageEntry
import com.discord.widgets.chat.list.entries.BotUiComponentEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.PollEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import com.discord.widgets.chat.list.entries.StickerGreetCompactEntry
import com.discord.widgets.chat.list.entries.StickerGreetEntry
import com.discord.widgets.chat.list.entries.ThreadDraftFormEntry
import com.discord.widgets.chat.list.entries.ThreadEmbedEntry
import rx.Observable

/**
 * Everything `WidgetChatList` renders: the row list plus the surrounding channel, guild and viewer
 * context the rows themselves do not carry.
 *
 * This is the *live chat* implementation of [WidgetChatListAdapter.Data]. The pinned-messages and
 * recent-mentions surfaces supply their own, which is why the adapter reads
 * [newestKnownMessageId] and [isLoadingMessages] through a cast: only the live surface can page,
 * so only the live surface knows whether there is anything newer to page towards.
 *
 * [isSpoilerClickAllowed] is always true here — tapping a spoiler in the real conversation reveals
 * it. The read-only surfaces override it to false.
 */
data class WidgetChatListModel(
    override val userId: Long,
    override val channelId: Long,
    override val guild: Guild?,
    override val guildId: Long,
    override val channelNames: Map<Long, String>,
    override val oldestMessageId: Long = 0L,
    override val list: List<ChatListEntry>,
    override val myRoleIds: Set<Long>,
    override val newMessagesMarkerMessageId: Long = 0L,
    val newestKnownMessageId: Long = 0L,
    val isLoadingMessages: Boolean,
) : WidgetChatListAdapter.Data {

    override val isSpoilerClickAllowed: Boolean = true

    enum class ChatListState {
        DETACHED,
        DETACHED_UNTOUCHED,
        ATTACHED;

        companion object {
            fun get(channelId: Long): Observable<ChatListState> =
                StoreStream.getMessages().observeIsDetached(channelId).switchMap { detached ->
                    if (detached) {
                        StoreStream.getMessagesLoader().getMessagesLoadedState(channelId)
                            .map(::from)
                            .distinctUntilChanged()
                    } else {
                        Observable.just(ATTACHED)
                    }
                }

            internal fun from(state: StoreMessagesLoader.ChannelLoadedState): ChatListState =
                if (state.isTouchedSinceLastJump) DETACHED else DETACHED_UNTOUCHED
        }
    }

    companion object {
        private const val MAX_MESSAGES_PER_CHANNEL = 25
        private const val WUMPUS_PACK_ID = 847199849233514549L
        private const val WUMPUS_WAVE_STICKER_ID = 749054660769218631L

        fun get(): Observable<WidgetChatListModel?> =
            StoreStream.getChannelsSelected().observeResolvedSelectedChannel().switchMap { selected ->
                when (selected) {
                    is StoreChannelsSelected.ResolvedSelectedChannel.Channel ->
                        if (selected.channel.isDirectory()) Observable.just(null)
                        else getChannel(selected.channel)
                    is StoreChannelsSelected.ResolvedSelectedChannel.ThreadDraft ->
                        getThreadDraft(selected.parentChannel, selected.starterMessageId)
                    else -> Observable.just(null)
                }
            }

        private fun getChannel(channel: Channel): Observable<WidgetChatListModel> {
            val stores = StoreStream
            val primary = Observable.combineLatest(
                WidgetChatListModelTop.get(channel),
                WidgetChatListModelMessages.get(channel),
                stores.getMessagesLoader().getMessagesLoadedState(channel.id).distinctUntilChanged(),
                stores.getChannels().observeNames(),
                stores.getUsers().observeMeId(),
                ::ChannelPrimary,
            )
            val secondary = Observable.combineLatest(
                stores.getGuilds().observeComputed(channel.guildId),
                stores.getGuilds().observeGuild(channel.guildId),
                stores.getUserRelationships().observe(),
                ChatListState.get(channel.id),
                stores.getPendingReplies().observePendingReply(channel.id).map { it?.originalMessage?.id },
                ::ChannelSecondary,
            )
            return Observable.combineLatest(primary, secondary) { first, second ->
                buildChannelModel(channel, first, second)
            }
        }

        internal fun buildChannelModel(
            channel: Channel,
            top: WidgetChatListModelTop,
            messages: WidgetChatListModelMessages,
            loadingState: StoreMessagesLoader.ChannelLoadedState,
            channelNames: Map<Long, String>,
            myId: Long,
            computedMembers: Map<Long, GuildMember>,
            guild: Guild?,
            relationships: Map<Long, Int>,
            chatListState: ChatListState,
            greetItem: ChatListEntry? = null,
            pendingReplyMessageId: Long? = null,
        ): WidgetChatListModel {
            val shouldGreet = shouldShowStickerGreet(loadingState, messages, channel, relationships)
            val items = ArrayList<ChatListEntry>(messages.items.size + 3)
            items += top.item
            items += messages.items.map { entry ->
                when {
                    entry is MessageEntry && entry.message.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is AttachmentEntry && entry.message.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is EmbedEntry && entry.message.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is ReactionsEntry && entry.message.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is BotUiComponentEntry && entry.message.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is ThreadEmbedEntry && entry.parentMessage.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is ActivityMessageEntry && entry.messageId == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is ForwardedMessageEntry && entry.parentMessage.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    entry is PollEntry && entry.message.id == pendingReplyMessageId ->
                        entry.copy(isPendingReply = true)
                    else -> entry
                }
            }
            if (chatListState == ChatListState.DETACHED) {
                items += LoadingEntry()
            } else {
                if (shouldGreet) (greetItem ?: getGreetMessageItem(messages, channel))?.let(items::add)
                items += SpacerEntry(channel.id)
            }
            return WidgetChatListModel(
                userId = myId,
                channelId = channel.id,
                guild = guild,
                guildId = channel.guildId,
                channelNames = channelNames,
                oldestMessageId = messages.oldestMessageId,
                list = items,
                myRoleIds = computedMembers[myId]?.getRoles()?.toHashSet().orEmpty(),
                newMessagesMarkerMessageId = messages.newMessagesMarkerMessageId,
                newestKnownMessageId = messages.newestKnownMessageId,
                isLoadingMessages = loadingState.isLoadingMessages,
            )
        }

        internal fun shouldShowStickerGreet(
            loadingState: StoreMessagesLoader.ChannelLoadedState,
            messages: WidgetChatListModelMessages,
            channel: Channel,
            relationships: Map<Long, Int>,
        ): Boolean {
            val recipientId = channel.getDMRecipient()?.id
            return loadingState.isOldestMessagesLoaded &&
                loadingState.isInitialMessagesLoaded &&
                loadingState.newestSentByUserMessageId == null &&
                messages.newestSentByUserMessageId == null &&
                messages.items.size < MAX_MESSAGES_PER_CHANNEL &&
                channel.isDM() &&
                !channel.isSystemDM() &&
                relationships[recipientId] != ModelUserRelationship.TYPE_BLOCKED
        }

        private fun getGreetMessageItem(
            messages: WidgetChatListModelMessages,
            channel: Channel,
        ): ChatListEntry? {
            val stickers = StoreStream.getStickers()
            if (stickers.getStickers()[WUMPUS_WAVE_STICKER_ID] == null) {
                stickers.fetchStickerPack(WUMPUS_PACK_ID)
            }
            val sticker = stickers.getStickers()[WUMPUS_WAVE_STICKER_ID] ?: return null
            val flags = StoreStream.getGuilds().getGuild(channel.guildId)?.systemChannelFlags ?: 0
            if (flags and 8 != 0) return null
            return if (messages.items.isEmpty()) {
                StickerGreetEntry(sticker, channel.id, channel.getDisplayName(), channel.type)
            } else if (messages.items.size < MAX_MESSAGES_PER_CHANNEL) {
                StickerGreetCompactEntry(sticker, channel.id, channel.getDisplayName(), channel.type)
            } else {
                null
            }
        }

        private fun getThreadDraft(
            parentChannel: Channel,
            parentMessageId: Long?,
        ): Observable<WidgetChatListModel> {
            val stores = StoreStream
            val messageAndItems = if (parentMessageId != null) {
                Observable.combineLatest(
                    stores.getMessages().observeMessagesForChannel(parentChannel.id, parentMessageId),
                    WidgetChatListModelMessages.getSingleMessage(parentChannel, parentMessageId),
                    ::Pair,
                )
            } else {
                Observable.just(Pair<Message?, List<ChatListEntry>>(null, emptyList()))
            }
            val primary = Observable.combineLatest(
                stores.getChannels().observeNames(),
                stores.getUsers().observeMeId(),
                stores.getGuilds().observeComputed(parentChannel.guildId),
                stores.getGuilds().observeGuild(parentChannel.guildId),
                stores.getThreadDraft().observeDraftState(),
                ::ThreadPrimary,
            )
            return Observable.combineLatest(
                primary,
                stores.getPermissions().observePermissionsForChannel(parentChannel.id),
                messageAndItems,
            ) { first, permissions, messagePair ->
                buildThreadDraftModel(parentChannel, parentMessageId, first, permissions, messagePair)
            }
        }

        private fun buildThreadDraftModel(
            parentChannel: Channel,
            parentMessageId: Long?,
            inputs: ThreadPrimary,
            permissions: Long?,
            messagePair: Pair<Message?, List<ChatListEntry>>,
        ): WidgetChatListModel {
            val items = ArrayList<ChatListEntry>(messagePair.second.size + 3)
            items += SpacerEntry(parentChannel.id)
            if (inputs.threadDraft.isSending) items += LoadingEntry()
            items += messagePair.second
            items += ThreadDraftFormEntry(
                parentChannel,
                parentMessageId,
                parentChannel.guildId,
                inputs.guild,
                parentChannel.defaultAutoArchiveDuration,
                inputs.threadDraft,
                canCreatePrivateThread(permissions, parentChannel, inputs.guild),
                canCreatePrivateThread(permissions, parentChannel, inputs.guild),
                canCreatePublicThread(permissions, parentChannel, messagePair.first, inputs.guild),
            )
            return WidgetChatListModel(
                inputs.myId,
                -3L,
                inputs.guild,
                parentChannel.guildId,
                inputs.channelNames,
                list = items,
                myRoleIds = inputs.computedMembers[inputs.myId]?.getRoles()?.toHashSet().orEmpty(),
                isLoadingMessages = false,
            )
        }

        private fun canCreatePrivateThread(
            permissions: Long?,
            channel: Channel,
            guild: Guild?,
        ): Boolean {
            guild ?: return false
            val permissionAllowed = PermissionUtils.can(Permission.CREATE_PRIVATE_THREADS, permissions)
            val channelAllowed = !channel.isThread() && !channel.isVoiceChannel() && !channel.isAnnouncementChannel()
            return permissionAllowed && channelAllowed
        }

        private fun canCreatePublicThread(
            permissions: Long?,
            channel: Channel,
            message: Message?,
            guild: Guild?,
        ): Boolean {
            guild ?: return false
            val permissionAllowed = PermissionUtils.can(Permission.CREATE_PUBLIC_THREADS, permissions) &&
                (message == null || PermissionUtils.can(Permission.MANAGE_MESSAGES, permissions))
            val channelAllowed = message?.isSystemMessage() != true && !channel.isThread() &&
                message?.hasThread() != true && !channel.isVoiceChannel()
            return permissionAllowed && channelAllowed
        }

        private data class ChannelPrimary(
            val top: WidgetChatListModelTop,
            val messages: WidgetChatListModelMessages,
            val loadingState: StoreMessagesLoader.ChannelLoadedState,
            val channelNames: Map<Long, String>,
            val myId: Long,
        )

        private data class ChannelSecondary(
            val computedMembers: Map<Long, GuildMember>,
            val guild: Guild?,
            val relationships: Map<Long, Int>,
            val chatListState: ChatListState,
            val pendingReplyMessageId: Long?,
        )

        private data class ThreadPrimary(
            val channelNames: Map<Long, String>,
            val myId: Long,
            val computedMembers: Map<Long, GuildMember>,
            val guild: Guild?,
            val threadDraft: StoreThreadDraft.ThreadDraftState,
        )

        private fun buildChannelModel(
            channel: Channel,
            first: ChannelPrimary,
            second: ChannelSecondary,
        ) = buildChannelModel(
            channel,
            first.top,
            first.messages,
            first.loadingState,
            first.channelNames,
            first.myId,
            second.computedMembers,
            second.guild,
            second.relationships,
            second.chatListState,
            pendingReplyMessageId = second.pendingReplyMessageId,
        )
    }
}
