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
import com.discord.utilities.SnowflakeUtils
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.permissions.PermissionUtils
import com.discord.utilities.icon.IconUtils
import com.discord.widgets.chat.list.entries.ChatListEntry
import com.discord.widgets.chat.list.entries.EphemeralMessageEntry
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
import java.util.Calendar

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
    private const val CONCATENATION_WINDOW_MS = 7 * 60 * 1000L
    private const val MAX_CONCATENATED_MESSAGES = 5

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
        var groupSize = 0
        var unreadAdded = false
        messages.forEach { message ->
            val timestamp = timestamp(message)
            if (previous == null || !sameDay(timestamp, timestamp(previous!!))) {
                rows += TimestampEntry(message.id, timestamp)
            }
            if (!unreadAdded && state.unreadMessageId != null && message.id > state.unreadMessageId) {
                rows += NewMessagesEntry(state.channel.id, message.id)
                unreadAdded = true
            }

            val concatenate = canConcatenate(previous, message, groupSize)
            rows += messageRows(state, message, isMinimal = concatenate)
            groupSize = if (concatenate) groupSize + 1 else 1
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
        rows += ChatListEntry.createGameInviteEntries(message)
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
            // Text-in-voice and the redesigned forum surface are behind experiments that
            // `StoreExperiments` would resolve; both flags default off until it exists.
            isTextInVoice = false,
            // A forum *post* is a thread whose parent is a forum channel; the parent is not part
            // of this state, so it is resolved by the caller when it can be.
            isGuildForumPost = channel.isGuildForumPost(state.parentChannel),
            canAccessRedesignedForumChannels = false,
            availableTags = channel.availableTags,
            appliedTags = channel.appliedTags,
        )
    }

    private fun canConcatenate(previous: Message?, message: Message, groupSize: Int): Boolean {
        if (previous == null || groupSize >= MAX_CONCATENATED_MESSAGES) return false
        if (message.type == REPLY_MESSAGE_TYPE || message.messageReference != null) return false
        if (previous.isFailed() || isSystem(previous) || message.isFailed() || isSystem(message)) return false
        val previousTime = timestamp(previous)
        val currentTime = timestamp(message)
        return previous.author?.id != null && previous.author?.id == message.author?.id &&
            currentTime >= previousTime && currentTime - previousTime <= CONCATENATION_WINDOW_MS
    }

    private fun isSystem(message: Message) = message.isSystemMessage() && !message.isLocal()

    private fun timestamp(message: Message) =
        message.timestamp?.dateTimeMillis ?: snowflakeTimestamp(message.id)

    private fun snowflakeTimestamp(id: Long) =
        if (id > 4_194_304L) SnowflakeUtils.toTimestamp(id) else 0L

    private fun sameDay(first: Long, second: Long): Boolean {
        val a = Calendar.getInstance().apply { timeInMillis = first }
        val b = Calendar.getInstance().apply { timeInMillis = second }
        return a.get(Calendar.ERA) == b.get(Calendar.ERA) &&
            a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /** `MessageTypes.REPLY`; a reply always starts its own block. */
    private const val REPLY_MESSAGE_TYPE = 19
}
