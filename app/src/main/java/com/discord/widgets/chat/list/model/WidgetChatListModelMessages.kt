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
import com.discord.stores.UserSettings
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.message.MessageUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.time.ClockFactory
import com.discord.widgets.botuikit.BotComponentExperiments
import com.discord.widgets.botuikit.ComponentChatListState
import com.discord.widgets.botuikit.ComponentStateMapper
import com.discord.widgets.chat.list.entries.BlockedMessagesEntry
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EphemeralMessageEntry
import com.discord.widgets.chat.list.entries.InviteEmbedResolver
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.NewMessagesEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.ThreadStarterDividerEntry
import com.discord.widgets.chat.list.entries.TimestampEntry
import com.discord.widgets.chat.list.entries.UploadProgressEntry
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

        @JvmStatic
        fun get(channel: Channel): Observable<WidgetChatListModelMessages> {
            val stores = StoreStream
            val first = Observable.combineLatest(
                MessagesWithMetadata.get(channel),
                stores.getChannels().observeChannel(channel.parentId),
                stores.getUserRelationships().observeBlocked().map { it.keys },
                stores.getUserRelationships().observeIgnoredUserIds(),
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
                observeUncachedUserUpdates(),
                ::ThirdInputs,
            )
            return Observable.combineLatest(first, second, third) { a, b, c ->
                build(
                    channel = channel,
                    metadata = a.metadata,
                    parentChannel = a.parentChannel,
                    blockedAuthorIds = a.blockedAuthorIds,
                    ignoredAuthorIds = a.ignoredAuthorIds,
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
                    userUpdates = c.userUpdates,
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
                observeUncachedUserUpdates(),
                ::SingleSecondInputs,
            )
            return Observable.combineLatest(first, second) { a, b ->
                val message = a.message ?: return@combineLatest emptyList()
                getMessageItems(
                    channel, a.members, a.roles, emptySet(), emptySet(), null, null, message,
                    a.metadata.messageState[messageId], a.metadata.messageReplyState, false, false,
                    null, a.animateEmojis, b.autoPlayGifs, b.renderEmbeds, b.meUserId, false,
                    emptyMap(), b.inviteResolver, false, b.userUpdates,
                ).asReversed()
            }
        }

        internal fun build(
            channel: Channel,
            metadata: MessagesWithMetadata,
            parentChannel: Channel? = null,
            blockedAuthorIds: Set<Long> = emptySet(),
            ignoredAuthorIds: Set<Long> = emptySet(),
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
            userUpdates: UserUpdates = UserUpdates(),
        ): WidgetChatListModelMessages {
            val items = Items(metadata.messages.size)
            var nextDayTimestamp = 0L
            var blockedCount = 0
            var blockedMessage: Message? = null
            var blockedChunkExpanded = false
            var blockedRunIsIgnored = false
            var groupStart: Message? = null
            var groupStartBlocked = false
            var separatorAdded = false

            metadata.messages.forEachIndexed { index, message ->
                val timestamp = message.timestamp?.dateTimeMillis ?: 0L
                if (willAddTimestamp(timestamp, nextDayTimestamp)) {
                    blockedCount =
                        addBlockedMessage(items, blockedMessage, blockedCount, blockedChunkExpanded, blockedRunIsIgnored)
                }
                nextDayTimestamp = tryAddTimestamp(items, message.id, timestamp, nextDayTimestamp)
                val isMostRecent = index == metadata.messages.lastIndex
                val blockedAuthor = if (message.type == 21) {
                    getThreadStarterMessageAndChannel(parentChannel, channel, message, metadata).first.author?.id
                } else message.author?.id
                // Blocking wins over ignoring, matching the reference: a blocked author is never
                // reported as ignored, so the two runs can never overlap.
                val authorBlocked = blockedAuthor != null && blockedAuthor in blockedAuthorIds
                val authorIgnored = !authorBlocked && blockedAuthor != null && blockedAuthor in ignoredAuthorIds
                val isBlocked = authorBlocked || authorIgnored
                // A run only ever holds one kind, so a change of kind closes the previous run.
                if (isBlocked && blockedCount > 0 && authorIgnored != blockedRunIsIgnored) {
                    blockedCount =
                        addBlockedMessage(items, blockedMessage, blockedCount, blockedChunkExpanded, blockedRunIsIgnored)
                }
                if (isBlocked) {
                    blockedCount++
                    if (blockedCount == 1) {
                        blockedChunkExpanded = message.id in expandedBlockedMessageIds
                        blockedMessage = message
                        blockedRunIsIgnored = authorIgnored
                    }
                }
                if (!isBlocked || isMostRecent) {
                    val collapsedRowAdded = blockedCount > 0 && !blockedChunkExpanded
                    blockedCount =
                        addBlockedMessage(items, blockedMessage, blockedCount, blockedChunkExpanded, blockedRunIsIgnored)
                    // A collapsed run renders as its own row, so the messages either side of it are
                    // no longer adjacent and must not be grouped together.
                    if (collapsedRowAdded) groupStart = null
                }
                if (!isBlocked && blockedChunkExpanded) blockedChunkExpanded = false

                if (!isBlocked || blockedChunkExpanded) {
                    val concatenate = shouldConcatMessage(
                        channel, parentChannel, groupStart, message, groupStartBlocked, isBlocked,
                    )
                    if (!concatenate) {
                        groupStart = message
                        groupStartBlocked = isBlocked
                    }
                    if (message.type == 21) {
                        items.addItems(
                            getThreadStarterMessageItems(
                                parentChannel, channel, guildMembers, guildRoles, blockedAuthorIds, ignoredAuthorIds,
                                message, metadata, animateEmojis, autoPlayGifs, renderEmbeds, meUserId,
                                componentStoreState, inviteResolver, userUpdates,
                            ),
                        )
                    } else {
                        items.addItems(
                            getMessageItems(
                                channel, guildMembers, guildRoles, blockedAuthorIds, ignoredAuthorIds,
                                metadata.messageThreads[message.id],
                                metadata.threadCountsAndLatestMessages[message.id],
                                message, metadata.messageState[message.id], metadata.messageReplyState,
                                blockedChunkExpanded, concatenate, permissionsForChannel, animateEmojis,
                                autoPlayGifs, renderEmbeds, meUserId, true, componentStoreState,
                                inviteResolver, false, userUpdates,
                            ),
                        )
                    }
                }
                if (!separatorAdded) {
                    separatorAdded = tryAddNewMessagesSeparator(
                        items, newMessagesMarkerMessageId, isMostRecent, message.id, channel,
                    )
                }
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
            blockedAuthorIds: Set<Long>,
            ignoredAuthorIds: Set<Long>,
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
            userUpdates: UserUpdates = UserUpdates(),
        ): List<ChatListEntry> {
            if (message.type == 38 && !PermissionUtils.can(Permission.CREATE_INSTANT_INVITE, permissionsForChannel)) return emptyList()

            val rows = ArrayList<ChatListEntry>()
            val replyState = message.messageReference?.messageId?.let(repliedMessages::get)
            val replyData = replyState?.let { state ->
                val entry = (state as? StoreMessageReplies.MessageState.Loaded)?.message?.let { replied ->
                    getMessageItems(
                        channel, guildMembers, guildRoles, blockedAuthorIds, ignoredAuthorIds, thread,
                        threadEmbedMetadata, replied, messageState, repliedMessages, blockedChunkExpanded,
                        false, null, animateEmojis, autoPlayGifs, renderEmbeds, meUserId, false,
                        emptyMap(), inviteResolver, false,
                        userUpdates,
                    ).firstOrNull() as? MessageEntry
                }
                val repliedAuthorId =
                    (state as? StoreMessageReplies.MessageState.Loaded)?.message?.author?.id
                MessageEntry.ReplyData(
                    state,
                    entry,
                    isRepliedUserBlocked = repliedAuthorId != null && repliedAuthorId in blockedAuthorIds,
                    isRepliedUserIgnored = repliedAuthorId != null &&
                        repliedAuthorId !in blockedAuthorIds && repliedAuthorId in ignoredAuthorIds,
                )
            }
            val author = guildMembers[message.author?.id]
            val firstMention = guildMembers[message.mentions?.firstOrNull()?.id]
            val roles = guildRoles.takeIf { RoleUtils.containsRoleMentions(message.content) }
            val names = MessageUtils.getNickOrUsernames(message, channel, guildMembers, channel.nicks).toMutableMap()
            if (FeatureFlags.fetchUncachedUsersEnabled()) {
                mergeFetchedMentionNames(
                    names,
                    message.content.orEmpty(),
                    userUpdates.users,
                    userUpdates.fetchingUserIds,
                )
            }
            val interactionAuthor = guildMembers[message.interaction?.user?.id]
            val parentChannel = if (channel.type in setOf(
                    Channel.ANNOUNCEMENT_THREAD,
                    Channel.PUBLIC_THREAD,
                    Channel.PRIVATE_THREAD,
                )
            ) StoreStream.getChannels().getChannel(channel.parentId) else null
            val isForumPostFirstMessage = channel.isGuildForumPost(parentChannel) && channel.id == message.id
            val isForumPostAuthor = channel.isGuildForumPost(parentChannel) &&
                channel.ownerId == message.author?.id
            val embedRows = ChatListEntry.createEmbedEntries(
                message, messageState, blockedChunkExpanded, animateEmojis, autoPlayGifs, renderEmbeds,
                isThreadStarterMessage, channel, author, roles, names,
            )
            val componentRows =
                if (showBotComponents && !message.components.isNullOrEmpty()) {
                    ChatListEntry.createBotComponentEntries(
                        message,
                        channel.guildId,
                        ComponentStateMapper.processComponentsToMessageComponents(
                            message.components.orEmpty(),
                            componentStoreState[message.id],
                            BotComponentExperiments,
                            animateEmojis,
                        ),
                        messageState,
                        channel,
                        guildMembers,
                        guildRoles,
                    )
                } else {
                    emptyList()
                }
            val inRow = embedRows.filter { it is AttachmentEntry || it is EmbedEntry } + componentRows
            rows += MessageEntry(
                message, messageState, author, firstMention, roles, names, shouldConcat,
                blockedChunkExpanded, animateEmojis, autoPlayGifs, inRow, replyData, interactionAuthor,
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
            rows += embedRows.filterNot { it is AttachmentEntry || it is EmbedEntry }
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
            if (UserSettings.renderReactions.get() && message.getReactionsMap().isNotEmpty()) {
                val canAdd = channel.threadMetadata?.archived != true
                rows += ReactionsEntry(
                    message,
                    canAdd,
                    canAdd && PermissionUtils.can(Permission.ADD_REACTIONS, permissionsForChannel),
                    animateEmojis,
                )
            }
            if (message.hasLocalUploads && !message.isFailed()) rows += UploadProgressEntry(message.nonce.orEmpty(), message.channelId, message)
            if (message.hasThread() && thread != null) {
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

        internal fun shouldConcatMessage(
            channel: Channel?,
            parentChannel: Channel?,
            groupStart: Message?,
            message: Message,
            groupStartBlocked: Boolean = false,
            messageBlocked: Boolean = false,
        ): Boolean = !MessageGrouping.isNewMessageGroup(
            channel, parentChannel, groupStart, message, groupStartBlocked, messageBlocked,
        )

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

        private fun addBlockedMessage(
            items: Items,
            message: Message?,
            count: Int,
            expanded: Boolean,
            isIgnored: Boolean,
        ): Int {
            if (count > 0) items.addItem(BlockedMessagesEntry(requireNotNull(message), count, expanded, isIgnored))
            return 0
        }

        private fun getThreadStarterMessageItems(
            parentChannel: Channel?,
            channel: Channel,
            guildMembers: Map<Long, GuildMember>,
            guildRoles: Map<Long, GuildRole>,
            blockedAuthorIds: Set<Long>,
            ignoredAuthorIds: Set<Long>,
            message: Message,
            metadata: MessagesWithMetadata,
            animateEmojis: Boolean,
            autoPlayGifs: Boolean,
            renderEmbeds: Boolean,
            meUserId: Long,
            componentStoreState: Map<Long, ComponentChatListState.ComponentStoreState>,
            inviteResolver: InviteEmbedResolver,
            userUpdates: UserUpdates,
        ): List<ChatListEntry> {
            val (starter, starterChannel) = getThreadStarterMessageAndChannel(parentChannel, channel, message, metadata)
            return getMessageItems(
                starterChannel, guildMembers, guildRoles, blockedAuthorIds, ignoredAuthorIds, null, null, starter,
                metadata.messageState[starter.id], metadata.parentChannelMessageReplyState, false, false,
                null, animateEmojis, autoPlayGifs, renderEmbeds, meUserId, true,
                componentStoreState, inviteResolver, true, userUpdates,
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

        private fun observeUncachedUserUpdates(): Observable<UserUpdates> =
            if (FeatureFlags.fetchUncachedUsersEnabled()) Observable.combineLatest(
                StoreStream.getUsers().observeAllUsers(),
                StoreStream.getUsers().observeFetchingUserIds(),
                ::UserUpdates,
            ) else Observable.just(UserUpdates(emptyMap(), emptySet()))

        internal class Items(size: Int) {
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
            val blockedAuthorIds: Set<Long>,
            val ignoredAuthorIds: Set<Long>,
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
            val userUpdates: UserUpdates,
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
            val userUpdates: UserUpdates,
        )

        internal data class UserUpdates(
            val users: Map<Long, com.discord.models.user.User> = emptyMap(),
            val fetchingUserIds: Set<Long> = emptySet(),
        )

        internal fun mergeFetchedMentionNames(
            names: MutableMap<Long, String>,
            content: String,
            users: Map<Long, com.discord.models.user.User>,
            fetchingUserIds: Set<Long>,
        ) {
            USER_MENTION.findAll(content).forEach { match ->
                val userId = match.groupValues[1].toLongOrNull() ?: return@forEach
                val user = users[userId]
                if (user != null) names[userId] = user.globalName ?: user.username
                else if (userId in fetchingUserIds) names[userId] = FETCHING_USER
            }
        }

        private val USER_MENTION = Regex("<@!?(\\d+)>")
        private const val FETCHING_USER = "fetching"
    }
}
