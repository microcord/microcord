package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isPrivate
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.api.stageinstance.StageInstancePrivacyLevel
import com.discord.api.utcdatetime.UtcDateTime
import com.discord.models.domain.ModelInvite
import com.discord.models.domain.NonceGenerator
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreInstantInvites
import com.discord.stores.StoreMessageReplies
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreStream
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.botuikit.BotComponentExperiments
import com.discord.widgets.botuikit.ComponentChatListState
import com.discord.widgets.botuikit.ComponentStateMapper
import com.discord.widgets.chat.list.ViewThreadsFeatureFlag
import com.discord.widgets.chat.list.entries.BlockedMessagesEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EphemeralMessageEntry
import com.discord.widgets.chat.list.entries.InviteEmbedResolver
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.NewMessagesEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.ThreadStarterDividerEntry
import com.discord.widgets.chat.list.entries.TimestampEntry
import com.discord.widgets.chat.list.entries.UploadProgressEntry
import com.discord.widgets.guild_role_subscriptions.GuildRoleSubscriptionsFeatureFlag
import java.util.Calendar
import rx.Observable

/** The message-derived portion of a live chat list, before its top row and bottom spacer are added. */
data class WidgetChatListModelMessages(
    val items: List<ChatListEntry>,
    val oldestMessageId: Long,
    val newestKnownMessageId: Long,
    val guildMembers: Map<Long, GuildMember>?,
    val newMessagesMarkerMessageId: Long,
    val newestSentByUserMessageId: Long?,
) {
    data class MessagesWithMetadata(
        val messages: List<Message>,
        val messageState: Map<Long, StoreMessageState.State>,
        val messageThreads: Map<Long, Channel>,
        val threadCountsAndLatestMessages: Map<Long, StoreThreadMessages.ThreadState>,
        val messageReplyState: Map<Long, StoreMessageReplies.MessageState>,
        val parentChannelMessageReplyState: Map<Long, StoreMessageReplies.MessageState>,
    ) {
        companion object {
            @JvmStatic
            fun get(messages: List<Message>): Observable<MessagesWithMetadata> {
                val stores = StoreStream
                return Observable.combineLatest(
                    stores.getMessageState().getMessageState(),
                    stores.getChannels().observeThreadsFromMessages(messages.map(Message::id)),
                    stores.getThreadMessages().observeThreadCountAndLatestMessage(),
                ) { states, threads, threadStates ->
                    MessagesWithMetadata(messages, states, threads, threadStates, emptyMap(), emptyMap())
                }
            }

            @JvmStatic
            fun get(channel: Channel): Observable<MessagesWithMetadata> =
                StoreStream.getMessages().observeMessagesForChannel(channel.id).switchMap { messages ->
                    Observable.combineLatest(
                        Observable.combineLatest(
                            Observable.just(messages),
                            StoreStream.getMessageState().getMessageState(),
                            StoreStream.getChannels().observeThreadsFromMessages(messages.map(Message::id)),
                            StoreStream.getThreadMessages().observeThreadCountAndLatestMessage(),
                            StoreStream.getMessageReplies().observeMessageReferencesForChannel(channel.id),
                        ) { source, states, threads, threadStates, replies ->
                            PartialMetadata(source, states, threads, threadStates, replies)
                        },
                        StoreStream.getMessageReplies().observeMessageReferencesForChannel(channel.parentId),
                    ) { partial: PartialMetadata, parentReplies ->
                        MessagesWithMetadata(
                            partial.messages,
                            partial.messageState,
                            partial.messageThreads,
                            partial.threadStates,
                            partial.replies,
                            parentReplies,
                        )
                    }
                }

            private data class PartialMetadata(
                val messages: List<Message>,
                val messageState: Map<Long, StoreMessageState.State>,
                val messageThreads: Map<Long, Channel>,
                val threadStates: Map<Long, StoreThreadMessages.ThreadState>,
                val replies: Map<Long, StoreMessageReplies.MessageState>,
            )
        }
    }

    companion object {
        private const val MAX_CONCAT_COUNT = 5
        private const val CONCAT_WINDOW_MS = 420_000L

        @JvmStatic
        fun get(channel: Channel): Observable<WidgetChatListModelMessages> {
            val stores = StoreStream
            val first = Observable.combineLatest(
                MessagesWithMetadata.get(channel),
                stores.getChannels().observeChannel(channel.parentId),
                stores.getUserRelationships().observeForType(2),
                stores.getChat().observeExpandedBlockedMessageIds(),
                stores.getGuilds().observeGuildMembers(channel.guildId),
                ::FirstInputs,
            )
            val permissions = if (channel.isPrivate()) Observable.just<Long?>(null)
            else stores.getPermissions().observePermissionsForChannel(channel.id)
            val second = Observable.combineLatest(
                stores.getReadStates().getUnreadMarker(channel.id).map { it.marker.messageId ?: 0L },
                stores.getGuilds().observeRoles(channel.guildId),
                permissions,
                stores.getUserSettings().observeIsAnimatedEmojisEnabled(false),
                stores.getUserSettings().observeIsAutoPlayGifsEnabled(false),
                ::SecondInputs,
            )
            val third = Observable.combineLatest(
                stores.getUserSettings().observeIsRenderEmbedsEnabled(),
                stores.getUsers().observeMeId(),
                ComponentChatListState.observeChatListComponentState(),
                observeInviteResolver(),
                GuildRoleSubscriptionsFeatureFlag()
                    .observeCanGuildSeePurchaseFeedbackLoopSystemMessages(channel.guildId),
                ::ThirdInputs,
            )
            return Observable.combineLatest(first, second, third) { a, b, c ->
                build(
                    channel = channel,
                    metadata = a.metadata,
                    parentChannel = a.parentChannel,
                    blockedRelationships = a.blockedRelationships,
                    expandedBlockedMessageIds = a.expandedBlockedMessageIds,
                    guildMembers = a.guildMembers,
                    newMessagesMarkerMessageId = b.unreadMarker,
                    guildRoles = b.guildRoles,
                    permissionsForChannel = b.permissions,
                    animateEmojis = b.animateEmojis,
                    autoPlayGifs = b.autoPlayGifs,
                    renderEmbeds = c.renderEmbeds,
                    meUserId = c.meUserId,
                    componentStoreState = c.components,
                    inviteResolver = c.inviteResolver,
                    showRoleSubscriptionPurchaseSystemMessage = c.showRoleSubscriptionMessages,
                )
            }
        }

        @JvmStatic
        fun getSingleMessage(channel: Channel, messageId: Long): Observable<List<ChatListEntry>> {
            val stores = StoreStream
            val first = Observable.combineLatest(
                stores.getMessages().observeMessagesForChannel(channel.id, messageId),
                MessagesWithMetadata.get(channel),
                stores.getGuilds().observeGuildMembers(channel.guildId),
                stores.getGuilds().observeRoles(channel.guildId),
                stores.getUserSettings().observeIsAnimatedEmojisEnabled(false),
                ::SingleFirstInputs,
            )
            val second = Observable.combineLatest(
                stores.getUserSettings().observeIsAutoPlayGifsEnabled(false),
                stores.getUsers().observeMeId(),
                stores.getUserSettings().observeIsRenderEmbedsEnabled(),
                observeInviteResolver(),
                GuildRoleSubscriptionsFeatureFlag()
                    .observeCanGuildSeePurchaseFeedbackLoopSystemMessages(channel.guildId),
                ::SingleSecondInputs,
            )
            return Observable.combineLatest(first, second) { a, b ->
                val message = a.message ?: return@combineLatest emptyList()
                getMessageItems(
                    channel, a.members, a.roles, emptyMap(), null, null, message,
                    a.metadata.messageState[messageId], a.metadata.messageReplyState, false, false,
                    null, a.animateEmojis, b.autoPlayGifs, b.renderEmbeds, b.meUserId, false,
                    emptyMap(), b.inviteResolver, false, b.showRoleSubscriptionMessages,
                ).asReversed()
            }
        }

        internal fun build(
            channel: Channel,
            metadata: MessagesWithMetadata,
            parentChannel: Channel? = null,
            blockedRelationships: Map<Long, Int> = emptyMap(),
            expandedBlockedMessageIds: List<Long> = emptyList(),
            guildMembers: Map<Long, GuildMember> = emptyMap(),
            newMessagesMarkerMessageId: Long = 0L,
            guildRoles: Map<Long, GuildRole> = emptyMap(),
            permissionsForChannel: Long? = null,
            animateEmojis: Boolean = true,
            autoPlayGifs: Boolean = true,
            renderEmbeds: Boolean = true,
            meUserId: Long = 0L,
            componentStoreState: Map<Long, ComponentChatListState.ComponentStoreState> = emptyMap(),
            inviteResolver: InviteEmbedResolver,
            showRoleSubscriptionPurchaseSystemMessage: Boolean = false,
        ): WidgetChatListModelMessages {
            val items = Items(metadata.messages.size)
            var nextDayTimestamp = 0L
            var blockedCount = 0
            var blockedMessage: Message? = null
            var blockedChunkExpanded = false
            var previousMessage: Message? = null
            var separatorAdded = false

            metadata.messages.forEachIndexed { index, message ->
                val timestamp = message.timestamp?.dateTimeMillis ?: 0L
                if (willAddTimestamp(timestamp, nextDayTimestamp)) {
                    blockedCount = addBlockedMessage(items, blockedMessage, blockedCount, blockedChunkExpanded)
                }
                nextDayTimestamp = tryAddTimestamp(items, message.id, timestamp, nextDayTimestamp)
                val isMostRecent = index == metadata.messages.lastIndex
                val blockedAuthor = if (message.type == 21) {
                    getThreadStarterMessageAndChannel(parentChannel, channel, message, metadata).first.author?.id
                } else message.author?.id
                val isBlocked = blockedAuthor?.let(blockedRelationships::containsKey) == true
                if (isBlocked) {
                    blockedCount++
                    if (blockedCount == 1) {
                        blockedChunkExpanded = message.id in expandedBlockedMessageIds
                        blockedMessage = message
                    }
                }
                if (!isBlocked || isMostRecent) {
                    blockedCount = addBlockedMessage(items, blockedMessage, blockedCount, blockedChunkExpanded)
                }
                if (!isBlocked && blockedChunkExpanded) blockedChunkExpanded = false

                if (!isBlocked || blockedChunkExpanded) {
                    val concatenate = shouldConcatMessage(items, message, previousMessage)
                    items.concatCount = if (concatenate) items.concatCount + 1 else 0
                    if (message.type == 21) {
                        items.addItems(
                            getThreadStarterMessageItems(
                                parentChannel, channel, guildMembers, guildRoles, blockedRelationships,
                                message, metadata, animateEmojis, autoPlayGifs, renderEmbeds, meUserId,
                                componentStoreState, inviteResolver,
                            ),
                        )
                    } else {
                        items.addItems(
                            getMessageItems(
                                channel, guildMembers, guildRoles, blockedRelationships,
                                metadata.messageThreads[message.id],
                                metadata.threadCountsAndLatestMessages[message.id],
                                message, metadata.messageState[message.id], metadata.messageReplyState,
                                blockedChunkExpanded, concatenate, permissionsForChannel, animateEmojis,
                                autoPlayGifs, renderEmbeds, meUserId, true, componentStoreState,
                                inviteResolver, false, showRoleSubscriptionPurchaseSystemMessage,
                            ),
                        )
                    }
                }
                if (!separatorAdded) {
                    separatorAdded = tryAddNewMessagesSeparator(
                        items, newMessagesMarkerMessageId, isMostRecent, message.id, channel,
                    )
                }
                previousMessage = message
            }

            items.items.reverse()
            return WidgetChatListModelMessages(
                items = items.items,
                oldestMessageId = metadata.messages.firstOrNull()?.id ?: 0L,
                newestKnownMessageId = metadata.messages.lastOrNull()?.id ?: 0L,
                guildMembers = guildMembers,
                newMessagesMarkerMessageId = newMessagesMarkerMessageId,
                newestSentByUserMessageId = metadata.messages.firstOrNull { it.author?.id == meUserId }?.id,
            )
        }

        internal fun getMessageItems(
            channel: Channel,
            guildMembers: Map<Long, GuildMember>,
            guildRoles: Map<Long, GuildRole>,
            blockedRelationships: Map<Long, Int>,
            thread: Channel?,
            threadEmbedMetadata: StoreThreadMessages.ThreadState?,
            message: Message,
            messageState: StoreMessageState.State?,
            repliedMessages: Map<Long, StoreMessageReplies.MessageState>,
            blockedChunkExpanded: Boolean,
            shouldConcat: Boolean,
            permissionsForChannel: Long?,
            animateEmojis: Boolean,
            autoPlayGifs: Boolean,
            renderEmbeds: Boolean,
            meUserId: Long,
            showBotComponents: Boolean,
            componentStoreState: Map<Long, ComponentChatListState.ComponentStoreState>,
            inviteResolver: InviteEmbedResolver,
            isThreadStarterMessage: Boolean = false,
            showRoleSubscriptionPurchaseSystemMessage: Boolean = false,
        ): List<ChatListEntry> {
            if (message.type == 38 && !PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, permissionsForChannel)) return emptyList()
            if (message.type == 25 && !showRoleSubscriptionPurchaseSystemMessage) return emptyList()

            val rows = ArrayList<ChatListEntry>()
            val replyState = message.messageReference?.messageId?.let(repliedMessages::get)
            val replyData = replyState?.let { state ->
                val entry = (state as? StoreMessageReplies.MessageState.Loaded)?.message?.let { replied ->
                    getMessageItems(
                        channel, guildMembers, guildRoles, blockedRelationships, thread,
                        threadEmbedMetadata, replied, messageState, repliedMessages, blockedChunkExpanded,
                        false, null, animateEmojis, autoPlayGifs, renderEmbeds, meUserId, false,
                        emptyMap(), inviteResolver, false, showRoleSubscriptionPurchaseSystemMessage,
                    ).firstOrNull() as? MessageEntry
                }
                MessageEntry.ReplyData(
                    state,
                    entry,
                    (state as? StoreMessageReplies.MessageState.Loaded)?.message?.author?.id
                        ?.let(blockedRelationships::containsKey) == true,
                )
            }
            val author = guildMembers[message.author?.id]
            val firstMention = guildMembers[message.mentions?.firstOrNull()?.id]
            val roles = guildRoles.takeIf { RoleUtils.containsRoleMentions(message.content) }
            val names = MessageUtils.getNickOrUsernames(message, channel, guildMembers, channel.nicks)
            val interactionAuthor = guildMembers[message.interaction?.user?.id]
            val parentChannel = if (channel.type in setOf(
                    Channel.ANNOUNCEMENT_THREAD,
                    Channel.PUBLIC_THREAD,
                    Channel.PRIVATE_THREAD,
                )
            ) StoreStream.getChannels().getChannel(channel.parentId) else null
            val isForumPostFirstMessage = channel.isGuildForumPost(parentChannel) && channel.id == message.id &&
                StoreStream.getExperiments().getGuildExperiment(
                    "2022-03_android_forum_channel_redesign", channel.guildId, false,
                )?.bucket == 1
            val isForumPostAuthor = channel.isGuildForumPost(parentChannel) && channel.ownerId == message.author?.id &&
                StoreStream.getExperiments().getGuildExperiment(
                    "2022-03_android_forum_channel_redesign", channel.guildId, false,
                )?.bucket == 1
            rows += MessageEntry(
                message, messageState, author, firstMention, roles, names, shouldConcat,
                blockedChunkExpanded, animateEmojis, replyData, interactionAuthor,
                isThreadStarterMessage, isForumPostFirstMessage, isForumPostAuthor,
                parseCtaData(message, meUserId, guildMembers, channel, permissionsForChannel),
                permissionsForChannel,
            )
            if (message.hasSnapshot()) {
                rows += ChatListRows.forwardedRows(
                    ChatListRows.State(
                        channel = channel,
                        guildMembers = guildMembers,
                        guildRoles = guildRoles,
                        messageStates = messageState?.let { mapOf(message.id to it) }.orEmpty(),
                        permissions = permissionsForChannel,
                        animateEmojis = animateEmojis,
                        autoPlayGifs = autoPlayGifs,
                        renderEmbeds = renderEmbeds,
                        inviteResolver = inviteResolver,
                    ),
                    message,
                )
            }
            val threadSpineStart = rows.size
            rows += ChatListEntry.createEmbedEntries(
                message, messageState, blockedChunkExpanded, animateEmojis, autoPlayGifs, renderEmbeds,
                isThreadStarterMessage, channel, author, roles, names,
            )
            rows += ChatListEntry.createStickerEntries(message)
            rows += ChatListEntry.createGameInviteEntries(message)
            rows += ChatListEntry.createActivityMessageEntries(message)
            rows += ChatListEntry.createSpotifyListenTogetherEntries(message)
            rows += ChatListEntry.createInviteEntries(message, inviteResolver)
            rows += ChatListEntry.createGuildTemplateEntries(message)
            rows += ChatListEntry.createGiftEntries(message)
            if (isThreadStarterMessage) {
                rows += ThreadStarterDividerEntry(channel.id, message.id)
                return rows
            }
            if (showBotComponents && !message.components.isNullOrEmpty()) {
                val components = ComponentStateMapper.processComponentsToMessageComponents(
                    message.components.orEmpty(),
                    componentStoreState[message.id],
                    BotComponentExperiments.get(StoreStream.getExperiments()),
                    animateEmojis,
                )
                rows += ChatListEntry.createBotComponentEntries(message, channel.guildId, components)
            }
            if (message.getReactionsMap().isNotEmpty()) {
                val canAdd = channel.threadMetadata?.archived != true
                rows += ReactionsEntry(
                    message,
                    canAdd,
                    canAdd && PermissionUtils.can(Permission.ADD_REACTIONS, permissionsForChannel),
                    animateEmojis,
                )
            }
            if (message.hasLocalUploads && !message.isFailed()) rows += UploadProgressEntry(message.nonce.orEmpty(), message.channelId)
            if (ViewThreadsFeatureFlag.isEnabled() && message.hasThread() && thread != null) {
                for (index in threadSpineStart until rows.size) rows[index].shouldShowThreadSpine = true
                rows += ChatListEntry.createThreadEmbedEntries(
                    channel, guildMembers, message, thread, threadEmbedMetadata, guildRoles,
                    animateEmojis, renderEmbeds,
                )
            }
            if (message.isEphemeralMessage() || (message.isInteraction() && message.isFailed())) {
                rows += EphemeralMessageEntry(message)
            }
            return rows
        }

        internal fun shouldConcatMessage(items: Items, message: Message, previous: Message?): Boolean {
            if (previous == null || previous.isSystemMessage() || message.hasThread() || previous.hasThread()) return false
            if (message.type != 0 && message.type != -1) return false
            if (items.listItemMostRecentlyAdded?.type !in setOf(0, 1, 21, 4)) return false
            if (previous.author?.id != message.author?.id) return false
            val delta = (message.timestamp?.dateTimeMillis ?: 0L) - (previous.timestamp?.dateTimeMillis ?: 0L)
            if (delta >= CONCAT_WINDOW_MS || previous.hasAttachments() || previous.hasEmbeds()) return false
            if (!previous.mentions.isNullOrEmpty() || message.hasAttachments() || message.hasEmbeds()) return false
            if (!message.mentions.isNullOrEmpty() || items.concatCount >= MAX_CONCAT_COUNT) return false
            return !message.isWebhook() || previous.author?.username == message.author?.username
        }

        internal fun tryAddNewMessagesSeparator(
            items: Items,
            markerMessageId: Long,
            messageMostRecent: Boolean,
            messageId: Long,
            channel: Channel,
        ): Boolean {
            val add = !messageMostRecent && markerMessageId > 0L &&
                MessageUtils.compareMessages(messageId, markerMessageId) == 0
            if (add) items.addItem(NewMessagesEntry(channel.id, messageId))
            return add
        }

        internal fun tryAddTimestamp(items: Items, messageId: Long, timestamp: Long, nextDayTimestamp: Long): Long {
            if (!willAddTimestamp(timestamp, nextDayTimestamp)) return nextDayTimestamp
            items.addItem(TimestampEntry(messageId, timestamp))
            return Calendar.getInstance().apply {
                timeInMillis = timestamp
                add(Calendar.DAY_OF_MONTH, 1)
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
            }.timeInMillis
        }

        internal fun willAddTimestamp(timestamp: Long, nextDayTimestamp: Long) = timestamp > nextDayTimestamp

        private fun addBlockedMessage(items: Items, message: Message?, count: Int, expanded: Boolean): Int {
            if (count > 0) items.addItem(BlockedMessagesEntry(requireNotNull(message), count, expanded))
            return 0
        }

        private fun getThreadStarterMessageItems(
            parentChannel: Channel?,
            channel: Channel,
            guildMembers: Map<Long, GuildMember>,
            guildRoles: Map<Long, GuildRole>,
            blockedRelationships: Map<Long, Int>,
            message: Message,
            metadata: MessagesWithMetadata,
            animateEmojis: Boolean,
            autoPlayGifs: Boolean,
            renderEmbeds: Boolean,
            meUserId: Long,
            componentStoreState: Map<Long, ComponentChatListState.ComponentStoreState>,
            inviteResolver: InviteEmbedResolver,
        ): List<ChatListEntry> {
            val (starter, starterChannel) = getThreadStarterMessageAndChannel(parentChannel, channel, message, metadata)
            return getMessageItems(
                starterChannel, guildMembers, guildRoles, blockedRelationships, null, null, starter,
                metadata.messageState[starter.id], metadata.parentChannelMessageReplyState, false, false,
                null, animateEmojis, autoPlayGifs, renderEmbeds, meUserId, true,
                componentStoreState, inviteResolver, true, false,
            )
        }

        private fun getThreadStarterMessageAndChannel(
            parentChannel: Channel?,
            channel: Channel,
            message: Message,
            metadata: MessagesWithMetadata,
        ): Pair<Message, Channel> {
            val state = message.messageReference?.messageId?.let(metadata.parentChannelMessageReplyState::get)
            if (parentChannel != null && state is StoreMessageReplies.MessageState.Loaded) {
                return state.message to parentChannel
            }
            val author = requireNotNull(message.author)
            val clock = ClockFactory.get()
            val nonce = NonceGenerator.computeNonce(clock)
            return Message(
                id = nonce,
                channelId = channel.id,
                author = author,
                content = "",
                timestamp = UtcDateTime(clock.currentTimeMillis()),
                nonce = nonce.toString(),
                type = -7,
            ) to channel
        }

        private fun parseCtaData(
            message: Message,
            meUserId: Long,
            guildMembers: Map<Long, GuildMember>,
            channel: Channel,
            permissions: Long?,
        ): MessageEntry.CtaData? {
            val suppressFlag = when (message.type) {
                7 -> 8
                25 -> 32
                else -> return null
            }
            val guild = StoreStream.getGuilds().getGuild(channel.guildId) ?: return null
            if ((guild.systemChannelFlags and suppressFlag) != 0) return null
            if (guildMembers[meUserId]?.pending != false) return null
            if (!PermissionUtils.can(Permission.SEND_MESSAGES, permissions)) return null
            if (message.author?.bot == true) return null
            val sticker = if (message.type == 7) MessageUtils.getWelcomeSticker(meUserId, message.id)
            else MessageUtils.getRoleSubscriptionPurchaseSticker(meUserId, message.id)
            return MessageEntry.CtaData(sticker, channel)
        }

        private fun observeInviteResolver(): Observable<InviteEmbedResolver> = Observable.combineLatest(
            StoreStream.getChannels().observeGuildAndPrivateChannels(),
            StoreStream.getInstantInvites().observeKnownInvites(),
            StoreStream.getStageInstances().observeStageInstances(),
            StoreStream.getRequestedStageChannels().observeRequestedStageChannels(),
        ) { channels, invites, stageInstances, requested ->
            object : InviteEmbedResolver {
                override fun resolveInvite(inviteCode: String, eventId: String?): ModelInvite? {
                    val key = ModelInvite.getInviteStoreKey(inviteCode, eventId?.toLongOrNull())
                    return (invites[key] as? StoreInstantInvites.InviteState.Resolved)?.invite
                }

                override fun resolveInviteForGuildScheduledEvent(inviteCode: String, eventId: String): ModelInvite? {
                    val key = ModelInvite.getInviteStoreKey(inviteCode, eventId.toLongOrNull())
                    return (invites[key] as? StoreInstantInvites.InviteState.Resolved)?.invite
                        ?.takeIf { it.getGuildScheduledEvent() != null }
                }

                override fun resolveInviteCodeForPublicStage(inviteCode: String): ModelInvite? {
                    val invite = (invites[inviteCode] as? StoreInstantInvites.InviteState.Resolved)?.invite ?: return null
                    val invitedChannel = invite.channel?.takeIf { it.type == Channel.GUILD_STAGE_VOICE } ?: return null
                    val live = stageInstances[invitedChannel.id]?.privacyLevel == StageInstancePrivacyLevel.PUBLIC ||
                        requested[invitedChannel.id]?.stageInstance?.instance?.privacyLevel == StageInstancePrivacyLevel.PUBLIC
                    if (live) return invite
                    if (channels[invitedChannel.id] == null &&
                        (requested[invitedChannel.id] == null || requested[invitedChannel.id]?.isError == true)
                    ) {
                        StoreStream.getRequestedStageChannels().enqueueStageChannelFetch(invitedChannel.id)
                    }
                    return null
                }
            } as InviteEmbedResolver
        }

        internal class Items(size: Int) {
            var concatCount: Int = 0
            val items: MutableList<ChatListEntry> = ArrayList(size + 10)
            var listItemMostRecentlyAdded: ChatListEntry? = null

            fun addItem(item: ChatListEntry) {
                items += item
                listItemMostRecentlyAdded = item
            }

            fun addItems(itemList: Collection<ChatListEntry>) {
                if (itemList.isEmpty()) return
                items += itemList
                listItemMostRecentlyAdded = itemList.last()
            }
        }

        private data class FirstInputs(
            val metadata: MessagesWithMetadata,
            val parentChannel: Channel?,
            val blockedRelationships: Map<Long, Int>,
            val expandedBlockedMessageIds: List<Long>,
            val guildMembers: Map<Long, GuildMember>,
        )

        private data class SecondInputs(
            val unreadMarker: Long,
            val guildRoles: Map<Long, GuildRole>,
            val permissions: Long?,
            val animateEmojis: Boolean,
            val autoPlayGifs: Boolean,
        )

        private data class ThirdInputs(
            val renderEmbeds: Boolean,
            val meUserId: Long,
            val components: Map<Long, ComponentChatListState.ComponentStoreState>,
            val inviteResolver: InviteEmbedResolver,
            val showRoleSubscriptionMessages: Boolean,
        )

        private data class SingleFirstInputs(
            val message: Message?,
            val metadata: MessagesWithMetadata,
            val members: Map<Long, GuildMember>,
            val roles: Map<Long, GuildRole>,
            val animateEmojis: Boolean,
        )

        private data class SingleSecondInputs(
            val autoPlayGifs: Boolean,
            val meUserId: Long,
            val renderEmbeds: Boolean,
            val inviteResolver: InviteEmbedResolver,
            val showRoleSubscriptionMessages: Boolean,
        )
    }
}
