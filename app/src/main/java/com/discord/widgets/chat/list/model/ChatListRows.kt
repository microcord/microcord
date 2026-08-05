package com.discord.widgets.chat.list.model

import com.discord.api.channel.Channel
import com.discord.api.channel.getDisplayName
import com.discord.api.channel.isGuildForumPost
import com.discord.api.channel.isPrivate
import com.discord.api.channel.isSystemDM
import com.discord.api.channel.isThread
import com.discord.api.permission.Permission
import com.discord.api.role.GuildRole
import com.discord.models.guild.Guild
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreMessagesLoader
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.chat.list.entries.AttachmentEntry
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EmbedEntry
import com.discord.widgets.chat.list.entries.EphemeralMessageEntry
import com.discord.widgets.chat.list.entries.ForwardedMessageEntry
import com.discord.widgets.chat.list.entries.InviteEmbedResolver
import com.discord.widgets.chat.list.entries.LoadingEntry
import com.discord.widgets.chat.list.entries.MessageEntry
import com.discord.widgets.chat.list.entries.MessageHeaderEntry
import com.discord.widgets.chat.list.entries.NewMessagesEntry
import com.discord.widgets.chat.list.entries.ReactionsEntry
import com.discord.widgets.chat.list.entries.SpacerEntry
import com.discord.widgets.chat.list.entries.StartOfChatEntry
import com.discord.widgets.chat.list.entries.StartOfPrivateChatEntry
import com.discord.widgets.chat.list.entries.TimestampEntry
import com.discord.widgets.chat.list.entries.UploadProgressEntry
import kotlin.math.abs

/**
 * Turns a channel's messages into the [ChatListEntry] rows `WidgetChatListAdapter` renders.
 *
 * This is the reconstruction's stand-in for the reference's `WidgetChatListModelMessages`. It
 * reproduces that class's per-message row order exactly — body, embeds and attachments, stickers,
 * activity invites, invite/template/gift cards, reactions, upload progress, ephemeral footer —
 * but sources its inputs from a plain [State] rather than from a nine-way store combine, because
 * several of those stores do not exist yet.
 *
 * Deliberately absent, each because its input has no source in this project:
 *  - bot UI-kit component rows (`ComponentChatListState`),
 *  - thread embed cards and the thread spine (`StoreThreadMessages` metadata),
 *  - collapsed blocked-message chunks (`StoreUserRelationships` grouping).
 *
 * [build] returns rows newest-first, matching the reversed layout manager the chat list uses.
 */
object ChatListRows {

    /**
     * Everything a row needs beyond the message itself.
     *
     * Everything except [channel] is defaulted so the single-message surfaces (pinned messages,
     * recent mentions) can build one without a loader state or an upload ledger.
     */
    data class State(
        val channel: Channel,
        /** The forum channel a forum post hangs off, when [channel] is one. */
        val parentChannel: Channel? = null,
        val messages: List<Message> = emptyList(),
        val loaded: StoreMessagesLoader.ChannelLoadedState = StoreMessagesLoader.ChannelLoadedState(),
        val detached: Boolean = false,
        val unreadMessageId: Long? = null,
        val permissions: Long? = null,
        val guild: Guild? = null,
        val guildMembers: Map<Long, GuildMember> = emptyMap(),
        val guildRoles: Map<Long, GuildRole> = emptyMap(),
        val messageStates: Map<Long, StoreMessageState.State> = emptyMap(),
        val animateEmojis: Boolean = true,
        val autoPlayGifs: Boolean = true,
        val renderEmbeds: Boolean = true,
        val inviteResolver: InviteEmbedResolver = InviteEmbedResolver.UNRESOLVED,
    )

    /** The whole channel, newest row first. */
    fun build(state: State): List<ChatListEntry> {
        val messages = state.messages.sortedBy(Message::id)
        val rows = ArrayList<ChatListEntry>()

        if (state.detached || !state.loaded.isInitialMessagesLoaded || state.loaded.isLoadingMessages) {
            rows += LoadingEntry()
        }
        if ((state.loaded.isOldestMessagesLoaded || messages.isEmpty()) &&
            state.loaded.isInitialMessagesLoaded &&
            !state.detached
        ) {
            rows += startEntry(state)
        }

        var previous: Message? = null
        var groupStart: Message? = null
        var unreadAdded = false
        messages.forEach { message ->
            val timestamp = MessageGrouping.timestamp(message)
            if (previous == null || !MessageGrouping.sameDay(timestamp, MessageGrouping.timestamp(previous!!))) {
                rows += TimestampEntry(message.id, timestamp)
            }
            // Reference short-circuits grouping here, so the oldest unread always keeps its header.
            var startsGroup = false
            if (!unreadAdded && state.unreadMessageId != null && message.id > state.unreadMessageId) {
                rows += NewMessagesEntry(state.channel.id, message.id)
                unreadAdded = true
                startsGroup = true
            }

            startsGroup = startsGroup ||
                MessageGrouping.isNewMessageGroup(state.channel, state.parentChannel, groupStart, message)
            rows += messageRows(state, message, isMinimal = !startsGroup)
            if (startsGroup) groupStart = message
            previous = message
        }

        rows += SpacerEntry(state.channel.id)
        return rows.asReversed()
    }

    /**
     * The rows one message expands into, top to bottom.
     *
     * The order is the reference's: body, then the attachment/embed block, stickers, the two
     * activity invites, the invite/template/gift cards, reactions, upload progress and finally the
     * "only you can see this" footer.
     */
    fun messageRows(
        state: State,
        message: Message,
        isMinimal: Boolean = false,
        forwardDepth: Int = 0,
    ): List<ChatListEntry> {
        val channel = state.channel
        val author = state.guildMembers[message.author?.id]
        val messageState = state.messageStates[message.id]
        val roles = state.guildRoles.takeIf { RoleUtils.containsRoleMentions(message.content) }
        val nickOrUsernames = ChatListEntry.nickOrUsernames(message, channel, state.guildMembers)

        val rows = ArrayList<ChatListEntry>()
        rows += MessageEntry(
            message = message,
            messageState = messageState,
            author = author,
            roles = roles,
            nickOrUsernames = nickOrUsernames,
            isMinimal = isMinimal,
            isExpandedBlocked = false,
            animateEmojis = state.animateEmojis,
            permissionsForChannel = state.permissions,
        )
        rows += forwardedRows(state, message, forwardDepth)
        rows += ChatListEntry.createEmbedEntries(
            message = message,
            messageState = messageState,
            blockedChunkExpanded = false,
            allowAnimatedEmojis = state.animateEmojis,
            autoPlayGifs = state.autoPlayGifs,
            renderEmbeds = state.renderEmbeds,
            isThreadStarterMessage = false,
            channel = channel,
            author = author,
            roles = roles,
            nickOrUsernames = nickOrUsernames,
        )
        rows += ChatListEntry.createStickerEntries(message)
        if (forwardDepth > 0 && !message.components.isNullOrEmpty()) {
            rows += ChatListEntry.createBotComponentEntries(
                message,
                channel.guildId,
                componentStoreState = null,
                animateEmojis = state.animateEmojis,
                messageState = messageState,
                channel = channel,
                guildMembers = state.guildMembers,
                guildRoles = state.guildRoles,
            )
        }
        rows += ChatListEntry.createGameInviteEntries(message)
        rows += ChatListEntry.createActivityMessageEntries(message)
        rows += ChatListEntry.createSpotifyListenTogetherEntries(message)
        rows += ChatListEntry.createInviteEntries(message, state.inviteResolver)
        rows += ChatListEntry.createGuildTemplateEntries(message)
        rows += ChatListEntry.createGiftEntries(message)

        if (message.getReactionsMap().isNotEmpty()) {
            val canAddReactions = channel.threadMetadata?.archived != true
            rows += ReactionsEntry(
                message = message,
                canAddReactions = canAddReactions,
                // The reference tests `ADD_REACTIONS` alone, because its `StorePermissions` answers
                // ALL for a private channel. Ours does not yet, so DMs are shortcut explicitly —
                // without it the "+" button would be dead in every DM.
                canCreateReactions = canAddReactions &&
                    (channel.isPrivate() ||
                        PermissionUtils.can(Permission.ADD_REACTIONS, state.permissions)),
                animateEmojis = state.animateEmojis,
            )
        }
        if (message.hasLocalUploads && !message.isFailed()) {
            rows += UploadProgressEntry(message.nonce.orEmpty(), message.channelId)
        }
        if (message.isEphemeralMessage() || (message.isInteraction() && message.isFailed())) {
            rows += EphemeralMessageEntry(message)
        }
        return rows
    }

    internal fun forwardedRows(
        state: State,
        message: Message,
        forwardDepth: Int = 0,
    ): List<ForwardedMessageEntry> = ForwardedMessageEntry.resolve(message, forwardDepth).map { forwarded ->
        val snapshot = forwarded.message
        if (snapshot == null) forwarded else forwarded.copy(
            rows = forwardedAccessoryRows(state, snapshot),
        )
    }

    private fun forwardedAccessoryRows(state: State, message: Message): List<ChatListEntry> {
        val rows = ArrayList<ChatListEntry>()
        var mediaIndex = 0
        message.attachments.orEmpty().forEach { attachment ->
            rows += AttachmentEntry(
                embedIndex = mediaIndex++,
                guildId = state.channel.guildId,
                message = message,
                messageState = state.messageStates[message.id],
                attachment = attachment,
                isBlockedExpanded = false,
                allowAnimatedEmojis = state.animateEmojis,
                autoPlayGifs = state.autoPlayGifs,
                isThreadStarterMessage = false,
            )
        }
        if (state.renderEmbeds) message.embeds.orEmpty().forEach { embed ->
            rows += EmbedEntry(
                embedIndex = mediaIndex++,
                guildId = state.channel.guildId,
                message = message,
                messageState = state.messageStates[message.id],
                embed = embed,
                isBlockedExpanded = false,
                allowAnimatedEmojis = state.animateEmojis,
                autoPlayGifs = state.autoPlayGifs,
                isThreadStarterMessage = false,
            )
        }
        rows += ChatListEntry.createStickerEntries(message)
        if (!message.components.isNullOrEmpty()) {
            rows += ChatListEntry.createBotComponentEntries(
                message = message,
                guildId = state.channel.guildId,
                componentStoreState = null,
                animateEmojis = state.animateEmojis,
                messageState = state.messageStates[message.id],
                channel = state.channel,
                guildMembers = state.guildMembers,
                guildRoles = state.guildRoles,
                disableInteractions = true,
            )
        }
        return rows
    }

    /**
     * One message rendered for a context list (pinned messages, recent mentions), preceded by the
     * header naming the guild and channel it came from.
     */
    fun contextMessageRows(state: State, message: Message): List<ChatListEntry> =
        listOf(MessageHeaderEntry(message, state.guild?.name, state.channel.getDisplayName())) +
            messageRows(state, message)

    /** The "beginning of…" panel that heads a fully-loaded channel. */
    private fun startEntry(state: State): ChatListEntry {
        val channel = state.channel
        if (channel.isPrivate()) {
            return StartOfPrivateChatEntry(
                channelId = channel.id,
                channelName = channel.getDisplayName(),
                channelType = channel.type,
                channelIcon = IconUtils.getForChannel(channel),
                isSystemDM = channel.isSystemDM(),
                // Mutual guilds come from `StoreUserMutualGuilds`, which does not exist yet.
                mutualGuilds = emptyList(),
            )
        }
        val isThread = channel.isThread()
        val creator = state.guildMembers[channel.ownerId]
        return StartOfChatEntry(
            channelId = channel.id,
            channelName = channel.getDisplayName(),
            canReadMessageHistory =
                PermissionUtils.can(Permission.READ_MESSAGE_HISTORY, state.permissions),
            canManageChannel = PermissionUtils.can(Permission.MANAGE_CHANNELS, state.permissions),
            canManageThread = isThread &&
                PermissionUtils.can(Permission.MANAGE_THREADS, state.permissions),
            isThread = isThread,
            threadAutoArchiveDuration = channel.threadMetadata?.autoArchiveDuration,
            threadCreatorMember = creator,
            // Only the guild nickname is reachable here; the account username would need
            // `StoreUser`, which this builder does not take.
            threadCreatorName = creator?.nick.orEmpty(),
            // Text-in-voice is behind an experiment `StoreExperiments` would resolve; it
            // defaults off until it exists.
            isTextInVoice = false,
            // A forum *post* is a thread whose parent is a forum channel; the parent is not part
            // of this state, so it is resolved by the caller when it can be.
            isGuildForumPost = channel.isGuildForumPost(state.parentChannel),
            availableTags = channel.availableTags,
            appliedTags = channel.appliedTags,
        )
    }

}
