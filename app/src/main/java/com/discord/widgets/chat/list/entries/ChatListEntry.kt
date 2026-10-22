package com.discord.widgets.chat.list.entries

import com.discord.api.channel.Channel
import com.discord.api.channel.isPrivate
import com.discord.api.message.MessageTypes
import com.discord.api.message.embed.EmbedType
import com.discord.api.message.embed.MessageEmbed
import com.discord.api.role.GuildRole
import com.discord.models.botuikit.MessageComponent
import com.discord.models.botuikit.withInteractionsDisabled
import com.discord.models.member.GuildMember
import com.discord.models.message.Message
import com.discord.stores.StoreMessageState
import com.discord.stores.StoreThreadMessages
import com.discord.utilities.feature.FeatureFlags
import com.discord.utilities.guilds.RoleUtils
import com.discord.utilities.mg_recycler.MGRecyclerDataPayload
import com.discord.utilities.user.NicknameUtils
import com.discord.utilities.user.UserUtils.getDisplayName
import com.discord.widgets.botuikit.BotComponentExperiments
import com.discord.widgets.botuikit.ComponentChatListState.ComponentStoreState
import com.discord.widgets.botuikit.ComponentStateMapper
import com.discord.api.message.attachment.MessageAttachmentType
import com.discord.api.message.attachment.MessageAttachment

/**
 * One row of the chat list.
 *
 * A single [Message] normally expands into several entries — the message body, then one row per
 * attachment, embed, sticker, invite, gift, bot-component block and the reaction strip — which is
 * why the row-type space is much larger than the message-type space.
 * `WidgetChatListAdapter` maps [type] onto a view holder and [key] identifies the row across diffs.
 */
abstract class ChatListEntry : MGRecyclerDataPayload {

    /**
     * Set while the list is being built, on every row that sits between a message which owns a
     * thread and that thread's embed, so `ThreadSpineItemDecoration` can draw the connecting spine
     * behind them. Mutable because the span is only known once the following rows exist.
     */
    var shouldShowThreadSpine: Boolean = false

    /** True while the composer is replying to the message that owns this row. */
    open val isPendingReply: Boolean = false

    /**
     * The message whose state decides this row's highlight, for rows that belong to one.
     *
     * A message expands into several rows and the wash has to run across all of them, so every row
     * that is part of a message has to be able to name it.
     */
    open val highlightMessage: Message? = null

    /** True when this row belongs to a blocked-message chunk the viewer has expanded. */
    open fun isInExpandedBlockedMessageChunk(): Boolean = false

    companion object {
        const val MESSAGE = 0
        const val MESSAGE_MINIMAL = 1
        const val LOAD_INDICATOR = 2
        const val START_OF_CHAT = 3
        const val REACTIONS = 4
        const val SYSTEM_MESSAGE = 5
        const val UPLOAD_STATUS = 6
        const val SPACER = 7
        const val NEW_MESSAGES = 8
        const val TIMESTAMP = 9
        const val BLOCKED_MESSAGES = 10
        const val SEARCH_RESULT_COUNT = 11
        const val SEARCH_INDEXING = 12
        const val SEARCH_EMPTY = 13
        const val SEARCH_ERROR = 14
        const val DIVIDER = 15
        const val EMPTY_PINS = 16
        const val MESSAGE_HEADER = 17
        const val MENTION_FOOTER = 18
        const val CALL_MESSAGE = 19
        const val MESSAGE_FAILED = 20
        const val MESSAGE_EMBED = 21
        const val GAME_INVITE = 22
        const val SPOTIFY_LISTEN_TOGETHER = 23
        const val INVITE = 24
        const val GUILD_WELCOME = 25
        const val GIFT = 26
        const val MESSAGE_ATTACHMENT = 28
        const val START_OF_PRIVATE_CHAT = 29
        const val GUILD_TEMPLATE = 30
        const val STICKER = 31
        const val REPLY = 32
        const val APPLICATION_COMMAND = 33
        const val EPHEMERAL_MESSAGE = 34
        const val THREAD_EMBED = 35
        const val BOT_UI_COMPONENT = 36
        const val THREAD_DRAFT_FORM = 37
        const val GUILD_INVITE_REMINDER = 38
        const val STAGE_INVITE = 39
        const val THREAD_STARTER_DIVIDER = 40
        const val STICKER_GREET = 41
        const val STICKER_GREET_COMPACT = 42
        const val GUILD_SCHEDULED_EVENT_INVITE = 43
        const val AUTOMOD_BLOCKED = 44
        const val AUTOMOD_SYSTEM_MESSAGE = 45
        const val FORWARDED_MESSAGE = 46
        const val ACTIVITY_MESSAGE = 47
        const val POLL = 48
        const val SEARCH_SPOILER_RESULT = 49
        const val MEDIA_MOSAIC = 50

        /** `MessageTypes.AUTO_MODERATION_ACTION`; its single embed gets a dedicated row. */
        private const val AUTO_MOD_SYSTEM_MESSAGE_TYPE = 24

        private fun MessageAttachment.isMosaicMedia() =
            (type == MessageAttachmentType.IMAGE || type == MessageAttachmentType.VIDEO) &&
                (width ?: 0) > 0 && (height ?: 0) > 0

        /**
         * Attachments first, then embeds, sharing one running `embedIndex` because revealed-spoiler
         * state is keyed on it. Same-URL embeds share one card; its binder collects their companion
         * images from the original message. Null-URL cards are independent and always kept.
         */
        fun createEmbedEntries(
            message: Message,
            messageState: StoreMessageState.State?,
            blockedChunkExpanded: Boolean,
            allowAnimatedEmojis: Boolean,
            autoPlayGifs: Boolean,
            renderEmbeds: Boolean,
            isThreadStarterMessage: Boolean,
            channel: Channel,
            author: GuildMember?,
            roles: Map<Long, GuildRole>?,
            nickOrUsernames: Map<Long, String>,
            groupMedia: Boolean = false,
        ): List<ChatListEntry> {
            // Type 46 embeds contain poll closure metadata consumed by the system-message row.
            if (message.type == MessageTypes.POLL_RESULT) return emptyList()
            if (message.poll == null && !message.hasAttachments() && !(message.hasEmbeds() && renderEmbeds)) {
                return emptyList()
            }

            val seenUrls = HashSet<String>()
            val embeds = message.embeds.orEmpty().filter { it.url == null || seenUrls.add(it.url) }

            val attachments = message.attachments.orEmpty()
            val entries = ArrayList<ChatListEntry>(embeds.size + attachments.size + if (message.poll != null) 1 else 0)
            message.poll?.let { entries.add(PollEntry(message, it)) }
            var embedIndex = 0
            val attachmentEntries = attachments.map { attachment ->
                AttachmentEntry(
                    embedIndex = embedIndex++,
                    guildId = channel.guildId,
                    message = message,
                    messageState = messageState,
                    attachment = attachment,
                    isBlockedExpanded = blockedChunkExpanded,
                    allowAnimatedEmojis = allowAnimatedEmojis,
                    autoPlayGifs = autoPlayGifs,
                    isThreadStarterMessage = isThreadStarterMessage,
                )
            }
            // Modern Discord groups the images and videos into one mosaic, ahead of the other files.
            val (media, files) = attachmentEntries.partition { it.attachment.isMosaicMedia() }
            if (groupMedia && media.size > 1) {
                entries.add(MediaMosaicEntry(media))
                entries.addAll(files)
            } else {
                entries.addAll(attachmentEntries)
            }

            val isAutoModSystemMessage = message.type == AUTO_MOD_SYSTEM_MESSAGE_TYPE
            if (isAutoModSystemMessage) {
                entries.add(
                    AutoModSystemMessageEmbedEntry(
                        channel = channel,
                        message = message,
                        messageState = messageState,
                        embed = embeds[0],
                        author = author,
                        animateEmojis = allowAnimatedEmojis,
                        roles = roles,
                        nickOrUsernames = nickOrUsernames,
                    ),
                )
            }

            if (!isAutoModSystemMessage) {
                embeds.forEach { embed ->
                    when (embed.type) {
                        EmbedType.APPLICATION_NEWS -> Unit
                        EmbedType.COMPONENTS -> createComponentEmbedEntry(
                            message = message,
                            embed = embed,
                            embedIndex = embedIndex,
                            guildId = channel.guildId,
                            animateEmojis = allowAnimatedEmojis,
                            messageState = messageState,
                            channel = channel,
                        )?.let(entries::add)
                        else -> entries.add(
                            EmbedEntry(
                                embedIndex = embedIndex,
                                guildId = channel.guildId,
                                message = message,
                                messageState = messageState,
                                embed = embed,
                                isBlockedExpanded = blockedChunkExpanded,
                                allowAnimatedEmojis = allowAnimatedEmojis,
                                autoPlayGifs = autoPlayGifs,
                                isThreadStarterMessage = isThreadStarterMessage,
                            ),
                        )
                    }
                    if (embed.type != EmbedType.APPLICATION_NEWS) embedIndex++
                }
            }
            return entries
        }

        /** Prefers `sticker_items` (the partial shipped inline) over the fully hydrated stickers. */
        fun createStickerEntries(message: Message): List<ChatListEntry> {
            val partials = message.stickerItems
            if (!partials.isNullOrEmpty()) return partials.map { StickerEntry(message, it) }
            return message.stickers.orEmpty().map { StickerEntry(message, it) }
        }

        fun createGameInviteEntries(message: Message): List<ChatListEntry> {
            val application = message.application ?: return emptyList()
            val activity = message.activity ?: return emptyList()
            val author = message.author!!
            return listOf(GameInviteEntry(author.id, message.id, activity, application, message))
        }

        fun createActivityMessageEntries(message: Message): List<ChatListEntry> {
            val instance = message.activityInstance ?: return emptyList()
            return listOf(
                ActivityMessageEntry(
                    messageId = message.id,
                    channelId = message.channelId,
                    guildId = message.guildId ?: 0L,
                    application = message.application,
                    instanceId = instance.id,
                    message = message,
                ),
            )
        }

        fun createSpotifyListenTogetherEntries(message: Message): List<ChatListEntry> {
            val activity = message.activity ?: return emptyList()
            if (!message.isSpotifyListeningActivity()) return emptyList()
            val author = message.author!!
            return listOf(SpotifyListenTogetherEntry(author.id, message.id, activity, message))
        }

        /** One card per distinct gift code in the body; a repeated code collapses onto one row. */
        fun createGiftEntries(message: Message): Collection<ChatListEntry> {
            val content = message.content
            if (content.isNullOrEmpty()) return emptyList()
            val entries = HashMap<String, ChatListEntry>()
            GIFT_LINK.findAll(content).forEach { match ->
                val code = match.groupValues.lastOrNull().orEmpty()
                val author = message.author!!
                entries[code] = GiftEntry(author.id, message.id, message.channelId, code, message)
            }
            return entries.values
        }

        /** One card per distinct guild-template code in the body. */
        fun createGuildTemplateEntries(message: Message): Collection<ChatListEntry> {
            val content = message.content
            if (content.isNullOrEmpty()) return emptyList()
            val entries = HashMap<String, ChatListEntry>()
            GUILD_TEMPLATE_LINK.findAll(content).forEach { match ->
                val code = match.groupValues.lastOrNull().orEmpty()
                val author = message.author!!
                entries[code] = GuildTemplateEntry(author.id, message.id, code, message)
            }
            return entries.values
        }

        /**
         * One card per distinct invite code in the body. Which row type is produced depends on what
         * the invite resolves to: a guild scheduled event, a live public stage, or an ordinary guild
         * invite. Unresolved invites fall back to the ordinary card, which then loads itself.
         */
        fun createInviteEntries(
            message: Message,
            inviteResolver: InviteEmbedResolver,
        ): Collection<ChatListEntry> {
            val content = message.content
            if (content.isNullOrEmpty()) return emptyList()
            val entries = HashMap<String, ChatListEntry>()
            INVITE_LINK.findAll(content).forEach { match ->
                val groups = match.groupValues
                if (groups.size == 3) {
                    val code = groups[1]
                    val eventId = groups[2].toLongOrNull()?.toString()
                    entries[code] = createEntryForInvite(message, code, eventId, inviteResolver)
                }
            }
            return entries.values
        }

        private fun createEntryForInvite(
            message: Message,
            inviteCode: String,
            eventId: String?,
            inviteResolver: InviteEmbedResolver,
        ): ChatListEntry {
            val resolved = inviteResolver.resolveInvite(inviteCode, eventId)
            if (resolved?.getTargetType() == com.discord.models.domain.ModelInvite.InviteTargetTypes.EMBEDDED_APPLICATION.code) {
                return ActivityMessageEntry(
                    messageId = message.id,
                    channelId = resolved.channel?.id ?: message.channelId,
                    guildId = resolved.guild?.id ?: message.guildId ?: 0L,
                    application = resolved.getTargetApplication(),
                    instanceId = null,
                    inviteCode = inviteCode,
                    inviter = resolved.getInviter() ?: message.author,
                    invite = resolved,
                    message = message,
                )
            }
            val author = message.author!!
            if (eventId != null) {
                val eventInvite = inviteResolver.resolveInviteForGuildScheduledEvent(inviteCode, eventId)
                val event = eventInvite?.getGuildScheduledEvent()
                if (eventInvite != null && event != null) {
                    return GuildScheduledEventInviteEntry(eventInvite, inviteCode, event, message)
                }
            }
            val stageInvite = inviteResolver.resolveInviteCodeForPublicStage(inviteCode)
            return if (stageInvite != null) {
                StageInviteEntry(author.id, message.id, inviteCode, stageInvite, message.guildId ?: 0L, message)
            } else {
                InviteEntry(author.id, message.id, inviteCode, message.guildId ?: 0L, eventId, message)
            }
        }

        /** The thread preview card rendered underneath the message that started the thread. */
        fun createThreadEmbedEntries(
            channel: Channel,
            channelMembers: Map<Long, GuildMember>,
            message: Message,
            thread: Channel,
            threadEmbedMetadata: StoreThreadMessages.ThreadState?,
            guildRoles: Map<Long, GuildRole>?,
            animateEmojis: Boolean,
            renderEmbeds: Boolean,
        ): Collection<ChatListEntry> {
            if (!renderEmbeds) return emptyList()
            val mostRecentMessage = threadEmbedMetadata?.mostRecentMessage
            val roleMentions = guildRoles
                .takeIf { RoleUtils.containsRoleMentions(mostRecentMessage?.content) }
            val nickOrUsernames = mostRecentMessage
                ?.let { nickOrUsernames(it, channel, channelMembers) }
                ?: emptyMap()
            return listOf(
                ThreadEmbedEntry(
                    messageId = message.id,
                    thread = thread,
                    threadMessageCount = threadEmbedMetadata?.count ?: 0,
                    mostRecentMessage = mostRecentMessage,
                    roleMentions = roleMentions,
                    nickOrUsernames = nickOrUsernames,
                    animateEmojis = animateEmojis,
                    mostRecentMessageGuildMember = channelMembers[mostRecentMessage?.author?.id],
                    parentMessage = message,
                ),
            )
        }

        /**
         * Adapts a read-only component link preview to the existing Components V2 renderer.
         *
         * All interaction controls are disabled before binding. Link buttons remain enabled because
         * following their URL does not send an application interaction.
         */
        fun createComponentEmbedEntry(
            message: Message,
            embed: MessageEmbed,
            embedIndex: Int,
            guildId: Long,
            animateEmojis: Boolean,
            messageState: StoreMessageState.State? = null,
            channel: Channel? = null,
        ): BotUiComponentEntry? {
            if (embed.type != EmbedType.COMPONENTS) return null
            val components = embed.components.orEmpty()
            if (components.isEmpty()) return null
            val mapped = ComponentStateMapper.processComponentsToMessageComponents(
                components,
                componentStoreState = null,
                componentExperiments = BotComponentExperiments,
                animateEmojis = animateEmojis,
            ).map(MessageComponent::withInteractionsDisabled)
            if (mapped.isEmpty()) return null
            return BotUiComponentEntry(
                message = message,
                applicationId = message.applicationId ?: message.author?.id ?: 0L,
                guildId = guildId,
                messageComponents = mapped,
                messageState = messageState,
                channel = channel,
                sourceEmbedIndex = embedIndex,
            )
        }

        /**
         * The bot UI-kit block. [messageComponents] arrive already mapped to view models, so this
         * only decides whether the message carries components and which application owns the
         * interaction — the application id when the message declares one, otherwise the bot author.
         */
        fun createBotComponentEntries(
            message: Message,
            guildId: Long,
            messageComponents: List<MessageComponent>,
            messageState: StoreMessageState.State? = null,
            channel: Channel? = null,
            guildMembers: Map<Long, GuildMember> = emptyMap(),
            guildRoles: Map<Long, GuildRole> = emptyMap(),
            disableInteractions: Boolean = false,
        ): Collection<ChatListEntry> {
            if (message.components.isNullOrEmpty()) return emptyList()
            val applicationId = message.applicationId ?: message.author!!.id
            return listOf(
                BotUiComponentEntry(
                    message,
                    applicationId,
                    guildId,
                    if (disableInteractions) {
                        messageComponents.map(MessageComponent::withInteractionsDisabled)
                    } else {
                        messageComponents
                    },
                    messageState = messageState,
                    channel = channel,
                    guildMembers = guildMembers,
                    guildRoles = guildRoles,
                ),
            )
        }

        fun createBotComponentEntries(
            message: Message,
            guildId: Long,
            componentStoreState: ComponentStoreState?,
            animateEmojis: Boolean,
            messageState: StoreMessageState.State? = null,
            channel: Channel? = null,
            guildMembers: Map<Long, GuildMember> = emptyMap(),
            guildRoles: Map<Long, GuildRole> = emptyMap(),
            disableInteractions: Boolean = false,
        ): Collection<ChatListEntry> {
            val components = message.components
            if (components.isNullOrEmpty()) return emptyList()
            val mapped = ComponentStateMapper.processComponentsToMessageComponents(
                components,
                componentStoreState,
                BotComponentExperiments,
                animateEmojis,
            )
            return createBotComponentEntries(
                message,
                guildId,
                mapped,
                messageState,
                channel,
                guildMembers,
                guildRoles,
                disableInteractions,
            )
        }

        /**
         * The display name each user mentioned by this message should render as: the guild nickname
         * when there is one, otherwise the account username, with group-DM nicknames winning over
         * both.
         */
        internal fun nickOrUsernames(
            message: Message,
            channel: Channel,
            guildMembers: Map<Long, GuildMember>,
        ): Map<Long, String> {
            val names = HashMap<Long, String>()
            // The two kinds of nickname do not compete: outside a guild the friend nickname is the
            // name, inside one only the member nick is.
            val isPrivate = channel.isPrivate()
            fun put(user: com.discord.api.user.User) {
                val nick = if (isPrivate) {
                    NicknameUtils.getFriendNickname(user.id)
                } else {
                    guildMembers[user.id]?.identityNick()
                        ?: user.member?.nick?.takeUnless { FeatureFlags.disableServerProfilesEnabled() }
                }
                names[user.id] = user.getDisplayName(nick)
            }
            message.author?.let(::put)
            message.mentions.orEmpty().forEach(::put)
            channel.nicks.orEmpty().forEach { names[it.id] = it.nick }
            return names
        }

        // The three patterns below are transcribed from the reference's shared Discord-URL pattern
        // table (the class that also holds the `/channels/...` and `/ra/...` deep-link patterns).
        // They belong in that shared utility; they live here only because it has not been
        // reconstructed yet. See docs/parity-audit/followups.md.
        private const val DISCORD_HOSTS = "(?:discord\\.com|discordapp\\.com)"
        private const val GG_HOST = "discord\\.gg"
        private const val NEW_HOST = "discord\\.new"
        private const val GIFT_HOST = "discord\\.gift"

        private val INVITE_LINK = Regex(
            "(?:https?://(?:(?:$DISCORD_HOSTS/invite)|(?:$GG_HOST))|(?:^$GG_HOST))/([\\w-]+)/?" +
                "(?:(?:\\S*[?&]event=)([\\w-]+))?",
            RegexOption.IGNORE_CASE,
        )
        private val GUILD_TEMPLATE_LINK = Regex(
            "(?:https?://(?:(?:$DISCORD_HOSTS/template)|(?:$NEW_HOST))|(?:$NEW_HOST))/([\\w-]+)/?",
            RegexOption.IGNORE_CASE,
        )
        private val GIFT_LINK = Regex(
            "(?:https?://)?(?:(?:$DISCORD_HOSTS/gifts)|(?:$GIFT_HOST))/([\\w-]+)/?",
            RegexOption.IGNORE_CASE,
        )
    }
}
